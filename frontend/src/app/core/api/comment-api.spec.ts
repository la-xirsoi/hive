import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { TEST_API_BASE, commentFixture, pageOf, provideTestConfig } from '../test-support.spec';
import { ApiError } from './api-error';
import { CommentApi } from './comment-api';

describe('CommentApi (contract section 6)', () => {
  let api: CommentApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideTestConfig()],
    });
    api = TestBed.inject(CommentApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GET /tasks/{taskId}/comments', async () => {
    const result = firstValueFrom(api.list(42, { page: 0, size: 100 }));
    const req = http.expectOne((r) => r.url === `${TEST_API_BASE}/tasks/42/comments`);

    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('size')).toBe('100');
    req.flush(pageOf([commentFixture], { size: 100 }));
    expect((await result).content[0]?.timestamp).toBe('2026-09-13T18:30:00Z');
  });

  it('POST /tasks/{taskId}/comments', async () => {
    const result = firstValueFrom(api.add(42, { content: 'Starting on this now.' }));
    const req = http.expectOne(`${TEST_API_BASE}/tasks/42/comments`);

    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ content: 'Starting on this now.' });
    req.flush(commentFixture, { status: 201, statusText: 'Created' });
    expect(await result).toEqual(commentFixture);
  });

  it('reports an invisible task as 404, never 403 (CM-1)', async () => {
    const result = firstValueFrom(api.add(999, { content: 'hi' }));
    http.expectOne(`${TEST_API_BASE}/tasks/999/comments`).flush(
      { status: 404, error: 'NOT_FOUND', message: 'Task 999 was not found.' },
      { status: 404, statusText: 'Not Found' },
    );

    await expectAsync(result).toBeRejectedWithError(ApiError, 'Task 999 was not found.');
  });

  it('preserves fieldErrors from a blank-content 400', async () => {
    const result = firstValueFrom(api.add(42, { content: '' }));
    http.expectOne(`${TEST_API_BASE}/tasks/42/comments`).flush(
      {
        status: 400,
        error: 'BAD_REQUEST',
        message: 'Validation failed.',
        fieldErrors: [{ field: 'content', message: 'must not be blank' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );

    await expectAsync(result).toBeRejected();
    await result.catch((error: ApiError) => {
      expect(error.fieldError('content')).toBe('must not be blank');
    });
  });
});
