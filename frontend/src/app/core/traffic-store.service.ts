import { Injectable, InjectionToken, OnDestroy, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { AdminApiService } from './admin-api.service';
import { TrafficBucket, TrafficEvent } from './admin-models';

const POLL_MS = 1000;
const RECONNECT_MS = 4000;
const STREAM_PATH = '/api/admin/rate-limit/traffic/stream';
/** The feed keeps this many newest events in memory; older ones scroll off, like a terminal. */
export const MAX_FEED_EVENTS = 500;
export const CHART_SECONDS = 60;

/** The fetch used to open the live stream. A token so tests can replace the network. */
export const TRAFFIC_FETCH = new InjectionToken<typeof fetch>('TRAFFIC_FETCH', {
  providedIn: 'root',
  factory: () => (input: RequestInfo | URL, init?: RequestInit) => fetch(input, init),
});

export type TrafficMode = 'stream' | 'polling' | 'stopped';

/**
 * Live traffic while the Traffic page is open.
 *
 * Normally the backend pushes each decision as a Server-Sent Event the moment it is recorded. EventSource
 * cannot send the admin credentials, so the stream is read with fetch. If the stream cannot be opened or
 * drops, the store polls once a second until it can reconnect, resuming after the last event it saw, so a
 * short outage loses nothing the backend still holds.
 *
 * The view starts from now: history from earlier sessions is not shown. Pausing freezes the feed, and
 * what arrives meanwhile is held and added when it resumes.
 */
@Injectable({ providedIn: 'root' })
export class TrafficStore implements OnDestroy {
  private readonly api = inject(AdminApiService);
  private readonly fetcher = inject(TRAFFIC_FETCH);

  readonly events = signal<TrafficEvent[]>([]);
  readonly buckets = signal<TrafficBucket[]>([]);
  readonly enabled = signal(true);
  readonly dropped = signal(0);
  readonly error = signal<string | null>(null);
  readonly paused = signal(false);
  readonly live = signal(false);
  readonly mode = signal<TrafficMode>('stopped');

  private since = 0;
  private inFlight = false;
  /** False until the cursor has been moved past everything recorded before the page opened. */
  private primed = false;
  private abort: AbortController | null = null;
  private held: TrafficEvent[] = [];

  start(): void {
    if (this.abort) return;
    this.primed = false;
    this.since = 0;
    this.held = [];
    this.events.set([]);
    this.live.set(true);
    this.abort = new AbortController();
    void this.supervise(this.abort.signal);
  }

  stop(): void {
    this.abort?.abort();
    this.abort = null;
    this.live.set(false);
    this.mode.set('stopped');
  }

  togglePause(): void {
    const nowPaused = !this.paused();
    this.paused.set(nowPaused);
    if (!nowPaused && this.held.length > 0) {
      const caughtUp = this.held;
      this.held = [];
      this.add(caughtUp);
    }
  }

  /** Empties the feed. The cursor is kept, so cleared events do not come back. */
  clear(): void {
    this.events.set([]);
    this.held = [];
  }

  ngOnDestroy(): void {
    this.stop();
  }

  /** Keeps the stream open; when it is unavailable, polls until the next reconnect attempt. */
  private async supervise(signal: AbortSignal): Promise<void> {
    while (!signal.aborted) {
      try {
        await this.readStream(signal);
      } catch {
        // Fall through to polling; the reason is not useful to show for a normal disconnect.
      }
      if (signal.aborted) return;
      this.mode.set('polling');
      const until = Date.now() + RECONNECT_MS;
      while (!signal.aborted && Date.now() < until) {
        await this.poll();
        await sleep(POLL_MS, signal);
      }
    }
  }

  private async readStream(signal: AbortSignal): Promise<void> {
    const authorization = this.api.authorizationHeader;
    if (!authorization) throw new Error('not logged in');
    const resume = this.since > 0 ? `&since=${this.since}` : '';
    const response = await this.fetcher(`${STREAM_PATH}?seconds=${CHART_SECONDS}${resume}`, {
      headers: { Authorization: authorization, Accept: 'text/event-stream' },
      cache: 'no-store',
      signal,
    });
    if (!response.ok || !response.body) throw new Error(`stream HTTP ${response.status}`);

    this.mode.set('stream');
    this.error.set(null);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (!signal.aborted) {
      const { value, done } = await reader.read();
      if (done) return;
      buffer += decoder.decode(value, { stream: true });
      let end = buffer.search(/\r?\n\r?\n/);
      while (end >= 0) {
        this.onMessage(buffer.slice(0, end));
        buffer = buffer.slice(end).replace(/^\r?\n\r?\n/, '');
        end = buffer.search(/\r?\n\r?\n/);
      }
    }
  }

  /** Handles one server-sent event: "hello" (cursor), "traffic" (a decision) or "buckets" (the chart). */
  private onMessage(raw: string): void {
    let name = 'message';
    const data: string[] = [];
    for (const line of raw.split(/\r?\n/)) {
      if (line.startsWith(':')) continue; // keep-alive comment
      if (line.startsWith('event:')) name = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5).trim());
    }
    if (data.length === 0) return;
    let payload: unknown;
    try {
      payload = JSON.parse(data.join('\n'));
    } catch {
      return;
    }
    if (name === 'hello') {
      const hello = payload as { cursor: number; dropped: number };
      this.since = Math.max(this.since, hello.cursor);
      this.primed = true;
      this.dropped.set(hello.dropped);
    } else if (name === 'traffic') {
      const event = payload as TrafficEvent;
      this.since = Math.max(this.since, event.id);
      if (this.paused()) this.held = [event, ...this.held].slice(0, MAX_FEED_EVENTS);
      else this.add([event]);
    } else if (name === 'buckets') {
      const update = payload as { buckets: TrafficBucket[]; dropped: number };
      this.buckets.set(update.buckets);
      this.dropped.set(update.dropped);
    }
  }

  /** Adds events (newest first) to the front of the feed. */
  private add(newest: TrafficEvent[]): void {
    this.events.update((current) => [...newest, ...current].slice(0, MAX_FEED_EVENTS));
  }

  /** One poll of the snapshot endpoint: the fallback while the stream is unavailable. */
  async poll(): Promise<void> {
    if (this.inFlight) return;
    this.inFlight = true;
    try {
      const result = await firstValueFrom(this.api.traffic(this.since, CHART_SECONDS));
      if ('status' in result) {
        this.error.set(result.message || 'Live traffic is unavailable.');
        return;
      }
      this.error.set(null);
      this.enabled.set(result.enabled);
      this.dropped.set(result.dropped);
      this.buckets.set(result.buckets);
      if (!this.primed) {
        // Skip history: remember the newest id and show only what arrives after it.
        this.since = Math.max(this.since, ...result.events.map((event) => event.id));
        this.primed = true;
        return;
      }
      if (result.events.length > 0) {
        this.since = Math.max(this.since, ...result.events.map((event) => event.id));
        if (this.paused()) this.held = [...result.events, ...this.held].slice(0, MAX_FEED_EVENTS);
        else this.add(result.events);
      }
    } finally {
      this.inFlight = false;
    }
  }
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) return resolve();
    const timer = setTimeout(resolve, ms);
    signal.addEventListener('abort', () => { clearTimeout(timer); resolve(); }, { once: true });
  });
}
