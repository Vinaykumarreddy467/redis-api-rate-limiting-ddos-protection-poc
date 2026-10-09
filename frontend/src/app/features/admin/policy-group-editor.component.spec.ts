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

  it('shows a focusable banner and an inline message when the group id is invalid', async () => {
    button('Create group').click();
    await settle();

    const banner = root().querySelector<HTMLElement>('[data-validation-banner]');
    expect(banner).not.toBeNull();
    expect(banner!.getAttribute('tabindex')).toBe('-1');
    expect(banner!.textContent).toContain('Group id is invalid.');

    const input = root().querySelector<HTMLInputElement>('input[name="group-id"]')!;
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(root().querySelector('#group-id-error')?.textContent).toContain('lowercase letters, digits and hyphens');
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
