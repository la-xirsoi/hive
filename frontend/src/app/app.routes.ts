import { Routes } from '@angular/router';
import { AUTH_CALLBACK_PATH } from './core/auth/auth-service';

export const routes: Routes = [
  {
    // OAuth redirect_uri target. Must stay in sync with `oauth.redirectUri` in
    // src/environments/environment.ts, which is built from AUTH_CALLBACK_PATH.
    path: AUTH_CALLBACK_PATH,
    loadComponent: () => import('./core/auth/auth-callback').then((m) => m.AuthCallback),
  },
];
