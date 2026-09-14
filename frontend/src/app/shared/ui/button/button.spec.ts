import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HiveButton } from './button';

@Component({
  imports: [HiveButton],
  template: `
    <hive-button
      [variant]="variant()"
      [size]="size()"
      [disabled]="disabled()"
      [loading]="loading()"
      [iconOnly]="iconOnly()"
      [ariaLabel]="ariaLabel()"
      (clicked)="clicks.set(clicks() + 1)"
    >
      Save task
    </hive-button>
  `,
})
class ButtonHost {
  readonly variant = signal<'primary' | 'secondary' | 'tertiary' | 'danger'>('primary');
  readonly size = signal<'sm' | 'md' | 'lg'>('md');
  readonly disabled = signal(false);
  readonly loading = signal(false);
  readonly iconOnly = signal(false);
  readonly ariaLabel = signal<string | null>(null);
  readonly clicks = signal(0);
}

describe('HiveButton', () => {
  let fixture: ComponentFixture<ButtonHost>;
  let host: ButtonHost;

  const root = () => fixture.nativeElement as HTMLElement;
  const button = () => root().querySelector('button') as HTMLButtonElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [ButtonHost] }).compileComponents();
    fixture = TestBed.createComponent(ButtonHost);
    host = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('renders a real <button> with type="button" by default', () => {
    expect(button()).toBeTruthy();
    expect(button().getAttribute('type')).toBe('button');
  });

  it('projects its label', () => {
    expect(button().textContent).toContain('Save task');
  });

  it('applies the primary gold variant by default', () => {
    expect(button().classList).toContain('hive-btn--primary');
    expect(button().classList).toContain('hive-btn--md');
  });

  it('supports secondary, tertiary and danger variants and all sizes', () => {
    for (const variant of ['secondary', 'tertiary', 'danger'] as const) {
      host.variant.set(variant);
      fixture.detectChanges();
      expect(button().classList).toContain(`hive-btn--${variant}`);
    }
    for (const size of ['sm', 'md', 'lg'] as const) {
      host.size.set(size);
      fixture.detectChanges();
      expect(button().classList).toContain(`hive-btn--${size}`);
    }
  });

  it('emits clicked when enabled', () => {
    button().click();
    expect(host.clicks()).toBe(1);
  });

  it('is disabled and silent when disabled', () => {
    host.disabled.set(true);
    fixture.detectChanges();
    expect(button().disabled).toBeTrue();
    button().click();
    expect(host.clicks()).toBe(0);
  });

  it('reports aria-busy, shows a spinner and blocks clicks while loading', () => {
    host.loading.set(true);
    fixture.detectChanges();
    expect(button().getAttribute('aria-busy')).toBe('true');
    expect(button().disabled).toBeTrue();
    expect(root().querySelector('hive-spinner')).toBeTruthy();
    button().click();
    expect(host.clicks()).toBe(0);
  });

  it('keeps the visible label while loading so the accessible name is stable', () => {
    host.loading.set(true);
    fixture.detectChanges();
    expect(button().textContent).toContain('Save task');
    expect(button().textContent).toContain('loading');
  });

  it('does not set aria-busy when idle', () => {
    expect(button().getAttribute('aria-busy')).toBeNull();
  });

  it('forwards an explicit aria-label for icon-only buttons', () => {
    host.iconOnly.set(true);
    host.ariaLabel.set('Delete task');
    fixture.detectChanges();
    expect(button().classList).toContain('hive-btn--icon-only');
    expect(button().getAttribute('aria-label')).toBe('Delete task');
  });
});
