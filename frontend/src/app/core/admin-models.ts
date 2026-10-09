/** Admin policy as returned by GET /api/admin/rate-limit/policies. Durations arrive as ISO-8601 strings. */
export interface AdminPolicy {
  id: string;
  name: string;
  method: string;
  path: string | null;
  algorithm: string;
  algorithmImplemented: boolean;
  scope: string;
  window: string | null;
  limit: number | null;
  capacity: number | null;
  refillInterval: string | null;
  cost: number | null;
  drainRate: number | null;
  queueCapacity: number | null;
  maxConcurrent: number | null;
  leaseDuration: string | null;
  enabled: boolean;
  onRedisError: 'FAIL_OPEN' | 'FAIL_CLOSED' | null;
  version: number;
  createdAt: string;
  updatedAt: string;
  updatedBy: string | null;
  parameterSummary: string;
  /** Where the policy is owned: a standalone document, a policy group rule, or a global scope rule. */
  source?: 'POLICY' | 'GROUP' | 'GLOBAL';
}

/** Body for create and update. Version carries the value the editor read. */
export interface PolicyEdit {
  id?: string;
  name?: string;
  method?: string;
  path?: string | null;
  algorithm?: string;
  scope?: string;
  window?: string | null;
  limit?: number | null;
  capacity?: number | null;
  refillInterval?: string | null;
  cost?: number | null;
  drainRate?: number | null;
  queueCapacity?: number | null;
  maxConcurrent?: number | null;
  leaseDuration?: string | null;
  enabled?: boolean | null;
  onRedisError?: 'FAIL_OPEN' | 'FAIL_CLOSED' | null;
  version?: number | null;
}

export interface ExemptionEdit {
  id: string;
  name?: string;
  method: string;
  path: string;
  enabled: boolean;
}

export interface ExemptionRecord {
  id: string;
  name: string;
  method: string;
  path: string;
  enabled: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
  updatedBy: string | null;
}

/**
 * One dropdown entry, produced per managed policy: the operator's policy plus the concrete request
 * that exercises it. `testable=false` entries stay visible with a reason rather than being dropped.
 */
export interface PolicyTargetRecord {
  /** Stable option key: the managed policy id, so one policy is one entry. */
  id: string;
  policyId: string;
  enabled: boolean;
  algorithm: string;
  scope: string;
  parameterSummary: string;
  /** As configured. May be `ANY`, a wildcard, a template, or null for a pathless scope. */
  configuredMethod: string;
  configuredPath: string | null;
  /** Concrete values to send. Null when the target is not testable. */
  method: string | null;
  concretePath: string | null;
  sampleQuery: string | null;
  matchedHandler: boolean;
  testable: boolean;
  requiresCredentials: boolean;
  note: string;
  reason: string;
  /** Every policy the limiter charges for this request, including the policy this entry is for. */
  enforcedWith: AdminPolicy[];
  exemptions: string[];
}

export interface DemoRouteCatalogResponse {
  targets: PolicyTargetRecord[];
  policies: AdminPolicy[];
}

export interface CapabilityOption {
  name: string;
  implemented: boolean;
  note: string;
}

export interface Capabilities {
  algorithms: CapabilityOption[];
  scopes: CapabilityOption[];
  composition: string;
  topology: string;
}

export interface AuditRecord {
  at: string;
  actor: string;
  policyId: string;
  operation: string;
  resultingVersion: number;
  changedFields: string[];
}

export interface AdminApiError {
  status: number;
  code: string;
  message: string;
  problems: string[];
}

export type FailureMode = 'FAIL_OPEN' | 'FAIL_CLOSED';
export type PolicyScope = 'ENDPOINT' | 'IP' | 'USER' | 'GLOBAL' | 'APPLICATION';
export type PolicyAlgorithm =
  | 'FIXED_WINDOW'
  | 'SLIDING_WINDOW'
  | 'SLIDING_WINDOW_COUNTER'
  | 'TOKEN_BUCKET'
  | 'LEAKY_BUCKET'
  | 'CONCURRENCY_LIMIT';

/** Matches PolicyGroup and the controller's GroupResponse JSON (projectionIds are not exposed). */
export interface PolicyGroup {
  id: string;
  name: string;
  enabled: boolean;
  endpoints: EndpointRule[];
  onRedisError: FailureMode | null;
  version: number;
  createdAt: string;
  updatedAt: string;
  updatedBy: string | null;
}

/** Matches EndpointRule and EndpointRuleDto. */
/** One rate-limit decision from the live traffic feed. Client is a masked IP; user is a hashed name. */
export interface TrafficEvent {
  id: number;
  at: string;
  method: string;
  path: string;
  outcome: 'ALLOWED' | 'REJECTED' | 'STORE_ERROR';
  status: number;
  policy: string;
  limit: number | null;
  remaining: number | null;
  retryAfterSeconds: number | null;
  client: string;
  user: string | null;
  /** Set for requests sent by the console's demo runner, so a decision matches its response. */
  run?: string | null;
  seq?: number | null;
}

/** Per-second outcome counts behind the live chart. */
export interface TrafficBucket {
  t: number;
  allowed: number;
  rejected: number;
  error: number;
}

export interface TrafficResponse {
  enabled: boolean;
  capacity: number;
  dropped: number;
  serverTime: string;
  events: TrafficEvent[];
  buckets: TrafficBucket[];
}

export interface EndpointRule {
  id: string;
  method: string;
  path: string;
  displayName: string;
  repeatable: boolean;
  exempt: boolean;
  scopeRules: ScopeRule[];
}

/** Durations are ISO-8601 strings in controller DTOs; null represents omitted algorithm parameters. */
export interface ScopeRule {
  scope: PolicyScope;
  algorithm: PolicyAlgorithm;
  window: string | null;
  limit: number | null;
  capacity: number | null;
  refillInterval: string | null;
  cost: number | null;
  drainRate: number | null;
  queueCapacity: number | null;
  maxConcurrent: number | null;
  leaseDuration: string | null;
  onRedisError: FailureMode | null;
}

/** Matches GlobalRulesResponse; update requests use the same writable fields plus version. */
export interface GlobalScopeRules {
  rules: ScopeRule[];
  onRedisError: FailureMode | null;
  version: number;
  createdAt: string;
  updatedAt: string;
  updatedBy: string | null;
}

/** Controller GroupRequest. Server-owned response metadata is intentionally excluded. */
export interface PolicyGroupEdit {
  id?: string;
  name?: string;
  enabled?: boolean;
  /** An endpoint without an id is new: the server generates the id and never lets it change. */
  endpoints: Array<Omit<EndpointRule, 'id'> & { id?: string }>;
  onRedisError?: FailureMode | null;
  version?: number;
}

/** Controller GlobalRulesRequest. */
export interface GlobalScopeRulesEdit {
  rules: ScopeRule[];
  onRedisError?: FailureMode | null;
  version: number;
}

export interface ProjectionRepairResult {
  groupId: string;
  written: number;
  deleted: number;
  at: string;
}

/** ISO-8601 duration (PT60S, PT1M, PT1H) to seconds. Null when absent or unparseable. */
export function durationToSeconds(value: string | null | undefined): number | null {
  if (!value) return null;
  const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(value);
  if (!match) return null;
  const hours = Number(match[1] ?? 0);
  const minutes = Number(match[2] ?? 0);
  const seconds = Number(match[3] ?? 0);
  return hours * 3600 + minutes * 60 + seconds;
}

/** Seconds to an ISO-8601 duration the backend accepts. */
export function secondsToDuration(seconds: number): string {
  return `PT${Math.max(1, Math.floor(seconds))}S`;
}
