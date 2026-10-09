import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';

import {
  MAX_REQUEST_COUNT,
  clampRequestCount,
  DemoRoute,
  configuredRoute,
  requestUrl,
  targetLabel,
  GroupDemoEndpoint,
} from '../../core/demo-catalog';
import { DemoSummary, ResponseEntry } from '../../core/models';
import { PolicyTargetRecord } from '../../core/admin-models';
import { AdminStore } from '../../core/admin-store.service';
import { DemoRunnerService } from './demo-runner.service';

@Component({
  selector: 'app-request-demo',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './request-demo.component.html',
  styleUrl: './request-demo.component.scss',
})
export class RequestDemoComponent {
  private readonly runner = inject(DemoRunnerService);
  private readonly store = inject(AdminStore);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  readonly maxCount = MAX_REQUEST_COUNT;

  /**
   * Keeps the newest response in view while a run streams in, unless the user has scrolled up to read an
   * earlier one; then the list stays where they put it.
   */
  private readonly followNewest = effect(() => {
    const count = this.runner.liveResponses().length;
    if (count === 0) return;
    const list = this.host.nativeElement.querySelector<HTMLElement>('.responses-scroll');
    const nearBottom = !list || list.scrollHeight - list.scrollTop - list.clientHeight < 48;
    if (!nearBottom) return;
    afterNextRender(() => {
      const current = this.host.nativeElement.querySelector<HTMLElement>('.responses-scroll');
      if (current) current.scrollTop = current.scrollHeight;
    }, { injector: this.injector });
  });

  /** The user picked a different mode, group, endpoint or policy to test. */
  readonly targetChanged = output<void>();
  /** A run passed validation and is about to send its first request. */
  readonly runStarted = output<void>();

  readonly targetId = signal<string | null>(null);
  readonly mode = signal<'groups' | 'legacy'>('groups');
  readonly groupId = signal<string | null>(null);
  readonly endpointId = signal<string | null>(null);
  readonly requestCount = signal(20);
  readonly username = signal('');
  readonly password = signal('');
  readonly progressSent = signal(0);
  readonly progressTotal = signal(0);
  readonly formError = signal<string | null>(null);
  /** Recorded at run start: the summary must name the target that was actually called. */
  readonly lastRunLabel = signal<string | null>(null);

  readonly running = this.runner.running;
  readonly summary = computed(() => this.runner.summary());
  /** Filled in one by one while a run is in flight, and kept after it ends until the next run starts. */
  readonly responses = this.runner.liveResponses;
  readonly responseExpanded = signal<Record<number, boolean>>({});

  json(value: unknown): string { return JSON.stringify(value, null, 2); }

  toggleResponse(index: number): void {
    this.responseExpanded.update((map) => ({ ...map, [index]: !map[index] }));
  }

  /**
   * Records the element's real open state. Unlike {@link toggleResponse} it is idempotent, which matters
   * because the browser also fires a toggle event when "Expand all" opens panels from code.
   */
  setResponseExpanded(index: number, open: boolean): void {
    this.responseExpanded.update((map) => (map[index] === open ? map : { ...map, [index]: open }));
  }

  isResponseExpanded(index: number): boolean {
    return this.responseExpanded()[index] ?? false;
  }

  readonly responseFilter = signal<'all' | 'ok' | 'rejected' | 'other'>('all');

  private static kindOf(entry: ResponseEntry): 'ok' | 'rejected' | 'other' {
    if (entry.status === 429) return 'rejected';
    return entry.status >= 200 && entry.status < 300 ? 'ok' : 'other';
  }

  readonly responseCounts = computed(() => {
    const counts = { ok: 0, rejected: 0, other: 0 };
    for (const entry of this.responses()) counts[RequestDemoComponent.kindOf(entry)]++;
    return counts;
  });

  readonly visibleResponses = computed(() => {
    const filter = this.responseFilter();
    return filter === 'all'
      ? this.responses()
      : this.responses().filter((entry) => RequestDemoComponent.kindOf(entry) === filter);
  });

  expandAll(): void {
    this.responseExpanded.set(Object.fromEntries(this.visibleResponses().map((entry) => [entry.index, true])));
  }

  collapseAll(): void {
    this.responseExpanded.set({});
  }

