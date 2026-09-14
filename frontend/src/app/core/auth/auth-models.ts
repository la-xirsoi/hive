/** Data shapes exchanged with the authorization server and held in storage. */

/** Raw RFC 6749 token endpoint response (snake_case, as it arrives on the wire). */
export interface TokenEndpointResponse {
  readonly access_token: string;
  readonly token_type?: string;
  /** Lifetime in seconds from the moment of issue. */
  readonly expires_in?: number;
  readonly refresh_token?: string;
  readonly id_token?: string;
  readonly scope?: string;
}

/** The token material the app holds, normalized to camelCase and absolute time. */
export interface TokenSet {
  readonly accessToken: string;
  readonly refreshToken: string | null;
  readonly idToken: string | null;
  readonly tokenType: string;
  /** Absolute expiry as epoch milliseconds. */
  readonly expiresAt: number;
  readonly scope: string | null;
  /** True when this session came from the dev token endpoint, never from an IdP. */
  readonly dev?: boolean;
}

/**
 * The in-flight authorization request, persisted across the redirect to the
 * identity provider and read back when the browser returns to the callback URL.
 */
export interface AuthTransaction {
  /** CSRF guard: must come back unchanged on the callback query string. */
  readonly state: string;
  /** Replay guard: must appear as the `nonce` claim of the returned id_token. */
  readonly nonce: string;
  /** The PKCE secret, never sent to the authorize endpoint. */
  readonly codeVerifier: string;
  /** Where to send the user once the exchange succeeds. */
  readonly returnUrl: string;
  readonly createdAt: number;
}

/** Query parameters the identity provider appends to the redirect URI. */
export interface AuthCallbackParams {
  readonly code?: string | null;
  readonly state?: string | null;
  /** OAuth error code, present when the provider refused the request. */
  readonly error?: string | null;
  readonly error_description?: string | null;
}

/** Response body of the development-only `POST {apiBaseUrl}/dev/token`. */
export interface DevTokenResponse {
  readonly accessToken: string;
}

/** Anything that goes wrong during sign-in, surfaced to the callback screen. */
export class AuthError extends Error {
  /** Machine-readable reason, useful for branching in tests and telemetry. */
  readonly reason: AuthFailureReason;

  constructor(reason: AuthFailureReason, message: string) {
    super(message);
    this.name = 'AuthError';
    this.reason = reason;
  }
}

export type AuthFailureReason =
  /** The provider itself returned `?error=...`. */
  | 'provider_error'
  /** The callback had no `code`. */
  | 'missing_code'
  /** No pending transaction in storage - a stale or replayed callback URL. */
  | 'no_transaction'
  /** `state` did not match the pending transaction: possible CSRF. */
  | 'state_mismatch'
  /** The id_token's `nonce` claim did not match: possible replay. */
  | 'nonce_mismatch'
  /** The token endpoint rejected the exchange or the refresh. */
  | 'token_exchange_failed'
  /** A refresh was attempted with no refresh token available. */
  | 'no_refresh_token'
  /** A dev-only code path was reached in a build where dev auth is off. */
  | 'dev_auth_disabled';
