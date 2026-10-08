import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, TimeoutError, catchError, map, of, timeout } from 'rxjs';

import { API_CONFIG } from './api-config';
import {
  AdminApiError,
  AdminPolicy,
  AuditRecord,
  Capabilities,
  DemoRouteCatalogResponse,
  ExemptionEdit,
  ExemptionRecord,
  GlobalScopeRules,
  GlobalScopeRulesEdit,
  PolicyGroup,
  PolicyGroupEdit,
  PolicyEdit,
  ProjectionRepairResult,
} from './admin-models';

const REQUEST_TIMEOUT_MS = 8000;
const BASE = '/api/admin/rate-limit';

/** Sentinel status for a client-side timeout. No HTTP response was ever received. */
export const TIMEOUT_STATUS = -1;

/**
 * Administration API client.
 *
 * Authentication is HTTP Basic with credentials supplied at login and held only in this service's
 * memory for the page session. They are never written to localStorage, sessionStorage, a URL, or a
 * log line. Logging out (or reloading the page) drops them.
 */
@Injectable({ providedIn: 'root' })
export class AdminApiService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(API_CONFIG);

  private credentials: { username: string; password: string } | null = null;

  /** Basic auth header value when logged in, otherwise null. */
  get authorizationHeader(): string | null {
    if (!this.credentials) return null;
    return 'Basic ' + btoa(`${this.credentials.username}:${this.credentials.password}`);
  }

  /** True once a login attempt has succeeded against the backend. */
  readonly loggedIn = signal(false);
  readonly loginName = signal<string | null>(null);

  login(username: string, password: string): Observable<{ ok: true } | { ok: false; error: AdminApiError }> {
    const candidate = { username: username.trim(), password };
    return this.request<AdminPolicy[]>('GET', '/policies', candidate).pipe(
      map((result) => {
        if (isAdminError(result)) {
          return { ok: false, error: result } as const;
        }
        this.credentials = candidate;
        this.loggedIn.set(true);
        this.loginName.set(candidate.username);
        return { ok: true } as const;
      }),
    );
  }

  logout(): void {
    this.credentials = null;
    this.loggedIn.set(false);
    this.loginName.set(null);
  }

  capabilities(): Observable<Capabilities | AdminApiError> {
    return this.authed<Capabilities>('GET', '/capabilities');
  }

  list(): Observable<AdminPolicy[] | AdminApiError> {
    return this.authed<AdminPolicy[]>('GET', '/policies');
  }

  get(id: string): Observable<AdminPolicy | AdminApiError> {
    return this.authed<AdminPolicy>('GET', `/policies/${encodeURIComponent(id)}`);
  }

  create(edit: PolicyEdit): Observable<AdminPolicy | AdminApiError> {
    return this.authed<AdminPolicy>('POST', '/policies', edit);
  }

  update(id: string, edit: PolicyEdit): Observable<AdminPolicy | AdminApiError> {
    return this.authed<AdminPolicy>('PUT', `/policies/${encodeURIComponent(id)}`, edit);
  }

  setEnabled(
    id: string,
    enabled: boolean,
    version: number,
  ): Observable<AdminPolicy | AdminApiError> {
    return this.authed<AdminPolicy>('PATCH', `/policies/${encodeURIComponent(id)}/enabled`, {
      enabled,
      version,
    });
  }

  remove(id: string): Observable<{ deleted: true } | AdminApiError> {
    return this.authed<void>('DELETE', `/policies/${encodeURIComponent(id)}`).pipe(
      map((result) => (isAdminError(result) ? result : { deleted: true as const })),
    );
  }

  audit(limit = 50): Observable<AuditRecord[] | AdminApiError> {
    return this.authed<AuditRecord[]>('GET', `/audit?limit=${limit}`);
  }

  deleteExemption(id: string): Observable<{ deleted: true } | AdminApiError> {
    return this.authed<void>('DELETE', `/exemptions/${encodeURIComponent(id)}`).pipe(
      map((result) => (isAdminError(result) ? result : { deleted: true as const })),
    );
  }

  listExemptions(): Observable<ExemptionRecord[] | AdminApiError> {
    return this.authed<ExemptionRecord[]>('GET', '/exemptions');
  }

  /** Registered demo routes with the policies that apply, from the backend's own matcher. */
  demoRoutes(): Observable<DemoRouteCatalogResponse | AdminApiError> {
    return this.authed<DemoRouteCatalogResponse>('GET', '/demo-routes');
  }

  createExemption(edit: ExemptionEdit): Observable<AdminPolicy | AdminApiError> {
    return this.authed<AdminPolicy>('POST', '/exemptions', edit);
  }

  listGroups(): Observable<PolicyGroup[] | AdminApiError> {
    return this.authed<PolicyGroup[]>('GET', '/groups');
  }

  getGroup(id: string): Observable<PolicyGroup | AdminApiError> {
    return this.authed<PolicyGroup>('GET', `/groups/${encodeURIComponent(id)}`);
  }

  createGroup(edit: PolicyGroupEdit): Observable<PolicyGroup | AdminApiError> {
    return this.authed<PolicyGroup>('POST', '/groups', edit);
  }

  updateGroup(id: string, edit: PolicyGroupEdit): Observable<PolicyGroup | AdminApiError> {
    return this.authed<PolicyGroup>('PUT', `/groups/${encodeURIComponent(id)}`, edit);
  }

  deleteGroup(id: string): Observable<{ deleted: true } | AdminApiError> {
    return this.authed<void>('DELETE', `/groups/${encodeURIComponent(id)}`).pipe(
      map((result) => (isAdminError(result) ? result : { deleted: true as const })),
    );
  }

  deleteEndpoint(groupId: string, endpointId: string): Observable<PolicyGroup | AdminApiError> {
    return this.authed<PolicyGroup>(
      'DELETE',
      `/groups/${encodeURIComponent(groupId)}/endpoints/${encodeURIComponent(endpointId)}`,
    );
  }

  repairGroup(id: string): Observable<ProjectionRepairResult | AdminApiError> {
    return this.authed<ProjectionRepairResult>('POST', `/groups/${encodeURIComponent(id)}/repair`);
  }

  globalRules(): Observable<GlobalScopeRules | AdminApiError> {
    return this.authed<GlobalScopeRules>('GET', '/global-rules');
  }

  updateGlobalRules(edit: GlobalScopeRulesEdit): Observable<GlobalScopeRules | AdminApiError> {
    return this.authed<GlobalScopeRules>('PUT', '/global-rules', edit);
  }

  private authed<T>(method: string, path: string, body?: unknown): Observable<T | AdminApiError> {
    if (!this.credentials) {
      return of({ status: 0, code: 'not-logged-in', message: 'Log in first.', problems: [] });
    }
    return this.request<T>(method, path, this.credentials, body);
  }

  private request<T>(
    method: string,
    path: string,
    credentials: { username: string; password: string },
    body?: unknown,
  ): Observable<T | AdminApiError> {
    const headers = new HttpHeaders({
      Accept: 'application/json',
      Authorization: 'Basic ' + btoa(`${credentials.username}:${credentials.password}`),
    });
    return this.http
      .request<T>(method, `${this.config.baseUrl}${BASE}${path}`, { headers, body })
      .pipe(
        timeout(REQUEST_TIMEOUT_MS),
        catchError((error: unknown) => of(this.toError(error))),
      );
  }

  /**
   * Normalizes every failure mode into an AdminApiError.
   *
   * `status` is a real HTTP status when one arrived, and 0 for transport failures (unreachable,
   * aborted) or -1 for a client-side timeout. That sentinel is what lets callers tell "the server
   * said no" apart from "the request never got an answer", which are different operator problems.
   */
  private toError(error: unknown): AdminApiError {
    if (error instanceof TimeoutError) {
      return {
        status: TIMEOUT_STATUS,
        code: 'timeout',
        message: `The API did not answer within ${REQUEST_TIMEOUT_MS / 1000}s. It may be starting up, or the request never reached it.`,
        problems: [],
      };
    }
    if (!(error instanceof HttpErrorResponse)) {
      return { status: 0, code: 'unknown', message: 'Unexpected client error.', problems: [] };
    }
    if (error.status === 0) {
      return { status: 0, code: 'unreachable', message: 'Backend unreachable.', problems: [] };
    }
    const body = error.error as { error?: string; message?: string; problems?: string[] } | null;
    return {
      status: error.status,
      code: body?.error ?? 'http-error',
      message: body?.message ?? `HTTP ${error.status}`,
      problems: body?.problems ?? [],
    };
  }
}

/** Type guard: a service result is an error when it carries a numeric status. */
export function isAdminError(value: unknown): value is AdminApiError {
  return typeof value === 'object' && value !== null && 'status' in value;
}
