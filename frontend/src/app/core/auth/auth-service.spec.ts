import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import {
  FakeLocation,
  TEST_AUTHORIZE,
  TEST_END_SESSION,
  TEST_REDIRECT_URI,
  TEST_TOKEN_ENDPOINT,
  accessTokenFor,
  makeJwt,
  provideTestConfig,
} from '../test-support.spec';
import { AppConfig, APP_CONFIG } from '../config/app-config';
import { AuthError, TokenSet } from './auth-models';
import { AUTH_STORAGE, BROWSER_LOCATION, MemoryStorage } from './browser';
import { AuthService, REFRESH_SKEW_MS } from './auth-service';
import { createCodeChallenge } from './pkce';
import { TokenStore } from './token-store';

const TOKENS_KEY = 'hive.auth.tokens';

/**
 * Drains the microtask queue. Used instead of `fakeAsync`/`tick`, which need
 * zone.js; this app is zoneless, so timers are driven by `jasmine.clock()` and
 * promise continuations by this helper.
 */
async function flushMicrotasks(times = 12): Promise<void> {
  for (let i = 0; i < times; i++) {
    await Promise.resolve();
  }
}

function storedTokens(
  expiresInMs: number,
  refreshToken: string | null = 'rt',
  idToken: string | null = null,
): string {
  const tokens: TokenSet = {
    accessToken: accessTokenFor(Math.round(expiresInMs / 1000)),
    refreshToken,
    idToken,
    tokenType: 'Bearer',
    expiresAt: Date.now() + expiresInMs,
    scope: 'openid',
  };
  return JSON.stringify(tokens);
}

