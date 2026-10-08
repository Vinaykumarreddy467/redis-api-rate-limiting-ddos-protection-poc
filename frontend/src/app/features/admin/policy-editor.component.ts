import {
  ChangeDetectionStrategy,
  afterNextRender,
  Component,
  ElementRef,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';

import { AdminStore } from '../../core/admin-store.service';
import { durationToSeconds, secondsToDuration } from '../../core/admin-models';

/**
 * Create/edit policy in a modal dialog instead of a permanent form under the table.
 *
 * Algorithm and scope options come from /capabilities, and the algorithm-specific fields are
 * conditional on the selected algorithm, so the form can never offer a combination the backend
 * would reject. `version` is read-only and carries the value the editor loaded, which is what makes
 * the backend's optimistic concurrency check meaningful.
 */
@Component({
  selector: 'app-policy-editor',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './policy-editor.component.html',
})
export class PolicyEditorComponent implements OnInit {
  protected readonly store = inject(AdminStore);

  /** Null opens the editor in create mode. */
  readonly policyId = input<string | null>(null);
  readonly closed = output<void>();
  readonly exemptionSaved = output<void>();

  protected readonly idField = viewChild<ElementRef<HTMLInputElement>>('idField');

  protected readonly isCreate = computed(() => this.policyId() === null);
  protected readonly heading = computed(() =>
    this.isCreate() ? 'Create policy' : `Edit ${this.policyId()}`,
  );

  protected readonly algorithmNote = computed(() => {
    const algo = this.store
      .capabilities()
      ?.algorithms.find((a) => a.name === this.fAlgorithm());
    return algo?.note ?? null;
  });

  // Editor fields.
  protected readonly fId = signal('');
  protected readonly fName = signal('');
  protected readonly fMethod = signal('GET');
  protected readonly fPath = signal('');
  protected readonly fAlgorithm = signal('FIXED_WINDOW');
  protected readonly fScope = signal('IP');
  protected readonly fWindowSeconds = signal(60);
  protected readonly fLimit = signal(100);
  protected readonly fCapacity = signal(100);
  protected readonly fRefillSeconds = signal(10);
  protected readonly fCost = signal(1);
  protected readonly fDrainRate = signal(10);
  protected readonly fQueueCapacity = signal(20);
  protected readonly fMaxConcurrent = signal(10);
  protected readonly fLeaseSeconds = signal(30);
  protected readonly fEnabled = signal(true);
  protected readonly fExempt = signal(false);
  protected readonly fFailureMode = signal<'' | 'FAIL_OPEN' | 'FAIL_CLOSED'>('');
  protected readonly fVersion = signal(1);
  protected readonly fieldErrors = signal<string[]>([]);

  ngOnInit(): void {
    const policy = this.store.policies().find((p) => p.id === this.policyId());
    if (!policy) return;
    this.fId.set(policy.id);
    this.fName.set(policy.name);
    this.fMethod.set(policy.method);
    this.fPath.set(policy.path ?? '');
    this.fAlgorithm.set(policy.algorithm);
    this.fScope.set(policy.scope);
    this.fWindowSeconds.set(durationToSeconds(policy.window) ?? 60);
    this.fLimit.set(policy.limit ?? 100);
    this.fCapacity.set(policy.capacity ?? 100);
    this.fRefillSeconds.set(durationToSeconds(policy.refillInterval) ?? 10);
    this.fCost.set(policy.cost ?? 1);
    this.fDrainRate.set(policy.drainRate ?? 10);
    this.fQueueCapacity.set(policy.queueCapacity ?? 20);
    this.fMaxConcurrent.set(policy.maxConcurrent ?? 10);
    this.fLeaseSeconds.set(durationToSeconds(policy.leaseDuration) ?? 30);
    this.fEnabled.set(policy.enabled);
    this.fFailureMode.set(policy.onRedisError ?? '');
    this.fVersion.set(policy.version);
  }

  constructor() {
    // Focus lands inside the dialog rather than staying on the button that opened it.
    afterNextRender(() => this.focusFirstField());
  }

  /** Move focus into the dialog so keyboard users start inside it. */
  focusFirstField(): void {
    this.idField()?.nativeElement.focus();
  }

  protected onClose(): void {
    this.closed.emit();
  }

  protected async onSave(): Promise<void> {
    const problems = this.validate();
    this.fieldErrors.set(problems);
    if (problems.length > 0) return;

    const id = this.fId().trim();
    this.store.busy.set(true);
    try {
      if (this.fExempt()) {
        const saved = await this.store.saveExemption({
          id,
          name: this.fName().trim() || id,
          method: this.fMethod(),
          path: this.fPath().trim(),
          enabled: this.fEnabled(),
        });
        if (!('status' in saved)) this.exemptionSaved.emit();
        return;
      }

      const failureMode = this.fFailureMode();
      const saved = await this.store.save(
        {
          id,
          name: this.fName().trim() || id,
          method: this.fMethod(),
          path: (this.fScope() === 'GLOBAL' || this.fScope() === 'APPLICATION') ? null : this.fPath().trim(),
          algorithm: this.fAlgorithm(),
          scope: this.fScope(),
          window: secondsToDuration(this.fWindowSeconds()),
          limit: this.fLimit(),
          capacity: this.fCapacity(),
          refillInterval: secondsToDuration(this.fRefillSeconds()),
          cost: this.fCost(),
          drainRate: this.fDrainRate(),
          queueCapacity: this.fQueueCapacity(),
          maxConcurrent: this.fMaxConcurrent(),
          leaseDuration: secondsToDuration(this.fLeaseSeconds()),
          enabled: this.fEnabled(),
          onRedisError: failureMode === '' ? null : failureMode,
          version: this.isCreate() ? undefined : this.fVersion(),
        },
        this.isCreate() ? null : id,
      );
      if (!('status' in saved)) this.onClose();
    } finally {
      this.store.busy.set(false);
    }
  }

  /** Client-side checks mirror the backend rules; the backend remains the authority. */
  private validate(): string[] {
    const problems: string[] = [];
    if (this.isCreate() && !/^[a-z0-9][a-z0-9-]{0,62}$/.test(this.fId().trim())) {
      problems.push('Id must start with a letter or digit and use only a-z, 0-9 and hyphens.');
    }
    if (this.fExempt()) {
      if (!this.fPath().trim().startsWith('/')) {
        problems.push('Path must start with /');
      }
      if (this.fPath().trim() === '/**') {
        problems.push('Path /** is too broad; use a specific route');
      }
      if (this.fPath().trim().startsWith('/api/admin') || this.fPath().trim().startsWith('/actuator')) {
        problems.push('Exemptions cannot target admin or actuator routes');
      }
      return problems;
    }
    // Algorithm and scope come from /capabilities: refuse to save rather than send invented values.
    if (!this.store.capabilitiesReady()) {
      problems.push(
        this.store.capabilitiesState().error
          ? 'Algorithm and scope options could not be loaded. Close the editor and retry.'
          : 'Algorithm and scope options are still loading. Try again in a moment.',
      );
      return problems;
    }
    if (this.fScope() !== 'GLOBAL' && this.fScope() !== 'APPLICATION' && !this.fPath().trim().startsWith('/')) {
      problems.push('Path must start with / — only a GLOBAL policy omits it.');
    }
    const positiveInt = (value: number, label: string) => {
      if (!Number.isInteger(value) || value < 1) {
        problems.push(`${label} must be a whole number of at least 1.`);
      }
    };
    switch (this.fAlgorithm()) {
      case 'TOKEN_BUCKET':
        positiveInt(this.fCapacity(), 'Bucket capacity');
        positiveInt(this.fRefillSeconds(), 'Refill interval');
        positiveInt(this.fCost(), 'Cost per request');
        break;
      case 'LEAKY_BUCKET':
        positiveInt(this.fDrainRate(), 'Drain rate');
        positiveInt(this.fQueueCapacity(), 'Queue capacity');
        break;
      case 'CONCURRENCY_LIMIT':
        positiveInt(this.fMaxConcurrent(), 'Max concurrent');
        positiveInt(this.fLeaseSeconds(), 'Lease duration');
        break;
      default:
        positiveInt(this.fLimit(), 'Limit per window');
        positiveInt(this.fWindowSeconds(), 'Window');
        break;
    }
    const algo = this.store.capabilities()?.algorithms.find((a) => a.name === this.fAlgorithm());
    if (algo && !algo.implemented) {
      problems.push(`${this.fAlgorithm()} is not enforced by this build, so it cannot be saved.`);
    }
    return problems;
  }
}
