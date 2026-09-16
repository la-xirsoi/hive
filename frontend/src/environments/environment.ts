import { isDevMode } from '@angular/core';
import type { AppConfig } from '../app/core/config/app-config';

/**
 * Default runtime configuration.
 *
 * WHY THIS IS NOT WIRED THROUGH `angular.json` fileReplacements:
 * the build configuration in `angular.json` has no `fileReplacements` block and
 * that file is owned by another workstream, so this module is the single
 * environment the bundler ever sees. It is injected as a plain value through the
 * `APP_CONFIG` token (see `app.config.ts`), which also makes it trivially
 * overridable in tests.
 *
 * `environment.development.ts` sits beside this file holding the dev-pinned
 * values, ready to be dropped into a `fileReplacements` entry the moment
 * `angular.json` gains one.
 *
 * DEV AUTH SAFETY: `devAuth` is `isDevMode()`, not a hand-maintained boolean.
 * A production build folds `ngDevMode` to a literal `false`, so `devAuth` is
 * statically false, every dev-only branch becomes dead code, and the dev login
 * service is tree-shaken out of the bundle entirely. There is no build flag, env
 * var or runtime toggle that can switch it back on in a production bundle.
 */

const origin = typeof window === 'undefined' ? '' : window.location.origin;

// The identity provider is served from this same origin, under /idp, by the
// gateway in front of the app (see containers/frontend/nginx.conf). Deriving
// the issuer from the origin rather than naming a host keeps the bundle free of
// any deployment's addresses -- the same build serves localhost and production
// -- and it guarantees the property that actually matters: the issuer the SPA
// asks for is the origin the browser is already on, so the `iss` claim that
// comes back is the string the backend is configured to require.
const issuer = `${origin}/idp/realms/hive`;

export const environment: AppConfig = {
  apiBaseUrl: '/api/v1',
  oauth: {
    issuer,
    clientId: 'hive-web',
    redirectUri: `${origin}/auth/callback`,
    scope: 'openid profile email offline_access',
    // Keycloak does not use the RFC 6749 conventional paths that
    // `resolveAuthorizeEndpoint` and `resolveTokenEndpoint` fall back to, so
    // both are given explicitly. Without these the SPA would call
    // `<issuer>/authorize` and get a 404 from the realm.
    authorizeEndpoint: `${issuer}/protocol/openid-connect/auth`,
    tokenEndpoint: `${issuer}/protocol/openid-connect/token`,
  },
  devAuth: isDevMode(),
};
