import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentInjector, runInInjectionContext } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { FakeLocation, accessTokenFor, provideTestConfig } from '../test-support.spec';
import { AppConfig } from '../config/app-config';
import { AUTH_STORAGE, BROWSER_LOCATION, MemoryStorage } from './browser';
import { AuthService } from './auth-service';
import { authGuard } from './auth-guard';

describe('authGuard', () => {
  let auth: AuthService;
  let router: Router;

  function setup(configOverrides: Partial<AppConfig> = { devAuth: true }) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideTestConfig(configOverrides),
        { provide: BROWSER_LOCATION, useValue: new FakeLocation() },
        { provide: AUTH_STORAGE, useValue: new MemoryStorage() },
      ],
    });
    auth = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
  }

  /** Runs the functional guard the way the router would. */
  function activate(url: string): boolean | UrlTree {
    const state = { url } as RouterStateSnapshot;
    const route = {} as ActivatedRouteSnapshot;
    return runInInjectionContext(
      TestBed.inject(EnvironmentInjector),
      () => authGuard(route, state) as boolean | UrlTree,
    );
  }

  it('allows an authenticated user through', () => {
    setup();
    auth.acceptDevToken(accessTokenFor(3600));

    expect(activate('/tasks/42')).toBeTrue();
    auth.clearSession();
  });

  it('redirects an anonymous user to the login route with the target preserved', () => {
    setup();

    const result = activate('/tasks/42');

    expect(result instanceof UrlTree).toBeTrue();
    expect(router.serializeUrl(result as UrlTree)).toBe('/login?returnUrl=%2Ftasks%2F42');
  });

  it('omits returnUrl when the target is the root route', () => {
    setup();

    expect(router.serializeUrl(activate('/') as UrlTree)).toBe('/login');
  });

  it('redirects when the held token has already expired', () => {
    setup();
    auth.acceptDevToken(accessTokenFor(-10));

    expect(auth.accessToken()).toBeTruthy();
    expect(activate('/projects') instanceof UrlTree).toBeTrue();
    auth.clearSession();
  });

  it('redirects again after the session is cleared by a 401', () => {
    setup();
    auth.acceptDevToken(accessTokenFor(3600));
    expect(activate('/teams')).toBeTrue();

    auth.clearSession();

    expect(activate('/teams') instanceof UrlTree).toBeTrue();
  });
});
