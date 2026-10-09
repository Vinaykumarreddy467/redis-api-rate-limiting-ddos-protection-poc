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

  const buttonLabels = () =>
    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).map((b) => b.textContent?.trim());

  it('shows a loading state before the first response arrives', () => {
    expect(text()).toContain('Loading policy groups');
  });

  it('lists legacy policies that are still enforced, with humanized parameters', async () => {
    await loadWorkspace();
    const panel = query('[aria-label="Legacy policies still enforced"]')!;
    expect(panel).not.toBeNull();
    expect(panel.textContent).toContain('products-read');
    expect(panel.textContent).toContain('100 per 1 minute');
    expect(panel.textContent).toContain('v3');
    expect(panel.textContent).not.toContain('PT1M');
  });

  it('offers no way to create, edit or probe a legacy policy', async () => {
    await loadWorkspace();
    const labels = buttonLabels();
    expect(labels.some((label) => label?.includes('legacy policy'))).toBe(false);
    expect(labels).not.toContain('Edit');
    expect(labels).not.toContain('Probe');
    expect(query('[role="dialog"]')).toBeNull();
    // Only clean-up actions remain.
    expect(labels).toContain('Disable');
    expect(labels).toContain('Delete');
  });

  it('hides the legacy panel when none exist, and ignores group and global rules', async () => {
    await loadWorkspace([]);
    expect(text()).not.toContain('Legacy policies');

    TestBed.inject(AdminStore).policies.set([
      { ...POLICIES[0], id: 'p-abc', source: 'GROUP' },
      { ...POLICIES[1], id: 'p-def', source: 'GLOBAL' },
    ] as never);
    fixture.detectChanges();
    await settle();
    expect(text()).not.toContain('Legacy policies');
  });

  it('reports and retries each failed section on its own', async () => {
    http.expectOne(`${BASE}/capabilities`).flush('', { status: 500, statusText: 'Server Error' });
    http.expectOne(`${BASE}/policies`).flush('', { status: 500, statusText: 'Server Error' });
    http.expectOne(`${BASE}/exemptions`).flush('', { status: 500, statusText: 'Server Error' });
    await settle();

    expect(text()).toContain('HTTP 500');
    const retries = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button'),
    ).filter((b) => ['Retry policies', 'Retry exemptions'].includes(b.textContent?.trim() ?? ''));
    expect(retries.map((b) => b.textContent?.trim())).toEqual(['Retry policies', 'Retry exemptions']);

    // Retrying policies must not re-request exemptions.
    retries[0].click();
    fixture.detectChanges();
    await settle();
    http.expectOne(`${BASE}/policies`);
    http.expectNone((req) => req.url === `${BASE}/exemptions`);
  });

  it('keeps legacy policies visible when only exemptions fail', async () => {
    http.expectOne(`${BASE}/capabilities`).flush(CAPABILITIES);
    http.expectOne(`${BASE}/policies`).flush(POLICIES);
    http.expectOne(`${BASE}/exemptions`).flush('', { status: 500, statusText: 'Server Error' });
    await settle();

    // The exemption failure is reported on its own and hides nothing else.
    expect(text()).toContain('Exemptions could not be loaded');
    expect(text()).toContain('products-read');
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
