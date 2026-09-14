import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HiveToast, type HiveToastVariant } from './toast';

describe('HiveToast', () => {
  let fixture: ComponentFixture<HiveToast>;

  const root = () => fixture.nativeElement as HTMLElement;
  const toast = () => root().querySelector('.hive-toast') as HTMLElement;

  const render = (variant: HiveToastVariant) => {
    fixture.componentRef.setInput('variant', variant);
    fixture.detectChanges();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [HiveToast] }).compileComponents();
    fixture = TestBed.createComponent(HiveToast);
    fixture.detectChanges();
  });

  it('defaults to an informational status message', () => {
    expect(toast().classList).toContain('hive-toast--info');
    expect(toast().getAttribute('role')).toBe('status');
  });

  it('uses the assertive alert role for errors only', () => {
    render('error');
    expect(toast().getAttribute('role')).toBe('alert');
    render('success');
    expect(toast().getAttribute('role')).toBe('status');
  });

  it('adds a hidden textual severity prefix so colour is never the only signal', () => {
    const prefixes: Record<HiveToastVariant, string> = {
      info: 'Information',
      success: 'Success',
      error: 'Error',
    };
    for (const variant of ['info', 'success', 'error'] as const) {
      render(variant);
      expect(root().querySelector('.hive-sr-only')?.textContent).toContain(prefixes[variant]);
    }
  });

  it('gives each variant a distinct glyph, hidden from assistive tech', () => {
    const glyphs = new Set<string>();
    for (const variant of ['info', 'success', 'error'] as const) {
      render(variant);
      const icon = root().querySelector('.hive-toast__icon') as HTMLElement;
      expect(icon.getAttribute('aria-hidden')).toBe('true');
      glyphs.add(icon.textContent?.trim() ?? '');
    }
    expect(glyphs.size).toBe(3);
  });

  it('renders an optional heading', () => {
    expect(root().querySelector('.hive-toast__heading')).toBeNull();
    fixture.componentRef.setInput('heading', 'Task saved');
    fixture.detectChanges();
    expect(root().querySelector('.hive-toast__heading')?.textContent?.trim()).toBe('Task saved');
  });

  it('is not dismissible by default', () => {
    expect(root().querySelector('.hive-toast__dismiss')).toBeNull();
  });

  it('emits dismissed from a labelled dismiss button', () => {
    fixture.componentRef.setInput('dismissible', true);
    fixture.detectChanges();
    const dismiss = root().querySelector('.hive-toast__dismiss') as HTMLButtonElement;
    expect(dismiss.getAttribute('aria-label')).toBe('Dismiss message');

    let dismissed = 0;
    fixture.componentInstance.dismissed.subscribe(() => dismissed++);
    dismiss.click();
    expect(dismissed).toBe(1);
  });
});
