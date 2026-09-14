import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HivePageHeader } from './page-header';

@Component({
  imports: [HivePageHeader],
  template: `
    <hive-page-header
      [heading]="heading()"
      [eyebrow]="eyebrow()"
      [subtitle]="subtitle()"
      [level]="level()"
    >
      <button hive-page-header-actions type="button">New task</button>
    </hive-page-header>
  `,
})
class PageHeaderHost {
  readonly heading = signal('My tasks');
  readonly eyebrow = signal<string | null>(null);
  readonly subtitle = signal<string | null>(null);
  readonly level = signal<1 | 2 | 3>(1);
}

describe('HivePageHeader', () => {
  let fixture: ComponentFixture<PageHeaderHost>;
  let host: PageHeaderHost;

  const root = () => fixture.nativeElement as HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [PageHeaderHost] }).compileComponents();
    fixture = TestBed.createComponent(PageHeaderHost);
    host = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('renders the heading as a real h1 by default', () => {
    const h1 = root().querySelector('h1');
    expect(h1).toBeTruthy();
    expect(h1?.textContent?.trim()).toBe('My tasks');
  });

  it('renders the requested heading level instead of faking it with type size', () => {
    host.level.set(2);
    fixture.detectChanges();
    expect(root().querySelector('h1')).toBeNull();
    expect(root().querySelector('h2')?.textContent?.trim()).toBe('My tasks');

    host.level.set(3);
    fixture.detectChanges();
    expect(root().querySelector('h3')?.textContent?.trim()).toBe('My tasks');
  });

  it('omits the eyebrow and subtitle when not supplied', () => {
    expect(root().querySelector('.hive-page-header__eyebrow')).toBeNull();
    expect(root().querySelector('.hive-page-header__subtitle')).toBeNull();
  });

  it('renders the eyebrow and subtitle when supplied', () => {
    host.eyebrow.set('Dashboard');
    host.subtitle.set('Everything assigned to you');
    fixture.detectChanges();
    expect(root().querySelector('.hive-page-header__eyebrow')?.textContent?.trim()).toBe(
      'Dashboard',
    );
    expect(root().querySelector('.hive-page-header__subtitle')?.textContent?.trim()).toBe(
      'Everything assigned to you',
    );
  });

  it('projects actions and hides the decorative gold rule from assistive tech', () => {
    expect(root().querySelector('.hive-page-header__actions')?.textContent).toContain('New task');
    expect(root().querySelector('.hive-page-header__rule')?.getAttribute('aria-hidden')).toBe(
      'true',
    );
  });

  it('uses a <header> landmark element', () => {
    expect(root().querySelector('header.hive-page-header')).toBeTruthy();
  });
});
