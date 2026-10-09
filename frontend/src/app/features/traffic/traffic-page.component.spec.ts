import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';

import { By } from '@angular/platform-browser';

import { AdminApiService } from '../../core/admin-api.service';
import { TRAFFIC_FETCH, TrafficStore } from '../../core/traffic-store.service';
import { RequestDemoComponent } from '../request-demo/request-demo.component';
import { TrafficPageComponent } from './traffic-page.component';

const BASE = '/api/admin/rate-limit';

const events = [
  {
    id: 2, at: '2026-10-09T10:00:02Z', method: 'GET', path: '/api/products', outcome: 'REJECTED', status: 429,
    policy: 'p-abc', limit: 9, remaining: 0, retryAfterSeconds: 42, client: '203.0.113.x', user: 'u-1a2b3c4d',
  },
  {
    id: 1, at: '2026-10-09T10:00:01Z', method: 'GET', path: '/api/products', outcome: 'ALLOWED', status: 200,
    policy: 'p-abc', limit: 9, remaining: 8, retryAfterSeconds: null, client: '203.0.113.x', user: null,
  },
];

describe('TrafficPageComponent', () => {
  let fixture: ComponentFixture<TrafficPageComponent>;
  let http: HttpTestingController;
  const root = () => fixture.nativeElement as HTMLElement;

  /** Answers whatever the page and the embedded tester asked for on load. */
  const answerAll = () => {
    for (const request of http.match(() => true)) {
      const url = request.request.url;
      if (url.startsWith(`${BASE}/traffic`)) {
        request.flush({
          enabled: true, capacity: 1000, dropped: 0, serverTime: '2026-10-09T10:00:03Z', events,
          buckets: [
            { t: 1, allowed: 3, rejected: 0, error: 0 },
            { t: 2, allowed: 1, rejected: 4, error: 0 },
          ],
        });
      } else if (url === `${BASE}/policies`) {
        request.flush([{
          id: 'p-abc', name: 'QA Orders - A', path: '/api/products', method: 'GET', algorithm: 'FIXED_WINDOW',
          scope: 'IP', enabled: true, version: 1, source: 'GROUP',
        }]);
      } else {
        request.flush([]);
      }
    }
  };

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [TrafficPageComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        // No live stream in these tests: the page falls back to polling, which the fake backend answers.
        { provide: TRAFFIC_FETCH, useValue: () => Promise.reject(new Error('no stream')) },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const login = firstValueFrom(TestBed.inject(AdminApiService).login('pocadmin', 'test-only'));
    http.expectOne(`${BASE}/policies`).flush([]);
    await login;
    fixture = TestBed.createComponent(TrafficPageComponent);
    fixture.detectChanges();
    // The first traffic answer is history from before the page opened; it must not reach the feed.
    await new Promise((resolve) => setTimeout(resolve, 0)); // the failed stream hands over to a poll
    answerAll();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(root().querySelectorAll('table.feed tbody tr')).toHaveLength(0);
    // The next poll carries new decisions.
    await nextPoll();
  });

  /** Runs one more poll and answers it with the sample events. */
  const nextPoll = async () => {
    const pending = TestBed.inject(TrafficStore).poll();
    answerAll();
    await pending;
    await fixture.whenStable();
    fixture.detectChanges();
  };

  const tester = () => fixture.debugElement.query(By.directive(RequestDemoComponent))
    .componentInstance as RequestDemoComponent;

  it('opens with an empty feed even when the server still holds older requests', async () => {
    TestBed.inject(TrafficStore).stop();
    TestBed.inject(TrafficStore).start();
    await new Promise((resolve) => setTimeout(resolve, 0));
    answerAll();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(root().querySelectorAll('table.feed tbody tr')).toHaveLength(0);
    expect(root().textContent).toContain('No requests since this view was opened or reset');
  });

  it('clears the feed when the tester switches target or starts a new run', async () => {
    expect(root().querySelectorAll('table.feed tbody tr')).toHaveLength(2);
    tester().targetChanged.emit();
    fixture.detectChanges();
    expect(root().querySelectorAll('table.feed tbody tr')).toHaveLength(0);

    await nextPoll();
    expect(root().querySelectorAll('table.feed tbody tr')).toHaveLength(2);
    tester().runStarted.emit();
    fixture.detectChanges();
    expect(root().querySelectorAll('table.feed tbody tr')).toHaveLength(0);
  });

  it('puts the tester and the live view on one page', () => {
    expect(root().querySelector('app-request-demo')).not.toBeNull();
    expect(root().querySelector('svg.chart')).not.toBeNull();
    expect(root().textContent).toContain('Live traffic');
  });

  it('draws one stacked bar per second with blocked requests in their own colour', () => {
    const bars = root().querySelectorAll('svg.chart g');
    expect(bars).toHaveLength(2);
    expect(bars[1].querySelectorAll('rect.bar.allowed, rect.bar.rejected')).toHaveLength(2);
    expect(root().querySelector('svg.chart')?.getAttribute('aria-label')).toContain('4 allowed, 4 blocked');
  });

  it('lists decisions newest first with the policy name, masked client and hashed user', () => {
    const rows = root().querySelectorAll('table.feed tbody tr');
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('Blocked');
    expect(rows[0].textContent).toContain('retry in 42s');
    expect(rows[0].textContent).toContain('QA Orders - A');
    expect(rows[0].textContent).toContain('203.0.113.x');
    expect(rows[0].textContent).toContain('u-1a2b3c4d');
    expect(rows[1].textContent).toContain('8 / 9 left');
  });

  it('pauses and clears the feed from its own buttons', async () => {
    const button = (label: string) => Array.from(root().querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === label)!;
    button('Pause feed').click();
    fixture.detectChanges();
    expect(root().textContent).toContain('Paused');
    expect(button('Resume feed')).toBeDefined();

    button('Clear').click();
    fixture.detectChanges();
    expect(root().querySelectorAll('table.feed tbody tr')).toHaveLength(0);
    expect(root().textContent).toContain('No requests since this view was opened or reset');
  });
});
