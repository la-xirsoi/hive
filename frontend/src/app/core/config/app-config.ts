import { InjectionToken } from '@angular/core';

/**
 * OAuth 2.0 / OpenID Connect client configuration.
 *
 * `authorizeEndpoint` and `tokenEndpoint` are optional overrides. When they are
 * omitted the endpoints are derived from `issuer` using the RFC 6749 conventional
 * paths (`/authorize` and `/token`). Identity providers that do not follow that
 * convention (Keycloak uses `/protocol/openid-connect/auth`, for example) can be
 * pointed at directly without changing any code.
 */
export interface OAuthConfig {
  /** Token issuer base URL, e.g. `https://id.example.com/realms/hive`. */
  readonly issuer: string;
  /** Public client id registered with the issuer. */
  readonly clientId: string;
  /** Absolute URL the issuer redirects back to after authorization. */
  readonly redirectUri: string;
  /**
   * Space-delimited scope string. Every scope named here must be one the client
   * is registered to hold -- an issuer rejects the entire authorization request
   * over a single unknown one. Note a refresh token does not need
   * `offline_access`: the authorization-code flow issues one regardless, and
   * `offline_access` only asks that it outlive the SSO session.
   */
  readonly scope: string;
  /** Optional explicit authorization endpoint; derived from `issuer` when absent. */
  readonly authorizeEndpoint?: string;
  /** Optional explicit token endpoint; derived from `issuer` when absent. */
  readonly tokenEndpoint?: string;
  /**
   * Optional explicit RP-initiated logout (end session) endpoint; derived from
   * `issuer` when absent. Sending the browser here is what ends the SSO session
   * at the provider -- clearing the local token set alone leaves the provider's
   * cookie intact, so the next authorization request is answered silently.
   */
  readonly endSessionEndpoint?: string;
}

/** Runtime configuration for the Hive web client. */
export interface AppConfig {
  /**
   * Base URL of the Hive REST API, including the `/api/v1` version segment.
   * The auth interceptor attaches bearer tokens to this origin + path prefix and
   * to nothing else.
   */
  readonly apiBaseUrl: string;
  readonly oauth: OAuthConfig;
  /**
   * DEVELOPMENT ONLY. Enables the local `POST {apiBaseUrl}/dev/token` login path.
   * This is false in every production build - see `src/environments/environment.ts`.
   */
  readonly devAuth: boolean;
}

/** DI token carrying the {@link AppConfig} for the running build. */
export const APP_CONFIG = new InjectionToken<AppConfig>('hive.app-config');

function trimTrailingSlash(url: string): string {
  return url.replace(/\/+$/, '');
}

/** Resolves the authorization endpoint, honouring an explicit override. */
export function resolveAuthorizeEndpoint(oauth: OAuthConfig): string {
  return oauth.authorizeEndpoint ?? `${trimTrailingSlash(oauth.issuer)}/authorize`;
}

/** Resolves the token endpoint, honouring an explicit override. */
export function resolveTokenEndpoint(oauth: OAuthConfig): string {
  return oauth.tokenEndpoint ?? `${trimTrailingSlash(oauth.issuer)}/token`;
}

/** Resolves the end-session endpoint, honouring an explicit override. */
export function resolveEndSessionEndpoint(oauth: OAuthConfig): string {
  return oauth.endSessionEndpoint ?? `${trimTrailingSlash(oauth.issuer)}/logout`;
}
