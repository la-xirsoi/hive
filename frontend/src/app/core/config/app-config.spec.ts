import { isDevMode } from '@angular/core';
import {
  OAuthConfig,
  resolveAuthorizeEndpoint,
  resolveEndSessionEndpoint,
  resolveTokenEndpoint,
} from './app-config';
import { environment } from '../../../environments/environment';
import { environment as devEnvironment } from '../../../environments/environment.development';
import { routes } from '../../app.routes';
import { AUTH_CALLBACK_PATH } from '../auth/auth-service';

function oauth(overrides: Partial<OAuthConfig> = {}): OAuthConfig {
  return {
    issuer: 'https://id.example.com/realms/hive',
    clientId: 'hive-web',
    redirectUri: 'https://app.example.com/auth/callback',
    scope: 'openid',
    ...overrides,
  };
}

describe('endpoint resolution', () => {
  it('derives the conventional endpoints from the issuer', () => {
    expect(resolveAuthorizeEndpoint(oauth())).toBe('https://id.example.com/realms/hive/authorize');
    expect(resolveTokenEndpoint(oauth())).toBe('https://id.example.com/realms/hive/token');
    expect(resolveEndSessionEndpoint(oauth())).toBe('https://id.example.com/realms/hive/logout');
  });

  it('does not double up a slash when the issuer has a trailing one', () => {
    const config = oauth({ issuer: 'https://id.example.com/realms/hive/' });

    expect(resolveAuthorizeEndpoint(config)).toBe('https://id.example.com/realms/hive/authorize');
    expect(resolveTokenEndpoint(config)).toBe('https://id.example.com/realms/hive/token');
    expect(resolveEndSessionEndpoint(config)).toBe('https://id.example.com/realms/hive/logout');
  });

  it('prefers an explicit override for providers that do not follow the convention', () => {
    const config = oauth({
      authorizeEndpoint: 'https://id.example.com/protocol/openid-connect/auth',
      tokenEndpoint: 'https://id.example.com/protocol/openid-connect/token',
      endSessionEndpoint: 'https://id.example.com/protocol/openid-connect/logout',
    });

    expect(resolveAuthorizeEndpoint(config)).toBe(
      'https://id.example.com/protocol/openid-connect/auth',
    );
    expect(resolveTokenEndpoint(config)).toBe(
      'https://id.example.com/protocol/openid-connect/token',
    );
    expect(resolveEndSessionEndpoint(config)).toBe(
      'https://id.example.com/protocol/openid-connect/logout',
    );
  });
});

describe('environment files', () => {
  it('targets the frozen /api/v1 base path', () => {
    expect(environment.apiBaseUrl).toBe('/api/v1');
    expect(devEnvironment.apiBaseUrl).toBe('/api/v1');
  });

  it('points the redirect URI at the registered callback route', () => {
    expect(environment.oauth.redirectUri.endsWith('/auth/callback')).toBeTrue();
    expect(devEnvironment.oauth.redirectUri.endsWith('/auth/callback')).toBeTrue();
  });

  it('requests the scopes the flow depends on, and none the client lacks', () => {
    // `openid` is what produces the id_token the nonce check validates.
    expect(environment.oauth.scope).toContain('openid');

    // NOT `offline_access`. The refresh token silent refresh needs comes with
    // the authorization-code flow regardless; `offline_access` only asks that
    // it outlive the SSO session, which is useless to a token set held in
    // sessionStorage. It has to be granted, too -- the realm's hive-web client
    // does not hold it, and an issuer rejects the whole authorization request
    // over one unknown scope rather than ignoring it. Asserted as an absence
    // because requesting it broke sign-in entirely (hive-m50).
    expect(environment.oauth.scope).not.toContain('offline_access');
    expect(devEnvironment.oauth.scope).not.toContain('offline_access');
  });

  it('points sign-out at the realm end-session endpoint, not the conventional path', () => {
    // Local-only sign-out leaves the SSO cookie standing, so "Sign out" then
    // "Sign in" silently re-authenticates the same user (hive-bra). Keycloak
    // does not serve `<issuer>/logout`, so the override has to be explicit.
    expect(resolveEndSessionEndpoint(environment.oauth)).toBe(
      `${environment.oauth.issuer}/protocol/openid-connect/logout`,
    );
  });

  it('derives devAuth from the build mode rather than hard-coding it on', () => {
    // This is the production-safety guarantee in one assertion: `devAuth` is
    // exactly `isDevMode()`, which a production build folds to a literal false.
    expect(environment.devAuth).toBe(isDevMode());
    expect(devEnvironment.devAuth).toBeTrue();
  });
});

describe('app routes', () => {
  it('registers the OAuth callback at the path the redirect URI points to', () => {
    const callback = routes.find((route) => route.path === AUTH_CALLBACK_PATH);

    expect(callback).toBeDefined();
    expect(callback?.loadComponent).toBeDefined();
    expect(environment.oauth.redirectUri.endsWith(`/${AUTH_CALLBACK_PATH}`)).toBeTrue();
  });

  it('lazily loads the callback component', async () => {
    const callback = routes.find((route) => route.path === AUTH_CALLBACK_PATH);
    const loaded = await callback!.loadComponent!();

    expect(loaded).toBeDefined();
  });
});
