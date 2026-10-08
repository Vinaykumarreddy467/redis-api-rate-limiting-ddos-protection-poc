import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { AdminApiService, isAdminError } from './admin-api.service';
import { parseDemoTargets } from './demo-catalog';
import {
  AdminApiError,
  AdminPolicy,
  AuditRecord,
  Capabilities,
  ExemptionEdit,
  ExemptionRecord,
  GlobalScopeRules,
  GlobalScopeRulesEdit,
  PolicyGroup,
  PolicyGroupEdit,
  PolicyEdit,
  PolicyTargetRecord,
} from './admin-models';

/** Audit history is always loaded in bounded chunks; the backend has no offset parameter. */
export const AUDIT_LIMITS = [25, 50, 100, 200] as const;

/** One independently loaded section: `loaded` once the attempt finished, `error` when it failed. */
export interface SectionState {
  loaded: boolean;
  error: string | null;
}

const NOT_LOADED: SectionState = { loaded: false, error: null };

/**
 * Shared administration state for the Policies and Audit workspaces.
 *
 * Admin credentials live only in AdminApiService memory and are never stored here.
 */
@Injectable({ providedIn: 'root' })
export class AdminStore {
  private readonly api = inject(AdminApiService);
  private readonly router = inject(Router);
  private demoTargetsRevision = 0;

  readonly busy = signal(false);
  readonly loaded = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly actionError = signal<string | null>(null);
  readonly actionInfo = signal<string | null>(null);

  /** Per-section load state. One failed request must never hide the sections that succeeded. */
  readonly capabilitiesState = signal<SectionState>({ loaded: false, error: null });
  readonly policiesState = signal<SectionState>({ loaded: false, error: null });
  readonly exemptionsState = signal<SectionState>({ loaded: false, error: null });
  readonly demoRoutesState = signal<SectionState>({ loaded: false, error: null });
  readonly groupsState = signal<SectionState>({ loaded: false, error: null });
  readonly globalRulesState = signal<SectionState>({ loaded: false, error: null });
  /** One entry per managed policy, refreshed whenever policies change. */
  readonly demoTargets = signal<PolicyTargetRecord[]>([]);

  readonly capabilities = signal<Capabilities | null>(null);
  readonly policies = signal<AdminPolicy[]>([]);
  readonly exemptions = signal<ExemptionRecord[]>([]);
  readonly groups = signal<PolicyGroup[]>([]);
  readonly globalRules = signal<GlobalScopeRules | null>(null);
  readonly audit = signal<AuditRecord[]>([]);
  readonly auditLimit = signal<(typeof AUDIT_LIMITS)[number]>(50);

  readonly implementedAlgorithms = computed(
    () => this.capabilities()?.algorithms.filter((a) => a.implemented) ?? [],
  );
  readonly unimplementedAlgorithms = computed(
    () => this.capabilities()?.algorithms.filter((a) => !a.implemented) ?? [],
  );
  readonly implementedScopes = computed(
    () => this.capabilities()?.scopes.filter((s) => s.implemented) ?? [],
  );
  /** The editor may only offer algorithm/scope choices once capabilities actually loaded. */
  readonly capabilitiesReady = computed(() => this.capabilities() !== null);
  readonly enabledPolicyCount = computed(() => this.policies().filter((p) => p.enabled).length);

  readonly loggedIn = this.api.loggedIn;
  readonly loginName = this.api.loginName;

  async login(username: string, password: string): Promise<AdminApiError | null> {
    const result = await firstValueFrom(this.api.login(username, password));
    return result.ok ? null : result.error;
  }

  /** Clears cached management state on logout so a later login never renders the previous session. */
  logout(): void {
    this.api.logout();
    this.reset();
    void this.router.navigate(['/admin/login']);
  }

  reset(): void {
    // Ignore any in-flight catalog response from the session being cleared.
    this.demoTargetsRevision++;
    this.capabilities.set(null);
    this.policies.set([]);
    this.exemptions.set([]);
    this.groups.set([]);
    this.globalRules.set(null);
    this.demoTargets.set([]);
    this.audit.set([]);
    this.auditLimit.set(50);
    this.capabilitiesState.set(NOT_LOADED);
    this.policiesState.set(NOT_LOADED);
    this.exemptionsState.set(NOT_LOADED);
    this.demoRoutesState.set(NOT_LOADED);
    this.groupsState.set(NOT_LOADED);
    this.globalRulesState.set(NOT_LOADED);
    this.loaded.set(false);
    this.loadError.set(null);
    this.actionError.set(null);
    this.actionInfo.set(null);
  }

  clearMessages(): void {
    this.actionError.set(null);
    this.actionInfo.set(null);
  }

