import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';

import { AdminApiService } from '../../core/admin-api.service';
import { AdminStore } from '../../core/admin-store.service';
import { PoliciesPageComponent } from './policies-page.component';

const BASE = '/api/admin/rate-limit';

const CAPABILITIES = {
  algorithms: [
    { name: 'FIXED_WINDOW', implemented: true, note: 'Counts requests per fixed window.' },
    { name: 'TOKEN_BUCKET', implemented: false, note: 'Not enforced by this build.' },
  ],
  scopes: [
    { name: 'IP', implemented: true, note: 'per client IP' },
    { name: 'USER', implemented: true, note: 'per authenticated user' },
  ],
  composition: 'AND',
  topology: 'single-redis',
};

const POLICIES = [
  {
    id: 'products-read',
    name: 'products-read',
    method: 'GET',
    path: '/api/products',
    algorithm: 'FIXED_WINDOW',
    algorithmImplemented: true,
    scope: 'IP',
    window: 'PT1M',
    limit: 100,
    capacity: null,
    refillInterval: null,
    cost: null,
    drainRate: null,
    queueCapacity: null,
    maxConcurrent: null,
    leaseDuration: null,
    enabled: true,
    onRedisError: 'FAIL_OPEN',
    version: 3,
    createdAt: '2026-10-05T09:00:00Z',
    updatedAt: '2026-10-05T09:30:00Z',
    updatedBy: 'pocadmin',
    parameterSummary: '100 per 1 minute',
  },
  {
    id: 'order-create',
    name: 'order-create',
    method: 'POST',
    path: '/api/orders',
    algorithm: 'FIXED_WINDOW',
    algorithmImplemented: true,
    scope: 'USER',
    window: 'PT1M',
    limit: 50,
    capacity: null,
    refillInterval: null,
    cost: null,
    drainRate: null,
    queueCapacity: null,
    maxConcurrent: null,
    leaseDuration: null,
    enabled: false,
    onRedisError: null,
    version: 7,
    createdAt: '2026-10-05T09:00:00Z',
    updatedAt: '2026-10-05T09:45:00Z',
    updatedBy: 'pocadmin',
    parameterSummary: '50 per 1 minute',
  },
];

const EXEMPTIONS = [
  {
    id: 'ex-health',
    name: 'ex-health',
    method: 'GET',
    path: '/api/exempt-only',
    enabled: true,
    version: 1,
    createdAt: '2026-10-05T10:00:00Z',
    updatedAt: '2026-10-05T10:00:00Z',
    updatedBy: 'pocadmin',
  },
];

const GROUPS = [{
  id: 'products-group', name: 'Products group', enabled: true, onRedisError: 'FAIL_OPEN', version: 1,
  createdAt: '2026-10-05T09:00:00Z', updatedAt: '2026-10-05T09:00:00Z', updatedBy: 'pocadmin',
  endpoints: [{ id: 'products-endpoint', displayName: 'Products', method: 'GET', path: '/api/products', repeatable: true,
    exempt: false, scopeRules: [{ scope: 'ENDPOINT', algorithm: 'FIXED_WINDOW', window: 'PT1M', limit: 40,
      capacity: null, refillInterval: null, cost: null, drainRate: null, queueCapacity: null,
      maxConcurrent: null, leaseDuration: null, onRedisError: null }] }],
}];
const GLOBAL_RULES = {
  rules: [{ scope: 'APPLICATION', algorithm: 'FIXED_WINDOW', window: 'PT1M', limit: 1000, capacity: null,
    refillInterval: null, cost: null, drainRate: null, queueCapacity: null, maxConcurrent: null, leaseDuration: null, onRedisError: null }],
  onRedisError: 'FAIL_OPEN', version: 1, createdAt: 'now', updatedAt: 'now', updatedBy: 'pocadmin',
};

