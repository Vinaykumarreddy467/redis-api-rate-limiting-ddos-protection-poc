import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { firstValueFrom } from 'rxjs';

import { AdminApiService, TIMEOUT_STATUS } from './admin-api.service';

const BASE = '/api/admin/rate-limit';

describe('AdminApiService', () => {
  let api: AdminApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClientTesting()] });
    api = TestBed.inject(AdminApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    api.logout();
  });

  it('logs in on 200 and then lists with Basic auth', async () => {
    const login = firstValueFrom(api.login('admin', 'secret'));
    http.expectOne(`${BASE}/policies`).flush([{ id: 'a' }]);
    expect(await login).toEqual({ ok: true });
    expect(api.loggedIn()).toBe(true);

    const list = firstValueFrom(api.list());
    const req = http.expectOne(`${BASE}/policies`);
    expect(req.request.headers.get('Authorization')).toBe('Basic ' + btoa('admin:secret'));
    req.flush([{ id: 'a' }]);
    expect(await list).toEqual([{ id: 'a' }]);
  });

  it('reports 401 as a login failure without storing credentials', async () => {
    const login = firstValueFrom(api.login('admin', 'wrong'));
    http.expectOne(`${BASE}/policies`).flush({ message: 'x' }, { status: 401, statusText: 'Unauthorized' });
    const result = await login;
    expect(result.ok).toBe(false);
    expect(api.loggedIn()).toBe(false);
  });

  it('reports a client-side timeout as a timeout error, never "HTTP undefined"', async () => {
    vi.useFakeTimers();
    try {
      // Issue the request but never flush it: the 8s timeout is the only thing that can settle it.
      const pending = firstValueFrom(api.login('admin', 'secret'));
      http.expectOne(`${BASE}/policies`);
      await vi.advanceTimersByTimeAsync(8000);

      const result = await pending;
      expect(result.ok).toBe(false);
      if (result.ok) throw new Error('unreachable');
      expect(result.error.code).toBe('timeout');
      expect(result.error.status).toBe(TIMEOUT_STATUS);
      expect(result.error.message).not.toContain('undefined');
      expect(api.loggedIn()).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it('maps a 409 body to a version_conflict error', async () => {
    await loginAsAdmin();
    const update = firstValueFrom(
      api.update('p', { id: 'p', limit: 1, version: 1 }),
    );
    http
      .expectOne(`${BASE}/policies/p`)
      .flush(
        { error: 'version_conflict', message: 'stored version is 2', problems: [] },
        { status: 409, statusText: 'Conflict' },
      );
    const result = await update;
    expect(result).toMatchObject({ status: 409, code: 'version_conflict' });
  });

  it('maps a 400 body with field problems', async () => {
    await loginAsAdmin();
    const create = firstValueFrom(api.create({ id: 'bad' }));
    http
      .expectOne(`${BASE}/policies`)
      .flush(
        { error: 'policy_invalid', message: 'policy is invalid', problems: ['limit must be at least 1'] },
        { status: 400, statusText: 'Bad Request' },
      );
    const result = await create;
    expect(result).toMatchObject({ status: 400, code: 'policy_invalid', message: 'policy is invalid', problems: ['limit must be at least 1'] });
  });

  it('uses group and global-rules routes, methods, and bodies', async () => {
    await loginAsAdmin();
    const endpoint = {
      id: 'ep-1', method: 'GET', path: '/api/products', displayName: 'Products',
      repeatable: true, exempt: false, scopeRules: [],
    };
    const group = {
      id: 'group-1', name: 'Products', enabled: true, endpoints: [endpoint],
      onRedisError: 'FAIL_OPEN', version: 2, createdAt: 'now', updatedAt: 'now', updatedBy: 'admin',
    };

    const list = firstValueFrom(api.listGroups());
    const listRequest = http.expectOne(`${BASE}/groups`);
    expect(listRequest.request.method).toBe('GET');
    listRequest.flush([group]);
    expect(await list).toEqual([group]);

    const get = firstValueFrom(api.getGroup('group /1'));
    const getRequest = http.expectOne(`${BASE}/groups/group%20%2F1`);
    expect(getRequest.request.method).toBe('GET');
    getRequest.flush(group);
    expect(await get).toEqual(group);

    const edit = { id: group.id, name: group.name, enabled: true, endpoints: [endpoint], version: 1 };
    const create = firstValueFrom(api.createGroup(edit));
    const createRequest = http.expectOne(`${BASE}/groups`);
    expect(createRequest.request.method).toBe('POST');
    expect(createRequest.request.body).toEqual(edit);
    createRequest.flush(group);
    expect(await create).toEqual(group);

    const update = firstValueFrom(api.updateGroup(group.id, { ...edit, version: 2 }));
    const updateRequest = http.expectOne(`${BASE}/groups/group-1`);
    expect(updateRequest.request.method).toBe('PUT');
    expect(updateRequest.request.body.version).toBe(2);
    updateRequest.flush(group);
    expect(await update).toEqual(group);

    const deleteEndpoint = firstValueFrom(api.deleteEndpoint(group.id, 'ep/1'));
    const endpointRequest = http.expectOne(`${BASE}/groups/group-1/endpoints/ep%2F1`);
    expect(endpointRequest.request.method).toBe('DELETE');
    endpointRequest.flush(group);
    expect(await deleteEndpoint).toEqual(group);

    const repair = firstValueFrom(api.repairGroup(group.id));
    const repairRequest = http.expectOne(`${BASE}/groups/group-1/repair`);
    expect(repairRequest.request.method).toBe('POST');
    repairRequest.flush({ groupId: group.id, written: 1, deleted: 0, at: 'now' });
    expect(await repair).toMatchObject({ written: 1, deleted: 0 });

    const deleteGroup = firstValueFrom(api.deleteGroup(group.id));
    const deleteRequest = http.expectOne(`${BASE}/groups/group-1`);
    expect(deleteRequest.request.method).toBe('DELETE');
    deleteRequest.flush(null);
    expect(await deleteGroup).toEqual({ deleted: true });

    const rules = { rules: [], onRedisError: 'FAIL_CLOSED', version: 3, createdAt: 'now', updatedAt: 'now', updatedBy: 'admin' };
    const getRules = firstValueFrom(api.globalRules());
    const rulesGetRequest = http.expectOne(`${BASE}/global-rules`);
    expect(rulesGetRequest.request.method).toBe('GET');
    rulesGetRequest.flush(rules);
    expect(await getRules).toEqual(rules);

    const rulesEdit = { rules: [], onRedisError: 'FAIL_CLOSED' as const, version: 3 };
    const saveRules = firstValueFrom(api.updateGlobalRules(rulesEdit));
    const rulesPutRequest = http.expectOne(`${BASE}/global-rules`);
    expect(rulesPutRequest.request.method).toBe('PUT');
    expect(rulesPutRequest.request.body).toEqual(rulesEdit);
    rulesPutRequest.flush(rules);
    expect(await saveRules).toEqual(rules);
  });

  it('maps a 204 delete to deleted:true and logout drops the session', async () => {
    await loginAsAdmin();
    const remove = firstValueFrom(api.remove('p'));
    http.expectOne(`${BASE}/policies/p`).flush(null);
    expect(await remove).toEqual({ deleted: true });

    api.logout();
    expect(api.loggedIn()).toBe(false);
    const list = await firstValueFrom(api.list());
    expect(list).toMatchObject({ code: 'not-logged-in' });
    http.expectNone(`${BASE}/policies`);
  });

  async function loginAsAdmin(): Promise<void> {
    const login = firstValueFrom(api.login('admin', 'secret'));
    http.expectOne(`${BASE}/policies`).flush([]);
    expect((await login).ok).toBe(true);
  }
});
