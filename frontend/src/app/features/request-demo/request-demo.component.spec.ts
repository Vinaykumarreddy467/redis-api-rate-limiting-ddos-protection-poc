import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';

import { AdminApiService } from '../../core/admin-api.service';
import { AdminPolicy, DemoRouteCatalogResponse, PolicyTargetRecord, PolicyGroup, EndpointRule, ScopeRule } from '../../core/admin-models';
import { RequestDemoComponent } from './request-demo.component';

const BASE = '/api/admin/rate-limit';

function scopeRule(overrides: Partial<ScopeRule> = {}): ScopeRule {
  return {
    scope: 'IP',
    algorithm: 'FIXED_WINDOW',
    window: 'PT1M',
    limit: 100,
    capacity: null,
    refillInterval: null,
    cost: null,
    drainRate: null,
    queueCapacity: null,
    maxConcurrent: null,
    leaseDuration: null,
    onRedisError: null,
    ...overrides,
  };
}

function endpointRule(overrides: Partial<EndpointRule> = {}): EndpointRule {
  return {
    id: 'ep-1',
    method: 'GET',
    path: '/api/products',
    displayName: 'Products Read',
    repeatable: true,
    exempt: false,
    scopeRules: [scopeRule()],
    ...overrides,
  };
}

function demoTarget(overrides: Partial<PolicyTargetRecord> = {}): PolicyTargetRecord {
  return target({
    method: 'GET',
    concretePath: '/api/products',
    requiresCredentials: false,
    ...overrides,
  });
}

function policyGroup(overrides: Partial<PolicyGroup> = {}): PolicyGroup {
  return {
    id: 'group-1',
    name: 'Products API',
    enabled: true,
    endpoints: [endpointRule()],
    onRedisError: null,
    version: 1,
    createdAt: '2026-10-05T09:00:00Z',
    updatedAt: '2026-10-05T09:30:00Z',
    updatedBy: 'pocadmin',
    ...overrides,
  };
}

function policy(overrides: Partial<AdminPolicy> = {}): AdminPolicy {
  return {
    id: 'products-read',
    name: 'products-read',
    method: 'GET',
    path: '/api/products',
    algorithm: 'FIXED_WINDOW',
    algorithmImplemented: true,
    scope: 'IP',
    window: 'PT1M',
    limit: 125,
    capacity: null,
    refillInterval: null,
    cost: null,
    drainRate: null,
    queueCapacity: null,
    maxConcurrent: null,
    leaseDuration: null,
    enabled: true,
    onRedisError: null,
    version: 12,
    createdAt: '2026-10-05T09:00:00Z',
    updatedAt: '2026-10-05T09:30:00Z',
    updatedBy: 'pocadmin',
    parameterSummary: '125 per 1 minute',
    ...overrides,
  };
}

function target(overrides: Partial<PolicyTargetRecord> = {}): PolicyTargetRecord {
  const configuredPolicy = policy({
    id: overrides.policyId ?? 'products-read',
    method: overrides.configuredMethod ?? 'GET',
    path: overrides.configuredPath === undefined ? '/api/products' : overrides.configuredPath,
    algorithm: overrides.algorithm ?? 'FIXED_WINDOW',
    scope: overrides.scope ?? 'IP',
    enabled: overrides.enabled ?? true,
    parameterSummary: overrides.parameterSummary ?? '125 per 1 minute',
  });
  return {
    id: configuredPolicy.id,
    policyId: configuredPolicy.id,
    enabled: configuredPolicy.enabled,
    algorithm: configuredPolicy.algorithm,
    scope: configuredPolicy.scope,
    parameterSummary: configuredPolicy.parameterSummary,
    configuredMethod: configuredPolicy.method,
    configuredPath: configuredPolicy.path,
    method: 'GET',
    concretePath: '/api/products',
    sampleQuery: '',
    matchedHandler: true,
    testable: true,
    requiresCredentials: false,
    note: 'Safe read endpoint.',
    reason: '',
    enforcedWith: [configuredPolicy],
    exemptions: [],
    ...overrides,
  };
}

