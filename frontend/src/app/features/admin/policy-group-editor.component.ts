import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  input,
  OnInit,
  output,
  signal,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { AdminStore } from '../../core/admin-store.service';
import {
  EndpointRule,
  FailureMode,
  GlobalScopeRulesEdit,
  PolicyAlgorithm,
  PolicyGroup,
  PolicyGroupEdit,
  PolicyScope,
  ScopeRule,
} from '../../core/admin-models';

const ALGORITHMS: PolicyAlgorithm[] = [
  'FIXED_WINDOW', 'SLIDING_WINDOW', 'SLIDING_WINDOW_COUNTER',
  'TOKEN_BUCKET', 'LEAKY_BUCKET', 'CONCURRENCY_LIMIT',
];
const GROUP_SCOPES: PolicyScope[] = ['ENDPOINT', 'IP', 'USER'];
const GLOBAL_SCOPES: PolicyScope[] = ['APPLICATION', 'GLOBAL'];
const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS', 'ANY'];
const GROUP_ID_PATTERN = /^[a-z0-9][a-z0-9-]{0,62}$/;

@Component({
  selector: 'app-policy-group-editor',
  standalone: true,
  imports: [FormsModule, NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './policy-group-editor.component.html',
})
export class PolicyGroupEditorComponent implements OnInit {
  protected readonly store = inject(AdminStore);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  readonly group = input<PolicyGroup | null>(null);
  readonly globalRulesOnly = input(false);
  readonly closed = output<void>();

  protected readonly algorithms = ALGORITHMS;
  protected readonly methods = METHODS;
  protected readonly groupScopes = GROUP_SCOPES;
  protected readonly globalScopes = GLOBAL_SCOPES;
  protected readonly editMode = computed(() => this.group() !== null);
  protected readonly groupId = signal('');
  protected readonly groupName = signal('');
  protected readonly groupEnabled = signal(true);
  protected readonly groupFailureMode = signal<FailureMode | ''>('');
  protected readonly endpoints = signal<EndpointRule[]>([]);
  protected readonly globalRules = signal<ScopeRule[]>([]);
  protected readonly globalFailureMode = signal<FailureMode | ''>('');
  protected readonly endpointDraft = signal<EndpointRule | null>(null);
  protected readonly endpointEditingId = signal<string | null>(null);
  protected readonly endpointId = signal('');
  protected readonly endpointName = signal('');
  protected readonly endpointMethod = signal('GET');
  protected readonly endpointPath = signal('');
  protected readonly endpointRepeatable = signal(true);
  protected readonly endpointExempt = signal(false);
  protected readonly endpointRules = signal<ScopeRule[]>([]);
  protected readonly validation = signal<{ code: string; message: string; problems: string[] } | null>(null);
  protected readonly confirmEndpointDelete = signal<string | null>(null);
  protected readonly confirmGroupDelete = signal(false);
  protected readonly editorBusy = signal(false);
  protected readonly endpointFormOpen = signal(false);
  /** Set once the user has tried to save, so an untouched empty form is not shown as an error. */
  protected readonly submitted = signal(false);
  protected readonly endpointError = signal<string | null>(null);
  protected readonly groupIdError = computed(() =>
    !this.editMode() && !GROUP_ID_PATTERN.test(this.groupId().trim())
      ? 'Use lowercase letters, digits and hyphens, starting with a letter or digit (for example orders-api).'
      : null);

  /**
   * Seeds the form from the inputs. This must not run in the constructor: signal inputs are only set
   * after construction, so reading them there always returns null and the edit form opened empty.
   */
  ngOnInit(): void {
    const group = this.group();
    if (group) {
      this.groupId.set(group.id);
      this.groupName.set(group.name);
      this.groupEnabled.set(group.enabled);
      this.groupFailureMode.set(group.onRedisError ?? '');
      this.endpoints.set(group.endpoints.map((endpoint) => ({ ...endpoint, scopeRules: [...endpoint.scopeRules] })));
    }
    const globals = this.store.globalRules();
    if (globals) {
      this.globalRules.set(globals.rules.map((rule) => ({ ...rule })));
      this.globalFailureMode.set(globals.onRedisError ?? '');
    }
  }

  /**
   * Shows an error banner and brings it into view. The editor is a long scrolling modal and the action
   * buttons sit at the bottom, so a banner at the top would otherwise appear to do nothing.
   */
  private showError(error: { code: string; message: string; problems: string[] }): void {
    this.validation.set(error);
    afterNextRender(() => {
      const banner = this.host.nativeElement.querySelector<HTMLElement>('[data-validation-banner]');
      banner?.scrollIntoView({ block: 'center', behavior: 'smooth' });
      banner?.focus({ preventScroll: true });
    }, { injector: this.injector });
  }

  protected scopeRules(): ScopeRule[] { return this.endpointRules(); }
  protected isGlobalScope(scope: PolicyScope): boolean { return GLOBAL_SCOPES.includes(scope); }

  protected newRule(scope: PolicyScope): ScopeRule {
    return {
      scope,
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
    };
  }

  protected updateRule(index: number, changes: Partial<ScopeRule>, global = false): void {
    const rows = [...(global ? this.globalRules() : this.endpointRules())];
    rows[index] = { ...rows[index], ...changes };
    global ? this.globalRules.set(rows) : this.endpointRules.set(rows);
  }

  protected addRule(scope: PolicyScope, global = false): void {
    const rows = global ? this.globalRules() : this.endpointRules();
    if (rows.some((rule) => rule.scope === scope)) return;
    const next = [...rows, this.newRule(scope)];
    global ? this.globalRules.set(next) : this.endpointRules.set(next);
  }

  protected removeRule(index: number, global = false): void {
    const next = (global ? this.globalRules() : this.endpointRules()).filter((_, i) => i !== index);
    global ? this.globalRules.set(next) : this.endpointRules.set(next);
  }

  protected ruleSummary(rule: ScopeRule): string {
    const params = rule.limit !== null
      ? `${rule.limit}/${rule.window ?? 'window'}`
      : rule.capacity !== null ? `capacity ${rule.capacity}`
        : rule.drainRate !== null ? `drain ${rule.drainRate}/s`
          : rule.maxConcurrent !== null ? `max ${rule.maxConcurrent} concurrent` : 'parameters unset';
    return `${rule.scope} · ${rule.algorithm} · ${params}`;
  }

  protected beginAddEndpoint(): void {
    this.endpointError.set(null);
    this.endpointFormOpen.set(true);
    this.endpointDraft.set(null);
    this.endpointEditingId.set(null);
    this.endpointId.set('');
    this.endpointName.set('');
    this.endpointMethod.set('GET');
    this.endpointPath.set('');
    this.endpointRepeatable.set(true);
    this.endpointExempt.set(false);
    this.endpointRules.set([]);
  }

  protected beginEditEndpoint(endpoint: EndpointRule): void {
    this.endpointError.set(null);
    this.endpointFormOpen.set(true);
    this.endpointDraft.set(endpoint);
    this.endpointEditingId.set(endpoint.id);
    this.endpointId.set(endpoint.id);
    this.endpointName.set(endpoint.displayName);
    this.endpointMethod.set(endpoint.method);
    this.endpointPath.set(endpoint.path);
    this.endpointRepeatable.set(endpoint.repeatable);
    this.endpointExempt.set(endpoint.exempt);
    this.endpointRules.set(endpoint.scopeRules.map((rule) => ({ ...rule })));
  }

  protected cancelEndpointEdit(): void {
    this.endpointDraft.set(null);
    this.endpointError.set(null);
    this.endpointFormOpen.set(false);
  }

  protected saveEndpoint(): void {
    if (!this.endpointPath().trim().startsWith('/')) {
      this.endpointError.set('Path is required and must start with "/" (for example /api/products).');
      return;
    }
    this.endpointError.set(null);
    const endpoint: EndpointRule = {
      id: this.endpointId().trim() || `endpoint-${Date.now()}`,
      displayName: this.endpointName().trim() || this.endpointPath().trim(),
      method: this.endpointMethod(),
      path: this.endpointPath().trim(),
      repeatable: this.endpointRepeatable(),
      exempt: this.endpointExempt(),
      scopeRules: this.endpointExempt() ? [] : this.endpointRules(),
    };
    const previousId = this.endpointEditingId();
    const rows = this.endpoints();
    this.endpoints.set(previousId
      ? rows.map((row) => row.id === previousId ? endpoint : row)
      : [...rows, endpoint]);
    this.endpointDraft.set(null);
    this.endpointEditingId.set(null);
    this.endpointFormOpen.set(false);
  }

  protected async save(): Promise<void> {
    this.validation.set(null);
    this.submitted.set(true);
    const isNew = this.group() === null;
    if (isNew && this.groupIdError()) {
      this.showError({ code: 'validation', message: 'Group id is invalid.', problems: [this.groupIdError()!] });
      return;
    }
    const body: PolicyGroupEdit = {
      id: isNew ? this.groupId().trim() : undefined,
      name: this.groupName().trim() || this.groupId(),
      enabled: this.groupEnabled(),
      endpoints: this.endpoints(),
      onRedisError: this.groupFailureMode() || null,
      version: this.group()?.version,
    };
    this.editorBusy.set(true);
    try {
      const result = await this.store.saveGroup(body, this.group()?.id ?? null);
      if ('status' in result) {
        this.showError({ code: result.code, message: result.message, problems: result.problems });
      } else {
        this.closed.emit();
      }
    } finally {
      this.editorBusy.set(false);
    }
  }

  protected async saveGlobals(): Promise<void> {
    this.validation.set(null);
    const current = this.store.globalRules();
    const body: GlobalScopeRulesEdit = {
      rules: this.globalRules(),
      onRedisError: this.globalFailureMode() || null,
      version: current?.version ?? 0,
    };
    this.editorBusy.set(true);
    try {
      const result = await this.store.saveGlobalRules(body);
      if ('status' in result) {
        this.showError({ code: result.code, message: result.message, problems: result.problems });
      }
    } finally {
      this.editorBusy.set(false);
    }
  }

  protected async deleteEndpoint(endpointId: string): Promise<void> {
    if (this.confirmEndpointDelete() !== endpointId) {
      this.confirmEndpointDelete.set(endpointId);
      return;
    }
    this.confirmEndpointDelete.set(null);
    const group = this.group();
    if (!group) {
      this.endpoints.update((rows) => rows.filter((row) => row.id !== endpointId));
      return;
    }
    this.editorBusy.set(true);
    try {
      const result = await this.store.deleteEndpoint(group.id, endpointId);
      if ('status' in result) this.showError({ code: result.code, message: result.message, problems: result.problems });
      else this.endpoints.set(result.endpoints);
    } finally {
      this.editorBusy.set(false);
    }
  }

  protected async deleteGroup(): Promise<void> {
    const group = this.group();
    if (!group) return;
    if (!this.confirmGroupDelete()) {
      this.confirmGroupDelete.set(true);
      return;
    }
    this.editorBusy.set(true);
    try {
      await this.store.deleteGroup(group.id);
      if (this.store.actionError()) this.showError({ code: 'delete_error', message: this.store.actionError()!, problems: [] });
      else this.closed.emit();
    } finally {
      this.editorBusy.set(false);
    }
  }

  protected async repair(): Promise<void> {
    const group = this.group();
    if (!group) return;
    this.editorBusy.set(true);
    try {
      await this.store.repairGroup(group.id);
      if (this.store.actionError()) {
        this.showError({ code: 'repair_error', message: this.store.actionError()!, problems: [] });
      }
    } finally {
      this.editorBusy.set(false);
    }
  }
}
