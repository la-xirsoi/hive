import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HiveFormField } from './form-field';

@Component({
  imports: [HiveFormField],
  template: `
    <hive-form-field [label]="label()" [hint]="hint()" [error]="error()" [required]="required()">
      <input class="hive-input" type="text" />
    </hive-form-field>
  `,
})
class FormFieldHost {
  readonly label = signal('Task name');
  readonly hint = signal<string | null>('Keep it short');
  readonly error = signal<string | null>(null);
  readonly required = signal(false);
}

describe('HiveFormField', () => {
  let fixture: ComponentFixture<FormFieldHost>;
  let host: FormFieldHost;

  const root = () => fixture.nativeElement as HTMLElement;
  const input = () => root().querySelector('input') as HTMLInputElement;
  const label = () => root().querySelector('label') as HTMLLabelElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [FormFieldHost] }).compileComponents();
    fixture = TestBed.createComponent(FormFieldHost);
    host = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('renders the label text', () => {
    expect(label().textContent).toContain('Task name');
  });

  it('wires the label to the projected control with a generated id', () => {
    expect(input().id).toBeTruthy();
    expect(label().getAttribute('for')).toBe(input().id);
  });

  it('points aria-describedby at the hint', () => {
    const hintId = root().querySelector('.hive-field__hint')?.id;
    expect(hintId).toBeTruthy();
    expect(input().getAttribute('aria-describedby')).toBe(hintId!);
  });

  it('is not marked invalid or required by default', () => {
    expect(input().getAttribute('aria-invalid')).toBeNull();
    expect(input().getAttribute('aria-required')).toBeNull();
    expect(root().querySelector('.hive-field__error')).toBeNull();
  });

  it('announces the error, swaps aria-describedby and sets aria-invalid', () => {
    host.error.set('Name is required');
    fixture.detectChanges();

    const error = root().querySelector('.hive-field__error') as HTMLElement;
    expect(error.getAttribute('role')).toBe('alert');
    expect(error.textContent).toContain('Name is required');
    expect(input().getAttribute('aria-invalid')).toBe('true');
    expect(input().getAttribute('aria-describedby')).toBe(error.id);
  });

  it('prefixes the error with a hidden "Error" word and a decorative glyph', () => {
    host.error.set('Name is required');
    fixture.detectChanges();
    expect(root().querySelector('.hive-field__error .hive-sr-only')?.textContent).toContain(
      'Error',
    );
    expect(root().querySelector('.hive-field__error-glyph')?.getAttribute('aria-hidden')).toBe(
      'true',
    );
  });

  it('hides the hint while an error is showing', () => {
    host.error.set('Name is required');
    fixture.detectChanges();
    expect(root().querySelector('.hive-field__hint')).toBeNull();
  });

  it('clears the invalid state when the error goes away', () => {
    host.error.set('Name is required');
    fixture.detectChanges();
    host.error.set(null);
    fixture.detectChanges();
    expect(input().getAttribute('aria-invalid')).toBeNull();
    expect(input().getAttribute('aria-describedby')).toBe(
      root().querySelector('.hive-field__hint')!.id,
    );
  });

  it('marks required fields both visibly and for assistive tech', () => {
    host.required.set(true);
    fixture.detectChanges();
    expect(root().querySelector('.hive-field__required')?.getAttribute('aria-hidden')).toBe('true');
    expect(label().textContent).toContain('(required)');
    expect(input().getAttribute('aria-required')).toBe('true');
  });

  it('drops aria-describedby entirely when there is no hint and no error', () => {
    host.hint.set(null);
    fixture.detectChanges();
    expect(input().getAttribute('aria-describedby')).toBeNull();
  });
});
