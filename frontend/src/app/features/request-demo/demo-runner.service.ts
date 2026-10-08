import { Injectable, signal } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { DemoRoute } from '../../core/demo-catalog';
import { Credentials, DemoRequestResult, DemoRequestService } from '../../core/demo-request.service';
import { DemoSummary, ResponseEntry } from '../../core/models';

/**
 * Bounded, sequential, cancellable demo execution.
 *
 * One request at a time, no retries, hard count cap. A cancelled run, or one that stops early on a
 * non-429 response, is reported as inconclusive rather than as a passing demonstration.
 */
@Injectable({ providedIn: 'root' })
export class DemoRunnerService {
  private readonly requests = new DemoRequestService();

  readonly running = signal(false);
  readonly sent = signal(0);
  readonly summary = signal<DemoSummary | null>(null);

  async run(
    route: DemoRoute,
    requestedCount: number,
    credentials: Credentials | null,
    onProgress: (sent: number, total: number) => void,
  ): Promise<DemoSummary> {
    const summary: DemoSummary = {
      routeId: route.id,
      totalSent: 0,
      success: 0,
      rejected: 0,
      notFound: 0,
      error: 0,
      statuses: {},
      first429Index: null,
      elapsedMs: 0,
      completed: false,
      cancelled: false,
      inconclusive: false,
      responses: [],
      lastRejection: null,
      lastError: null,
    };

    if (this.running()) return summary; // never overlap runs
    this.running.set(true);
    this.sent.set(0);
    this.summary.set(null);
    const startedAt = Date.now();

    try {
      for (let index = 1; index <= requestedCount; index += 1) {
        if (!this.running()) {
          summary.cancelled = true;
          break;
        }

        const result: DemoRequestResult = await firstValueFrom(
          this.requests.send(route, credentials) as Observable<DemoRequestResult>,
        );
        summary.totalSent += 1;
        summary.statuses[result.status] = (summary.statuses[result.status] ?? 0) + 1;
        this.sent.set(summary.totalSent);
        onProgress(summary.totalSent, requestedCount);

        const entry: ResponseEntry = {
          index,
          status: result.status,
          headers: result.headers,
          body: result.body,
          message: result.message,
          transportError: result.transportError,
        };
        summary.responses.push(entry);

        if (result.status >= 200 && result.status < 300) {
          summary.success += 1;
        } else if (result.status === 429) {
          summary.rejected += 1;
          if (summary.first429Index === null) {
            summary.first429Index = index;
          }
          summary.lastRejection = entry;
        } else if (result.status === 404) {
          // A configured policy may name a path no handler serves. The filter already ran before
          // routing, so this response says nothing about the limit; keep going so a later 429 can
          // still show whether the policy is enforced.
          summary.notFound += 1;
        } else {
          // 401, 403, 503 or offline: the route is not behaving as this POC expects, so stop
          // rather than spending the remaining budget on a broken run.
          summary.error += 1;
          summary.lastError = entry;
          break;
        }
      }
    } finally {
      summary.completed = !summary.cancelled && summary.error === 0 && summary.totalSent === requestedCount;
      summary.inconclusive = !summary.completed;
      summary.elapsedMs = Date.now() - startedAt;
      this.running.set(false);
      this.summary.set({ ...summary });
    }

    return summary;
  }

  cancel(): void {
    this.running.set(false);
  }

  reset(): void {
    this.cancel();
    this.sent.set(0);
    this.summary.set(null);
  }
}
