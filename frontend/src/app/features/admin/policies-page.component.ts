import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { AdminStore } from '../../core/admin-store.service';
import { AdminPolicy, PolicyGroup } from '../../core/admin-models';
import { PolicyGroupEditorComponent } from './policy-group-editor.component';

/**
 * Policies workspace: policy groups and global scope rules are created and edited here.
 *
 * Policies that pre-date groups ("legacy") can no longer be created or edited. Any that still exist keep
 * being enforced, so they are listed in a small clean-up panel where they can be disabled or deleted; the
 * panel disappears once none are left.
 */
@Component({
  selector: 'app-policies-page',
  standalone: true,
  imports: [FormsModule, PolicyGroupEditorComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './policies-page.component.html',
})
export class PoliciesPageComponent {
  protected readonly store = inject(AdminStore);

  protected readonly groupEditorOpen = signal(false);
  protected readonly editingGroupId = signal<string | null>(null);
  protected readonly globalRulesEditorOpen = signal(false);
  protected readonly confirmDeleteId = signal<string | null>(null);

  protected readonly groups = this.store.groups;
  protected readonly groupFilter = signal('');
  protected readonly filteredGroups = computed(() => {
    const needle = this.groupFilter().trim().toLowerCase();
    return this.groups().filter((group) => !needle ||
      `${group.id} ${group.name} ${group.endpoints.map((endpoint) => `${endpoint.method} ${endpoint.path}`).join(' ')}`
        .toLowerCase().includes(needle));
  });

  /** Policies not owned by a group or by the global rules: still enforced, no longer editable. */
  protected readonly legacyPolicies = computed(() =>
    this.store.policies().filter((policy) => policy.source !== 'GROUP' && policy.source !== 'GLOBAL'));

  constructor() {
    void this.store.load();
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

  protected async onDeleteExemption(id: string): Promise<void> {
    this.store.busy.set(true);
    try {
      await this.store.removeExemption(id);
    } finally {
      this.store.busy.set(false);
    }
  }
}
