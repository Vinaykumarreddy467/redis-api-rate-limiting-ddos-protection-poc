import { AdminPolicy, PolicyTargetRecord } from './admin-models';

/** One concrete request the console may send, plus the label the summary reports. */
export interface DemoRoute {
  id: string;
  label: string;
  /** Concrete verb from the catalog. Widened beyond GET/POST because a policy may name any method. */
  method: string;
  path: string;
  needsAuth: boolean;
  note: string;
}

/** A managed group endpoint, actionable only after it is resolved from the authenticated group catalog. */
export interface GroupDemoEndpoint {
  id: string;
  groupId: string;
  groupName: string;
  method: string;
  path: string;
  displayName: string;
  exempt: boolean;
  enabled: boolean;
  requiresCredentials: boolean;
  note: string;
}

export const DEFAULT_REQUEST_COUNT = 20;
export const MAX_REQUEST_COUNT = 150;

export function clampRequestCount(value: number | string): number {
  const parsed = typeof value === 'number' ? value : Number.parseInt(value, 10);
  if (!Number.isFinite(parsed) || parsed < 1) return 1;
  return Math.min(Math.floor(parsed), MAX_REQUEST_COUNT);
}

export function formatWindow(seconds: number): string {
  if (seconds < 60) return `${seconds} s`;
  const minutes = seconds / 60;
  return minutes === 1 ? '1 minute' : `${minutes} minutes`;
}

/**
 * Validates the catalog response before it is allowed into the store.
 *
 * TypeScript types are erased at runtime, so a backend running an older build answers with a
 * different shape and `response.targets` is simply `undefined`. Left unchecked, the `.map()` on it
 * throws inside the loader and the UI never leaves its loading state. Every field the console
 * dereferences is checked here, so a contract mismatch becomes a readable error instead.
 *
 * @throws Error with an operator-actionable message when the shape is not the catalog contract.
 */
export function parseDemoTargets(response: unknown): PolicyTargetRecord[] {
  if (typeof response !== 'object' || response === null) {
    throw new Error('the catalog response was not an object');
  }
  const targets = (response as { targets?: unknown }).targets;
  if (!Array.isArray(targets)) {
    const keys = Object.keys(response as object).sort().join(', ') || 'none';
    throw new Error(
      `the API did not return a policy-target catalog (no "targets" array; top-level keys: ${keys}). ` +
        'The backend is probably running an older build than this console.',
    );
  }
  return targets.map((raw, index) => parseTarget(raw, index));
}

const STRING_FIELDS = ['policyId', 'algorithm', 'scope', 'parameterSummary', 'configuredMethod', 'note', 'reason'] as const;
const BOOLEAN_FIELDS = ['enabled', 'matchedHandler', 'testable', 'requiresCredentials'] as const;

function parseTarget(raw: unknown, index: number): PolicyTargetRecord {
  if (typeof raw !== 'object' || raw === null) {
    throw new Error(`target #${index} was not an object`);
  }
  const item = raw as Record<string, unknown>;

  for (const field of STRING_FIELDS) {
    if (typeof item[field] !== 'string') {
      throw new Error(`target #${index} is missing a valid "${field}"`);
    }
  }
  for (const field of BOOLEAN_FIELDS) {
    if (typeof item[field] !== 'boolean') {
      throw new Error(`target #${index} is missing a valid "${field}"`);
    }
  }
  // Nullable: a pathless scope has no configured path, and an untestable target has no request.
  if (item['configuredPath'] !== null && typeof item['configuredPath'] !== 'string') {
    throw new Error(`target #${index} has an invalid "configuredPath"`);
  }
  for (const field of ['method', 'concretePath', 'sampleQuery'] as const) {
    if (item[field] !== null && typeof item[field] !== 'string') {
      throw new Error(`target #${index} has an invalid "${field}"`);
    }
  }
  if (!Array.isArray(item['enforcedWith'])) {
    throw new Error(`target #${index} is missing an "enforcedWith" list`);
  }
  if (!Array.isArray(item['exemptions']) || item['exemptions'].some((e) => typeof e !== 'string')) {
    throw new Error(`target #${index} is missing a valid "exemptions" list`);
  }

  const policyId = item['policyId'] as string;
  return {
    id: policyId,
    policyId,
    enabled: item['enabled'] as boolean,
    algorithm: item['algorithm'] as string,
    scope: item['scope'] as string,
    parameterSummary: item['parameterSummary'] as string,
    configuredMethod: item['configuredMethod'] as string,
    configuredPath: item['configuredPath'] as string | null,
    method: item['method'] as string | null,
    concretePath: item['concretePath'] as string | null,
    sampleQuery: item['sampleQuery'] as string | null,
    matchedHandler: item['matchedHandler'] as boolean,
    testable: item['testable'] as boolean,
    requiresCredentials: item['requiresCredentials'] as boolean,
    note: item['note'] as string,
    reason: item['reason'] as string,
    enforcedWith: item['enforcedWith'] as AdminPolicy[],
    exemptions: item['exemptions'] as string[],
  };
}

/**
 * Appends the sample query the backend chose. Matching stays on the backend: it uses the same
 * matcher the enforcement filter uses, which this component cannot reproduce faithfully.
 */
export function requestUrl(target: Pick<PolicyTargetRecord, 'concretePath' | 'sampleQuery'>): string {
  const path = target.concretePath ?? '';
  return target.sampleQuery ? `${path}?${target.sampleQuery}` : path;
}

/** How the policy names its route: the configured method and path, or its scope when pathless. */
export function configuredRoute(target: Pick<PolicyTargetRecord, 'configuredMethod' | 'configuredPath' | 'scope'>): string {
  if (target.configuredPath) return `${target.configuredMethod} ${target.configuredPath}`;
  return `${target.scope} (no path)`;
}

/**
 * One dropdown option per policy: its id, how it is configured, and its algorithm and parameters.
 * Disabled and untestable policies stay labelled rather than hidden, so the operator can see why.
 */
export function targetLabel(target: PolicyTargetRecord): string {
  const suffix = !target.enabled
    ? ' — disabled, not enforced'
    : !target.testable
      ? ' — cannot test automatically'
      : '';
  return `${target.policyId} — ${configuredRoute(target)} · ${target.algorithm} · ${target.parameterSummary}${suffix}`;
}
