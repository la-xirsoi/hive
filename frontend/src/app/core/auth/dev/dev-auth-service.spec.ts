import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { FakeLocation, TEST_API_BASE, accessTokenFor, provideTestConfig } from '../../test-support.spec';
import { ApiError } from '../../api/api-error';
import { AuthError } from '../auth-models';
import { AUTH_STORAGE, BROWSER_LOCATION, MemoryStorage } from '../browser';
import { AuthService } from '../auth-service';
import { DevAuthService } from './dev-auth-service';

describe('DevAuthService', () => {
  let dev: DevAuthService;
  let auth: AuthService;
  let http: HttpTestingController;

  function setup(devAuth: boolean) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideTestConfig({ devAuth }),
        { provide: BROWSER_LOCATION, useValue: new FakeLocation() },
        { provide: AUTH_STORAGE, useValue: new MemoryStorage() },
      ],
    });
    dev = TestBed.inject(DevAuthService);
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  }

  afterEach(() => http.verify());

  describe('when devAuth is enabled', () => {
    beforeEach(() => setup(true));

    it('reports itself enabled', () => {
      expect(dev.enabled).toBeTrue();
    });

    it('posts the email to the dev token endpoint and adopts the session', async () => {
      const token = accessTokenFor(3600);
      const result = firstValueFrom(dev.login('alice@hive.test'));
      const req = http.expectOne(`${TEST_API_BASE}/dev/token`);

      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ email: 'alice@hive.test' });
      req.flush({ accessToken: token });

      const tokens = await result;
      expect(tokens.accessToken).toBe(token);
      expect(tokens.dev).toBeTrue();
      expect(auth.isAuthenticated()).toBeTrue();
      expect(auth.isDevSession()).toBeTrue();
      auth.clearSession();
    });

    it('normalizes a backend rejection like any other API error', async () => {
      const result = firstValueFrom(dev.login('nobody@hive.test'));
      http.expectOne(`${TEST_API_BASE}/dev/token`).flush(
        { status: 400, error: 'BAD_REQUEST', message: 'No such user: nobody@hive.test' },
        { status: 400, statusText: 'Bad Request' },
      );

      await expectAsync(result).toBeRejectedWithError(ApiError, 'No such user: nobody@hive.test');
      expect(auth.isAuthenticated()).toBeFalse();
    });
  });

  describe('when devAuth is disabled (every production build)', () => {
    beforeEach(() => setup(false));

    it('reports itself disabled', () => {
      expect(dev.enabled).toBeFalse();
    });

    it('refuses to issue the request at all', async () => {
      await expectAsync(firstValueFrom(dev.login('alice@hive.test'))).toBeRejectedWithError(
        AuthError,
        /not available in this build/,
      );
      // The decisive assertion: nothing reached the network. http.verify() in
      // afterEach would fail if a request had been made.
      http.expectNone(`${TEST_API_BASE}/dev/token`);
      expect(auth.isAuthenticated()).toBeFalse();
    });

    it('is also blocked at the AuthService boundary, independently of this service', () => {
      expect(() => auth.acceptDevToken(accessTokenFor(3600))).toThrowMatching(
        (error: unknown) => error instanceof AuthError && error.reason === 'dev_auth_disabled',
      );
    });
  });
});
