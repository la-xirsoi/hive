import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { alice, provideFakeBrowser } from '../../core/test-support.spec';
import { CurrentUser } from '../shared/current-user';
import {
  API,
  byTestId,
  featureProviders,
  query,
  queryAll,
  text,
} from '../shared/feature-test-support.spec';
import { ShellLayout } from './shell-layout';

describe('ShellLayout', () => {
  let fixture: ComponentFixture<ShellLayout>;
  let http: HttpTestingController;

  const navLinks = () =>
    queryAll<HTMLAnchorElement>(fixture, 'nav a').map((link) => ({
      label: link.textContent?.trim(),
      href: link.getAttribute('href'),
    }));

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ShellLayout],
      // A `login` route has to exist: signing out navigates to it.
      providers: featureProviders([...provideFakeBrowser()], [{ path: 'login', children: [] }]),
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ShellLayout);
    await fixture.whenStable();
  });

  afterEach(() => http.verify({ ignoreCancelled: true }));

  it('provisions the Hive user on first sight by reading `GET /users/me` (US-3)', () => {
    const request = http.expectOne(`${API}/users/me`);
    expect(request.request.method).toBe('GET');
    request.flush(alice);
  });

  it('wraps the routed feature in the application shell', async () => {
    http.expectOne(`${API}/users/me`).flush(alice);
    await fixture.whenStable();

    expect(query(fixture, 'hive-app-shell')).not.toBeNull();
    expect(query(fixture, 'router-outlet')).not.toBeNull();
  });

  it('offers real navigation to every authenticated area', async () => {
    http.expectOne(`${API}/users/me`).flush(alice);
    await fixture.whenStable();

    expect(navLinks()).toEqual([
      { label: 'Dashboard', href: '/' },
      { label: 'My tasks', href: '/tasks' },
      { label: 'Unassigned', href: '/tasks/unassigned' },
      { label: 'Teams', href: '/teams' },
      { label: 'Projects', href: '/projects' },
    ]);
  });

  it('names the signed-in user once their record arrives', async () => {
    http.expectOne(`${API}/users/me`).flush(alice);
    await fixture.whenStable();

    expect(text(fixture)).toContain('Alice Ng');
    expect(text(fixture)).toContain('alice@hive.test');
  });

  it('still renders a header when the identity request fails', async () => {
    http
      .expectOne(`${API}/users/me`)
      .flush(
        { status: 500, error: 'INTERNAL_ERROR', message: 'nope' },
        { status: 500, statusText: 'Server Error' },
      );
    await fixture.whenStable();

    expect(query(fixture, 'hive-app-shell')).not.toBeNull();
    expect(text(fixture)).toContain('Signed in');
  });

  it('forgets the cached identity on sign-out, so the next session re-reads it', async () => {
    http.expectOne(`${API}/users/me`).flush(alice);
    await fixture.whenStable();
    expect(TestBed.inject(CurrentUser).user()).toEqual(alice);

    byTestId<HTMLElement>(fixture, 'sign-out')!.querySelector('button')!.click();
    await fixture.whenStable();

    expect(TestBed.inject(CurrentUser).user()).toBeNull();
  });
});
