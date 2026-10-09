import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AdminApiService } from './admin-api.service';
import { TrafficEvent } from './admin-models';
import { MAX_FEED_EVENTS, TRAFFIC_FETCH, TrafficStore } from './traffic-store.service';

const BASE = '/api/admin/rate-limit';

const event = (id: number, outcome: TrafficEvent['outcome'] = 'ALLOWED'): TrafficEvent => ({
  id,
  at: '2026-10-09T10:00:00Z',
  method: 'GET',
  path: '/api/products',
  outcome,
  status: outcome === 'REJECTED' ? 429 : 200,
  policy: 'products-read',
  limit: 10,
  remaining: 9,
  retryAfterSeconds: null,
  client: '203.0.113.x',
  user: 'u-1a2b3c4d',
});

const response = (events: TrafficEvent[], extra: object = {}) => ({
  enabled: true,
  capacity: 1000,
  dropped: 0,
  serverTime: '2026-10-09T10:00:00Z',
  events,
  buckets: [{ t: 1, allowed: 2, rejected: 1, error: 0 }],
  ...extra,
});

describe('TrafficStore', () => {
  let store: TrafficStore;
  let http: HttpTestingController;
  let fetchImpl: (...args: Parameters<typeof fetch>) => Promise<Response>;

  /** Answers the next poll with the given body and waits for the store to absorb it. */
  const poll = async (body: object, expectSince?: number) => {
    const pending = store.poll();
    const request = http.expectOne((req) => req.url.startsWith(`${BASE}/traffic`));
    if (expectSince !== undefined) {
      expect(request.request.url).toContain(`since=${expectSince}`);
    }
    request.flush(body);
    await pending;
  };

  beforeEach(async () => {
    fetchImpl = () => Promise.reject(new Error('no live stream in this test'));
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: TRAFFIC_FETCH, useValue: (...args: Parameters<typeof fetch>) => fetchImpl(...args) },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const login = firstValueFrom(TestBed.inject(AdminApiService).login('pocadmin', 'test-only'));
    http.expectOne(`${BASE}/policies`).flush([]);
    await login;
    store = TestBed.inject(TrafficStore);
  });

  it('skips history recorded before the view opened and shows only newer events', async () => {
    await poll(response([event(42), event(41)]), 0);
    expect(store.events()).toEqual([]);
    // The chart still shows the recent seconds.
    expect(store.buckets()).toHaveLength(1);

    await poll(response([event(43)]), 42);
    expect(store.events().map((e) => e.id)).toEqual([43]);
  });

  it('starts empty again every time the view is reopened', async () => {
    await poll(response([]));
    await poll(response([event(1)]));
    expect(store.events()).toHaveLength(1);
    store.start();
    expect(store.events()).toEqual([]);
    // The stream cannot open here, so the store falls back to a poll that re-primes the cursor.
    await vi.waitFor(() => http.expectOne((req) => req.url.startsWith(`${BASE}/traffic`)).flush(response([event(1)])));
    store.stop();
  });

  describe('after priming', () => {
    beforeEach(async () => {
      await poll(response([]), 0);
    });

  it('shows newest events first and asks only for newer ones next time', async () => {
    await poll(response([event(3), event(2)]), 0);
    expect(store.events().map((e) => e.id)).toEqual([3, 2]);

    await poll(response([event(5, 'REJECTED'), event(4)]), 3);
    expect(store.events().map((e) => e.id)).toEqual([5, 4, 3, 2]);
    expect(store.buckets()).toHaveLength(1);
  });

  it('freezes the feed while paused and catches up on resume', async () => {
    await poll(response([event(1)]));
    store.togglePause();
    await poll(response([event(2)]));
    expect(store.events().map((e) => e.id)).toEqual([1]);
    // The chart keeps moving while the feed is frozen.
    expect(store.buckets()).toHaveLength(1);

    // What arrived meanwhile was held, and is added the moment the feed resumes.
    store.togglePause();
    expect(store.events().map((e) => e.id)).toEqual([2, 1]);
  });

  it('clearing the feed does not bring old events back', async () => {
    await poll(response([event(1)]));
    store.clear();
    expect(store.events()).toEqual([]);
    await poll(response([]), 1);
    expect(store.events()).toEqual([]);
  });

  it('keeps only the newest events in memory', async () => {
    const many = Array.from({ length: MAX_FEED_EVENTS + 50 }, (_, i) => event(MAX_FEED_EVENTS + 50 - i));
    await poll(response(many));
    expect(store.events()).toHaveLength(MAX_FEED_EVENTS);
    expect(store.events()[0].id).toBe(MAX_FEED_EVENTS + 50);
  });

  it('reports a failure and recovers on the next good poll', async () => {
    const pending = store.poll();
    http.expectOne((req) => req.url.startsWith(`${BASE}/traffic`)).flush(
      { error: 'x', message: 'boom', problems: [] },
      { status: 500, statusText: 'Server Error' },
    );
    await pending;
    expect(store.error()).toBeTruthy();

    await poll(response([], { dropped: 7, enabled: false }));
    expect(store.error()).toBeNull();
    expect(store.dropped()).toBe(7);
    expect(store.enabled()).toBe(false);
  });
  });

  describe('live stream', () => {
    const encoder = new TextEncoder();
    /** A server-sent-event response that stays open, delivering the given chunks. */
    const sse = (...chunks: string[]) =>
      Promise.resolve(new Response(new ReadableStream<Uint8Array>({
        start(controller) { for (const chunk of chunks) controller.enqueue(encoder.encode(chunk)); },
      }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } }));
    const message = (name: string, data: unknown) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`;

    afterEach(() => store.stop());

    it('shows pushed decisions newest first, from now on, even when a message arrives in pieces', async () => {
      const second = message('traffic', event(42, 'REJECTED'));
      fetchImpl = () => sse(
        message('hello', { cursor: 40, capacity: 1000, dropped: 0 }),
        message('traffic', event(41)),
        second.slice(0, 25), second.slice(25),
        ': keep-alive\n\n',
        message('buckets', { buckets: [{ t: 1, allowed: 1, rejected: 1, error: 0 }], dropped: 3 }),
      );
      store.start();
      await vi.waitFor(() => expect(store.events().map((e) => e.id)).toEqual([42, 41]));
      expect(store.mode()).toBe('stream');
      expect(store.buckets()).toHaveLength(1);
      expect(store.dropped()).toBe(3);
      http.expectNone((req) => req.url.startsWith(`${BASE}/traffic`));
    });

    it('opens the stream with the admin credentials and no resume cursor on first connect', async () => {
      let url = '';
      let headers: HeadersInit | undefined;
      fetchImpl = (input, init) => {
        url = String(input);
        headers = init?.headers;
        return sse(message('hello', { cursor: 0, capacity: 1000, dropped: 0 }));
      };
      store.start();
      await vi.waitFor(() => expect(store.mode()).toBe('stream'));
      expect(url).toContain('/api/admin/rate-limit/traffic/stream');
      expect(url).not.toContain('since=');
      expect((headers as Record<string, string>)['Authorization']).toMatch(/^Basic /);
    });

    it('holds pushed events while paused and adds them on resume', async () => {
      fetchImpl = () => sse(message('hello', { cursor: 0, capacity: 1000, dropped: 0 }), message('traffic', event(1)));
      store.togglePause();
      store.start();
      await vi.waitFor(() => expect(store.mode()).toBe('stream'));
      await new Promise((resolve) => setTimeout(resolve, 30));
      expect(store.events()).toEqual([]);
      store.togglePause();
      expect(store.events().map((e) => e.id)).toEqual([1]);
    });

    it('falls back to polling when the stream cannot be opened', async () => {
      fetchImpl = () => Promise.resolve(new Response('nope', { status: 503 }));
      store.start();
      await vi.waitFor(() => http.expectOne((req) => req.url.startsWith(`${BASE}/traffic`)).flush(response([event(1)])));
      expect(store.mode()).toBe('polling');
    });
  });
});
