import type { AppConfig } from '../app/core/config/app-config';

/**
 * Development-pinned configuration.
 *
 * This file is NOT currently referenced by the build: `angular.json` has no
 * `fileReplacements` mechanism and is owned by another workstream. It is kept
 * here so that adding
 *
 *   "fileReplacements": [
 *     { "replace": "src/environments/environment.ts",
 *       "with": "src/environments/environment.development.ts" }
 *   ]
 *
 * to the `development` build configuration is the only change needed to adopt it.
 *
 * Until then `environment.ts` derives the same behaviour from `isDevMode()`,
 * which is false in production builds by construction.
 */

const origin = typeof window === 'undefined' ? 'http://localhost:4200' : window.location.origin;

export const environment: AppConfig = {
  apiBaseUrl: '/api/v1',
  oauth: {
    issuer: 'http://localhost:8081/realms/hive',
    clientId: 'hive-web',
    redirectUri: `${origin}/auth/callback`,
    scope: 'openid profile email offline_access',
  },
  // No container runtime is available locally to host a real identity provider,
  // so development logs in through the backend's dev token endpoint instead.
  devAuth: true,
};
