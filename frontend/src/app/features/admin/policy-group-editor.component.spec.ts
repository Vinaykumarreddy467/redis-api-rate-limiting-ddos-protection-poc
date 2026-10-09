import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { PolicyGroupEditorComponent } from './policy-group-editor.component';

describe('PolicyGroupEditorComponent validation', () => {
  let fixture: ComponentFixture<PolicyGroupEditorComponent>;

  const root = () => fixture.nativeElement as HTMLElement;
  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };
  const button = (label: string) =>
    Array.from(root().querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.trim() === label)!;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [PolicyGroupEditorComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    fixture = TestBed.createComponent(PolicyGroupEditorComponent);
    await settle();
  });

  it('does not flag an untouched empty form', () => {
    expect(root().querySelector('.field-error')).toBeNull();
    expect(root().querySelector('[data-validation-banner]')).toBeNull();
  });

  it('asks for a name instead of an id, since the server generates the id', async () => {
    expect(root().querySelector('input[name="group-id"]')).toBeNull();

    button('Create group').click();
    await settle();

    const banner = root().querySelector<HTMLElement>('[data-validation-banner]');
    expect(banner).not.toBeNull();
    expect(banner!.getAttribute('tabindex')).toBe('-1');
    expect(banner!.textContent).toContain('Group name is required.');

    const input = root().querySelector<HTMLInputElement>('input[name="group-name"]')!;
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(root().querySelector('#group-name-error')?.textContent).toContain('id is generated');
  });

  it('never sends ids for new endpoints and shows the id of saved ones read-only', async () => {
    const existing = {
      id: 'qa-orders', name: 'QA Orders', enabled: true, onRedisError: null, version: 3,
      createdAt: '2026-10-09T00:00:00Z', updatedAt: '2026-10-09T00:00:00Z', updatedBy: 'pocadmin',
      endpoints: [{
        id: 'ep-1a2b3c4d', method: 'GET', path: '/api/products', displayName: 'Orders list',
        repeatable: true, exempt: false, scopeRules: [],
      }],
    };
    const edit = TestBed.createComponent(PolicyGroupEditorComponent);
    edit.componentRef.setInput('group', existing);
    edit.detectChanges();
    await edit.whenStable();
    const el = edit.nativeElement as HTMLElement;
    const click = (label: string) => Array.from(el.querySelectorAll<HTMLButtonElement>('button'))
      .find((b) => b.textContent?.trim() === label)!.click();

    click('Edit endpoint');
    edit.detectChanges();
    expect(el.querySelector('input[name="endpoint-id"]')).toBeNull();
    expect(el.querySelector('[data-endpoint-id-readonly]')?.textContent).toBe('ep-1a2b3c4d');

    click('Add endpoint');
    edit.detectChanges();
    expect(el.querySelector('[data-endpoint-id-readonly]')).toBeNull();
  });

  it('refuses to add an endpoint whose path does not start with a slash', async () => {
    button('Add endpoint').click();
    await settle();

    button('Save endpoint').click();
    await settle();

    expect(root().textContent).toContain('Path is required and must start with "/"');
    // The endpoint form is still open and nothing was added to the list.
    expect(root().querySelector('[aria-label="Endpoint editor"]')).not.toBeNull();
    expect(root().querySelector('[data-endpoint-id]')).toBeNull();
  });

  it('opens an existing group with its name, state and endpoints filled in', async () => {
    const existing = {
      id: 'qa-orders',
      name: 'QA Orders',
      enabled: false,
      onRedisError: 'FAIL_CLOSED' as const,
      version: 3,
      createdAt: '2026-10-09T00:00:00Z',
      updatedAt: '2026-10-09T00:00:00Z',
      updatedBy: 'pocadmin',
      endpoints: [{
        id: 'ep-1', method: 'GET', path: '/api/products', displayName: 'Orders list',
        repeatable: true, exempt: false, scopeRules: [],
      }],
    };
    const edit = TestBed.createComponent(PolicyGroupEditorComponent);
    edit.componentRef.setInput('group', existing);
    edit.detectChanges();
    await edit.whenStable();
    edit.detectChanges();
    const el = edit.nativeElement as HTMLElement;

    expect(el.querySelector<HTMLInputElement>('input[name="group-name"]')!.value).toBe('QA Orders');
    expect(el.querySelector<HTMLInputElement>('input[name="group-enabled"]')!.checked).toBe(false);
    expect(el.querySelector<HTMLSelectElement>('select[name="group-failure"]')!.value).toBe('FAIL_CLOSED');
    expect(el.textContent).toContain('Orders list');
    expect(el.textContent).toContain('GET /api/products');
    expect(el.textContent).not.toContain('No endpoints configured.');
  });

  it('shows friendly failure-mode labels while keeping the stored values', () => {
    const options = Array.from(root().querySelectorAll<HTMLOptionElement>('select[name="group-failure"] option'));
    expect(options.map((o) => o.value)).toEqual(['', 'FAIL_OPEN', 'FAIL_CLOSED']);
    expect(options.map((o) => o.textContent?.trim())).toEqual([
      'Inherit global default',
      'Fail open (allow)',
      'Fail closed (503)',
    ]);
  });
});
