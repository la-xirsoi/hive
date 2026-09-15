import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRoute,
  ParamMap,
  Router,
  convertToParamMap,
  provideRouter,
} from '@angular/router';
import {
  FakeLocation,
  TEST_TOKEN_ENDPOINT,
  accessTokenFor,
  provideTestConfig,
} from '../test-support.spec';
import { AUTH_STORAGE, BROWSER_LOCATION, MemoryStorage } from './browser';
import { AuthCallback } from './auth-callback';
import { AuthService } from './auth-service';

/** A mutable ActivatedRoute stub, so query params can be set after injection. */
class RouteStub {
  snapshot: { queryParamMap: ParamMap } = { queryParamMap: convertToParamMap({}) };

  setQuery(params: Record<string, string>): void {
    this.snapshot = { queryParamMap: convertToParamMap(params) };
  }
}

describe('AuthCallback', () => {
  let route: RouteStub;
  let location: FakeLocation;
  let http: HttpTestingController;
  let router: Router;
  let auth: AuthService;

  beforeEach(() => {
    route = new RouteStub();
    location = new FakeLocation();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideTestConfig(),
        { provide: BROWSER_LOCATION, useValue: location },
        { provide: AUTH_STORAGE, useValue: new MemoryStorage() },
        { provide: ActivatedRoute, useValue: route },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => http.verify());

  /** Starts a real sign-in so a valid transaction is in storage, and returns its state. */
  async function beginLogin(returnUrl = '/tasks/42'): Promise<string> {
    await auth.login(returnUrl);
    return location.lastUrl().searchParams.get('state')!;
  }

  function text(element: unknown): string {
    return (element as HTMLElement).textContent ?? '';
  }

  it('completes the exchange and navigates to the recorded return URL', async () => {
    const state = await beginLogin('/tasks/42');
    route.setQuery({ code: 'the-code', state });
    const navigate = spyOn(router, 'navigateByUrl').and.resolveTo(true);

    const fixture = TestBed.createComponent(AuthCallback);
    http
      .expectOne(TEST_TOKEN_ENDPOINT)
      .flush({ access_token: accessTokenFor(3600), expires_in: 3600 });
    await fixture.whenStable();

    expect(auth.isAuthenticated()).toBeTrue();
    expect(navigate).toHaveBeenCalledWith('/tasks/42');
    auth.clearSession();
  });

  it('shows a progress message while the exchange is in flight', async () => {
    const state = await beginLogin();
    route.setQuery({ code: 'the-code', state });

    const fixture = TestBed.createComponent(AuthCallback);
    fixture.detectChanges();
    expect(text(fixture.nativeElement)).toContain('Completing sign-in');

    http.expectOne(TEST_TOKEN_ENDPOINT).flush({ access_token: accessTokenFor(60), expires_in: 60 });
    await fixture.whenStable();
    auth.clearSession();
  });

  it('shows the failure instead of navigating when the provider refuses', async () => {
    route.setQuery({ error: 'access_denied', error_description: 'You said no.' });
    const navigate = spyOn(router, 'navigateByUrl');

    const fixture = TestBed.createComponent(AuthCallback);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(text(fixture.nativeElement)).toContain('Sign-in failed');
    expect(text(fixture.nativeElement)).toContain('You said no.');
    expect(navigate).not.toHaveBeenCalled();
    expect(auth.isAuthenticated()).toBeFalse();
  });

  it('shows a failure for a callback with no pending transaction', async () => {
    route.setQuery({ code: 'c', state: 's' });

    const fixture = TestBed.createComponent(AuthCallback);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(text(fixture.nativeElement)).toContain('no longer valid');
  });

  it('shows a failure when the token endpoint rejects the exchange', async () => {
    const state = await beginLogin();
    route.setQuery({ code: 'c', state });

    const fixture = TestBed.createComponent(AuthCallback);
    http
      .expectOne(TEST_TOKEN_ENDPOINT)
      .flush({ error: 'invalid_grant' }, { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(text(fixture.nativeElement)).toContain('could not complete the sign-in');
    expect(auth.isAuthenticated()).toBeFalse();
  });

  it('offers a way back to sign in after a failure', async () => {
    route.setQuery({ error: 'server_error' });

    const fixture = TestBed.createComponent(AuthCallback);
    await fixture.whenStable();
    fixture.detectChanges();

    const link = (fixture.nativeElement as HTMLElement).querySelector('a');
    expect(link?.getAttribute('href')).toBe('/login');
  });
});