  /**
   * One dropdown entry per managed policy, in the backend's order. Disabled policies stay listed and
   * selectable so the operator can read why they are not enforced; only entries the backend marked
   * untestable are separated out.
   */
  readonly targets = computed(() => this.store.demoTargets());
  readonly groups = computed(() => this.store.groups());
  readonly groupEndpoints = computed<GroupDemoEndpoint[]>(() => {
    return this.groups().flatMap((group) => group.endpoints.map((endpoint) => ({
        id: endpoint.id,
        groupId: group.id,
        groupName: group.name,
        method: endpoint.method,
        path: endpoint.path,
        displayName: endpoint.displayName,
        exempt: endpoint.exempt,
        enabled: group.enabled,
        requiresCredentials: this.store.demoTargets().some((target) => target.testable
          && target.method === endpoint.method && target.concretePath === endpoint.path && target.requiresCredentials),
        note: endpoint.exempt ? 'This endpoint is exempt from all rate limits.' : `Group ${group.name} endpoint.`,
      })));
  });
  readonly selectedGroup = computed(() => this.groups().find((group) => group.id === this.groupId()) ?? this.groups()[0] ?? null);
  readonly testable = computed(() => this.targets().filter((t) => t.testable));
  readonly untestable = computed(() => this.targets().filter((t) => !t.testable));
  readonly loading = computed(() => !this.store.demoRoutesState().loaded);
  readonly groupsLoading = computed(() => !this.store.groupsState().loaded);
  readonly loadError = computed(() => this.store.demoRoutesState().error);

  readonly targetId2Label = computed(() => new Map(this.targets().map((t) => [t.id, targetLabel(t)])));
  readonly targetId2Configured = computed(
    () => new Map(this.targets().map((t) => [t.id, configuredRoute(t)])),
  );

  /** Keep every policy selectable for inspection, including disabled or non-testable entries. */
  readonly target = computed<PolicyTargetRecord | null>(() => {
    const options = this.targets();
    return options.find((t) => t.id === this.targetId())
      ?? this.testable()[0]
      ?? options[0]
      ?? null;
  });

  readonly selectedGroupEndpoint = computed(() => {
    const group = this.selectedGroup();
    const endpoint = group?.endpoints.find((item) => item.id === this.endpointId()) ?? group?.endpoints[0];
    if (!group || !endpoint) return null;
    return this.groupEndpoints().find((item) => item.groupId === group.id && item.id === endpoint.id) ?? null;
  });

  readonly selectedGroupEndpoints = computed(() => {
    const group = this.selectedGroup();
    return group ? this.groupEndpoints().filter((entry) => entry.groupId === group.id) : [];
  });

  /** Every policy the limiter charges for the selected request, including the selected one. */
  readonly enforcedWith = computed(() => this.target()?.enforcedWith ?? []);
  readonly exemptions = computed(() => this.target()?.exemptions ?? []);
  readonly needsAuth = computed(() =>
    this.mode() === 'groups'
      ? this.selectedGroupEndpoint()?.requiresCredentials ?? false
      : this.target()?.requiresCredentials ?? false);

  /**
   * Why the selected group endpoint cannot be run, or null when it can. The Start button is disabled
   * in these cases, so the reason must be visible next to it instead of leaving a silent dead button.
   */
  readonly groupRunBlockReason = computed<string | null>(() => {
    if (this.mode() !== 'groups') return null;
    const endpoint = this.selectedGroupEndpoint();
    if (!endpoint) return 'This group has no endpoint to test.';
    if (!endpoint.enabled) return 'This group is disabled, so its rules are not enforced and there is nothing to demonstrate.';
    if (endpoint.exempt) return 'This endpoint is exempt from all rate limits, so a demo run would never be limited.';
    if (!endpoint.path.startsWith('/api/')) {
      return `The demo console only sends requests to this POC's /api/ routes; ${endpoint.path} is outside that, so it cannot be run from here.`;
    }
    return null;
  });

  /** Enabled policies configured for exactly the selected group endpoint's method and path. */
  readonly groupEndpointPolicies = computed(() => {
    const endpoint = this.selectedGroupEndpoint();
    if (!endpoint) return [];
    return this.store.policies().filter((policy) =>
      policy.enabled && policy.method === endpoint.method && policy.path === endpoint.path);
  });

