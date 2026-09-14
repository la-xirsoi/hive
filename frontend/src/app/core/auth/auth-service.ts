import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { APP_CONFIG, resolveAuthorizeEndpoint, resolveTokenEndpoint } from '../config/app-config';
import {
  AuthCallbackParams,
  AuthError,
  AuthTransaction,
  TokenEndpointResponse,
  TokenSet,
} from './auth-models';
import { BROWSER_LOCATION } from './browser';
import { decodeJwtPayload, jwtExpiryMs, principalFromToken } from './jwt';
import { CODE_CHALLENGE_METHOD, createNonce, createPkcePair, createState } from './pkce';
import { TokenStore } from './token-store';

/** Route the user is sent to when there is no usable session. */
export const LOGIN_ROUTE = '/login';

/** Path of the OAuth redirect callback route registered in `app.routes.ts`. */
export const AUTH_CALLBACK_PATH = 'auth/callback';

/** Refresh this long before expiry, so an in-flight request never races it. */
export const REFRESH_SKEW_MS = 60_000;

/** Assumed lifetime when neither `expires_in` nor a JWT `exp` is available. */
const FALLBACK_LIFETIME_MS = 5 * 60 * 1000;

const FORM_HEADERS = new HttpHeaders({ 'Content-Type': 'application/x-www-form-urlencoded' });

/**
 * Rejects absolute or protocol-relative return URLs so a crafted link cannot
 * turn a successful login into an open redirect.
 */
function safeReturnUrl(candidate: string | null | undefined): string {
  if (!candidate || !candidate.startsWith('/') || candidate.startsWith('//')) {
    return '/';
  }
  return candidate;
}

