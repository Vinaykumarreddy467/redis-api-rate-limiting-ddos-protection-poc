import { Routes } from '@angular/router';

import { adminGuard } from './admin-guard';
import { AdminLoginComponent } from './features/admin/admin-login.component';
import { AuditPageComponent } from './features/admin/audit-page.component';
import { PoliciesPageComponent } from './features/admin/policies-page.component';
import { TrafficPageComponent } from './features/traffic/traffic-page.component';
import { OverviewPageComponent } from './features/overview/overview-page.component';

/**
 * Every console route except login requires admin authentication. The guard redirects
 * unauthenticated users to login, preserving the requested URL for post-login redirect.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'overview' },
  { path: 'overview', component: OverviewPageComponent, canActivate: [adminGuard], title: 'RateGuard — Overview' },
  { path: 'traffic', component: TrafficPageComponent, canActivate: [adminGuard], title: 'RateGuard — Traffic' },
  { path: 'policies', component: PoliciesPageComponent, canActivate: [adminGuard], title: 'RateGuard — Policies' },
  { path: 'audit', component: AuditPageComponent, canActivate: [adminGuard], title: 'RateGuard — Audit' },
  { path: 'admin/login', component: AdminLoginComponent, title: 'RateGuard — Sign in' },
  { path: '**', redirectTo: 'overview' },
];