import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';

import { AdminApiService } from '../../core/admin-api.service';
import { AdminStore } from '../../core/admin-store.service';
import { DashboardPageComponent } from './dashboard-page.component';

const BASE = '/api/admin/rate-limit';

function policy(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'products-read',
    name: 'products-read',
    method: 'GET',
    path: '/api/products',
    algorithm: 'FIXED_WINDOW',
    algorithmImplemented: true,
    scope: 'IP',
    window: 'PT1M',
    limit: 125,
    capacity: null,
    refillInterval: null,
    cost: null,
    drainRate: null,
    queueCapacity: null,
    maxConcurrent: null,
    leaseDuration: null,
    enabled: true,
    onRedisError: null,
    version: 12,
    createdAt: '2026-10-05T09:00:00Z',
    updatedAt: '2026-10-05T09:30:00Z',
    updatedBy: 'pocadmin',
    parameterSummary: '125 per 1 minute',
    ...overrides,
  };
}

describe('DashboardPageComponent policy table', () => {
  let fixture: ComponentFixture<DashboardPageComponent>;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DashboardPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    const login = firstValueFrom(TestBed.inject(AdminApiService).login('pocadmin', 'test-only'));
    http.expectOne(`${BASE}/policies`).flush([]);
    await login;
    fixture = TestBed.createComponent(DashboardPageComponent);
    // Overview performs this load on entry; the table itself only reads shared state.
    void TestBed.inject(AdminStore).loadPolicies();
    fixture.detectChanges();
  });

  const settle = async () => {
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  };

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';

  it('renders the live managed policies, not the shipped defaults', async () => {
    http.expectOne(`${BASE}/policies`).flush([
      policy(),
      policy({ id: 'order-create', method: 'POST', path: '/api/orders', scope: 'USER', enabled: false }),
      policy({ id: 'app-quota', method: 'ANY', path: null, scope: 'APPLICATION' }),
    ]);
    await settle();

    expect(text()).toContain('products-read');
    expect(text()).toContain('125 per 1 minute');
    expect(text()).toContain('Shared across all routes');
    expect(text()).toContain('every route');
    const off = (fixture.nativeElement as HTMLElement).querySelectorAll('.state.off');
    expect(off).toHaveLength(1);
    expect(off[0].textContent?.trim()).toBe('Off');
    expect(off[0].parentElement?.textContent).toContain('v12');
    expect(text()).toContain('Read from the managed policy API');
    // The old sample-config wording and its application.yml rows must be gone.
    expect(text()).not.toContain('Sample policy configuration');
    expect(text()).not.toContain('10 per 1 minute');
  });

  it('reports an API failure with a retry and renders no sample rows', async () => {
    http.expectOne(`${BASE}/policies`).flush('', { status: 500, statusText: 'Server Error' });
    await settle();

    expect(text()).toContain('Live policies unavailable');
    expect(text()).not.toContain('Sample policy configuration');

    const retry = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button'),
    ).find((b) => b.textContent?.includes('Retry policies'))!;
    retry.click();
    fixture.detectChanges();
    await settle();
    http.expectOne(`${BASE}/policies`).flush([policy()]);
    await settle();

    expect(text()).toContain('products-read');
    expect(text()).not.toContain('Live policies unavailable');
  });

  it('shows an empty state that points at the workspace instead of fake rows', async () => {
    http.expectOne(`${BASE}/policies`).flush([]);
    await settle();

    expect(text()).toContain('No policies are stored');
    expect(text()).not.toContain('products-read');
  });
});