  /**
   * Loads the three workspace sections independently.
   *
   * Each one keeps its own loaded/error state, so a failing section (an older backend without
   * `/exemptions`, for instance) can no longer hide the policies and capabilities that did load.
   */
  async load(): Promise<void> {
    if (this.loaded()) return;
    this.loadError.set(null);
    await Promise.all([
      this.loadCapabilities(),
      this.loadPolicies(),
      this.loadExemptions(),
      this.loadDemoTargets(),
      this.loadGroups(),
      this.loadGlobalRules(),
    ]);
    this.loaded.set(true);
  }

  /**
   * Re-reads the demo targets: one entry per managed policy. Called on page entry, by the console's
   * refresh button, and after every policy mutation, so a create, edit, enable, disable or delete
   * shows up in the dropdown without restarting the API.
   */
  async loadDemoTargets(): Promise<void> {
    const revision = ++this.demoTargetsRevision;
    this.demoRoutesState.set(NOT_LOADED);
    try {
      const result = await firstValueFrom(this.api.demoRoutes());
      if (revision !== this.demoTargetsRevision) return;
      if (isAdminError(result)) {
        this.demoRoutesState.set({ loaded: true, error: this.describeLoadFailure(result) });
        return;
      }
      const targets = parseDemoTargets(result);
      this.demoTargets.set(targets);
      this.demoRoutesState.set({ loaded: true, error: null });
    } catch (error: unknown) {
      if (revision === this.demoTargetsRevision) {
        const detail = error instanceof Error ? error.message : 'unexpected client error';
        this.demoRoutesState.set({ loaded: true, error: `Could not load policy targets: ${detail}` });
      }
    }
  }

  async loadCapabilities(): Promise<void> {
    this.capabilitiesState.set(NOT_LOADED);
    const result = await firstValueFrom(this.api.capabilities());
    if (isAdminError(result)) {
      this.capabilitiesState.set({ loaded: true, error: this.describeLoadFailure(result) });
      return;
    }
    this.capabilities.set(result);
    this.capabilitiesState.set({ loaded: true, error: null });
  }

  async loadPolicies(): Promise<void> {
    this.policiesState.set(NOT_LOADED);
    const result = await firstValueFrom(this.api.list());
    if (isAdminError(result)) {
      this.policiesState.set({ loaded: true, error: this.describeLoadFailure(result) });
      return;
    }
    this.policies.set(result);
    this.policiesState.set({ loaded: true, error: null });
  }

  async loadExemptions(): Promise<void> {
    this.exemptionsState.set(NOT_LOADED);
    const result = await firstValueFrom(this.api.listExemptions());
    if (isAdminError(result)) {
      this.exemptionsState.set({ loaded: true, error: this.describeLoadFailure(result) });
      return;
    }
    this.exemptions.set(result);
    this.exemptionsState.set({ loaded: true, error: null });
  }

  async loadGroups(): Promise<void> {
    this.groupsState.set(NOT_LOADED);
    const result = await firstValueFrom(this.api.listGroups());
    if (isAdminError(result)) {
      this.groupsState.set({ loaded: true, error: this.describeLoadFailure(result) });
      return;
    }
    this.groups.set(result);
    this.groupsState.set({ loaded: true, error: null });
  }

  async loadGlobalRules(): Promise<void> {
    this.globalRulesState.set(NOT_LOADED);
    const result = await firstValueFrom(this.api.globalRules());
    if (isAdminError(result)) {
      this.globalRulesState.set({ loaded: true, error: this.describeLoadFailure(result) });
      return;
    }
    this.globalRules.set(result);
    this.globalRulesState.set({ loaded: true, error: null });
  }

  async saveGroup(edit: PolicyGroupEdit, editingId: string | null): Promise<PolicyGroup | AdminApiError> {
    this.clearMessages();
    const result = editingId === null
      ? await firstValueFrom(this.api.createGroup(edit))
      : await firstValueFrom(this.api.updateGroup(editingId, edit));
    if (isAdminError(result)) {
      this.actionError.set(this.describeSaveFailure(result));
      return result;
    }
    await this.loadGroups();
    this.actionInfo.set(`Saved group ${result.id} at version ${result.version}.`);
    return result;
  }

  async saveGlobalRules(edit: GlobalScopeRulesEdit): Promise<GlobalScopeRules | AdminApiError> {
    this.clearMessages();
    const result = await firstValueFrom(this.api.updateGlobalRules(edit));
    if (isAdminError(result)) {
      this.actionError.set(this.describeSaveFailure(result));
      return result;
    }
    this.globalRules.set(result);
    this.globalRulesState.set({ loaded: true, error: null });
    this.actionInfo.set(`Saved global rules at version ${result.version}.`);
    return result;
  }

  async deleteGroup(id: string): Promise<void> {
    this.clearMessages();
    const result = await firstValueFrom(this.api.deleteGroup(id));
    if (isAdminError(result)) {
      this.actionError.set(this.describeSaveFailure(result));
      return;
    }
    await this.loadGroups();
    this.actionInfo.set(`Deleted group ${id}.`);
  }