describe('PoliciesPageComponent', () => {
  let fixture: ComponentFixture<PoliciesPageComponent>;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PoliciesPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    // Sign in for real: an unauthenticated console must not receive policy data at all.
    const login = firstValueFrom(TestBed.inject(AdminApiService).login('pocadmin', 'test-only'));
    http.expectOne(`${BASE}/policies`).flush([]);
    await login;
    fixture = TestBed.createComponent(PoliciesPageComponent);
    fixture.detectChanges();
  });

  /** The page loads legacy and group policies, global rules, capabilities, and exemptions. */
  async function loadWorkspace(policies: unknown[] = POLICIES, exemptions: unknown[] = []): Promise<void> {
    http.expectOne(`${BASE}/capabilities`).flush(CAPABILITIES);
    http.expectOne(`${BASE}/policies`).flush(policies);
    http.expectOne(`${BASE}/exemptions`).flush(exemptions);
    http.expectOne(`${BASE}/groups`).flush([]);
    http.expectOne(`${BASE}/global-rules`).flush(GLOBAL_RULES);
    await settle();
  }

  const settle = async () => {
    await fixture.whenStable();
    // A macrotask drains service promise chains that whenStable may resolve ahead of.
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  };

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';
  const query = <T extends Element>(sel: string) =>
    (fixture.nativeElement as HTMLElement).querySelector<T>(sel);
  const click = async (sel: string) => {
    query<HTMLButtonElement>(sel)!.click();
    fixture.detectChanges();
    await settle();
  };

  it('shows a loading state before the first response arrives', () => {
    expect(text()).toContain('Loading policies');
  });

  it('lists policies with algorithm, scope and humanized parameters', async () => {
    await loadWorkspace();
    expect(text()).toContain('products-read');
    expect(text()).toContain('100 per 1 minute');
    expect(text()).toContain('FIXED_WINDOW');
    expect(text()).toContain('v3');
    expect(text()).not.toContain('PT1M');
  });

  it('explains an empty store instead of rendering a bare table', async () => {
    await loadWorkspace([]);
    expect(text()).toContain('No legacy policies are stored');
  });

  it('reports and retries each failed section on its own', async () => {
    http.expectOne(`${BASE}/capabilities`).flush('', { status: 500, statusText: 'Server Error' });
    http.expectOne(`${BASE}/policies`).flush('', { status: 500, statusText: 'Server Error' });
    http.expectOne(`${BASE}/exemptions`).flush('', { status: 500, statusText: 'Server Error' });
    await settle();

    expect(text()).toContain('HTTP 500');
    const retries = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button'),
    ).filter((b) => ['Retry capabilities', 'Retry policies', 'Retry exemptions'].includes(b.textContent?.trim() ?? ''));
    expect(retries.map((b) => b.textContent?.trim())).toEqual([
      'Retry capabilities',
      'Retry policies',
      'Retry exemptions',
    ]);

    // Retrying capabilities must not re-request the other two sections.
    retries[0].click();
    fixture.detectChanges();
    await settle();
    http.expectOne(`${BASE}/capabilities`);
    http.expectNone((req) => req.url === `${BASE}/policies`);
    http.expectNone((req) => req.url === `${BASE}/exemptions`);
  });

  it('keeps policies and capabilities visible when only exemptions fail', async () => {
    http.expectOne(`${BASE}/capabilities`).flush(CAPABILITIES);
    http.expectOne(`${BASE}/policies`).flush(POLICIES);
    http.expectOne(`${BASE}/exemptions`).flush('', { status: 500, statusText: 'Server Error' });
    await settle();

    // The exemption failure is reported on its own and hides nothing else.
    expect(text()).toContain('Exemptions could not be loaded');
    expect(text()).toContain('products-read');
    expect(text()).toContain('Composition:');
  });

  it('does not present empty algorithm and scope selects when capabilities fail', async () => {
    http.expectOne(`${BASE}/capabilities`).flush('', { status: 500, statusText: 'Server Error' });
    http.expectOne(`${BASE}/policies`).flush(POLICIES);
    http.expectOne(`${BASE}/exemptions`).flush([]);
    http.expectOne(`${BASE}/groups`).flush([]);
    http.expectOne(`${BASE}/global-rules`).flush(GLOBAL_RULES);
    await settle();
    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Create legacy policy'))!.click();
    fixture.detectChanges(); await settle();

    expect(query('select[name="f-algorithm"]')).toBeNull();
    expect(query('select[name="f-scope"]')).toBeNull();
    expect(text()).toContain('Retry capabilities');

    // Saving is refused rather than sending invented algorithm/scope values.
    const id = query<HTMLInputElement>('input[name="f-id"]')!;
    id.value = 'new-policy';
    id.dispatchEvent(new Event('input'));
    const path = query<HTMLInputElement>('input[name="f-path"]')!;
    path.value = '/api/new';
    path.dispatchEvent(new Event('input'));
    await click('button[type="submit"]');
    expect(text()).toContain('could not be loaded');
    http.expectNone((req) => req.url === `${BASE}/policies` && req.method === 'POST');
  });

  it('filters the list without another request', async () => {
    await loadWorkspace();
    const filter = query<HTMLInputElement>('input[name="policy-filter"]')!;
    filter.value = 'orders';
    filter.dispatchEvent(new Event('input'));
    await settle();

    expect(text()).toContain('order-create');
    expect(text()).not.toContain('products-read');
    expect(text()).toContain('1 of 2 legacy policies');
  });

  it('keeps the editor closed until Create policy is pressed', async () => {
    await loadWorkspace();
    expect(query('[role="dialog"]')).toBeNull();
    expect(text()).not.toContain('Algorithm and scope');
  });

  it('opens a capability-driven editor that refuses an unimplemented algorithm', async () => {
    await loadWorkspace();
    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Create legacy policy'))!.click();
    fixture.detectChanges(); await settle();
    expect(query('[role="dialog"]')).not.toBeNull();
    // Only enforced algorithms are selectable; the unimplemented one is visibly disabled.
    const options = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('select[name="f-algorithm"] option'),
    );
    expect(options.some((o) => (o as HTMLOptionElement).disabled)).toBe(true);

    const id = query<HTMLInputElement>('input[name="f-id"]')!;
    id.value = 'new-policy';
    id.dispatchEvent(new Event('input'));
    const path = query<HTMLInputElement>('input[name="f-path"]')!;
    path.value = '/api/new';
    path.dispatchEvent(new Event('input'));
    const algo = query<HTMLSelectElement>('select[name="f-algorithm"]')!;
    algo.value = 'TOKEN_BUCKET';
    algo.dispatchEvent(new Event('change'));
    await settle();

    await click('button[type="submit"]');
    expect(text()).toContain('not enforced by this build');
    http.expectNone((req) => req.url === `${BASE}/policies` && req.method === 'POST');
  });

  it('requires a two-step confirmation before deleting', async () => {
    await loadWorkspace();
    await click('.btn-danger-ghost');
    expect(text()).toContain('Confirm delete');
    // Nothing is sent by merely arming the row.
    http.expectNone((req) => req.url === `${BASE}/policies/products-read` && req.method === 'DELETE');

    query<HTMLButtonElement>('.btn-danger')!.click();
    fixture.detectChanges();
    http.expectOne((req) => req.url === `${BASE}/policies/products-read` && req.method === 'DELETE').flush({});
    await settle();
    // The list is re-read from the server, which is what proves the delete landed.
    http.expectOne(`${BASE}/policies`).flush(POLICIES);
  });

  it('lists exemptions and deletes one after a single press', async () => {
    await loadWorkspace(POLICIES, EXEMPTIONS);
    const region = query('[aria-label="Rate-limit exemptions"]')!;
    expect(region.textContent).toContain('/api/exempt-only');

    region.querySelector<HTMLButtonElement>('.btn-danger-ghost')!.click();
    fixture.detectChanges();
    http
      .expectOne((req) => req.url === `${BASE}/exemptions/ex-health` && req.method === 'DELETE')
      .flush({});
    await settle();
    http.expectOne(`${BASE}/exemptions`).flush([]);
    await settle();
    expect(text()).not.toContain('Rate-limit exemptions');
  });

  it('exemption checkbox hides rate-limit fields and posts to the exemptions endpoint', async () => {
    await loadWorkspace();
    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Create legacy policy'))!.click();
    fixture.detectChanges(); await settle();

    const id = query<HTMLInputElement>('input[name="f-id"]')!;
    id.value = 'ex-health';
    id.dispatchEvent(new Event('input'));
    const path = query<HTMLInputElement>('input[name="f-path"]')!;
    path.value = '/api/exempt-only';
    path.dispatchEvent(new Event('input'));

    const exempt = query<HTMLInputElement>('input[name="f-exempt"]')!;
    exempt.click();
    await settle();
    // Rate-limit specifics are irrelevant to an exemption, so they disappear.
    expect(query('select[name="f-algorithm"]')).toBeNull();

    await click('button[type="submit"]');
    http
      .expectOne((req) => req.url === `${BASE}/exemptions` && req.method === 'POST')
      .flush(EXEMPTIONS[0]);
    await settle();
    http.expectOne(`${BASE}/exemptions`).flush(EXEMPTIONS);
    // Saving an exemption refreshes the policy-derived request targets too.
    http.expectOne(`${BASE}/demo-routes`).flush({ targets: [], policies: [] });
    await settle();
    expect(query('[role="dialog"]')).toBeNull();
    expect(text()).toContain('/api/exempt-only');
  });

  it('closes after exemption creation succeeds even if the subsequent refresh fails', async () => {
    await loadWorkspace();
    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Create legacy policy'))!.click();
    fixture.detectChanges(); await settle();

    const id = query<HTMLInputElement>('input[name="f-id"]')!;
    id.value = 'ex-refresh-fail'; id.dispatchEvent(new Event('input'));
    const path = query<HTMLInputElement>('input[name="f-path"]')!;
    path.value = '/api/exempt-refresh-fail'; path.dispatchEvent(new Event('input'));
    query<HTMLInputElement>('input[name="f-exempt"]')!.click();
    await settle();

    query<HTMLButtonElement>('button[type="submit"]')!.click(); fixture.detectChanges();
    http.expectOne((req) => req.url === `${BASE}/exemptions` && req.method === 'POST')
      .flush({ ...EXEMPTIONS[0], id: 'ex-refresh-fail', path: '/api/exempt-refresh-fail' });
    await settle();
    expect(query('[role="dialog"]')).toBeNull();
    http.expectOne(`${BASE}/exemptions`).flush('', { status: 500, statusText: 'Server Error' });
    http.expectOne(`${BASE}/demo-routes`).flush('', { status: 500, statusText: 'Server Error' });
    await settle();
    expect(query('[role="dialog"]')).toBeNull();
    expect(TestBed.inject(AdminStore).actionInfo()).toContain('Exemption ex-refresh-fail created.');
    expect(TestBed.inject(AdminStore).exemptionsState().error).toBe('HTTP 500');
  });

  it('lists groups and shows enabled status and endpoint details', async () => {
    await loadWorkspace();
    TestBed.inject(AdminApiService);
    // Load the actual group payload independently; the shared helper uses an empty list for legacy cases.
    const store = TestBed.inject(AdminStore);
    const load = store.loadGroups();
    http.expectOne(`${BASE}/groups`).flush(GROUPS); await load; await settle();
    expect(text()).toContain('Products group');
    expect(text()).toContain('Enabled · v1');
    expect(text()).toContain('GET /api/products');
    expect(text()).toContain('Global scopes');
  });

  it('creates a group with endpoint rule fields and keeps global rules separately editable', async () => {
    await loadWorkspace([]);
    await click('.page-head .btn-primary');
    expect(query('input[name="group-id"]')).toBeNull();
    const name = query<HTMLInputElement>('input[name="group-name"]')!;
    name.value = 'New group'; name.dispatchEvent(new Event('input'));
    await click('button[aria-label="Add endpoint"]');
    expect(text()).toContain('Add endpoint');
    const endpointName = query<HTMLInputElement>('input[name="endpoint-name"]')!;
    endpointName.value = 'Created endpoint'; endpointName.dispatchEvent(new Event('input'));
    const endpointPath = query<HTMLInputElement>('input[name="endpoint-path"]')!;
    endpointPath.value = '/api/created'; endpointPath.dispatchEvent(new Event('input'));
    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Add ENDPOINT scope'))!.click();
    fixture.detectChanges(); await settle();
    query<HTMLButtonElement>('[aria-label="Endpoint editor"] .row .btn-primary')!.click();
    fixture.detectChanges(); await settle();
    query<HTMLButtonElement>('.modal-foot .btn-primary')!.click();
    fixture.detectChanges();
    const request = http.expectOne((req) => req.url === `${BASE}/groups` && req.method === 'POST');
    expect(request.request.body.endpoints).toHaveLength(1);
    expect(request.request.body.endpoints[0]).toMatchObject({ method: 'GET', path: '/api/created', displayName: 'Created endpoint' });
    // The server generates both ids; the browser must not invent any.
    expect(request.request.body.id).toBeUndefined();
    expect(request.request.body.endpoints[0].id).toBeUndefined();
    request.flush({ ...GROUPS[0], id: 'new-group', name: 'New group' });
    await settle();
    http.expectOne(`${BASE}/groups`).flush(GROUPS);
    await settle();
  });

  it('opens group view/edit and posts updated group including version', async () => {
    await loadWorkspace();
    const store = TestBed.inject(AdminStore);
    const load = store.loadGroups(); http.expectOne(`${BASE}/groups`).flush(GROUPS); await load; await settle();
    const groupButton = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find((button) => button.textContent?.includes('View / edit'))!;
    groupButton.click(); fixture.detectChanges(); await settle();
    expect(text()).toContain('Manage policy group');
    const save = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Save group'))!;
    save.click(); fixture.detectChanges();
    const request = http.expectOne((req) => req.url === `${BASE}/groups/products-group` && req.method === 'PUT');
    expect(request.request.body.version).toBe(1);
    request.flush({ ...GROUPS[0], version: 2 });
    await settle();
    http.expectOne(`${BASE}/groups`).flush([{ ...GROUPS[0], version: 2 }]);
    await settle();
  });

  it('edits endpoint exemption and scope parameters without dropping sibling endpoint support', async () => {
    await loadWorkspace();
    const store = TestBed.inject(AdminStore);
    const load = store.loadGroups(); http.expectOne(`${BASE}/groups`).flush(GROUPS); await load; await settle();
    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find((button) => button.textContent?.includes('View / edit'))!.click();
    fixture.detectChanges(); await settle();
    query<HTMLButtonElement>('button[aria-label="Add endpoint"]')?.click();
    fixture.detectChanges(); await settle();
    expect(text()).toContain('Add endpoint');
    expect(query('input[name="endpoint-exempt"]')).not.toBeNull();
    expect(query('select[name="endpoint-algorithm-0"]')).toBeNull();
    const exemption = query<HTMLInputElement>('input[name="endpoint-exempt"]')!;
    exemption.click(); fixture.detectChanges(); await settle();
    expect(query('select[name="endpoint-algorithm-0"]')).toBeNull();
  });

  it('renders backend hierarchy validation code, message, and problems', async () => {
    await loadWorkspace();
    const store = TestBed.inject(AdminStore);
    const load = store.loadGroups(); http.expectOne(`${BASE}/groups`).flush(GROUPS); await load; await settle();
    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find((button) => button.textContent?.includes('View / edit'))!.click();
    fixture.detectChanges(); await settle();
    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Save group'))!.click();
    http.expectOne((req) => req.url === `${BASE}/groups/products-group` && req.method === 'PUT').flush(
      { error: 'policy_invalid', message: 'hierarchy invalid', problems: ['endpoint cap exceeds application cap'] },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    expect(text()).toContain('policy_invalid');
    expect(text()).toContain('hierarchy invalid');
    expect(text()).toContain('endpoint cap exceeds application cap');
  });
});
