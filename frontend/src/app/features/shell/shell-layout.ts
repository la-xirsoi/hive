import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { AuthService } from '../../core/auth/auth-service';
import { HiveAppShell, HiveButton, HiveUserChip, type HiveNavLink } from '../../shared';
import { CurrentUser } from '../shared/current-user';

/**
 * The authenticated layout: `HiveAppShell` wrapped around the feature outlet.
 *
 * Every authenticated route is a child of this one, so the guard runs once on
 * the parent and the chrome is created once for the whole session rather than
 * re-mounting on each navigation.
 *
 * The chip and the sign-out button need no on-dark styling of their own: the
 * shell header carries `hive-surface-inverse`, so the colour roles they read are
 * already the on-ink ones inside it.
 *
 * The displayed name prefers `GET /users/me` (the Hive record, which the user
 * controls under US-5) and falls back to the token's `name` claim while that
 * request is in flight, so the header is never blank.
 */
@Component({
  selector: 'app-shell-layout',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, HiveAppShell, HiveButton, HiveUserChip],
  template: `
    <hive-app-shell [links]="links" homeLink="/">
      <hive-user-chip hive-app-shell-user size="sm" [name]="displayName()" [secondary]="email()" />
      <hive-button
        hive-app-shell-actions
        variant="tertiary"
        size="sm"
        (clicked)="signOut()"
        data-testid="sign-out"
      >
        Sign out
      </hive-button>

      <router-outlet />

      <span hive-app-shell-footer>Hive &middot; task tracking for teams</span>
    </hive-app-shell>
  `,
  styles: `
    :host {
      display: block;
    }
  `,
})
export class ShellLayout {
  private readonly auth = inject(AuthService);
  private readonly currentUser = inject(CurrentUser);

  protected readonly links: readonly HiveNavLink[] = [
    { label: 'Dashboard', routerLink: '/', exact: true },
    { label: 'My tasks', routerLink: '/tasks', exact: true },
    { label: 'Unassigned', routerLink: '/tasks/unassigned' },
    { label: 'Teams', routerLink: '/teams' },
    { label: 'Projects', routerLink: '/projects' },
  ];

  protected readonly displayName = computed(
    () => this.currentUser.user()?.name ?? this.auth.principal()?.name ?? 'Signed in',
  );

  protected readonly email = computed(
    () => this.currentUser.user()?.email ?? this.auth.principal()?.email ?? null,
  );

  constructor() {
    // Fires `GET /users/me`, which both names the header and provisions the
    // Hive user from the token claims on first sight (US-3). Failure is not
    // fatal: the token claims still name the user, and every screen renders its
    // own error state for its own data.
    this.currentUser.load().subscribe({ error: () => undefined });
  }

  protected signOut(): void {
    this.currentUser.reset();
    this.auth.logout();
  }
}
