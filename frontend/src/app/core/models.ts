/** Read-only policy metadata served by GET /api/poc/policies (added for this console). */
export interface PolicySummary {
  id: string;
  method: string;
  path: string | null;
  limit: number | null;
  windowSeconds: number | null;
  identity: string;
  /** Absent on backends older than the managed-metadata change, so the table must tolerate null. */
  algorithm?: string | null;
  scope?: string | null;
  parameterSummary?: string | null;
  enabled?: boolean;
  version?: number;
  redisFailureMode: 'FAIL_OPEN' | 'FAIL_CLOSED';
  redisFailureModeLabel: string;
}

export interface PolicyResponse {
  source: string;
  editable: boolean;
  limiterEnabled: boolean;
  defaultRedisFailureMode: string;
  policyCount: number;
  policies: PolicySummary[];
}

export interface HealthResponse {
  status: 'UP' | 'DOWN' | string;
}

/**
 * Actuator metric payload. Verified shape:
 * { name, description, measurements: [{ statistic: 'COUNT', value }], availableTags: [{ tag, values }] }
 * An unknown tag value returns HTTP 404 with an empty body, which is "no data", not an error.
 */
export interface MetricResponse {
  name: string;
  description?: string;
  measurements: { statistic: string; value: number }[];
  availableTags?: { tag: string; values: string[] }[];
}

export interface RejectionHeaders {
  retryAfter: string | null;
  limit: string | null;
  remaining: string | null;
  policy: string | null;
}

export interface ResponseEntry {
  index: number;
  status: number;
  headers: RejectionHeaders;
  /** Full response body for pretty-printing, null if not JSON or empty. */
  body: unknown | null;
  /** Extracted message field for quick display. */
  message: string | null;
  /** Set when the request never produced a response, e.g. offline or cancelled. */
  transportError: string | null;
}

export type DemoOutcome = 'success' | 'rejected' | 'error';

export interface DemoSummary {
  routeId: string | null;
  totalSent: number;
  success: number;
  rejected: number;
  /**
   * 404s from downstream routing. A configured policy may name a path no handler serves; the filter
   * runs before routing, so these requests were still charged. Counted apart from rejections.
   */
  notFound: number;
  error: number;
  statuses: Record<number, number>;
  first429Index: number | null;
  elapsedMs: number;
  completed: boolean;
  cancelled: boolean;
  inconclusive: boolean;
  /** All responses in order, for per-request display. */
  responses: ResponseEntry[];
  /** Convenience: last rejection (429) for the inspector panel. */
  lastRejection: ResponseEntry | null;
  /** Convenience: last error (non-429 failure) for the inspector panel. */
  lastError: ResponseEntry | null;
}
