import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, computed, inject } from '@angular/core';

import { AdminStore } from '../../core/admin-store.service';
import { TrafficEvent } from '../../core/admin-models';
import { CHART_SECONDS, MAX_FEED_EVENTS, TrafficStore } from '../../core/traffic-store.service';
import { RequestDemoComponent } from '../request-demo/request-demo.component';

const CHART_WIDTH = 600;
const CHART_HEIGHT = 140;
const MIN_SCALE = 5;

interface ChartBar {
  x: number;
  width: number;
  segments: { y: number; height: number; kind: 'allowed' | 'rejected' | 'error' }[];
  label: string;
}

/**
 * Traffic workspace: the request tester on top, and below it a live view of what the limiter decided.
 *
 * The chart is hand-drawn SVG from per-second counters, so it needs no charting dependency. Polling runs
 * only while this page is open.
 */
@Component({
  selector: 'app-traffic-page',
  standalone: true,
  imports: [DatePipe, RequestDemoComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './traffic-page.component.html',
  styleUrl: './traffic-page.component.scss',
})
export class TrafficPageComponent implements OnInit, OnDestroy {
  protected readonly traffic = inject(TrafficStore);
  private readonly adminStore = inject(AdminStore);

  protected readonly chartWidth = CHART_WIDTH;
  protected readonly chartHeight = CHART_HEIGHT;
  protected readonly maxEvents = MAX_FEED_EVENTS;

  /** Policy ids from group projections are long hashes, so show the name the admin gave them. */
  private readonly policyNames = computed(
    () => new Map(this.adminStore.policies().map((policy) => [policy.id, policy.name || policy.id])),
  );

  protected readonly totals = computed(() => {
    const sum = { allowed: 0, rejected: 0, error: 0 };
    for (const bucket of this.traffic.buckets()) {
      sum.allowed += bucket.allowed;
      sum.rejected += bucket.rejected;
      sum.error += bucket.error;
    }
    return sum;
  });

  protected readonly scale = computed(() =>
    Math.max(MIN_SCALE, ...this.traffic.buckets().map((b) => b.allowed + b.rejected + b.error)),
  );

  protected readonly bars = computed<ChartBar[]>(() => {
    const buckets = this.traffic.buckets();
    const scale = this.scale();
    const slot = CHART_WIDTH / CHART_SECONDS;
    return buckets.map((bucket, index) => {
      let y = CHART_HEIGHT;
      const segments: ChartBar['segments'] = [];
      for (const kind of ['allowed', 'rejected', 'error'] as const) {
        const count = bucket[kind];
        if (count > 0) {
          const height = (count / scale) * CHART_HEIGHT;
          y -= height;
          segments.push({ y, height, kind });
        }
      }
      return {
        x: index * slot + 1,
        width: Math.max(1, slot - 2),
        segments,
        label: `${bucket.allowed} allowed, ${bucket.rejected} blocked, ${bucket.error} errors`,
      };
    });
  });

  protected readonly chartSummary = computed(() => {
    const t = this.totals();
    return `Last ${CHART_SECONDS} seconds: ${t.allowed} allowed, ${t.rejected} blocked, ${t.error} store errors.`;
  });

  ngOnInit(): void {
    void this.adminStore.loadPolicies();
    this.traffic.start();
  }

  ngOnDestroy(): void {
    this.traffic.stop();
  }

  protected policyName(event: TrafficEvent): string {
    return this.policyNames().get(event.policy) ?? event.policy;
  }

  protected resultLabel(event: TrafficEvent): string {
    switch (event.outcome) {
      case 'ALLOWED':
        return 'Allowed';
      case 'REJECTED':
        return 'Blocked';
      default:
        return 'Store error';
    }
  }

  protected quota(event: TrafficEvent): string {
    if (event.outcome === 'REJECTED') {
      return event.retryAfterSeconds !== null ? `retry in ${event.retryAfterSeconds}s` : 'over limit';
    }
    return event.limit !== null && event.remaining !== null ? `${event.remaining} / ${event.limit} left` : '—';
  }
}
