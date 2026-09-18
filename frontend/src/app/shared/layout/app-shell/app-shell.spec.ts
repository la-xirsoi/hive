import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { HiveAppShell, type HiveNavLink } from './app-shell';

@Component({
  imports: [HiveAppShell],
  template: `
    <hive-app-shell [brand]="brand()" [links]="links()">
      <span hive-app-shell-user>Ada Lovelace</span>
      <button hive-app-shell-actions type="button">New task</button>
      <p id="page-body">Dashboard content</p>
    </hive-app-shell>
  `,
})
class ShellHost {
  readonly brand = signal('Hive');
  readonly links = signal<readonly HiveNavLink[]>([
    { label: 'Dashboard', routerLink: '/', exact: true },
    { label: 'Tasks', routerLink: '/tasks' },
    { label: 'Teams', routerLink: '/teams' },
  ]);
}

@Component({ template: '<p>route</p>' })
class StubRoute {}

describe('HiveAppShell', () => {
  let fixture: ComponentFixture<ShellHost>;

  const root = () => fixture.nativeElement as HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ShellHost],
      providers: [
        provideRouter([
          { path: '', component: StubRoute },
          { path: 'tasks', component: StubRoute },
          { path: 'teams', component: StubRoute },
        ]),
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ShellHost);
    fixture.detectChanges();
  });

  it('renders the banner, navigation, main and footer landmarks', () => {
    expect(root().querySelector('header[role="banner"]')).toBeTruthy();
    expect(root().querySelector('nav')).toBeTruthy();
    expect(root().querySelector('main#hive-main')).toBeTruthy();
    expect(root().querySelector('footer')).toBeTruthy();
  });

  it('puts a skip link first, targeting the main region', () => {
    const first = root().querySelector('a') as HTMLAnchorElement;
    expect(first.textContent?.trim()).toBe('Skip to main content');
    expect(first.getAttribute('href')).toBe('#hive-main');
    expect(first.classList).toContain('hive-sr-only-focusable');
  });

  it('labels the primary navigation', () => {
    expect(root().querySelector('nav')?.getAttribute('aria-label')).toBe('Primary');
  });

  it('renders the wordmark and a decorative gold hexagon mark', () => {
    expect(root().querySelector('.hive-shell__wordmark')?.textContent?.trim()).toBe('Hive');
    expect(root().querySelector('.hive-shell__mark')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('renders one nav link per supplied item, as a list', () => {
    const items = root().querySelectorAll('.hive-shell__nav-list > li');
    expect(items.length).toBe(3);
    const labels = Array.from(root().querySelectorAll('.hive-shell__nav-link')).map((link) =>
      link.textContent?.trim(),
    );
    expect(labels).toEqual(['Dashboard', 'Tasks', 'Teams']);
  });

  it('omits the nav entirely when no links are supplied', () => {
    fixture.componentInstance.links.set([]);
    fixture.detectChanges();
    expect(root().querySelector('nav')).toBeNull();
  });

  it('marks the dark header as an inverse surface, so slotted controls invert', () => {
    // Without this class the header paints #1A1A1A while its content keeps the
    // light-surface text roles - ink on ink, which is how hive-0lu happened.
    const header = root().querySelector('header[role="banner"]') as HTMLElement;
    expect(header.classList).toContain('hive-surface-inverse');

    const aside = root().querySelector('.hive-shell__aside') as HTMLElement;
    expect(aside.closest('.hive-surface-inverse')).toBe(header);
  });

  it('projects the user slot, the actions slot and the page content', () => {
    expect(root().querySelector('.hive-shell__aside')?.textContent).toContain('Ada Lovelace');
    expect(root().querySelector('.hive-shell__aside')?.textContent).toContain('New task');
    expect(root().querySelector('main')?.textContent).toContain('Dashboard content');
  });

  it('makes the main region programmatically focusable for the skip link', () => {
    expect(root().querySelector('main')?.getAttribute('tabindex')).toBe('-1');
  });

  it('marks the active route with aria-current as well as the gold underline', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/tasks');
    fixture.detectChanges();

    const active = root().querySelector('.hive-shell__nav-link--active') as HTMLAnchorElement;
    expect(active).toBeTruthy();
    expect(active.textContent?.trim()).toBe('Tasks');
    expect(active.getAttribute('aria-current')).toBe('page');

    const inactive = Array.from(
      root().querySelectorAll<HTMLAnchorElement>('.hive-shell__nav-link'),
    ).filter((link) => !link.classList.contains('hive-shell__nav-link--active'));
    expect(inactive.every((link) => link.getAttribute('aria-current') === null)).toBeTrue();
  });
});