/**
 * OAuth 2.0 Authorization Code + PKCE client and the single source of truth for
 * "who is signed in".
 *
 * State is exposed as signals so templates and guards read it synchronously with
 * no subscription bookkeeping. The token set is the only writable signal;
 * everything else derives from it.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(APP_CONFIG);
  private readonly store = inject(TokenStore);
  private readonly location = inject(BROWSER_LOCATION);
  private readonly router = inject(Router);

  /** The only writable state. Rehydrated from storage on construction. */
  private readonly tokens = signal<TokenSet | null>(this.store.readTokens());

  /** Bumped when a token lapses without being refreshed, to invalidate derived state. */
  private readonly expiryTick = signal(0);

  private refreshTimer: ReturnType<typeof setTimeout> | null = null;
  private refreshInFlight: Promise<TokenSet> | null = null;

  /** The bearer token to send, or null when there is none. */
  readonly accessToken = computed(() => this.tokens()?.accessToken ?? null);

  /** The decoded principal from the access token. Display-only; never trusted. */
  readonly principal = computed(() => principalFromToken(this.tokens()?.accessToken));

  /** Absolute expiry of the current access token, epoch ms. */
  readonly expiresAt = computed(() => this.tokens()?.expiresAt ?? null);

  /** True while a non-expired access token is held. */
  readonly isAuthenticated = computed(() => {
    this.expiryTick();
    const tokens = this.tokens();
    return !!tokens && tokens.expiresAt > Date.now();
  });

  /** True when the session came from the dev token endpoint rather than an IdP. */
  readonly isDevSession = computed(() => this.tokens()?.dev === true);

  constructor() {
    this.scheduleRefresh();
    inject(DestroyRef).onDestroy(() => this.cancelRefresh());
  }

  // -------------------------------------------------------------------------
  // Sign-in
  // -------------------------------------------------------------------------

  /**
   * Starts the Authorization Code + PKCE flow by navigating the browser to the
   * identity provider. Resolves only if the redirect does not happen (in a test,
   * or a non-browser environment); in a real browser the page is gone.
   *
   * @param returnUrl in-app URL to come back to; defaults to the current route.
   */
  async login(returnUrl?: string): Promise<void> {
    const { oauth } = this.config;
    const pkce = await createPkcePair();
    const transaction: AuthTransaction = {
      state: createState(),
      nonce: createNonce(),
      codeVerifier: pkce.codeVerifier,
      returnUrl: safeReturnUrl(returnUrl ?? this.router.url),
      createdAt: Date.now(),
    };
    this.store.writeTransaction(transaction);

    const url = new URL(resolveAuthorizeEndpoint(oauth));
    url.searchParams.set('response_type', 'code');
    url.searchParams.set('client_id', oauth.clientId);
    url.searchParams.set('redirect_uri', oauth.redirectUri);
    url.searchParams.set('scope', oauth.scope);
    url.searchParams.set('state', transaction.state);
    url.searchParams.set('nonce', transaction.nonce);
    url.searchParams.set('code_challenge', pkce.codeChallenge);
    url.searchParams.set('code_challenge_method', CODE_CHALLENGE_METHOD);
    this.location.assign(url.toString());
  }

  /**
   * Completes the flow from the redirect callback.
   *
   * Validates `state` against the single-use stored transaction, exchanges the
   * code with the PKCE verifier, validates the id_token `nonce`, stores the
   * tokens and schedules the silent refresh.
   *
   * @param params callback query parameters; read from `window.location` when omitted.
   * @returns the in-app URL the user should be returned to.
   * @throws AuthError with a `reason` describing exactly which check failed.
   */
  async handleCallback(params?: AuthCallbackParams): Promise<string> {
    const callback = params ?? this.readCallbackParams();

    if (callback.error) {
      throw new AuthError(
        'provider_error',
        callback.error_description ?? `The identity provider rejected the sign-in (${callback.error}).`,
      );
    }
    if (!callback.code) {
      throw new AuthError('missing_code', 'The sign-in response did not include an authorization code.');
    }

    const transaction = this.store.takeTransaction();
    if (!transaction) {
      throw new AuthError(
        'no_transaction',
        'This sign-in link is no longer valid. Please start signing in again.',
      );
    }
    if (callback.state !== transaction.state) {
      throw new AuthError(
        'state_mismatch',
        'The sign-in response did not match this browser session and was rejected.',
      );
    }

    const response = await this.exchange(
      new HttpParams({
        fromObject: {
          grant_type: 'authorization_code',
          code: callback.code,
          redirect_uri: this.config.oauth.redirectUri,
          client_id: this.config.oauth.clientId,
          code_verifier: transaction.codeVerifier,
        },
      }),
    );

    this.assertNonce(response.id_token, transaction.nonce);
    this.adopt(this.toTokenSet(response));
    return transaction.returnUrl;
  }

  // -------------------------------------------------------------------------
  // Refresh and sign-out
  // -------------------------------------------------------------------------

  /**
   * Exchanges the refresh token for a new access token. Concurrent callers share
   * one in-flight request. On failure the session is cleared, because a refresh
   * token the provider will not honour cannot be recovered from.
   */
  refresh(): Promise<TokenSet> {
    if (this.refreshInFlight) {
      return this.refreshInFlight;
    }
    const refreshToken = this.tokens()?.refreshToken;
    if (!refreshToken) {
      return Promise.reject(
        new AuthError('no_refresh_token', 'This session cannot be renewed. Please sign in again.'),
      );
    }

    const request = this.exchange(
      new HttpParams({
        fromObject: {
          grant_type: 'refresh_token',
          refresh_token: refreshToken,
          client_id: this.config.oauth.clientId,
        },
      }),
    )
      .then((response) => {
        // Providers that do not rotate refresh tokens omit it; keep the old one.
        const tokens = this.toTokenSet(response, refreshToken);
        this.adopt(tokens);
        return tokens;
      })
      .catch((cause: unknown) => {
        this.clearSession();
        throw cause instanceof AuthError
          ? cause
          : new AuthError('token_exchange_failed', 'Your session could not be renewed.');
      })
      .finally(() => {
        this.refreshInFlight = null;
      });

    this.refreshInFlight = request;
    return request;
  }

  /** Clears the session and returns the user to the login route. */
  logout(): void {
    this.clearSession();
    void this.router.navigateByUrl(LOGIN_ROUTE);
  }

  /**
   * Invoked by the HTTP interceptor when the API answers 401. The server has
   * declared the token unusable, so the local session is discarded and the user
   * is sent to the login route with the page they were on preserved.
   */
  onUnauthorized(returnUrl?: string): void {
    const target = safeReturnUrl(returnUrl ?? this.router.url);
    this.clearSession();
    void this.router.navigate([LOGIN_ROUTE], {
      queryParams: target === '/' ? {} : { returnUrl: target },
    });
  }

  /** Drops all token state without navigating. */
  clearSession(): void {
    this.cancelRefresh();
    this.store.clear();
    this.tokens.set(null);
    this.expiryTick.update((tick) => tick + 1);
  }

  // -------------------------------------------------------------------------
  // Development-only entry point
  // -------------------------------------------------------------------------

  /**
   * DEVELOPMENT ONLY. Adopts a token minted by the backend's dev token endpoint.
   *
   * Called exclusively by `DevAuthService`. It re-checks `devAuth` itself so the
   * guarantee does not depend on that one caller: in a production build
   * `config.devAuth` is a statically false `isDevMode()`, so this method cannot
   * do anything but throw.
   */
  acceptDevToken(accessToken: string): TokenSet {
    if (!this.config.devAuth) {
      throw new AuthError(
        'dev_auth_disabled',
        'Development sign-in is not available in this build.',
      );
    }
    const expiresAt = jwtExpiryMs(decodeJwtPayload(accessToken)) ?? Date.now() + FALLBACK_LIFETIME_MS;
    const tokens: TokenSet = {
      accessToken,
      refreshToken: null,
      idToken: null,
      tokenType: 'Bearer',
      expiresAt,
      scope: null,
      dev: true,
    };
    this.adopt(tokens);
    return tokens;
  }

  // -------------------------------------------------------------------------
  // Internals
  // -------------------------------------------------------------------------

  private readCallbackParams(): AuthCallbackParams {
    const query = new URLSearchParams(this.location.search);
    return {
      code: query.get('code'),
      state: query.get('state'),
      error: query.get('error'),
      error_description: query.get('error_description'),
    };
  }

  private exchange(body: HttpParams): Promise<TokenEndpointResponse> {
    return firstValueFrom(
      this.http.post<TokenEndpointResponse>(resolveTokenEndpoint(this.config.oauth), body.toString(), {
        headers: FORM_HEADERS,
      }),
    ).catch((cause: unknown) => {
      throw cause instanceof AuthError
        ? cause
        : new AuthError(
            'token_exchange_failed',
            'The identity provider could not complete the sign-in. Please try again.',
          );
    });
  }

  private assertNonce(idToken: string | undefined, expected: string): void {
    if (!idToken) {
      // No id_token means no OIDC nonce to check (a plain OAuth2 scope). PKCE and
      // state still cover code interception and CSRF.
      return;
    }
    if (decodeJwtPayload(idToken)?.['nonce'] !== expected) {
      throw new AuthError(
        'nonce_mismatch',
        'The sign-in response failed a security check and was rejected.',
      );
    }
  }

  private toTokenSet(response: TokenEndpointResponse, fallbackRefresh?: string): TokenSet {
    const lifetimeMs =
      typeof response.expires_in === 'number' && response.expires_in > 0
        ? response.expires_in * 1000
        : null;
    const expiresAt =
      (lifetimeMs === null ? null : Date.now() + lifetimeMs) ??
      jwtExpiryMs(decodeJwtPayload(response.access_token)) ??
      Date.now() + FALLBACK_LIFETIME_MS;

    return {
      accessToken: response.access_token,
      refreshToken: response.refresh_token ?? fallbackRefresh ?? null,
      idToken: response.id_token ?? null,
      tokenType: response.token_type ?? 'Bearer',
      expiresAt,
      scope: response.scope ?? null,
    };
  }

  private adopt(tokens: TokenSet): void {
    this.store.writeTokens(tokens);
    this.tokens.set(tokens);
    this.expiryTick.update((tick) => tick + 1);
    this.scheduleRefresh();
  }

  /**
   * Arms the silent refresh.
   *
   * With a refresh token the timer fires {@link REFRESH_SKEW_MS} before expiry so
   * the new token is in hand before the old one lapses. Without one (a dev
   * session, or a provider issuing no `offline_access`) the timer instead fires
   * at expiry and ends the session, so `isAuthenticated` never reports a token
   * the API would reject.
   */
  private scheduleRefresh(): void {
    this.cancelRefresh();
    const tokens = this.tokens();
    if (!tokens) {
      return;
    }
    if (tokens.refreshToken) {
      const delay = Math.max(0, tokens.expiresAt - Date.now() - REFRESH_SKEW_MS);
      this.refreshTimer = setTimeout(() => {
        this.refreshTimer = null;
        void this.refresh().catch(() => undefined);
      }, delay);
      return;
    }
    const delay = Math.max(0, tokens.expiresAt - Date.now());
    this.refreshTimer = setTimeout(() => {
      this.refreshTimer = null;
      this.clearSession();
    }, delay);
  }

  private cancelRefresh(): void {
    if (this.refreshTimer !== null) {
      clearTimeout(this.refreshTimer);
      this.refreshTimer = null;
    }
  }
}
