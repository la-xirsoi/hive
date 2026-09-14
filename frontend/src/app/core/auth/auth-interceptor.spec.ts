import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { FakeLocation, accessTokenFor, provideTestConfig } from '../test-support.spec';
import { AppConfig } from '../config/app-config';
import { AUTH_STORAGE, BROWSER_LOCATION, MemoryStorage } from './browser';
import { AuthService } from './auth-service';
import { authInterceptor, isApiRequest } from './auth-interceptor';

describe('isApiRequest', () => {
  const base = 'https://hive.example.com/app/';

  it('matches the API path and paths beneath it', () => {
    expect(isApiRequest('/api/v1/users/me', '/api/v1', base)).toBeTrue();
    expect(isApiRequest('/api/v1', '/api/v1', base)).toBeTrue();
    expect(isApiRequest('https://hive.example.com/api/v1/tasks/1', '/api/v1', base)).toBeTrue();
  });

  it('does not match a different origin even when the path is identical', () => {
    expect(isApiRequest('https://evil.example.com/api/v1/collect', '/api/v1', base)).toBeFalse();
    expect(isApiRequest('http://hive.example.com/api/v1/x', '/api/v1', base)).toBeFalse();
  });

  it('does not match a path that merely shares a prefix', () => {
    expect(isApiRequest('/api/v10/users', '/api/v1', base)).toBeFalse();
    expect(isApiRequest('/api/v1x', '/api/v1', base)).toBeFalse();
    expect(isApiRequest('/apiv1/users', '/api/v1', base)).toBeFalse();
  });

  it('works with an absolute API base URL', () => {
    const api = 'https://api.hive.example.com/api/v1';
    expect(isApiRequest(`${api}/teams/mine`, api, base)).toBeTrue();
    expect(isApiRequest('https://hive.example.com/api/v1/teams', api, base)).toBeFalse();
  });

  it('tolerates a trailing slash on the configured base', () => {
    expect(isApiRequest('/api/v1/users', '/api/v1/', base)).toBeTrue();
  });

  it('returns false rather than throwing for an unparseable URL', () => {
    expect(isApiRequest('http://[bad', '/api/v1', base)).toBeFalse();
  });
});

describe('authInterceptor', () => {
  const API = '/api/v1';
  let storage: MemoryStorage;
  let http: HttpClient;
  let backend: HttpTestingController;
  let auth: AuthService;

  function setup(configOverrides: Partial<AppConfig> = {}) {
    storage = new MemoryStorage();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        provideTestConfig(configOverrides),
        { provide: BROWSER_LOCATION, useValue: new FakeLocation() },
        { provide: AUTH_STORAGE, useValue: storage },
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  }

  afterEach(() => backend.verify());

  it('attaches the bearer token to API requests', async () => {
    // `devAuth` is only a convenient way to put a token into the signal without
    // driving the whole redirect flow; the interceptor cannot tell the
    // difference between a dev token and an IdP-issued one.
    setup({ devAuth: true });
    const token = accessTokenFor(3600);
    auth.acceptDevToken(token);

    const result = firstValueFrom(http.get(`${API}/users/me`));
    const req = backend.expectOne(`${API}/users/me`);

    expect(req.request.headers.get('Authorization')).toBe(`Bearer ${token}`);
    req.flush({ id: 1 });
    await result;
    auth.clearSession();
  });

  it('sends no Authorization header when there is no token', async () => {
    setup();

    const result = firstValueFrom(http.get(`${API}/users/me`));
    const req = backend.expectOne(`${API}/users/me`);

    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
    await result;
  });

  it('never attaches the token to a third-party host', async () => {
    setup({ devAuth: true });
    auth.acceptDevToken(accessTokenFor(3600));

    const result = firstValueFrom(http.get('https://analytics.example.com/api/v1/collect'));
    const req = backend.expectOne('https://analytics.example.com/api/v1/collect');

    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
    await result;
    auth.clearSession();
  });

  it('never attaches the token to the identity provider', async () => {
    setup({ devAuth: true });
    auth.acceptDevToken(accessTokenFor(3600));

    const result = firstValueFrom(http.get('https://id.test.example/realms/hive/token'));
    const req = backend.expectOne('https://id.test.example/realms/hive/token');

    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
    await result;
    auth.clearSession();
  });

  it('leaves an explicit Authorization header alone', async () => {
    setup({ devAuth: true });
    auth.acceptDevToken(accessTokenFor(3600));

    const result = firstValueFrom(
      http.get(`${API}/users/me`, { headers: { Authorization: 'Basic abc' } }),
    );
    const req = backend.expectOne(`${API}/users/me`);

    expect(req.request.headers.get('Authorization')).toBe('Basic abc');
    req.flush({});
    await result;
    auth.clearSession();
  });

  it('clears the session and redirects on a 401 from the API', async () => {
    setup({ devAuth: true });
    auth.acceptDevToken(accessTokenFor(3600));
    const onUnauthorized = spyOn(auth, 'onUnauthorized').and.callThrough();

    const result = firstValueFrom(http.get(`${API}/tasks/42`));
    backend
      .expectOne(`${API}/tasks/42`)
      .flush({ status: 401, error: 'UNAUTHORIZED', message: 'Expired.' }, {
        status: 401,
        statusText: 'Unauthorized',
      });

    await expectAsync(result).toBeRejected();
    expect(onUnauthorized).toHaveBeenCalled();
    expect(auth.isAuthenticated()).toBeFalse();
  });

  it('does not clear the session for a 403', async () => {
    setup({ devAuth: true });
    auth.acceptDevToken(accessTokenFor(3600));

    const result = firstValueFrom(http.get(`${API}/tasks/42`));
    backend
      .expectOne(`${API}/tasks/42`)
      .flush({ message: 'Nope.' }, { status: 403, statusText: 'Forbidden' });

    await expectAsync(result).toBeRejected();
    expect(auth.isAuthenticated()).toBeTrue();
    auth.clearSession();
  });

  it('ignores a 401 from a third-party host', async () => {
    setup({ devAuth: true });
    auth.acceptDevToken(accessTokenFor(3600));
    const onUnauthorized = spyOn(auth, 'onUnauthorized');

    const result = firstValueFrom(http.get('https://elsewhere.example.com/whoami'));
    backend
      .expectOne('https://elsewhere.example.com/whoami')
      .flush(null, { status: 401, statusText: 'Unauthorized' });

    await expectAsync(result).toBeRejected();
    expect(onUnauthorized).not.toHaveBeenCalled();
    expect(auth.isAuthenticated()).toBeTrue();
    auth.clearSession();
  });

  it('re-throws the original HttpErrorResponse so the normalizer still sees it', async () => {
    setup();

    const result = firstValueFrom(http.get(`${API}/tasks/42`));
    backend
      .expectOne(`${API}/tasks/42`)
      .flush({ message: 'Gone.' }, { status: 404, statusText: 'Not Found' });

    // The interceptor deliberately re-throws the raw HttpErrorResponse: turning
    // it into an ApiError is the API services' job, one layer up.
    await expectAsync(result).toBeRejected();
    const rejection = await result.catch((cause: unknown) => cause);
    expect(rejection instanceof HttpErrorResponse).toBeTrue();
    expect((rejection as HttpErrorResponse).status).toBe(404);
  });
});