  readonly route = computed<DemoRoute | null>(() => {
    if (this.mode() === 'groups') {
      const endpoint = this.selectedGroupEndpoint();
      if (!endpoint || !endpoint.enabled || endpoint.exempt || !endpoint.path.startsWith('/api/')) return null;
      return {
        id: `group:${endpoint.groupId}:${endpoint.id}`,
        label: `${endpoint.groupName} — ${endpoint.displayName}`,
        method: endpoint.method,
        path: endpoint.path,
        needsAuth: endpoint.requiresCredentials,
        note: endpoint.note,
      };
    }
    const target = this.target();
    if (!target?.testable || !target.method || !target.concretePath) return null;
    return {
      id: target.id,
      label: targetLabel(target),
      method: target.method,
      path: requestUrl(target),
      needsAuth: target.requiresCredentials,
      note: target.note,
    };
  });

  refresh(): void {
    void this.store.loadDemoTargets();
    void this.store.loadGroups();
  }

  constructor() {
    // This component owns catalog loading. Fetch once on every Overview entry so the options
    // reflect current managed policies; the parent must not race it with a duplicate request.
    void this.store.loadDemoTargets();
    void this.store.loadGroups();
  }

  readonly progressPercent = computed(() => {
    const total = this.progressTotal();
    return total === 0 ? 0 : Math.round((this.progressSent() / total) * 100);
  });
  readonly statusBreakdown = computed(() => {
    const statuses = this.summary()?.statuses ?? {};
    return Object.entries(statuses)
      .map(([code, count]) => `HTTP ${code}: ${count}`)
      .join(', ');
  });

  onCountChange(value: string): void {
    this.requestCount.set(clampRequestCount(value));
  }

  onTargetChange(event: Event): void {
    this.targetId.set((event.target as HTMLSelectElement).value || null);
    this.formError.set(null);
    this.targetChanged.emit();
  }

  onGroupChange(event: Event): void {
    this.groupId.set((event.target as HTMLSelectElement).value || null);
    this.endpointId.set(null);
    this.targetChanged.emit();
  }

  onEndpointChange(event: Event): void {
    this.endpointId.set((event.target as HTMLSelectElement).value || null);
    this.formError.set(null);
    this.targetChanged.emit();
  }

  onModeChange(event: Event): void {
    this.mode.set((event.target as HTMLSelectElement).value as 'groups' | 'legacy');
    this.formError.set(null);
    this.targetChanged.emit();
  }

  onStart(): Promise<DemoSummary | null> {
    this.formError.set(null);
    if (this.running()) return Promise.resolve(null);

    const target = this.target();
    const route = this.route();
    // Groups mode runs a group endpoint and never needs the legacy catalogue's target.
    if (!route || (this.mode() === 'legacy' && !target)) {
      this.formError.set(this.mode() === 'groups'
        ? this.groupRunBlockReason() ?? 'Select an endpoint that can be tested automatically.'
        : target?.reason || 'Select a policy that can be tested automatically.');
      return Promise.resolve(null);
    }
    let credentials: { username: string; password: string } | null = null;
    if (route.needsAuth) {
      if (!this.username().trim() || !this.password()) {
        this.formError.set(
          'This target needs HTTP Basic credentials. Without them the API answers 401 and the ' +
            'run would stop on the first request.',
        );
        return Promise.resolve(null);
      }
      credentials = { username: this.username().trim(), password: this.password() };
    }

    const total = clampRequestCount(this.requestCount());
    this.runStarted.emit();
    this.progressTotal.set(total);
    this.progressSent.set(0);

    if (this.mode() === 'groups') {
      const endpoint = this.selectedGroupEndpoint();
      this.lastRunLabel.set(`${endpoint?.groupName} — ${endpoint?.displayName} (${endpoint?.method} ${endpoint?.path})`);
    } else if (target) {
      this.lastRunLabel.set(`${targetLabel(target)} (${target.method} ${requestUrl(target)})`);
    }

    return this.runner.run(route, total, credentials, (sent, budget) => {
      this.progressSent.set(sent);
      this.progressTotal.set(budget);
    }).then((summary) => {
      this.password.set('');
      return summary;
    });
  }

  onCancel(): void {
    this.runner.cancel();
  }
}