  async deleteEndpoint(groupId: string, endpointId: string): Promise<PolicyGroup | AdminApiError> {
    this.clearMessages();
    const result = await firstValueFrom(this.api.deleteEndpoint(groupId, endpointId));
    if (isAdminError(result)) {
      this.actionError.set(this.describeSaveFailure(result));
      return result;
    }
    await this.loadGroups();
    this.actionInfo.set(`Deleted endpoint ${endpointId} from group ${groupId}.`);
    return result;
  }

  async repairGroup(id: string): Promise<void> {
    this.clearMessages();
    const result = await firstValueFrom(this.api.repairGroup(id));
    if (isAdminError(result)) {
      this.actionError.set(this.describeSaveFailure(result));
      return;
    }
    await this.loadGroups();
    this.actionInfo.set(`Repaired group ${id}: ${result.written} written, ${result.deleted} deleted.`);
  }

  /** Fetches a bounded audit window. Larger limits replace the window rather than appending. */
  async loadAudit(limit: (typeof AUDIT_LIMITS)[number]): Promise<void> {
    this.loadError.set(null);
    this.auditLimit.set(limit);
    const result = await firstValueFrom(this.api.audit(limit));
    if (isAdminError(result)) {
      this.loadError.set(this.describeLoadFailure(result));
      return;
    }
    this.audit.set(result);
  }

  async save(edit: PolicyEdit, editingId: string | null): Promise<AdminPolicy | AdminApiError> {
    this.clearMessages();
    const saved =
      editingId === null
        ? await firstValueFrom(this.api.create(edit))
        : await firstValueFrom(this.api.update(editingId, edit));
    if (isAdminError(saved)) {
      this.actionError.set(this.describeSaveFailure(saved));
      return saved;
    }
    await this.refreshPolicies();
    this.actionInfo.set(
      `Saved ${saved.id} at version ${saved.version}. Every instance reads it from shared Redis, so no restart is needed.`,
    );
    return saved;
  }

  async toggleEnabled(policy: AdminPolicy): Promise<void> {
    this.clearMessages();
    const result = await firstValueFrom(this.api.setEnabled(policy.id, !policy.enabled, policy.version));
    if (isAdminError(result)) {
      this.actionError.set(this.describeSaveFailure(result));
      return;
    }
    await this.refreshPolicies();
    this.actionInfo.set(`${policy.id} is now ${result.enabled ? 'enabled' : 'disabled'}.`);
  }

  async remove(id: string): Promise<void> {
    this.clearMessages();
    const result = await firstValueFrom(this.api.remove(id));
    if (isAdminError(result)) {
      this.actionError.set(this.describeSaveFailure(result));
      return;
    }
    await this.refreshPolicies();
    this.actionInfo.set(`Deleted ${id}.`);
  }

  async saveExemption(edit: ExemptionEdit): Promise<AdminPolicy | AdminApiError> {
    this.clearMessages();
    const result = await firstValueFrom(this.api.createExemption(edit));
    if (isAdminError(result)) {
      this.actionError.set(this.describeSaveFailure(result));
      return result;
    }
    this.actionInfo.set(`Exemption ${edit.id} created.`);
    void this.refreshExemptions();
    return result as unknown as AdminPolicy;
  }

  async removeExemption(id: string): Promise<void> {
    this.clearMessages();
    const result = await firstValueFrom(this.api.deleteExemption(id));
    if (isAdminError(result)) {
      this.actionError.set(this.describeSaveFailure(result));
      return;
    }
    await this.refreshExemptions();
    this.actionInfo.set(`Deleted exemption ${id}.`);
  }

  private async refreshExemptions(): Promise<void> {
    await this.loadExemptions();
    // Exemptions change which requests bypass the limiter, so the targets must be re-read.
    await this.loadDemoTargets();
  }

  /** Every policy mutation re-reads the targets, so the dropdown never shows a stale policy list. */
  private async refreshPolicies(): Promise<void> {
    await this.loadPolicies();
    await this.loadDemoTargets();
  }

  private describeLoadFailure(error: AdminApiError): string {
    if (error.status === 0) return 'Backend unreachable. Is the API running on the configured port?';
    if (error.status === 401) return 'Session expired. Sign in again.';
    if (error.status === 403) return 'That account is not an administrator.';
    return error.message;
  }

  private describeSaveFailure(error: AdminApiError): string {
    if (error.status === 409 || error.code === 'version_conflict') {
      return 'Someone else changed this resource first. Reload, then re-apply your change.';
    }
    if (error.status === 404) return 'That resource no longer exists. Reload the list.';
    if (error.status === 0) return 'Backend unreachable. Nothing was saved.';
    if (error.problems.length > 0) return `${error.message}: ${error.problems.join(' ')}`;
    return error.message;
  }
}
