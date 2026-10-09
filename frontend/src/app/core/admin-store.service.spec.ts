import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { AdminStore } from './admin-store.service';
import { AdminApiService } from './admin-api.service';

const BASE = '/api/admin/rate-limit';

describe('AdminStore policy-group state', () => {
  let store: AdminStore;
  let api: AdminApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    store = TestBed.inject(AdminStore);
    api = TestBed.inject(AdminApiService);
    http = TestBed.inject(HttpTestingController);
    const login = api.login('admin', 'secret').subscribe();
    http.expectOne(`${BASE}/policies`).flush([]);
    login.unsubscribe();
  });

  afterEach(() => {
    http.verify();
    api.logout();
  });

  it('records independently loaded group and global-rule success and API errors', async () => {
    const groupsLoad = store.loadGroups();
    const groupsRequest = http.expectOne(`${BASE}/groups`);
    groupsRequest.flush([{ id: 'g1', name: 'Group', enabled: true, endpoints: [], onRedisError: 'FAIL_OPEN',
      version: 1, createdAt: 'now', updatedAt: 'now', updatedBy: 'admin' }]);
    await groupsLoad;
    expect(store.groupsState()).toEqual({ loaded: true, error: null });
    expect(store.groups()[0]?.id).toBe('g1');

    const rulesLoad = store.loadGlobalRules();
    http.expectOne(`${BASE}/global-rules`).flush(
      { error: 'policy_invalid', message: 'policy is invalid', problems: ['minimum rule violation'] },
      { status: 400, statusText: 'Bad Request' },
    );
    await rulesLoad;
    expect(store.globalRulesState()).toEqual({ loaded: true, error: 'policy is invalid' });
    expect(store.globalRules()).toBeNull();
  });

  it('surfaces hierarchy validation problems on group save and preserves legacy policy state', async () => {
    store.policies.set([{ id: 'legacy' } as never]);
    const save = store.saveGroup({ endpoints: [] }, null);
    http.expectOne(`${BASE}/groups`).flush(
      { error: 'policy_invalid', message: 'policy is invalid', problems: ['endpoint scopes exceed the application cap'] },
      { status: 400, statusText: 'Bad Request' },
    );
    const result = await save;
    expect(result).toMatchObject({ code: 'policy_invalid', problems: ['endpoint scopes exceed the application cap'] });
    expect(store.actionError()).toContain('policy is invalid: endpoint scopes exceed the application cap');
    expect(store.policies()).toEqual([{ id: 'legacy' }]);
  });
});
