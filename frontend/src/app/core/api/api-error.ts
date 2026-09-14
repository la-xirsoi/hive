import { HttpErrorResponse } from '@angular/common/http';
import { MonoTypeOperatorFunction, Observable, catchError, throwError } from 'rxjs';

/**
 * Central error normalizer.
 *
 * `docs/api-contract.md` section 1.1 freezes one error body for every non-2xx
 * response. This module turns whatever actually arrives - a contract body, an
 * HTML error page from a proxy, a network failure with no response at all -
 * into exactly one type, so components never touch `HttpErrorResponse` and
 * always have a human-readable `message` to render.
 */

/** The `error` discriminator from the contract, plus two transport-level codes. */
export type ApiErrorCode =
  | 'BAD_REQUEST'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'METHOD_NOT_ALLOWED'
  | 'CONFLICT'
  | 'INTERNAL_ERROR'
  /** No HTTP response reached us at all: offline, DNS, TLS or CORS failure. */
  | 'NETWORK_ERROR'
  /** A response arrived with a status the contract does not define. */
  | 'UNKNOWN_ERROR';

/** One field-level validation failure. Present only on 400 responses. */
export interface FieldError {
  readonly field: string;
  readonly message: string;
}

/** The wire shape of the contract's error body (section 1.1). */
export interface ApiErrorBody {
  readonly status: number;
  readonly error: string;
  readonly message: string;
  readonly path?: string;
  readonly timestamp?: string;
  readonly fieldErrors?: readonly FieldError[];
}

/**
 * The single error type every API service rejects with.
 *
 * Extends `Error` so it survives `instanceof`, keeps a stack, and renders
 * sensibly if it ever reaches a global error handler.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly error: ApiErrorCode;
  /** Present only for 400 validation failures, per the contract. */
  readonly fieldErrors?: readonly FieldError[];
  /** Request path echoed by the server, when it sent one. */
  readonly path?: string;
  /** Server timestamp (ISO-8601 UTC), when it sent one. */
  readonly timestamp?: string;

  constructor(init: {
    status: number;
    error: ApiErrorCode;
    message: string;
    fieldErrors?: readonly FieldError[];
    path?: string;
    timestamp?: string;
  }) {
    super(init.message);
    this.name = 'ApiError';
    this.status = init.status;
    this.error = init.error;
    if (init.fieldErrors) {
      this.fieldErrors = init.fieldErrors;
    }
    if (init.path !== undefined) {
      this.path = init.path;
    }
    if (init.timestamp !== undefined) {
      this.timestamp = init.timestamp;
    }
  }

  /** Convenience for form binding: the first message reported for `field`, if any. */
  fieldError(field: string): string | undefined {
    return this.fieldErrors?.find((f) => f.field === field)?.message;
  }

  /** True when the server rejected the payload with field-level detail. */
  get hasFieldErrors(): boolean {
    return !!this.fieldErrors && this.fieldErrors.length > 0;
  }
}

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError;
}

const CODE_BY_STATUS: Readonly<Record<number, ApiErrorCode>> = {
  400: 'BAD_REQUEST',
  401: 'UNAUTHORIZED',
  403: 'FORBIDDEN',
  404: 'NOT_FOUND',
  405: 'METHOD_NOT_ALLOWED',
  409: 'CONFLICT',
  500: 'INTERNAL_ERROR',
};

const MESSAGE_BY_STATUS: Readonly<Record<number, string>> = {
  400: 'The request was rejected as invalid.',
  401: 'Your session has expired. Please sign in again.',
  403: 'You do not have permission to perform this action.',
  404: 'That item does not exist, or you do not have access to it.',
  405: 'That operation is not supported.',
  409: 'That operation is not possible in the current state.',
  500: 'Something went wrong on the server. Please try again.',
};

