import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { TEST_API_BASE, alice, bob, pageOf, provideTestConfig } from '../test-support.spec';
import { ApiError } from './api-error';
import { UserApi } from './user-api';

describe('UserApi (contract section 2)', () => {
  let api: UserApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideTestConfig()],
    });
    api = TestBed.inject(UserApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GET /users/me', async () => {
    const result = firstValueFrom(api.getMe());
    const req = http.expectOne(`${TEST_API_BASE}/users/me`);

    expect(req.request.method).toBe('GET');
    req.flush(alice);
    expect(await result).toEqual(alice);
  });

  it('PATCH /users/me', async () => {
    const result = firstValueFrom(api.updateMe({ name: 'Alice N.' }));
    const req = http.expectOne(`${TEST_API_BASE}/users/me`);

    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ name: 'Alice N.' });
    req.flush({ ...alice, name: 'Alice N.' });
    expect((await result).name).toBe('Alice N.');
  });

  it('GET /users with a query and explicit paging', async () => {
    const result = firstValueFrom(api.search('ali', { page: 2, size: 10 }));
    const req = http.expectOne(
      (r) => r.url === `${TEST_API_BASE}/users` && r.params.get('query') === 'ali',
    );

    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('10');
    req.flush(pageOf([alice], { page: 2, size: 10, totalElements: 21, totalPages: 3 }));
    expect((await result).totalPages).toBe(3);
  });

  it('GET /users omits page and size so server defaults apply', async () => {
    const result = firstValueFrom(api.search());
    const req = http.expectOne(`${TEST_API_BASE}/users`);

    expect(req.request.params.keys()).toEqual([]);
    req.flush(pageOf([alice, bob]));
    expect((await result).content.length).toBe(2);
  });

  it('clamps an out-of-range page size to the contract 1..200 range', async () => {
    const result = firstValueFrom(api.search(undefined, { page: -5, size: 5000 }));
    const req = http.expectOne((r) => r.url === `${TEST_API_BASE}/users`);

    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('200');
    req.flush(pageOf([]));
    await result;
  });

  it('GET /users/{id}', async () => {
    const result = firstValueFrom(api.getById(2));
    const req = http.expectOne(`${TEST_API_BASE}/users/2`);

    expect(req.request.method).toBe('GET');
    req.flush(bob);
    expect(await result).toEqual(bob);
  });

  it('rejects with a normalized ApiError, not an HttpErrorResponse', async () => {
    const result = firstValueFrom(api.getById(99));
    http.expectOne(`${TEST_API_BASE}/users/99`).flush(
      { status: 404, error: 'NOT_FOUND', message: 'User 99 was not found.' },
      {
        status: 404,
        statusText: 'Not Found',
      },
    );

    await expectAsync(result).toBeRejectedWithError(ApiError, 'User 99 was not found.');
  });
});
