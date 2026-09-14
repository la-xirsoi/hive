import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom, of, throwError } from 'rxjs';
import { ApiError, ApiErrorBody, isApiError, normalizeApiError, normalizeErrors } from './api-error';

/**
 * The normalizer is the only place that reads `HttpErrorResponse`, so it carries
 * a case per status the contract defines plus the malformed-response paths that
 * a proxy, a captive portal or an offline browser can produce.
 */

function httpError(status: number, body: unknown, url = '/api/v1/tasks/42'): HttpErrorResponse {
  return new HttpErrorResponse({ status, statusText: 'x', error: body, url });
}

function contractBody(status: number, error: string, message: string): ApiErrorBody {
  return {
    status,
    error,
    message,
    path: '/api/v1/tasks/42',
    timestamp: '2026-09-13T18:30:00Z',
  };
}

describe('normalizeApiError', () => {
  it('maps a 400 and preserves fieldErrors', () => {
    const normalized = normalizeApiError(
      httpError(400, {
        ...contractBody(400, 'BAD_REQUEST', 'Validation failed for 2 fields.'),
        fieldErrors: [
          { field: 'email', message: 'must be a valid email address' },
          { field: 'name', message: 'must not be blank' },
        ],
      }),
    );

    expect(normalized.status).toBe(400);
    expect(normalized.error).toBe('BAD_REQUEST');
    expect(normalized.message).toBe('Validation failed for 2 fields.');
    expect(normalized.fieldErrors?.length).toBe(2);
    expect(normalized.hasFieldErrors).toBeTrue();
    expect(normalized.fieldError('email')).toBe('must be a valid email address');
    expect(normalized.fieldError('missing')).toBeUndefined();
    expect(normalized.path).toBe('/api/v1/tasks/42');
    expect(normalized.timestamp).toBe('2026-09-13T18:30:00Z');
  });

  it('drops malformed fieldErrors entries rather than surfacing junk', () => {
    const normalized = normalizeApiError(
      httpError(400, {
        ...contractBody(400, 'BAD_REQUEST', 'Bad.'),
        fieldErrors: [{ field: 'email' }, 'nope', { field: 'name', message: 'required' }],
      }),
    );

    expect(normalized.fieldErrors).toEqual([{ field: 'name', message: 'required' }]);
  });

  it('maps a 401', () => {
    const normalized = normalizeApiError(
      httpError(401, contractBody(401, 'UNAUTHORIZED', 'The access token has expired.')),
    );

    expect(normalized.status).toBe(401);
    expect(normalized.error).toBe('UNAUTHORIZED');
    expect(normalized.message).toBe('The access token has expired.');
    expect(normalized.fieldErrors).toBeUndefined();
  });

  it('maps a 403', () => {
    const normalized = normalizeApiError(
      httpError(403, contractBody(403, 'FORBIDDEN', 'Only the Team Lead may assign tasks.')),
    );

    expect(normalized.status).toBe(403);
    expect(normalized.error).toBe('FORBIDDEN');
    expect(normalized.message).toBe('Only the Team Lead may assign tasks.');
  });

  it('maps a 404', () => {
    const normalized = normalizeApiError(
      httpError(404, contractBody(404, 'NOT_FOUND', 'Task 42 was not found.')),
    );

    expect(normalized.status).toBe(404);
    expect(normalized.error).toBe('NOT_FOUND');
    expect(normalized.message).toBe('Task 42 was not found.');
  });

  it('maps a 405', () => {
    const normalized = normalizeApiError(
      httpError(405, contractBody(405, 'METHOD_NOT_ALLOWED', 'Tasks cannot be deleted.')),
    );

    expect(normalized.status).toBe(405);
    expect(normalized.error).toBe('METHOD_NOT_ALLOWED');
  });

  it('maps a 409 and keeps the server message verbatim', () => {
    const normalized = normalizeApiError(
      httpError(
        409,
        contractBody(409, 'CONFLICT', 'Task 42 is Completed and can no longer be edited.'),
      ),
    );

    expect(normalized.status).toBe(409);
    expect(normalized.error).toBe('CONFLICT');
    expect(normalized.message).toBe('Task 42 is Completed and can no longer be edited.');
  });

  it('maps a 500', () => {
    const normalized = normalizeApiError(
      httpError(500, contractBody(500, 'INTERNAL_ERROR', 'Something went wrong.')),
    );

    expect(normalized.status).toBe(500);
    expect(normalized.error).toBe('INTERNAL_ERROR');
    expect(normalized.message).toBe('Something went wrong.');
  });

  it('falls back to a friendly message when a 500 body is not the contract shape', () => {
    const normalized = normalizeApiError(httpError(500, '<html>Gateway blew up</html>'));

    expect(normalized.status).toBe(500);
    expect(normalized.error).toBe('INTERNAL_ERROR');
    expect(normalized.message).toBe('Something went wrong on the server. Please try again.');
  });

  it('parses a contract body that arrived as an unparsed JSON string', () => {
    const normalized = normalizeApiError(
      httpError(409, JSON.stringify(contractBody(409, 'CONFLICT', 'Already Completed.'))),
    );

    expect(normalized.error).toBe('CONFLICT');
    expect(normalized.message).toBe('Already Completed.');
  });

  it('reports a transport failure as NETWORK_ERROR', () => {
    const normalized = normalizeApiError(httpError(0, new ProgressEvent('error')));

    expect(normalized.status).toBe(0);
    expect(normalized.error).toBe('NETWORK_ERROR');
    expect(normalized.message).toContain('Could not reach the Hive server');
  });

  it('reports an undocumented status as UNKNOWN_ERROR', () => {
    const normalized = normalizeApiError(httpError(418, null));

    expect(normalized.status).toBe(418);
    expect(normalized.error).toBe('UNKNOWN_ERROR');
    expect(normalized.message).toBe('The request failed (HTTP 418).');
  });

  it('ignores an unrecognised error code in the body and derives it from the status', () => {
    const normalized = normalizeApiError(httpError(403, { error: 'TEAPOT', message: 'Nope.' }));

    expect(normalized.error).toBe('FORBIDDEN');
    expect(normalized.message).toBe('Nope.');
  });

  it('passes an existing ApiError through unchanged', () => {
    const original = new ApiError({ status: 409, error: 'CONFLICT', message: 'Already done.' });

    expect(normalizeApiError(original)).toBe(original);
  });

  it('wraps a non-HTTP failure', () => {
    const normalized = normalizeApiError(new TypeError('boom'));

    expect(normalized.status).toBe(0);
    expect(normalized.error).toBe('UNKNOWN_ERROR');
    expect(normalized.message).toBe('boom');
  });

  it('wraps a thrown non-Error value', () => {
    expect(normalizeApiError('nope').message).toBe('An unexpected error occurred.');
  });

  it('is an Error subclass so it survives instanceof and global handlers', () => {
    const normalized = normalizeApiError(httpError(404, null));

    expect(normalized instanceof Error).toBeTrue();
    expect(isApiError(normalized)).toBeTrue();
    expect(isApiError(new Error('x'))).toBeFalse();
    expect(normalized.name).toBe('ApiError');
  });
});

describe('normalizeErrors operator', () => {
  it('leaves a successful stream untouched', async () => {
    await expectAsync(firstValueFrom(of(1).pipe(normalizeErrors()))).toBeResolvedTo(1);
  });

  it('converts a failing stream to an ApiError', async () => {
    const failing = throwError(() => httpError(409, contractBody(409, 'CONFLICT', 'Locked.'))).pipe(
      normalizeErrors<never>(),
    );

    await expectAsync(firstValueFrom(failing)).toBeRejectedWithError(ApiError, 'Locked.');
  });
});
