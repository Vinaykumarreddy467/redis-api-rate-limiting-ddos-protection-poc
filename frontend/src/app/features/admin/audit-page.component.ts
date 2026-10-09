import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { AUDIT_LIMITS, AdminStore } from '../../core/admin-store.service';

const PAGE_SIZE = 20;
/** Long field lists collapse to a summary; the full list stays reachable via the row toggle. */
const FIELD_PREVIEW = 3;

/**
 * Audit workspace.
 *
 * The backend accepts a limit but no offset, so this page loads one bounded window and pages
 * through it locally. That keeps the page short instead of rendering every entry ever recorded.
 */
@Component({
  selector: 'app-audit-page',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './audit-page.component.html',
})
export class AuditPageComponent {
  protected readonly store = inject(AdminStore);
  protected readonly limits = AUDIT_LIMITS;
  protected readonly pageSize = PAGE_SIZE;

  protected readonly page = signal(0);
  protected readonly expanded = signal<Set<string>>(new Set());

  protected readonly pageCount = computed(() => Math.max(1, Math.ceil(this.store.audit().length / PAGE_SIZE)));
  protected readonly visible = computed(() =>
    this.store.audit().slice(this.page() * PAGE_SIZE, this.page() * PAGE_SIZE + PAGE_SIZE),
  );

  constructor() {
    void this.store.loadAudit(this.store.auditLimit());
  }

  protected async onLimitChange(value: number): Promise<void> {
    this.page.set(0);
    this.expanded.set(new Set());
    await this.store.loadAudit(value as (typeof AUDIT_LIMITS)[number]);
  }

  protected nextPage(): void {
    this.page.update((p) => Math.min(p + 1, this.pageCount() - 1));
  }

  protected previousPage(): void {
    this.page.update((p) => Math.max(0, p - 1));
  }

  /** Per-row detail toggle, keyed by a row's own fields since the API returns no id. */
  protected keyFor(entry: { at: string; policyId: string; operation: string }): string {
    return `${entry.at}|${entry.policyId}|${entry.operation}`;
  }

  protected isExpanded(key: string): boolean {
    return this.expanded().has(key);
  }

  protected toggle(key: string): void {
    const next = new Set(this.expanded());
    if (next.has(key)) next.delete(key);
    else next.add(key);
    this.expanded.set(next);
  }

  protected previewFields(fields: string[]): string {
    return fields.slice(0, FIELD_PREVIEW).join(', ');
  }

  protected hiddenFieldCount(fields: string[]): number {
    return Math.max(0, fields.length - FIELD_PREVIEW);
  }

  /** Nanosecond ISO timestamps read badly; a local date and second-precision time is enough. */
  protected formatTime(iso: string): string {
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) return iso;
    return `${date.toLocaleDateString()} ${date.toLocaleTimeString()}`;
  }
}