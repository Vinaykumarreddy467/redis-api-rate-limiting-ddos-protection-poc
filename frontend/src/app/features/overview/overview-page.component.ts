import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { StatusService } from '../../core/status.service';
import { AdminStore } from '../../core/admin-store.service';
import { DashboardPageComponent } from '../dashboard/dashboard-page.component';

/**
 * Read-only landing page: service health, cumulative request counters and the active policy snapshot.
 * Nothing here writes. The request tester and live traffic live on the Traffic page.
 */
@Component({
  selector: 'app-overview-page',
  standalone: true,
  imports: [DashboardPageComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './overview-page.component.html',
})
export class OverviewPageComponent {
  protected readonly status = inject(StatusService);
  protected readonly adminStore = inject(AdminStore);

  constructor() {
    this.status.prime();
    // Always re-read: an admin may have created, edited or deleted policies since the last visit.
    void this.adminStore.loadPolicies();
    void this.adminStore.loadCapabilities();
  }
}