describe('AuthService', () => {
  let location: FakeLocation;
  let storage: MemoryStorage;

  function setup(configOverrides: Partial<AppConfig> = {}): {
    service: AuthService;
    http: HttpTestingController;
    router: Router;
  } {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideTestConfig(configOverrides),
        { provide: BROWSER_LOCATION, useValue: location },
        { provide: AUTH_STORAGE, useValue: storage },
      ],
    });
    return {
      service: TestBed.inject(AuthService),
      http: TestBed.inject(HttpTestingController),
      router: TestBed.inject(Router),
    };
  }

  beforeEach(() => {
    location = new FakeLocation();
    storage = new MemoryStorage();
  });

  // -------------------------------------------------------------------------
  // Starting the flow
  // -------------------------------------------------------------------------

  describe('login()', () => {
    it('redirects to the authorization endpoint with a full PKCE request', async () => {
      const { service, http } = setup();

      await service.login('/tasks/42');

      const url = location.lastUrl();
      expect(`${url.origin}${url.pathname}`).toBe(TEST_AUTHORIZE);
      expect(url.searchParams.get('response_type')).toBe('code');
      expect(url.searchParams.get('client_id')).toBe('hive-web-test');
      expect(url.searchParams.get('redirect_uri')).toBe(TEST_REDIRECT_URI);
      expect(url.searchParams.get('scope')).toBe('openid profile email offline_access');
      expect(url.searchParams.get('code_challenge_method')).toBe('S256');
      expect(url.searchParams.get('state')).toBeTruthy();
      expect(url.searchParams.get('nonce')).toBeTruthy();
      expect(url.searchParams.get('code_challenge')).toBeTruthy();
      http.verify();
    });

    it('never puts the code verifier on the wire, only its S256 challenge', async () => {
      const { service } = setup();

      await service.login();

      const transaction = TestBed.inject(TokenStore).takeTransaction();
      const url = location.lastUrl();
      expect(transaction).not.toBeNull();
      expect(url.toString()).not.toContain(transaction!.codeVerifier);
      expect(url.searchParams.get('code_challenge')).toBe(
        await createCodeChallenge(transaction!.codeVerifier),
      );
      expect(url.searchParams.get('state')).toBe(transaction!.state);
      expect(url.searchParams.get('nonce')).toBe(transaction!.nonce);
    });

    it('records the requested return URL', async () => {
      const { service } = setup();

      await service.login('/projects/20');

      expect(TestBed.inject(TokenStore).takeTransaction()?.returnUrl).toBe('/projects/20');
    });

    it('defaults the return URL to the current route', async () => {
      const { service } = setup();

      await service.login();

      expect(TestBed.inject(TokenStore).takeTransaction()?.returnUrl).toBe('/');
    });

    it('refuses an off-site return URL so login cannot become an open redirect', async () => {
      const { service } = setup();

      await service.login('https://evil.example.com/steal');
      expect(TestBed.inject(TokenStore).takeTransaction()?.returnUrl).toBe('/');

      await service.login('//evil.example.com/steal');
      expect(TestBed.inject(TokenStore).takeTransaction()?.returnUrl).toBe('/');
    });

    it('honours an explicit authorize endpoint override', async () => {
      const { service } = setup({
        oauth: {
          issuer: 'https://id.test.example/realms/hive',
          clientId: 'hive-web-test',
          redirectUri: TEST_REDIRECT_URI,
          scope: 'openid',
          authorizeEndpoint: 'https://id.test.example/protocol/openid-connect/auth',
        },
      });

      await service.login();

      expect(location.lastUrl().pathname).toBe('/protocol/openid-connect/auth');
    });
  });

  // -------------------------------------------------------------------------
  // Completing the flow
  // -------------------------------------------------------------------------

  describe('handleCallback()', () => {
    async function beginLogin(service: AuthService) {
      await service.login('/tasks/42');
      const url = location.lastUrl();
      return {
        state: url.searchParams.get('state')!,
        nonce: url.searchParams.get('nonce')!,
      };
    }

    it('exchanges the code with the stored verifier and adopts the session', async () => {
      const { service, http } = setup();
      const { state, nonce } = await beginLogin(service);
      const accessToken = accessTokenFor(3600);

      const returnUrl = service.handleCallback({ code: 'the-code', state });
      const req = http.expectOne(TEST_TOKEN_ENDPOINT);
      const body = new URLSearchParams(req.request.body as string);

      expect(req.request.method).toBe('POST');
      expect(req.request.headers.get('Content-Type')).toBe('application/x-www-form-urlencoded');
      expect(body.get('grant_type')).toBe('authorization_code');
      expect(body.get('code')).toBe('the-code');
      expect(body.get('redirect_uri')).toBe(TEST_REDIRECT_URI);
      expect(body.get('client_id')).toBe('hive-web-test');
      expect(body.get('code_verifier')).toBeTruthy();
      // PKCE: no client secret is ever sent from a public client.
      expect(body.get('client_secret')).toBeNull();

      req.flush({
        access_token: accessToken,
        token_type: 'Bearer',
        expires_in: 3600,
        refresh_token: 'rt-1',
        id_token: makeJwt({ sub: 'auth0|alice', nonce }),
        scope: 'openid profile',
      });

      expect(await returnUrl).toBe('/tasks/42');
      expect(service.isAuthenticated()).toBeTrue();
      expect(service.accessToken()).toBe(accessToken);
      expect(service.principal()?.email).toBe('alice@hive.test');
      expect(service.principal()?.name).toBe('Alice Ng');
      expect(service.isDevSession()).toBeFalse();
      // Survives a reload: the token set is persisted, not just in memory.
      expect(TestBed.inject(TokenStore).readTokens()?.refreshToken).toBe('rt-1');
      http.verify();
    });

    it('reads the callback parameters from the browser URL when none are passed', async () => {
      const { service, http } = setup();
      const { state } = await beginLogin(service);
      location.search = `?code=abc&state=${encodeURIComponent(state)}`;

      const returnUrl = service.handleCallback();
      http
        .expectOne(TEST_TOKEN_ENDPOINT)
        .flush({ access_token: accessTokenFor(60), expires_in: 60 });

      expect(await returnUrl).toBe('/tasks/42');
      http.verify();
    });

    it('rejects a provider error before touching the token endpoint', async () => {
      const { service, http } = setup();
      await beginLogin(service);

      await expectAsync(
        service.handleCallback({ error: 'access_denied', error_description: 'User said no.' }),
      ).toBeRejectedWithError(AuthError, 'User said no.');
      expect(service.isAuthenticated()).toBeFalse();
      http.verify();
    });

    it('falls back to a readable message when the provider sends no description', async () => {
      const { service, http } = setup();

      await expectAsync(service.handleCallback({ error: 'server_error' })).toBeRejectedWithError(
        AuthError,
        /server_error/,
      );
      http.verify();
    });

    it('rejects a callback with no code', async () => {
      const { service, http } = setup();
      await beginLogin(service);

      await expectAsync(service.handleCallback({ state: 'x' })).toBeRejectedWithError(
        AuthError,
        /did not include an authorization code/,
      );
      http.verify();
    });

    it('rejects a callback with no pending transaction (stale or replayed link)', async () => {
      const { service, http } = setup();

      await expectAsync(service.handleCallback({ code: 'c', state: 's' })).toBeRejectedWithError(
        AuthError,
        /no longer valid/,
      );
      http.verify();
    });

    it('rejects a state mismatch without exchanging the code', async () => {
      const { service, http } = setup();
      await beginLogin(service);

      await expectAsync(
        service.handleCallback({ code: 'c', state: 'forged-state' }),
      ).toBeRejectedWithError(AuthError, /did not match this browser session/);
      expect(service.isAuthenticated()).toBeFalse();
      http.verify();
    });

    it('consumes the transaction so a replayed callback cannot be reused', async () => {
      const { service, http } = setup();
      const { state } = await beginLogin(service);

      const first = service.handleCallback({ code: 'c', state });
      http
        .expectOne(TEST_TOKEN_ENDPOINT)
        .flush({ access_token: accessTokenFor(60), expires_in: 60 });
      await first;

      await expectAsync(service.handleCallback({ code: 'c', state })).toBeRejectedWithError(
        AuthError,
        /no longer valid/,
      );
      http.verify();
    });

    it('rejects an id_token whose nonce does not match', async () => {
      const { service, http } = setup();
      const { state } = await beginLogin(service);

      const result = service.handleCallback({ code: 'c', state });
      http.expectOne(TEST_TOKEN_ENDPOINT).flush({
        access_token: accessTokenFor(60),
        expires_in: 60,
        id_token: makeJwt({ sub: 'x', nonce: 'a-different-nonce' }),
      });

      await expectAsync(result).toBeRejectedWithError(AuthError, /failed a security check/);
      expect(service.isAuthenticated()).toBeFalse();
      http.verify();
    });

    it('accepts a response with no id_token (plain OAuth2 scope)', async () => {
      const { service, http } = setup();
      const { state } = await beginLogin(service);

      const result = service.handleCallback({ code: 'c', state });
      http
        .expectOne(TEST_TOKEN_ENDPOINT)
        .flush({ access_token: accessTokenFor(60), expires_in: 60 });

      await result;
      expect(service.isAuthenticated()).toBeTrue();
      http.verify();
    });

    it('reports a rejected token exchange', async () => {
      const { service, http } = setup();
      const { state } = await beginLogin(service);

      const result = service.handleCallback({ code: 'c', state });
      http
        .expectOne(TEST_TOKEN_ENDPOINT)
        .flush({ error: 'invalid_grant' }, { status: 400, statusText: 'Bad Request' });

      await expectAsync(result).toBeRejectedWithError(AuthError, /could not complete the sign-in/);
      expect(service.isAuthenticated()).toBeFalse();
      http.verify();
    });

    it('honours an explicit token endpoint override', async () => {
      const tokenEndpoint = 'https://id.test.example/protocol/openid-connect/token';
      const { service, http } = setup({
        oauth: {
          issuer: 'https://id.test.example/realms/hive',
          clientId: 'hive-web-test',
          redirectUri: TEST_REDIRECT_URI,
          scope: 'openid',
          tokenEndpoint,
        },
      });
      const { state } = await beginLogin(service);

      const result = service.handleCallback({ code: 'c', state });
      // The derived `${issuer}/token` default must NOT have been used.
      http.expectNone(TEST_TOKEN_ENDPOINT);
      http.expectOne(tokenEndpoint).flush({ access_token: accessTokenFor(60), expires_in: 60 });

      await result;
      expect(service.isAuthenticated()).toBeTrue();
      service.clearSession();
      http.verify();
    });
  });

  // -------------------------------------------------------------------------
  // Expiry accounting
  // -------------------------------------------------------------------------

  describe('expiry', () => {
    async function exchange(response: Record<string, unknown>): Promise<AuthService> {
      const { service, http } = setup();
      await service.login();
      const state = location.lastUrl().searchParams.get('state')!;
      const done = service.handleCallback({ code: 'c', state });
      http.expectOne(TEST_TOKEN_ENDPOINT).flush(response);
      await done;
      return service;
    }

    it('prefers expires_in', async () => {
      const before = Date.now();
      const service = await exchange({ access_token: accessTokenFor(99999), expires_in: 120 });

      expect(service.expiresAt()!).toBeGreaterThanOrEqual(before + 120_000);
      expect(service.expiresAt()!).toBeLessThan(before + 125_000);
    });

    it('falls back to the access token exp claim when expires_in is absent', async () => {
      const exp = Math.floor(Date.now() / 1000) + 300;
      const service = await exchange({ access_token: makeJwt({ sub: 's', exp }) });

      expect(service.expiresAt()).toBe(exp * 1000);
    });

    it('falls back to a short default when neither is available', async () => {
      const before = Date.now();
      const service = await exchange({ access_token: makeJwt({ sub: 's' }) });

      expect(service.expiresAt()!).toBeGreaterThanOrEqual(before + 5 * 60_000);
      expect(service.expiresAt()!).toBeLessThan(before + 5 * 60_000 + 5000);
    });

    it('reports an already-expired stored token as not authenticated', () => {
      storage.setItem(TOKENS_KEY, storedTokens(-1000));
      const { service } = setup();

      expect(service.accessToken()).toBeTruthy();
      expect(service.isAuthenticated()).toBeFalse();
    });

    it('rehydrates a valid session from storage on construction', () => {
      storage.setItem(TOKENS_KEY, storedTokens(3_600_000));
      const { service } = setup();

      expect(service.isAuthenticated()).toBeTrue();
      expect(service.principal()?.subject).toBe('auth0|alice');
      service.clearSession();
    });
  });

  // -------------------------------------------------------------------------
  // Refresh
  // -------------------------------------------------------------------------

  describe('refresh()', () => {
    it('exchanges the refresh token and keeps a non-rotated one', async () => {
      storage.setItem(TOKENS_KEY, storedTokens(3_600_000, 'rt-original'));
      const { service, http } = setup();

      const result = service.refresh();
      const req = http.expectOne(TEST_TOKEN_ENDPOINT);
      const body = new URLSearchParams(req.request.body as string);

      expect(body.get('grant_type')).toBe('refresh_token');
      expect(body.get('refresh_token')).toBe('rt-original');
      expect(body.get('client_id')).toBe('hive-web-test');

      req.flush({ access_token: 'new-access', expires_in: 600 });

      const tokens = await result;
      expect(tokens.accessToken).toBe('new-access');
      expect(tokens.refreshToken).toBe('rt-original');
      expect(service.accessToken()).toBe('new-access');
      service.clearSession();
      http.verify();
    });

    it('adopts a rotated refresh token', async () => {
      storage.setItem(TOKENS_KEY, storedTokens(3_600_000, 'rt-original'));
      const { service, http } = setup();

      const result = service.refresh();
      http
        .expectOne(TEST_TOKEN_ENDPOINT)
        .flush({ access_token: 'new-access', expires_in: 600, refresh_token: 'rt-rotated' });

      expect((await result).refreshToken).toBe('rt-rotated');
      service.clearSession();
      http.verify();
    });

    it('keeps the id_token when the refresh response omits one', async () => {
      const idToken = makeJwt({ sub: 'auth0|alice' });
      storage.setItem(TOKENS_KEY, storedTokens(3_600_000, 'rt-original', idToken));
      const { service, http } = setup();

      const result = service.refresh();
      http.expectOne(TEST_TOKEN_ENDPOINT).flush({ access_token: 'new-access', expires_in: 600 });

      // Losing it here would silently downgrade sign-out to the local-only path
      // after the first silent refresh.
      expect((await result).idToken).toBe(idToken);
      service.clearSession();
      http.verify();
    });

    it('rejects when there is no refresh token to use', async () => {
      storage.setItem(TOKENS_KEY, storedTokens(3_600_000, null));
      const { service, http } = setup();

      await expectAsync(service.refresh()).toBeRejectedWithError(AuthError, /cannot be renewed/);
      service.clearSession();
      http.verify();
    });

    it('clears the session when the provider refuses the refresh', async () => {
      storage.setItem(TOKENS_KEY, storedTokens(3_600_000));
      const { service, http } = setup();

      const result = service.refresh();
      http
        .expectOne(TEST_TOKEN_ENDPOINT)
        .flush({ error: 'invalid_grant' }, { status: 400, statusText: 'Bad Request' });

      await expectAsync(result).toBeRejected();
      expect(service.isAuthenticated()).toBeFalse();
      expect(service.accessToken()).toBeNull();
      expect(TestBed.inject(TokenStore).readTokens()).toBeNull();
      http.verify();
    });

    it('coalesces concurrent refreshes into a single token request', async () => {
      storage.setItem(TOKENS_KEY, storedTokens(3_600_000));
      const { service, http } = setup();

      const first = service.refresh();
      const second = service.refresh();

      expect(first).toBe(second);
      http.expectOne(TEST_TOKEN_ENDPOINT).flush({ access_token: 'new-access', expires_in: 600 });

      expect((await first).accessToken).toBe('new-access');
      expect((await second).accessToken).toBe('new-access');
      service.clearSession();
      http.verify();
    });
  });

  // -------------------------------------------------------------------------
  // Silent refresh scheduling
  // -------------------------------------------------------------------------

  describe('silent refresh scheduling', () => {
    beforeEach(() => {
      jasmine.clock().install();
      jasmine.clock().mockDate(new Date(1_700_000_000_000));
    });

    afterEach(() => jasmine.clock().uninstall());

    it('refreshes one skew window before the token expires', async () => {
      const lifetime = 10 * 60_000;
      storage.setItem(TOKENS_KEY, storedTokens(lifetime));
      const { service, http } = setup();

      // Nothing happens until the skew window opens.
      jasmine.clock().tick(lifetime - REFRESH_SKEW_MS - 1000);
      http.expectNone(TEST_TOKEN_ENDPOINT);

      jasmine.clock().tick(1000);
      const req = http.expectOne(TEST_TOKEN_ENDPOINT);
      expect(new URLSearchParams(req.request.body as string).get('grant_type')).toBe(
        'refresh_token',
      );
      req.flush({ access_token: 'refreshed', expires_in: 600 });
      await flushMicrotasks();

      expect(service.accessToken()).toBe('refreshed');
      service.clearSession();
      http.verify();
    });

    it('ends the session at expiry when there is no refresh token', async () => {
      const lifetime = 10 * 60_000;
      storage.setItem(TOKENS_KEY, storedTokens(lifetime, null));
      const { service, http } = setup();

      expect(service.isAuthenticated()).toBeTrue();

      jasmine.clock().tick(lifetime);
      await flushMicrotasks();

      expect(service.isAuthenticated()).toBeFalse();
      expect(service.accessToken()).toBeNull();
      http.verify();
    });

    it('swallows a failed scheduled refresh instead of raising an unhandled rejection', async () => {
      const lifetime = 10 * 60_000;
      storage.setItem(TOKENS_KEY, storedTokens(lifetime));
      const { service, http } = setup();

      jasmine.clock().tick(lifetime - REFRESH_SKEW_MS);
      http
        .expectOne(TEST_TOKEN_ENDPOINT)
        .flush({ error: 'invalid_grant' }, { status: 400, statusText: 'Bad Request' });
      await flushMicrotasks();

      expect(service.isAuthenticated()).toBeFalse();
      http.verify();
    });

    it('cancels the timer once the session is cleared', async () => {
      storage.setItem(TOKENS_KEY, storedTokens(10 * 60_000));
      const { service, http } = setup();

      service.clearSession();
      jasmine.clock().tick(60 * 60_000);
      await flushMicrotasks();

      // A surviving timer would fire a refresh here; expectNone proves it did not.
      http.expectNone(TEST_TOKEN_ENDPOINT);
      expect(service.isAuthenticated()).toBeFalse();
    });
  });

  // -------------------------------------------------------------------------
  // Sign-out and 401 handling
  // -------------------------------------------------------------------------

  describe('logout() and onUnauthorized()', () => {
    it('logout clears everything and returns to the login route without an id_token', () => {
      storage.setItem(TOKENS_KEY, storedTokens(3_600_000));
      const { service, router } = setup();
      const navigate = spyOn(router, 'navigateByUrl').and.resolveTo(true);

      service.logout();

      expect(service.isAuthenticated()).toBeFalse();
      expect(service.principal()).toBeNull();
      expect(TestBed.inject(TokenStore).readTokens()).toBeNull();
      expect(navigate).toHaveBeenCalledWith('/login');
    });

    it('logout hands the browser to the provider end-session endpoint', () => {
      const idToken = makeJwt({ sub: 'auth0|alice' });
      storage.setItem(TOKENS_KEY, storedTokens(3_600_000, 'rt', idToken));
      const { service, router } = setup();
      const navigate = spyOn(router, 'navigateByUrl').and.resolveTo(true);

      service.logout();

      // Local state goes first and unconditionally, so a provider that refuses
      // the redirect still leaves no usable session behind.
      expect(service.isAuthenticated()).toBeFalse();
      expect(TestBed.inject(TokenStore).readTokens()).toBeNull();
      expect(storage.getItem(TOKENS_KEY)).toBeNull();

      // The redirect is what actually ends the SSO session; an in-app
      // navigation here would be the hive-bra bug.
      expect(navigate).not.toHaveBeenCalled();
      const url = location.lastUrl();
      expect(`${url.origin}${url.pathname}`).toBe(TEST_END_SESSION);
      expect(url.searchParams.get('id_token_hint')).toBe(idToken);
      expect(url.searchParams.get('client_id')).toBe('hive-web-test');
      expect(url.searchParams.get('post_logout_redirect_uri')).toBe('http://localhost:9876/login');
    });

    it('logout stays local for a dev session, which has no provider session', () => {
      const { service, router } = setup({ devAuth: true });
      const navigate = spyOn(router, 'navigateByUrl').and.resolveTo(true);
      service.acceptDevToken(accessTokenFor(3600));

      service.logout();

      expect(service.isAuthenticated()).toBeFalse();
      expect(location.assigned).toEqual([]);
      expect(navigate).toHaveBeenCalledWith('/login');
    });

    it('onUnauthorized clears the session and preserves where the user was', () => {
      storage.setItem(TOKENS_KEY, storedTokens(3_600_000));
      const { service, router } = setup();
      const navigate = spyOn(router, 'navigate').and.resolveTo(true);

      service.onUnauthorized('/tasks/42');

      expect(service.isAuthenticated()).toBeFalse();
      expect(navigate).toHaveBeenCalledWith(['/login'], {
        queryParams: { returnUrl: '/tasks/42' },
      });
    });

    it('onUnauthorized omits returnUrl for the root route', () => {
      const { service, router } = setup();
      const navigate = spyOn(router, 'navigate').and.resolveTo(true);

      service.onUnauthorized('/');

      expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: {} });
    });

    it('onUnauthorized refuses an off-site returnUrl', () => {
      const { service, router } = setup();
      const navigate = spyOn(router, 'navigate').and.resolveTo(true);

      service.onUnauthorized('https://evil.example.com');

      expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: {} });
    });
  });

  // -------------------------------------------------------------------------
  // Dev-only entry point
  // -------------------------------------------------------------------------

  describe('acceptDevToken()', () => {
    it('refuses to adopt a dev token when devAuth is off', () => {
      const { service } = setup({ devAuth: false });

      expect(() => service.acceptDevToken(accessTokenFor(3600))).toThrowMatching(
        (error: unknown) => error instanceof AuthError && error.reason === 'dev_auth_disabled',
      );
      expect(service.isAuthenticated()).toBeFalse();
    });

    it('adopts a dev token and marks the session as a dev session', () => {
      const { service } = setup({ devAuth: true });
      const token = accessTokenFor(3600);

      const tokens = service.acceptDevToken(token);

      expect(tokens.dev).toBeTrue();
      expect(tokens.refreshToken).toBeNull();
      expect(service.isAuthenticated()).toBeTrue();
      expect(service.isDevSession()).toBeTrue();
      expect(service.accessToken()).toBe(token);
      expect(service.principal()?.email).toBe('alice@hive.test');
      service.clearSession();
    });

    it('falls back to a short lifetime for a dev token with no exp claim', () => {
      const { service } = setup({ devAuth: true });
      const before = Date.now();

      const tokens = service.acceptDevToken(makeJwt({ sub: 'dev' }));

      expect(tokens.expiresAt).toBeGreaterThanOrEqual(before + 5 * 60_000);
      service.clearSession();
    });
  });

  it('exposes the configured API base URL through the shared token', () => {
    setup();
    expect(TestBed.inject(APP_CONFIG).apiBaseUrl).toBe('/api/v1');
  });
});
