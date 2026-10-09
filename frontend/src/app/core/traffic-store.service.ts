import { Injectable, OnDestroy, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { AdminApiService } from './admin-api.service';
import { TrafficBucket, TrafficEvent } from './admin-models';

const POLL_MS = 1000;
/** The feed keeps this many newest events in memory; older ones scroll off, like a terminal. */
export const MAX_FEED_EVENTS = 500;
export const CHART_SECONDS = 60;

/**
 * Polls the live traffic endpoint while the Traffic page is open.
 *
 * Events are fetched with a cursor (the largest id already seen), so each poll transfers only what is new.
 * The first poll after {@link start} only positions the cursor: the feed shows what happens from now on,
 * not requests from earlier sessions. Pausing freezes the feed but not the cursor, so resuming catches up on
 * whatever the backend still holds.
 */
@Injectable({ providedIn: 'root' })
export class TrafficStore implements OnDestroy {
  private readonly api = inject(AdminApiService);

  readonly events = signal<TrafficEvent[]>([]);
  readonly buckets = signal<TrafficBucket[]>([]);
  readonly enabled = signal(true);
  readonly dropped = signal(0);
  readonly error = signal<string | null>(null);
  readonly paused = signal(false);
  readonly live = signal(false);

  private since = 0;
  private timer: ReturnType<typeof setInterval> | null = null;
  private inFlight = false;
  /** False until the cursor has been moved past everything recorded before the page opened. */
  private primed = false;

  start(): void {
    if (this.timer) return;
    this.primed = false;
    this.events.set([]);
    this.live.set(true);
    void this.poll();
    this.timer = setInterval(() => void this.poll(), POLL_MS);
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    this.live.set(false);
  }

  togglePause(): void {
    this.paused.update((value) => !value);
  }

  /** Empties the feed. The cursor is kept, so cleared events do not come back. */
  clear(): void {
    this.events.set([]);
  }

  ngOnDestroy(): void {
    this.stop();
  }

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
      if (!this.paused() && result.events.length > 0) {
        this.since = Math.max(this.since, ...result.events.map((event) => event.id));
        this.events.update((current) => [...result.events, ...current].slice(0, MAX_FEED_EVENTS));
      }
    } finally {
      this.inFlight = false;
    }
  }
}
