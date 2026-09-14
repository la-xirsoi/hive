import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService, LOGIN_ROUTE } from './auth-service';

/**
 * Protects authenticated routes.
 *
 * An unauthenticated visitor is redirected to {@link LOGIN_ROUTE} with the URL
 * they asked for preserved as `returnUrl`, rather than being thrown straight at
 * the identity provider. That keeps a single place responsible for starting the
 * flow (the login screen, which calls `AuthService.login()`), and it is the only
 * way the dev sign-in path can work at all, since that one needs an email from
 * the user before it can mint anything.
 *
 * Returning a `UrlTree` rather than performing navigation keeps the guard pure
 * and synchronous, which is what makes it directly unit-testable.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isAuthenticated()) {
    return true;
  }

  return router.createUrlTree([LOGIN_ROUTE], {
    queryParams: state.url && state.url !== '/' ? { returnUrl: state.url } : {},
  });
};
