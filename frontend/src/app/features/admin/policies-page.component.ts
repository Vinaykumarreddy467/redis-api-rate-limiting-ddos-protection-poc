import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { AdminStore } from '../../core/admin-store.service';
import { AdminPolicy, PolicyGroup, durationToSeconds } from '../../core/admin-models';
import { DemoRoute } from '../../core/demo-catalog';
import { DemoSummary } from '../../core/models';
import { DemoRunnerService } from '../request-demo/demo-runner.service';
import { PolicyEditorComponent } from './policy-editor.component';
import { PolicyGroupEditorComponent } from './policy-group-editor.component';

const PROBE_MAX = 30;

/**
 * Policies workspace: the authoritative managed list, plus create/edit in a modal editor.
 *
 * The list is filtered client-side because the admin API has no query parameters; filtering a
 * bounded set of policies locally keeps the backend contract untouched.
 */
@Component({
  selector: 'app-policies-page',
  standalone: true,
  imports: [FormsModule, PolicyEditorComponent, PolicyGroupEditorComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './policies-page.component.html',
})
export class PoliciesPageComponent {
  protected readonly store = inject(AdminStore);
  private readonly probeRunner = inject(DemoRunnerService);

  protected readonly filter = signal('');
  protected readonly scopeFilter = signal('');
  protected readonly editorOpen = signal(false);
  protected readonly editingId = signal<string | null>(null);
  protected readonly groupEditorOpen = signal(false);
  protected readonly editingGroupId = signal<string | null>(null);
  protected readonly globalRulesEditorOpen = signal(false);
  protected readonly confirmDeleteId = signal<string | null>(null);

  // Bounded probe state: sequential, user-triggered, capped, cancellable.
  protected readonly probePolicyId = signal<string | null>(null);
  protected readonly probeCount = signal(10);
  protected readonly probeSummary = signal<DemoSummary | null>(null);
  protected readonly probeRunning = this.probeRunner.running;

  protected readonly policies = this.store.policies;
  protected readonly capabilities = this.store.capabilities;
  protected readonly groups = this.store.groups;
  protected readonly groupFilter = signal('');
  protected readonly filteredGroups = computed(() => {
    const needle = this.groupFilter().trim().toLowerCase();
    return this.groups().filter((group) => !needle ||
      `${group.id} ${group.name} ${group.endpoints.map((endpoint) => `${endpoint.method} ${endpoint.path}`).join(' ')}`
        .toLowerCase().includes(needle));
  });

  protected readonly scopeOptions = computed(() => this.store.implementedScopes().map((s) => s.name));

  protected readonly filtered = computed(() => {
    const needle = this.filter().trim().toLowerCase();
    const scope = this.scopeFilter();
    return this.policies().filter((policy) => {
      if (scope && policy.scope !== scope) return false;
      if (!needle) return true;
      return `${policy.id} ${policy.name} ${policy.method} ${policy.path ?? ''} ${policy.algorithm}`
        .toLowerCase()
        .includes(needle);
    });
  });

  protected readonly policyById = computed(() => {
    const map = new Map<string, AdminPolicy>();
    for (const policy of this.policies()) map.set(policy.id, policy);
    return map;
  });

  constructor() {
    void this.store.load();
  }

  protected onCreate(): void {
    this.store.clearMessages();
    this.editingId.set(null);
    this.editorOpen.set(true);
  }

  protected onCreateGroup(): void {
    this.store.clearMessages();
    this.editingGroupId.set(null);
    this.groupEditorOpen.set(true);
  }

  protected onEditGroup(group: PolicyGroup): void {
    this.store.clearMessages();
    this.editingGroupId.set(group.id);
    this.groupEditorOpen.set(true);
  }

  protected onEditGlobalRules(): void {
    this.store.clearMessages();
    this.globalRulesEditorOpen.set(true);
  }

  protected groupById(id: string): PolicyGroup | null {
    return this.groups().find((group) => group.id === id) ?? null;
  }

  protected async onToggleGroup(group: PolicyGroup): Promise<void> {
    this.store.busy.set(true);
    try {
      await this.store.saveGroup({
        name: group.name,
        enabled: !group.enabled,
        endpoints: group.endpoints,
        onRedisError: group.onRedisError,
        version: group.version,
      }, group.id);
    } finally {
      this.store.busy.set(false);
    }
  }

  protected onEdit(policy: AdminPolicy): void {
    this.store.clearMessages();
    this.editingId.set(policy.id);
    this.editorOpen.set(true);
  }

  protected onEditorClosed(): void {
    this.editorOpen.set(false);
  }

  protected onExemptionSaved(): void {
    this.editorOpen.set(false);
  }

  protected async onToggleEnabled(policy: AdminPolicy): Promise<void> {
    this.store.busy.set(true);
    try {
      await this.store.toggleEnabled(policy);
    } finally {
      this.store.busy.set(false);
    }
  }

  /** Two-step confirm: the first press arms the row, the second deletes. */
  protected async onDelete(policy: AdminPolicy): Promise<void> {
    if (this.confirmDeleteId() !== policy.id) {
      this.confirmDeleteId.set(policy.id);
      return;
    }
    this.confirmDeleteId.set(null);
    this.store.busy.set(true);
    try {
      await this.store.remove(policy.id);
    } finally {
      this.store.busy.set(false);
    }
  }

  protected armDelete(id: string): void {
    this.confirmDeleteId.set(id);
  }

  protected cancelDelete(): void {
    this.confirmDeleteId.set(null);
  }

  protected onProbe(policy: AdminPolicy): void {
    this.probePolicyId.set(policy.id);
    this.probeSummary.set(null);
  }

  protected async onProbeStart(policy: AdminPolicy): Promise<void> {
    if (this.probeRunning()) return;
    this.probeSummary.set(null);
    const method = (policy.method === 'GET' || policy.method === 'POST') ? policy.method : 'GET';
    const route: DemoRoute = {
      id: `probe:${policy.id}`,
      label: `${policy.method} ${policy.path ?? '(global)'}`,
      method,
      path: policy.path ?? '/',
      needsAuth: false,
      note: method !== policy.method
        ? `Admin probe: ${policy.method} not safe for automated probe; using GET instead.`
        : 'Admin probe: bounded, sequential, cancellable traffic.',
    };
    const total = Math.max(1, Math.min(this.probeCount(), PROBE_MAX));
    this.probeSummary.set(await this.probeRunner.run(route, total, null, () => undefined));
  }

  protected onProbeCancel(): void {
    this.probeRunner.cancel();
  }

  protected closeProbe(): void {
    this.probePolicyId.set(null);
    this.probeSummary.set(null);
  }

  protected async onDeleteExemption(id: string): Promise<void> {
    this.store.busy.set(true);
    try {
      await this.store.removeExemption(id);
    } finally {
      this.store.busy.set(false);
    }
  }

  /** Honest note about whether the probe run crossed a window edge, so totals are not misread. */
  protected probeWindowNote(): string | null {
    const summary = this.probeSummary();
    const id = this.probePolicyId();
    const policy = id ? this.policyById().get(id) : null;
    if (!summary || !policy) return null;
    const windowSeconds = durationToSeconds(policy.window);
    if (windowSeconds === null) return null;
    return summary.elapsedMs >= windowSeconds * 1000
      ? `Ran ${summary.elapsedMs} ms across a ${windowSeconds}s window edge, so these totals span two windows.`
      : `Ran ${summary.elapsedMs} ms inside one ${windowSeconds}s window.`;
  }
}
