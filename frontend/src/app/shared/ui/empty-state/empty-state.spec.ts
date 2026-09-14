import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HiveEmptyState } from './empty-state';

@Component({
  imports: [HiveEmptyState],
  template: `
    <hive-empty-state heading="No tasks yet" message="Create your first task to get going.">
      <button hive-empty-state-actions type="button">New task</button>
    </hive-empty-state>
  `,
})
class EmptyStateHost {}

describe('HiveEmptyState', () => {
  describe('standalone', () => {
    let fixture: ComponentFixture<HiveEmptyState>;
    const root = () => fixture.nativeElement as HTMLElement;
    const section = () => root().querySelector('section.hive-empty') as HTMLElement;

    beforeEach(async () => {
      await TestBed.configureTestingModule({ imports: [HiveEmptyState] }).compileComponents();
      fixture = TestBed.createComponent(HiveEmptyState);
      fixture.componentRef.setInput('heading', 'Nothing here');
      fixture.detectChanges();
    });

    it('labels its region with the heading', () => {
      expect(section().getAttribute('aria-label')).toBe('Nothing here');
      expect(root().querySelector('h2')?.textContent?.trim()).toBe('Nothing here');
    });

    it('hides the decorative gold plate from assistive technology', () => {
      expect(root().querySelector('.hive-empty__plate')?.getAttribute('aria-hidden')).toBe('true');
    });

    it('omits the message paragraph when there is no message', () => {
      expect(root().querySelector('.hive-empty__message')).toBeNull();
    });

    it('is not a live region by default', () => {
      expect(section().getAttribute('aria-live')).toBeNull();
    });

    it('becomes a polite live region when the state appears after an interaction', () => {
      fixture.componentRef.setInput('live', true);
      fixture.detectChanges();
      expect(section().getAttribute('aria-live')).toBe('polite');
    });

    it('supports a compact variant', () => {
      fixture.componentRef.setInput('compact', true);
      fixture.detectChanges();
      expect(section().classList).toContain('hive-empty--compact');
    });
  });

  describe('with projected content', () => {
    let fixture: ComponentFixture<EmptyStateHost>;

    beforeEach(async () => {
      await TestBed.configureTestingModule({ imports: [EmptyStateHost] }).compileComponents();
      fixture = TestBed.createComponent(EmptyStateHost);
      fixture.detectChanges();
    });

    it('renders heading, message and projected actions', () => {
      const root = fixture.nativeElement as HTMLElement;
      expect(root.querySelector('h2')?.textContent?.trim()).toBe('No tasks yet');
      expect(root.querySelector('.hive-empty__message')?.textContent).toContain(
        'Create your first task',
      );
      expect(root.querySelector('.hive-empty__actions')?.textContent).toContain('New task');
    });
  });
});
