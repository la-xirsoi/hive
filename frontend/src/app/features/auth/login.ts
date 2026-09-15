import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { APP_CONFIG } from '../../core/config/app-config';
import { normalizeApiError } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth-service';
import { DevAuthService } from '../../core/auth/dev/dev-auth-service';
import { HiveButton, HiveCard, HiveFormField, HivePageHeader, HiveToast } from '../../shared/ui';
import { inputValue } from '../shared/dom';

/**
 * Sign-in screen. The only route besides the OAuth callback that is not behind
 * the auth guard, because it is where the guard sends people.
 *
 * The guard preserves the URL the visitor asked for as `?returnUrl=`, which is
 * handed to `AuthService.login()` so the round trip through the identity
 * provider lands them back where they were going.
 *
 * The email form below is the DEVELOPMENT-ONLY path: `environment.devAuth` is
 * `isDevMode()`, so in a production build the flag is statically false, this
 * branch is dead code and `DevAuthService` is tree-shaken out entirely. It
 * exists because no container runtime is available locally to host a real
 * identity provider (api-contract.md section 9).
 */
@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [HiveButton, HiveCard, HiveFormField, HivePageHeader, HiveToast],
  template: `
    <div class="login hive-container">
      <hive-card padding="lg" accent>
        <hive-page-header
          hive-card-header
          heading="Sign in to Hive"
          eyebrow="Welcome"
          subtitle="Hive tracks your teams, projects and the tasks assigned to you."
        />

        @if (error(); as message) {
          <hive-toast variant="error" dismissible (dismissed)="error.set(null)">
            {{ message }}
          </hive-toast>
        }

        <div class="login__primary">
          <hive-button [loading]="busy()" (clicked)="signIn()" data-testid="oauth-sign-in">
            Sign in with your organisation account
          </hive-button>
        </div>

        @if (devAuth) {
          <section class="login__dev" aria-labelledby="login-dev-heading">
            <h2 class="login__dev-heading" id="login-dev-heading">Development sign-in</h2>
            <p class="hive-text-secondary">
              This build has no identity provider available. Enter an email address to have the
              development server mint a token for it.
            </p>
            <form class="login__dev-form" (submit)="devSignIn($event)">
              <hive-form-field label="Email address" required>
                <input
                  class="hive-input"
                  type="email"
                  name="email"
                  autocomplete="username"
                  [value]="email()"
                  (input)="email.set(value($event))"
                />
              </hive-form-field>
              <hive-button type="submit" variant="secondary" [loading]="busy()">
                Continue
              </hive-button>
            </form>
          </section>
        }
      </hive-card>
    </div>
  `,
  styles: `
    :host {
      display: block;
      padding-block: var(--hive-space-10);
    }

    .login {
      max-width: 42rem;
    }

    .login__primary {
      margin-top: var(--hive-space-4);
    }

    .login__dev {
      margin-top: var(--hive-space-8);
      padding-top: var(--hive-space-6);
      border-top: 1px dashed var(--hive-color-border);
    }

    .login__dev-heading {
      margin: 0 0 var(--hive-space-2);
      font-size: var(--hive-font-size-md);
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-color-text);
    }

    .login__dev-form {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: var(--hive-space-3);
      margin-top: var(--hive-space-4);
    }

    .login__dev-form hive-form-field {
      flex: 1 1 18rem;
    }
  `,
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly devAuthService = inject(DevAuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly devAuth = inject(APP_CONFIG).devAuth;
  protected readonly email = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly value = inputValue;

  /** Where to land after a successful sign-in; the guard put it here. */
  private returnUrl(): string {
    return this.route.snapshot.queryParamMap.get('returnUrl') ?? '/';
  }

  protected signIn(): void {
    this.busy.set(true);
    this.error.set(null);
    this.auth.login(this.returnUrl()).catch((cause: unknown) => {
      this.busy.set(false);
      this.error.set(
        cause instanceof Error ? cause.message : 'Sign-in could not be started. Please try again.',
      );
    });
  }

  protected devSignIn(event: Event): void {
    event.preventDefault();
    const email = this.email().trim();
    if (!email) {
      this.error.set('Enter an email address to continue.');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.devAuthService.login(email).subscribe({
      next: () => {
        this.busy.set(false);
        void this.router.navigateByUrl(this.returnUrl());
      },
      error: (cause: unknown) => {
        this.busy.set(false);
        this.error.set(normalizeApiError(cause).message);
      },
    });
  }
}
