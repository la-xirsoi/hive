import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, throwError } from 'rxjs';
import { APP_CONFIG } from '../../config/app-config';
import { normalizeErrors } from '../../api/api-error';
import { AuthError, DevTokenResponse, TokenSet } from '../auth-models';
import { AuthService } from '../auth-service';

/**
 * ============================================================================
 * DEVELOPMENT ONLY - NOT REACHABLE IN A PRODUCTION BUILD
 * ============================================================================
 *
 * WHY THIS EXISTS: no container runtime is available in this environment to host
 * a real identity provider, so there is nothing to run the PKCE flow against
 * locally. The backend exposes `POST {apiBaseUrl}/dev/token` which mints a JWT
 * for a given email; this service is the only client of it.
 *
 * HOW IT IS CONTAINED, in three independent layers:
 *
 *  1. `environment.devAuth` is `isDevMode()`. A production build folds
 *     `ngDevMode` to a literal `false`, so the flag is statically false.
 *  2. {@link DevAuthService.login} refuses to issue the request when the flag is
 *     false, and {@link AuthService.acceptDevToken} refuses to adopt a token
 *     when the flag is false - so neither half works without the other's guard
 *     also being bypassed.
 *  3. Nothing in the production code path imports this file. With `devAuth`
 *     statically false the only importer is the dev sign-in affordance, and the
 *     class drops out of the bundle entirely.
 *
 * This file is deliberately quarantined in its own `dev/` directory so that
 * "does production import anything under core/auth/dev?" is a one-line grep.
 */
@Injectable({ providedIn: 'root' })
export class DevAuthService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(APP_CONFIG);
  private readonly auth = inject(AuthService);

  /** Whether the dev sign-in affordance should be rendered at all. */
  get enabled(): boolean {
    return this.config.devAuth;
  }

  /**
   * Exchanges an email address for a backend-minted access token and adopts it
   * as the current session.
   *
   * Errors on the wire are normalized to `ApiError` like any other API call, so
   * a dev login form can render the backend's message with no special handling.
   */
  login(email: string): Observable<TokenSet> {
    if (!this.enabled) {
      return throwError(
        () =>
          new AuthError('dev_auth_disabled', 'Development sign-in is not available in this build.'),
      );
    }
    const url = `${this.config.apiBaseUrl.replace(/\/+$/, '')}/dev/token`;
    return this.http.post<DevTokenResponse>(url, { email }).pipe(
      normalizeErrors(),
      map((response) => this.auth.acceptDevToken(response.accessToken)),
    );
  }
}
