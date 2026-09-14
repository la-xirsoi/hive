import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HiveSpinner } from './spinner';

describe('HiveSpinner', () => {
  let fixture: ComponentFixture<HiveSpinner>;

  const root = () => fixture.nativeElement as HTMLElement;
  const spinner = () => root().querySelector('.hive-spinner') as HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [HiveSpinner] }).compileComponents();
    fixture = TestBed.createComponent(HiveSpinner);
  });

  it('defaults to a medium gold spinner', () => {
    fixture.detectChanges();
    expect(spinner().classList).toContain('hive-spinner--md');
    expect(spinner().classList).toContain('hive-spinner--gold');
  });

  it('applies the requested size and tone', () => {
    fixture.componentRef.setInput('size', 'lg');
    fixture.componentRef.setInput('tone', 'inverse');
    fixture.detectChanges();
    expect(spinner().classList).toContain('hive-spinner--lg');
    expect(spinner().classList).toContain('hive-spinner--inverse');
  });

  it('announces itself as a live status region with a hidden label', () => {
    fixture.componentRef.setInput('label', 'Loading tasks');
    fixture.detectChanges();
    expect(spinner().getAttribute('role')).toBe('status');
    expect(root().querySelector('.hive-sr-only')?.textContent?.trim()).toBe('Loading tasks');
  });

  it('can suppress the announcement when a parent already reports busy state', () => {
    fixture.componentRef.setInput('announce', false);
    fixture.detectChanges();
    expect(spinner().getAttribute('role')).toBeNull();
    expect(root().querySelector('.hive-sr-only')).toBeNull();
  });

  it('hides the decorative svg from assistive technology', () => {
    fixture.detectChanges();
    const svg = root().querySelector('svg') as SVGElement;
    expect(svg.getAttribute('aria-hidden')).toBe('true');
    expect(svg.getAttribute('focusable')).toBe('false');
  });
});