function codeForStatus(status: number): ApiErrorCode {
  if (status === 0) {
    return 'NETWORK_ERROR';
  }
  const known = CODE_BY_STATUS[status];
  if (known) {
    return known;
  }
  return status >= 500 ? 'INTERNAL_ERROR' : 'UNKNOWN_ERROR';
}

function messageForStatus(status: number): string {
  if (status === 0) {
    return 'Could not reach the Hive server. Check your connection and try again.';
  }
  return MESSAGE_BY_STATUS[status] ?? `The request failed (HTTP ${status}).`;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Extracts the response body as an object.
 *
 * `HttpErrorResponse.error` is already parsed JSON for the default
 * `responseType: 'json'`, but is a raw string when the body failed to parse or
 * the caller asked for text, so a string body gets one parse attempt.
 */
function parseBody(raw: unknown): Record<string, unknown> | null {
  if (isRecord(raw)) {
    return raw;
  }
  if (typeof raw === 'string') {
    const trimmed = raw.trim();
    if (!trimmed.startsWith('{')) {
      return null;
    }
    try {
      const parsed: unknown = JSON.parse(trimmed);
      return isRecord(parsed) ? parsed : null;
    } catch {
      return null;
    }
  }
  return null;
}

function readFieldErrors(raw: unknown): readonly FieldError[] | undefined {
  if (!Array.isArray(raw)) {
    return undefined;
  }
  const parsed = raw
    .filter(isRecord)
    .filter((entry) => typeof entry['field'] === 'string' && typeof entry['message'] === 'string')
    .map((entry) => ({ field: entry['field'] as string, message: entry['message'] as string }));
  return parsed.length > 0 ? parsed : undefined;
}

function readString(body: Record<string, unknown> | null, key: string): string | undefined {
  const value = body?.[key];
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

const KNOWN_CODES = new Set<string>([
  'BAD_REQUEST',
  'UNAUTHORIZED',
  'FORBIDDEN',
  'NOT_FOUND',
  'METHOD_NOT_ALLOWED',
  'CONFLICT',
  'INTERNAL_ERROR',
]);

/**
 * Converts any failure from `HttpClient` into an {@link ApiError}.
 *
 * Priority for every field is "believe the server first": the contract body's
 * `message`, `error` and `fieldErrors` win, and the status-derived fallbacks are
 * used only when the server did not supply them. That is what keeps the server's
 * descriptive messages ("Task 42 is Completed and can no longer be edited.")
 * in front of the user instead of a generic "Conflict".
 */
export function normalizeApiError(cause: unknown): ApiError {
  if (isApiError(cause)) {
    return cause;
  }

  if (!(cause instanceof HttpErrorResponse)) {
    const message = cause instanceof Error ? cause.message : 'An unexpected error occurred.';
    return new ApiError({ status: 0, error: 'UNKNOWN_ERROR', message });
  }

  const body = parseBody(cause.error);

  // A body-reported status is only trusted when the transport has none (status 0
  // with a parsed body cannot really happen, but the contract body carries it).
  const bodyStatus = typeof body?.['status'] === 'number' ? (body['status'] as number) : undefined;
  const status = cause.status || bodyStatus || 0;

  const bodyCode = readString(body, 'error');
  const error: ApiErrorCode =
    bodyCode && KNOWN_CODES.has(bodyCode) ? (bodyCode as ApiErrorCode) : codeForStatus(status);

  const message = readString(body, 'message') ?? messageForStatus(status);

  return new ApiError({
    status,
    error,
    message,
    fieldErrors: readFieldErrors(body?.['fieldErrors']),
    path: readString(body, 'path'),
    timestamp: readString(body, 'timestamp'),
  });
}

/**
 * RxJS operator applied by every API service method so callers only ever see an
 * {@link ApiError} on the error channel.
 */
export function normalizeErrors<T>(): MonoTypeOperatorFunction<T> {
  return catchError<T, Observable<never>>((cause: unknown) =>
    throwError(() => normalizeApiError(cause)),
  );
}