describe('RequestDemoComponent', () => {
  let fixture: ComponentFixture<RequestDemoComponent>;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RequestDemoComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    const login = firstValueFrom(TestBed.inject(AdminApiService).login('pocadmin', 'test-only'));
    http.expectOne(`${BASE}/policies`).flush([]);
    await login;
    fixture = TestBed.createComponent(RequestDemoComponent);
    fixture.detectChanges();
  });

  const settle = async () => {
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  };

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';
  const options = () =>
    Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLSelectElement>(
        'select[name="target"] option',
      ),
    ).map((option) => option.textContent?.trim());

  const load = async (targets: PolicyTargetRecord[]) => {
    const response: DemoRouteCatalogResponse = {
      targets,
      policies: targets.map((entry) => policy({ id: entry.policyId })),
    };
    http.expectOne(`${BASE}/demo-routes`).flush(response);
    http.expectOne(`${BASE}/groups`).flush([]);
    await settle();
    await setMode('legacy');
  };

  const loadGroups = async (groups: PolicyGroup[], targets: PolicyTargetRecord[] = [demoTarget()]) => {
    http.expectOne(`${BASE}/groups`).flush(groups);
    http.expectOne(`${BASE}/demo-routes`).flush({ targets, policies: targets.map(t => policy({ id: t.policyId })) });
    await settle();
  };

  const groupOptions = () =>
    Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLSelectElement>(
        'select[name="group"] option',
      ),
    ).map((option) => option.textContent?.trim());

  const endpointOptions = () =>
    Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLSelectElement>(
        'select[name="endpoint"] option',
      ),
    ).map((option) => option.textContent?.trim());

  const modeSelect = () =>
    (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="mode"]')!;

  const setMode = async (mode: 'groups' | 'legacy') => {
    const select = modeSelect();
    select.value = mode;
    select.dispatchEvent(new Event('change'));
    await settle();
  };

  const setCount = async (count: number) => {
    const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[name="count"]')!;
    input.value = String(count);
    input.dispatchEvent(new Event('input'));
    await settle();
  };

  it('shows one dropdown option for every configured policy, including an orphan and non-testable policy', async () => {
    await load([
      target({ policyId: 'normal', parameterSummary: '50 per 1 minute' }),
      target({
        policyId: 'login-attempt',
        configuredMethod: 'POST',
        configuredPath: '/api/login',
        method: 'POST',
        concretePath: '/api/login',
        sampleQuery: 'user=demo',
        matchedHandler: true,
        testable: false,
        reason: 'This route must not be replayed automatically.',
      }),
      target({
        policyId: 'disabled-policy',
        enabled: false,
        testable: false,
        method: null,
        concretePath: null,
        reason: 'This policy is disabled, so it is not enforced.',
        enforcedWith: [],
      }),
      target({
        policyId: 'orphan-policy',
        configuredPath: '/api/my-new-read-route',
        concretePath: '/api/my-new-read-route',
        matchedHandler: false,
        note: 'No handler serves this path; the limiter runs before routing.',
      }),
    ]);

    expect(options()).toHaveLength(4);
    expect(options().join('\n')).toContain('normal');
    expect(options().join('\n')).toContain('login-attempt');
    expect(options().join('\n')).toContain('disabled-policy');
    expect(options().join('\n')).toContain('orphan-policy');
    expect(options().join('\n')).toContain('cannot test automatically');
    expect(options().join('\n')).toContain('disabled, not enforced');
  });

  it('lets the operator inspect a non-testable policy but prevents sending it', async () => {
    await load([
      target({
        policyId: 'unsafe-policy',
        testable: false,
        reason: 'This operation is not safe to replay automatically.',
      }),
    ]);

    const select = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>(
      'select[name="target"]',
    )!;
    select.value = 'unsafe-policy';
    select.dispatchEvent(new Event('change'));
    await settle();

    expect(text()).toContain('This operation is not safe to replay automatically.');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[type="submit"]')
        ?.disabled,
    ).toBe(true);
  });

  it('changes selected policy details when the dropdown selection changes', async () => {
    await load([
      target({ policyId: 'products-read', configuredPath: '/api/products' }),
      target({
        policyId: 'new-read-policy',
        configuredPath: '/api/new-read',
        concretePath: '/api/new-read',
        parameterSummary: '7 per 1 minute',
        enforcedWith: [policy({ id: 'new-read-policy', path: '/api/new-read', limit: 7 })],
      }),
    ]);

    const select = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>(
      'select[name="target"]',
    )!;
    select.value = 'new-read-policy';
    select.dispatchEvent(new Event('change'));
    await settle();

    expect(select.value).toBe('new-read-policy');
    expect(text()).toContain('new-read-policy');
    expect(text()).toContain('GET /api/new-read');
    expect(text()).toContain('7 per 1 minute');
  });

  it('exits loading with an actionable error when backend returns an old or invalid catalog shape', async () => {
    http.expectOne(`${BASE}/demo-routes`).flush({ routes: [], policiesWithoutHandler: [] });
    http.expectOne(`${BASE}/groups`).flush([]);
    await settle();
    await setMode('legacy');

    expect(text()).not.toContain('Loading your policies');
    expect(text()).toContain('backend is probably running an older build');
    expect(options()).toEqual(['Targets unavailable']);
    expect(
      (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button.primary')
        ?.disabled,
    ).toBe(false);
  });

  it('shows each co-matching policy with its own algorithm and parameters', async () => {
    const normal = policy({ id: 'normal', limit: 50, parameterSummary: '50 per 1 minute' });
    const burst = policy({
      id: 'burst',
      algorithm: 'TOKEN_BUCKET',
      scope: 'APPLICATION',
      limit: null,
      capacity: 50,
      parameterSummary: 'capacity 50, refill 1 per 5 s',
    });
    await load([
      target({
        policyId: 'normal',
        parameterSummary: normal.parameterSummary,
        enforcedWith: [normal, burst],
      }),
    ]);

    expect(text()).toContain('normal');
    expect(text()).toContain('50 per 1 minute');
    expect(text()).toContain('burst');
    expect(text()).toContain('TOKEN_BUCKET');
    expect(text()).toContain('APPLICATION');
    expect(text()).toContain('capacity 50, refill 1 per 5 s');
    expect(text()).toContain('Selecting normal does not isolate it');
  });

  it('shows a loading state and provides a retry when policy targets fail to load', async () => {
    expect(text()).toContain('Loading your policies');

    http.expectOne(`${BASE}/demo-routes`).flush('', { status: 500, statusText: 'Server Error' });
    http.expectOne(`${BASE}/groups`).flush([]);
    await settle();
    await setMode('legacy');
    expect(text()).toContain('Policy targets could not be loaded');
    expect(options()).toEqual(['Targets unavailable']);

    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('button.primary')!
      .click();
    fixture.detectChanges();
    await settle();
    await load([target()]);

    expect(options()).toHaveLength(1);
    expect(text()).not.toContain('Policy targets could not be loaded');
  });

  // --- Policy Groups mode tests ---

  it('shows policy groups and their endpoints in groups mode', async () => {
    await loadGroups([
      policyGroup({
        id: 'group-1',
        name: 'Products API',
        endpoints: [
          endpointRule({ id: 'ep-1', method: 'GET', path: '/api/products', displayName: 'Products Read' }),
          endpointRule({ id: 'ep-2', method: 'POST', path: '/api/products', displayName: 'Products Create' }),
        ],
      }),
      policyGroup({
        id: 'group-2',
        name: 'Orders API',
        endpoints: [
          endpointRule({ id: 'ep-3', method: 'GET', path: '/api/orders', displayName: 'Orders Read' }),
        ],
      }),
    ]);

    await setMode('groups');

    expect(groupOptions()).toHaveLength(2);
    expect(groupOptions()[0]).toContain('Products API (enabled)');
    expect(groupOptions()[1]).toContain('Orders API (enabled)');

    // Select first group and check its endpoints
    const groupSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="group"]')!;
    groupSelect.value = 'group-1';
    groupSelect.dispatchEvent(new Event('change'));
    await settle();

    expect(endpointOptions()).toHaveLength(2);
    expect(endpointOptions()[0]).toContain('Products Read — GET /api/products');
    expect(endpointOptions()[1]).toContain('Products Create — POST /api/products');
  });

  it('shows endpoint details when an endpoint is selected in groups mode', async () => {
    await loadGroups([
      policyGroup({
        id: 'group-1',
        name: 'Products API',
        endpoints: [
          endpointRule({ id: 'ep-1', method: 'GET', path: '/api/products', displayName: 'Products Read' }),
        ],
      }),
    ]);

    await setMode('groups');

    const groupSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="group"]')!;
    groupSelect.value = 'group-1';
    groupSelect.dispatchEvent(new Event('change'));
    await settle();

    const endpointSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="endpoint"]')!;
    endpointSelect.value = 'ep-1';
    endpointSelect.dispatchEvent(new Event('change'));
    await settle();

    expect(text()).toContain('GET /api/products');
    expect(text()).toContain('subject to configured rules');
  });

  it('shows exempt endpoint notice when endpoint is exempt from rate limits', async () => {
    await loadGroups([
      policyGroup({
        id: 'group-1',
        name: 'Products API',
        endpoints: [
          endpointRule({ id: 'ep-1', method: 'GET', path: '/api/health', displayName: 'Health Check', exempt: true }),
        ],
      }),
    ]);

    await setMode('groups');

    const groupSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="group"]')!;
    groupSelect.value = 'group-1';
    groupSelect.dispatchEvent(new Event('change'));
    await settle();

    const endpointSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="endpoint"]')!;
    endpointSelect.value = 'ep-1';
    endpointSelect.dispatchEvent(new Event('change'));
    await settle();

    expect(text()).toContain('exempt from all rate limits');
  });

  it('disables endpoint dropdown when selected group has no endpoints', async () => {
    await loadGroups([
      policyGroup({
        id: 'group-1',
        name: 'Empty Group',
        endpoints: [],
      }),
    ]);

    await setMode('groups');

    const groupSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="group"]')!;
    groupSelect.value = 'group-1';
    groupSelect.dispatchEvent(new Event('change'));
    await settle();

    const endpointSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="endpoint"]')!;
    expect(endpointSelect.disabled).toBe(true);
  });

  it('shows hint when no policy groups are available', async () => {
    await loadGroups([]);

    await setMode('groups');

    expect(text()).toContain('No policy groups available. Select the legacy demo catalog.');
  });

  it('explains why a group endpoint outside /api/ cannot be run instead of leaving a silent dead button', async () => {
    await loadGroups(
      [policyGroup({ endpoints: [endpointRule({ path: '/lol/lol', displayName: 'lol' })] })],
      [target({ policyId: 'login-attempt', configuredMethod: 'POST', configuredPath: '/api/login' })],
    );

    await setMode('groups');

    const start = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[type="submit"]')!;
    expect(start.disabled).toBe(true);
    expect(text()).toContain('/lol/lol is outside that, so it cannot be run from here');
    // The legacy catalogue's first policy must not be described as if it belonged to this endpoint.
    expect(text()).not.toContain('The console sends');
    expect(text()).not.toContain('login-attempt');
  });

  it('runs a group endpoint even when the legacy catalogue has no targets', async () => {
    await loadGroups([policyGroup()], []);

    await setMode('groups');

    const start = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[type="submit"]')!;
    expect(start.disabled).toBe(false);
  });

  it('legacy mode still works when switched back from groups mode', async () => {
    await loadGroups(
      [policyGroup()],
      [target({ policyId: 'legacy-policy', configuredPath: '/api/legacy' })],
    );

    await setMode('groups');
    await setMode('legacy');

    expect(options()).toHaveLength(1);
    expect(options()[0]).toContain('legacy-policy');
  });

  // --- Request execution tests ---

  it('sends requests to group endpoint and records results', async () => {
    await loadGroups([
      policyGroup({
        id: 'group-1',
        name: 'Products API',
        endpoints: [endpointRule({ id: 'ep-1', method: 'GET', path: '/api/products', displayName: 'Products Read' })],
      }),
    ]);

    await setMode('groups');

    const groupSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="group"]')!;
    groupSelect.value = 'group-1';
    groupSelect.dispatchEvent(new Event('change'));
    await settle();

    const endpointSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="endpoint"]')!;
    endpointSelect.value = 'ep-1';
    endpointSelect.dispatchEvent(new Event('change'));
    await settle();

    await setCount(3);

    const runPromise = fixture.componentInstance.onStart();
    await settle();

    // Answer 3 requests with 200
    for (let i = 0; i < 3; i++) {
      const req = http.expectOne('/api/products');
      expect(req.request.method).toBe('GET');
      req.flush({ ok: true });
      await settle();
    }

    const summary = await runPromise;
    expect(summary?.totalSent).toBe(3);
    expect(summary?.success).toBe(3);
    expect(summary?.rejected).toBe(0);
    expect(summary?.completed).toBe(true);
  });

  it('records first 429 index and rate-limit headers', async () => {
    await loadGroups([
      policyGroup({
        id: 'group-1',
        name: 'Products API',
        endpoints: [endpointRule({ id: 'ep-1', method: 'GET', path: '/api/products', displayName: 'Products Read' })],
      }),
    ]);

    await setMode('groups');

    await setCount(3);

    const groupSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="group"]')!;
    groupSelect.value = 'group-1';
    groupSelect.dispatchEvent(new Event('change'));
    await settle();

    const endpointSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="endpoint"]')!;
    endpointSelect.value = 'ep-1';
    endpointSelect.dispatchEvent(new Event('change'));
    await settle();

    const runPromise = fixture.componentInstance.onStart();
    await settle();

    // First request: 200
    http.expectOne('/api/products').flush({ ok: true });
    await settle();

    // Second request: 429 with rate-limit headers
    const req2 = http.expectOne('/api/products');
    req2.flush(
      { message: 'Rate limit exceeded', policy: 'products-read' },
      {
        status: 429,
        statusText: 'Too Many Requests',
        headers: {
          'Retry-After': '42',
          'X-RateLimit-Limit': '100',
          'X-RateLimit-Remaining': '0',
          'X-RateLimit-Policy': 'products-read',
        },
      },
    );
    await settle();

    // Third request: 429
    http.expectOne('/api/products').flush(
      { message: 'Rate limit exceeded' },
      { status: 429, statusText: 'Too Many Requests' },
    );
    await settle();

    const summary = await runPromise;
    expect(summary?.totalSent).toBe(3);
    expect(summary?.success).toBe(1);
    expect(summary?.rejected).toBe(2);
    expect(summary?.first429Index).toBe(2);
    expect(summary?.responses[1]?.headers).toEqual({
      retryAfter: '42',
      limit: '100',
      remaining: '0',
      policy: 'products-read',
    });
  });

  it('stops on 401 and reports error instead of spending budget', async () => {
    await loadGroups([
      policyGroup({
        id: 'group-1',
        name: 'Products API',
        endpoints: [endpointRule({ id: 'ep-1', method: 'GET', path: '/api/products', displayName: 'Products Read' })],
      }),
    ], [demoTarget({ requiresCredentials: true })]);

    await setMode('groups');

    const groupSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="group"]')!;
    groupSelect.value = 'group-1';
    groupSelect.dispatchEvent(new Event('change'));
    await settle();

    const endpointSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="endpoint"]')!;
    endpointSelect.value = 'ep-1';
    endpointSelect.dispatchEvent(new Event('change'));
    await settle();

    // Fill credentials
    const userInput = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[name="username"]')!;
    userInput.value = 'alice';
    userInput.dispatchEvent(new Event('input'));
    await settle();

    const passInput = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[name="password"]')!;
    passInput.value = 'wrong';
    passInput.dispatchEvent(new Event('input'));
    await settle();

    const runPromise = fixture.componentInstance.onStart();
    await settle();

    const req = http.expectOne('/api/products');
    expect(req.request.headers.get('Authorization')).toContain('Basic ');
    req.flush({ error: 'Unauthorized' }, { status: 401, statusText: 'Unauthorized' });
    await settle();

    const summary = await runPromise;
    expect(summary?.totalSent).toBe(1);
    expect(summary?.error).toBe(1);
    expect(summary?.lastError?.status).toBe(401);
    expect(summary?.inconclusive).toBe(true);
  });

  it('can be cancelled mid-run and reports as inconclusive', async () => {
    await loadGroups([
      policyGroup({
        id: 'group-1',
        name: 'Products API',
        endpoints: [endpointRule({ id: 'ep-1', method: 'GET', path: '/api/products', displayName: 'Products Read' })],
      }),
    ]);

    await setMode('groups');
    await setCount(3);

    const groupSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="group"]')!;
    groupSelect.value = 'group-1';
    groupSelect.dispatchEvent(new Event('change'));
    await settle();

    const endpointSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="endpoint"]')!;
    endpointSelect.value = 'ep-1';
    endpointSelect.dispatchEvent(new Event('change'));
    await settle();

    const runPromise = fixture.componentInstance.onStart();
    await settle();

    // Answer first request
    http.expectOne('/api/products').flush({ ok: true });
    // Cancel synchronously before the runner's next microtask can dispatch request two.
    const cancelButton = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.actions button[type="button"]')!;
    cancelButton.click();
    await settle();

    const summary = await runPromise;
    expect(summary?.totalSent).toBe(1);
    expect(summary?.cancelled).toBe(true);
    expect(summary?.completed).toBe(false);
    expect(summary?.inconclusive).toBe(true);
  });

  // --- Response expand/collapse tests ---

  it('shows response details in collapsible entry after run completes', async () => {
    await loadGroups([
      policyGroup({
        id: 'group-1',
        name: 'Products API',
        endpoints: [endpointRule({ id: 'ep-1', method: 'GET', path: '/api/products', displayName: 'Products Read' })],
      }),
    ]);

    await setMode('groups');
    await setCount(1);

    const groupSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="group"]')!;
    groupSelect.value = 'group-1';
    groupSelect.dispatchEvent(new Event('change'));
    await settle();

    const endpointSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="endpoint"]')!;
    endpointSelect.value = 'ep-1';
    endpointSelect.dispatchEvent(new Event('change'));
    await settle();

    const runPromise = fixture.componentInstance.onStart();
    await settle();

    http.expectOne('/api/products').flush({ ok: true });
    await settle();

    await runPromise;
    await settle();

    // Check that response details section exists
    const details = (fixture.nativeElement as HTMLElement).querySelector('details.response-entry');
    expect(details).toBeTruthy();
    expect(details?.textContent).toContain('Request #1 — HTTP 200 OK');
    expect(details?.textContent).toContain('HTTP 200');
  });

  it('keeps every response in one scrollable side panel with expand, collapse and a filter', async () => {
    await loadGroups([
      policyGroup({
        id: 'group-1',
        name: 'Products API',
        endpoints: [endpointRule({ id: 'ep-1', method: 'GET', path: '/api/products', displayName: 'Products Read' })],
      }),
    ]);
    await setMode('groups');
    await setCount(2);
    const root = fixture.nativeElement as HTMLElement;
    const groupSelect = root.querySelector<HTMLSelectElement>('select[name="group"]')!;
    groupSelect.value = 'group-1';
    groupSelect.dispatchEvent(new Event('change'));
    await settle();
    const endpointSelect = root.querySelector<HTMLSelectElement>('select[name="endpoint"]')!;
    endpointSelect.value = 'ep-1';
    endpointSelect.dispatchEvent(new Event('change'));
    await settle();

    const run = fixture.componentInstance.onStart();
    await settle();
    http.expectOne('/api/products').flush({ ok: true });
    await settle();
    http.expectOne('/api/products').flush({ message: 'slow down' }, { status: 429, statusText: 'Too Many Requests' });
    await settle();
    await run;
    await settle();

    const panel = root.querySelector('aside.responses-panel')!;
    expect(panel).not.toBeNull();
    expect(panel.querySelector('.responses-scroll')?.getAttribute('tabindex')).toBe('0');
    expect(panel.querySelectorAll('details.response-entry')).toHaveLength(2);
    expect(panel.querySelector('.responses-summary')?.textContent).toContain('1 successful');
    expect(panel.querySelector('.responses-summary')?.textContent).toContain('1 rejected');

    const button = (label: string) => Array.from(panel.querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === label)!;
    const open = () => Array.from(panel.querySelectorAll<HTMLDetailsElement>('details.response-entry'))
      .map((d) => d.open);

    expect(open()).toEqual([false, false]);
    button('Expand all').click();
    await settle();
    expect(open()).toEqual([true, true]);
    button('Collapse all').click();
    await settle();
    expect(open()).toEqual([false, false]);

    const filter = panel.querySelector<HTMLSelectElement>('select[name="response-filter"]')!;
    filter.value = 'rejected';
    filter.dispatchEvent(new Event('change'));
    await settle();
    const shown = panel.querySelectorAll('details.response-entry');
    expect(shown).toHaveLength(1);
    expect(shown[0].textContent).toContain('HTTP 429');
  });

  it('response details can be expanded and collapsed', async () => {
    await loadGroups([
      policyGroup({
        id: 'group-1',
        name: 'Products API',
        endpoints: [endpointRule({ id: 'ep-1', method: 'GET', path: '/api/products', displayName: 'Products Read' })],
      }),
    ]);

    await setMode('groups');
    await setCount(1);

    const groupSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="group"]')!;
    groupSelect.value = 'group-1';
    groupSelect.dispatchEvent(new Event('change'));
    await settle();

    const endpointSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="endpoint"]')!;
    endpointSelect.value = 'ep-1';
    endpointSelect.dispatchEvent(new Event('change'));
    await settle();

    const runPromise = fixture.componentInstance.onStart();
    await settle();

    http.expectOne('/api/products').flush({ ok: true });
    await settle();

    await runPromise;
    await settle();

    const details = (fixture.nativeElement as HTMLElement).querySelector<HTMLDetailsElement>('details.response-entry');
    expect(details).toBeTruthy();

    // Initially closed (open attribute not set)
    expect(details?.open).toBe(false);

    // Click summary to expand
    const summary = details?.querySelector('summary');
    summary?.click();
    await settle();

    expect(details?.open).toBe(true);

    // Click again to collapse
    summary?.click();
    await settle();

    expect(details?.open).toBe(false);
  });

  it('shows last rejection details when 429 occurs', async () => {
    await loadGroups([
      policyGroup({
        id: 'group-1',
        name: 'Products API',
        endpoints: [endpointRule({ id: 'ep-1', method: 'GET', path: '/api/products', displayName: 'Products Read' })],
      }),
    ]);

    await setMode('groups');
    await setCount(2);

    const groupSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="group"]')!;
    groupSelect.value = 'group-1';
    groupSelect.dispatchEvent(new Event('change'));
    await settle();

    const endpointSelect = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[name="endpoint"]')!;
    endpointSelect.value = 'ep-1';
    endpointSelect.dispatchEvent(new Event('change'));
    await settle();

    const runPromise = fixture.componentInstance.onStart();
    await settle();

    // First request: 200
    http.expectOne('/api/products').flush({ ok: true });
    await settle();

    // Second request: 429
    const req2 = http.expectOne('/api/products');
    req2.flush(
      { message: 'Rate limit exceeded', policy: 'products-read' },
      {
        status: 429,
        statusText: 'Too Many Requests',
        headers: {
          'Retry-After': '42',
          'X-RateLimit-Limit': '100',
          'X-RateLimit-Remaining': '0',
          'X-RateLimit-Policy': 'products-read',
        },
      },
    );
    await settle();

    await runPromise;
    await settle();

    // Check inspector section for last rejection
    const inspector = (fixture.nativeElement as HTMLElement).querySelector('.inspector');
    expect(inspector).toBeTruthy();
    expect(inspector?.textContent).toContain('Last rejected response');
    expect(inspector?.textContent).toContain('HTTP 429');
    expect(inspector?.textContent).toContain('Retry-After');
    expect(inspector?.textContent).toContain('X-RateLimit-Limit');
    expect(inspector?.textContent).toContain('X-RateLimit-Remaining');
    expect(inspector?.textContent).toContain('X-RateLimit-Policy');
  });
});
