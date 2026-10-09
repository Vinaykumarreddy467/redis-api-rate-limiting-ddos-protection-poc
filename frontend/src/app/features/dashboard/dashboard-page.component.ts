import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { OverviewSnapshot } from '../../core/dashboard-api.service';
import { formatWindow } from '../../core/demo-catalog';
import { AdminStore } from '../../core/admin-store.service';

@Component({
  selector: 'app-dashboard-page',
  standalone: true,
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dashboard-page.component.html',
  styleUrl: './dashboard-page.component.scss',
})
export class DashboardPageComponent {
  private readonly store = inject(AdminStore);

  // App owns the single polling loop and passes the snapshot down, so the page never fetches again.
  readonly snapshot = input<OverviewSnapshot | null>(null);

  readonly loading = computed(() => this.snapshot() === null);

  readonly healthText = computed(() => {
    const snapshot = this.snapshot();
    if (!snapshot) return 'Checking';
    if (!snapshot.health.available) return 'Unavailable';
    return snapshot.health.value.state === 'healthy' ? 'Healthy' : 'Degraded';
  });

  readonly redisText = computed(() => {
    const snapshot = this.snapshot();
    if (!snapshot) return 'Checking';
    if (!snapshot.health.available) return 'Unknown';
    return snapshot.health.value.state === 'healthy' ? 'Connected' : 'Unavailable';
  });

  /** The live managed policies. There is no sample fallback: an unavailable API stays empty. */
  readonly policies = computed(() => this.store.policies());
  readonly policiesLoading = computed(() => !this.store.policiesState().loaded);
  readonly policiesError = computed(() => this.store.policiesState().error);
  readonly policyCount = computed(() => (this.policiesError() ? null : this.policies().length));

  retryPolicies(): void {
    void this.store.loadPolicies();
  }

  readonly allowedShare = computed(() => {
    const snapshot = this.snapshot();
    if (!snapshot?.countersAvailable || snapshot.total === 0) return 0;
    return Math.round((snapshot.allowed / snapshot.total) * 100);
  });

  readonly rejectedShare = computed(() => {
    const snapshot = this.snapshot();
    if (!snapshot?.countersAvailable || snapshot.total === 0) return 0;
    return Math.round((snapshot.rejected / snapshot.total) * 100);
  });

  readonly redisErrorShare = computed(() => {
    const snapshot = this.snapshot();
    if (!snapshot?.countersAvailable || snapshot.total === 0) return 0;
    return Math.round((snapshot.redisError / snapshot.total) * 100);
  });

  formatWindow(seconds: number): string {
    return formatWindow(seconds);
  }

  /** FIXED_WINDOW becomes "Fixed window": the raw enum name is long, unbreakable and shouty in a table. */
  algorithmLabel(algorithm: string): string {
    const words = algorithm.toLowerCase().split('_').join(' ');
    return words.charAt(0).toUpperCase() + words.slice(1);
  }

  identityLabel(scope: string): string {
    switch (scope) {
      case 'USER':
        return 'Authenticated user';
      case 'GLOBAL':
        return 'Shared global quota';
      case 'APPLICATION':
        return 'Shared across all routes';
      case 'ENDPOINT':
        return 'This route only';
      default:
        return 'Client IP';
    }
  }
}