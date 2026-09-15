import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { APP_CONFIG } from '../config/app-config';
import { AuthService } from './auth-service';

/**
 * Resolves the document base once. Requests and `apiBaseUrl` are both commonly
 * relative (`/api/v1`), so both need the same base to be compared as URLs.
 */
function documentBase(): string {
  if (typeof document !== 'undefined' && document.baseURI) {
    return document.baseURI;
  }
  return typeof window === 'undefined' ? 'http://localhost/' : window.location.href;
}

/**
 * True only when `requestUrl` addresses the Hive API.
 *
 * The comparison is on the parsed URL, not on string prefixes: matching
 * `startsWith('/api/v1')` against a raw URL would happily attach the bearer
 * token to `https://evil.example.com/api/v1/collect`. Origin must match exactly,
 * and the path must be the API path or a segment beneath it - so `/api/v1x` does
 * not qualify either.
 */
export function isApiRequest(
  requestUrl: string,
  apiBaseUrl: string,
  base = documentBase(),
): boolean {
  let api: URL;
  let target: URL;
  try {
    api = new URL(apiBaseUrl, base);
    target = new URL(requestUrl, base);
  } catch {
    return false;
  }
  if (api.origin !== target.origin) {
    return false;
  }
  const apiPath = api.pathname.replace(/\/+$/, '');
  return target.pathname === apiPath || target.pathname.startsWith(`${apiPath}/`);
}

/**
 * Attaches `Authorization: Bearer <token>` to Hive API requests, and turns a 401
 * from the API into a cleared session plus a redirect to the login route.
 *
 * Two deliberate restrictions:
 *
 * - The header is attached **only** to requests matching `apiBaseUrl`. The token
 *   endpoint, the identity provider and any third-party host never see it.
 * - An `Authorization` header already present on the request is left alone, so a
 *   caller that sets its own credentials is never silently overridden.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const { apiBaseUrl } = inject(APP_CONFIG);
  if (!isApiRequest(req.url, apiBaseUrl)) {
    return next(req);
  }

  const auth = inject(AuthService);
  const token = auth.accessToken();
  const authorized =
    token && !req.headers.has('Authorization')
      ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : req;

  return next(authorized).pipe(
    catchError((cause: unknown) => {
      if (cause instanceof HttpErrorResponse && cause.status === 401) {
        auth.onUnauthorized();
      }
      return throwError(() => cause);
    }),
  );
};
