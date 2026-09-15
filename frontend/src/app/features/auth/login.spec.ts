import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, ParamMap, convertToParamMap } from '@angular/router';
import { FakeLocation, provideFakeBrowser, provideTestConfig } from '../../core/test-support.spec';
import {
  API,
  byTestId,
  featureProviders,
  query,
  text,
  type,
} from '../shared/feature-test-support.spec';
import { LoginPage } from './login';

class RouteStub {
  snapshot: { queryParamMap: ParamMap } = { queryParamMap: convertToParamMap({}) };

  setQuery(params: Record<string, string>): void {
    this.snapshot = { queryParamMap: convertToParamMap(params) };
  }
}

describe('LoginPage', () => {
  let fixture: ComponentFixture<LoginPage>;
  let http: HttpTestingController;
  let location: FakeLocation;
  let route: RouteStub;

  async function setUp(devAuth: boolean): Promise<void> {
    location = new FakeLocation();
    route = new RouteStub();
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [LoginPage],
      providers: featureProviders([
        provideTestConfig({ devAuth }),
        ...provideFakeBrowser(location),
        { provide: ActivatedRoute, useValue: route },
      ]),
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(LoginPage);
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  it('offers the organisation sign-in', async () => {
    await setUp(false);
    expect(byTestId(fixture, 'oauth-sign-in')).not.toBeNull();
    expect(text(fixture)).toContain('Sign in to Hive');
  });

  it('starts the PKCE flow at the identity provider', async () => {
    await setUp(false);

    byTestId<HTMLElement>(fixture, 'oauth-sign-in')!.querySelector('button')!.click();
    await fixture.whenStable();

    const target = location.lastUrl();
    expect(target.searchParams.get('response_type')).toBe('code');
    expect(target.searchParams.get('code_challenge_method')).toBe('S256');
  });

  it('carries the URL the guard was protecting through the round trip', async () => {
    await setUp(false);
    route.setQuery({ returnUrl: '/tasks/42' });

    byTestId<HTMLElement>(fixture, 'oauth-sign-in')!.querySelector('button')!.click();
    await fixture.whenStable();

    expect(location.assigned.length).toBe(1);
    expect(TestBed.inject(ActivatedRoute).snapshot.queryParamMap.get('returnUrl')).toBe(
      '/tasks/42',
    );
  });

  it('hides the development sign-in in a build without it', async () => {
    await setUp(false);

    expect(query(fixture, 'input[type="email"]')).toBeNull();
    expect(text(fixture)).not.toContain('Development sign-in');
  });

  it('offers the development sign-in only when the build enables it', async () => {
    await setUp(true);

    expect(text(fixture)).toContain('Development sign-in');
    expect(query(fixture, 'input[type="email"]')).not.toBeNull();
  });

  it('mints a development token for the email given', async () => {
    await setUp(true);

    type(query<HTMLInputElement>(fixture, 'input[type="email"]')!, 'alice@hive.test');
    await fixture.whenStable();
    query(fixture, '.login__dev-form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    const request = http.expectOne(`${API}/dev/token`);
    expect(request.request.body).toEqual({ email: 'alice@hive.test' });
    request.flush({ accessToken: 'header.payload.signature' });
    await fixture.whenStable();
  });

  it('refuses to submit an empty development email', async () => {
    await setUp(true);

    query(fixture, '.login__dev-form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(text(fixture)).toContain('Enter an email address to continue.');
    http.verify();
  });

  it('shows the server message when the development token is refused', async () => {
    await setUp(true);

    type(query<HTMLInputElement>(fixture, 'input[type="email"]')!, 'nobody@hive.test');
    await fixture.whenStable();
    query(fixture, '.login__dev-form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    http
      .expectOne(`${API}/dev/token`)
      .flush(
        { status: 400, error: 'BAD_REQUEST', message: 'That email is not recognised.' },
        { status: 400, statusText: 'Bad Request' },
      );
    await fixture.whenStable();

    expect(text(fixture)).toContain('That email is not recognised.');
  });
});
