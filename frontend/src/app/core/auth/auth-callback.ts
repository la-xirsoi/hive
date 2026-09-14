import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthError } from './auth-models';
import { AuthService, LOGIN_ROUTE } from './auth-service';

/**
 * Landing page for the OAuth `redirect_uri`.
 *
 * It exists only to finish the code exchange and get out of the way, so its
 * markup is intentionally unstyled and dependency-free: it must render before
 * any feature or design-system code is reachable, and it is on screen for a few
 * hundred milliseconds at most. Styling for it, if it is ever wanted, belongs to
 * whoever owns the shell.
 */
@Component({
  selector: 'app-auth-callback',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (error()) {
      <section role="alert">
        <h1>Sign-in failed</h1>
        <p>{{ error() }}</p>
        <a [routerLink]="loginRoute">Back to sign in</a>
      </section>
    } @else {
      <p role="status">Completing sign-in&hellip;</p>
    }
  `,
  imports: [RouterLink],
})
export class AuthCallback {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly loginRoute = LOGIN_ROUTE;
  protected readonly error = signal<string | null>(null);

  constructor() {
    void this.complete();
  }

  private async complete(): Promise<void> {
    const query = this.route.snapshot.queryParamMap;
    try {
      const returnUrl = await this.auth.handleCallback({
        code: query.get('code'),
        state: query.get('state'),
        error: query.get('error'),
        error_description: query.get('error_description'),
      });
      await this.router.navigateByUrl(returnUrl);
    } catch (cause: unknown) {
      this.error.set(
        cause instanceof AuthError || cause instanceof Error
          ? cause.message
          : 'Sign-in could not be completed.',
      );
    }
  }
}
