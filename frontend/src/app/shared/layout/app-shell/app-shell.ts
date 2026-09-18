import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

/** One primary navigation destination in the app header. */
export interface HiveNavLink {
  readonly label: string;
  /** Anything `routerLink` accepts, e.g. '/tasks' or ['/projects', id]. */
  readonly routerLink: string | readonly unknown[];
  /** Match the URL exactly (use for the dashboard/root link). */
  readonly exact?: boolean;
}

/**
 * Application chrome: skip link, near-black header with the gold accent rule,
 * primary navigation, a user slot, and the main content region.
 *
 * This component is intentionally NOT wired into the router config - it is
 * exported for the feature/app agents to place around <router-outlet>:
 *
 *   <hive-app-shell [links]="links">
 *     <hive-user-chip hive-app-shell-user name="Ada" />
 *     <router-outlet />
 *   </hive-app-shell>
 *
 * Slots: [hive-app-shell-user], [hive-app-shell-actions], (default) = main.
 *
 * Accessibility:
 *   * "Skip to main content" link is the first focusable element and becomes
 *     visible on focus (WCAG 2.4.1 bypass blocks)
 *   * landmarks: <header role=banner>, <nav aria-label>, <main>, <footer>
 *   * the active nav item is marked with `aria-current="page"` as well as the
 *     gold underline, so the state is not colour-only
 *   * nav text is #F0F0F0 on #1A1A1A (15.27:1) and the active item is
 *     #FFD700 on #1A1A1A (12.41:1) - gold is only ever used as text on the
 *     near-black chrome, never on a light surface
 *   * the header carries `hive-surface-inverse` (see styles/_tokens.scss), which
 *     re-points the colour roles at their on-ink values for everything slotted
 *     into it. Projected content - the user chip, the action buttons - is
 *     readable because of that context, not because each component was told it
 *     is sitting on dark chrome.
 */
@Component({
  selector: 'hive-app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive],
  template: `
    <a class="hive-shell__skip hive-sr-only-focusable" href="#hive-main">Skip to main content</a>

    <header class="hive-shell__header hive-surface-inverse" role="banner">
      <div class="hive-shell__bar">
        <a class="hive-shell__brand" [routerLink]="homeLink()">
          <span class="hive-shell__mark" aria-hidden="true"></span>
          <span class="hive-shell__wordmark">{{ brand() }}</span>
        </a>

        @if (links().length > 0) {
          <nav class="hive-shell__nav" [attr.aria-label]="navLabel()">
            <ul class="hive-shell__nav-list">
              @for (link of links(); track link.label) {
                <li>
                  <a
                    class="hive-shell__nav-link"
                    [routerLink]="link.routerLink"
                    routerLinkActive="hive-shell__nav-link--active"
                    [routerLinkActiveOptions]="{ exact: link.exact ?? false }"
                    ariaCurrentWhenActive="page"
                  >
                    {{ link.label }}
                  </a>
                </li>
              }
            </ul>
          </nav>
        }

        <div class="hive-shell__aside">
          <ng-content select="[hive-app-shell-actions]" />
          <ng-content select="[hive-app-shell-user]" />
        </div>
      </div>
    </header>

    <main class="hive-shell__main" id="hive-main" tabindex="-1">
      <div class="hive-shell__content"><ng-content /></div>
    </main>

    <footer class="hive-shell__footer">
      <div class="hive-shell__content hive-shell__footer-inner">
        <ng-content select="[hive-app-shell-footer]" />
      </div>
    </footer>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      min-height: 100vh;
      background-color: var(--hive-color-bg);
    }

    .hive-shell__skip {
      position: absolute;
      top: var(--hive-space-2);
      left: var(--hive-space-2);
      z-index: var(--hive-z-toast);
      padding: var(--hive-space-2) var(--hive-space-4);
      font-size: var(--hive-font-size-sm);
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-ink); /* 12.41:1 on gold */
      background-color: var(--hive-gold);
      border: 2px solid var(--hive-ink);
      border-radius: var(--hive-radius-sm);
      text-decoration: none;
    }

    /* ---- header ---- */
    /* Painted --hive-color-surface-inverse; the matching .hive-surface-inverse
       class on the same element re-points the text/surface roles for the
       subtree, so slotted controls inherit on-ink colours. */
    .hive-shell__header {
      position: sticky;
      top: 0;
      z-index: var(--hive-z-header);
      background-color: var(--hive-color-surface-inverse);
      /* The brand accent: a gold rule under the whole header. */
      border-bottom: 3px solid var(--hive-gold);
    }

    .hive-shell__bar {
      display: flex;
      align-items: center;
      gap: var(--hive-space-6);
      width: 100%;
      max-width: var(--hive-layout-max-width);
      min-height: var(--hive-header-height);
      margin-inline: auto;
      padding: var(--hive-space-2) var(--hive-layout-gutter);
    }

    .hive-shell__brand {
      display: inline-flex;
      align-items: center;
      gap: var(--hive-space-2);
      flex: 0 0 auto;
      color: var(--hive-color-text-inverse); /* 17.40:1 on #1A1A1A */
      font-size: var(--hive-font-size-xl);
      font-weight: var(--hive-font-weight-bold);
      letter-spacing: var(--hive-letter-spacing-tight);
      text-decoration: none;
      border-radius: var(--hive-radius-sm);
    }

    .hive-shell__brand:hover {
      color: var(--hive-color-text-inverse);
    }

    .hive-shell__brand:focus-visible {
      outline: none;
      box-shadow: var(--hive-focus-ring-inverse);
    }

    /* Hexagon mark - gold fill, the Hive motif. Decorative. */
    .hive-shell__mark {
      width: 22px;
      height: 24px;
      background-color: var(--hive-gold);
      clip-path: polygon(25% 3%, 75% 3%, 100% 50%, 75% 97%, 25% 97%, 0% 50%);
    }

    /* ---- nav ---- */
    .hive-shell__nav {
      flex: 1 1 auto;
      min-width: 0;
      overflow-x: auto;
      scrollbar-width: thin;
    }

    .hive-shell__nav-list {
      display: flex;
      align-items: center;
      gap: var(--hive-space-1);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .hive-shell__nav-link {
      position: relative;
      display: inline-flex;
      align-items: center;
      min-height: var(--hive-tap-target-min);
      padding: var(--hive-space-2) var(--hive-space-3);
      font-size: var(--hive-font-size-sm);
      font-weight: var(--hive-font-weight-semibold);
      white-space: nowrap;
      color: var(--hive-gray-light); /* 15.27:1 on #1A1A1A */
      text-decoration: none;
      border-radius: var(--hive-radius-sm);
      transition:
        color var(--hive-duration-fast) var(--hive-ease-standard),
        background-color var(--hive-duration-fast) var(--hive-ease-standard);
    }

    .hive-shell__nav-link:hover {
      color: var(--hive-color-text-inverse);
      background-color: var(--hive-color-surface-inverse-raised);
      text-decoration: none;
    }

    .hive-shell__nav-link:focus-visible {
      outline: none;
      box-shadow: var(--hive-focus-ring-inverse);
    }

    /* Active: gold text PLUS a gold underline PLUS aria-current - three cues. */
    .hive-shell__nav-link--active {
      color: var(--hive-gold); /* 12.41:1 on #1A1A1A */
    }

    .hive-shell__nav-link--active::after {
      content: '';
      position: absolute;
      left: var(--hive-space-3);
      right: var(--hive-space-3);
      bottom: 2px;
      height: 3px;
      background-color: var(--hive-gold);
      border-radius: var(--hive-radius-pill);
    }

    /* ---- aside (user / actions) ---- */
    .hive-shell__aside {
      display: flex;
      align-items: center;
      gap: var(--hive-space-3);
      flex: 0 0 auto;
      margin-left: auto;
      color: var(--hive-color-text-inverse);
    }

    /* ---- main ---- */
    .hive-shell__main {
      flex: 1 1 auto;
      padding-block: var(--hive-space-8);
    }

    .hive-shell__main:focus {
      outline: none;
    }

    .hive-shell__content {
      width: 100%;
      max-width: var(--hive-layout-max-width);
      margin-inline: auto;
      padding-inline: var(--hive-layout-gutter);
    }

    /* ---- footer ---- */
    .hive-shell__footer {
      border-top: 1px solid var(--hive-color-border-subtle);
      padding-block: var(--hive-space-5);
      font-size: var(--hive-font-size-xs);
      color: var(--hive-color-text-secondary); /* 9.22:1 on #F5F5F5 */
    }

    .hive-shell__footer-inner:empty {
      display: none;
    }

    @media (max-width: 720px) {
      .hive-shell__bar {
        flex-wrap: wrap;
        gap: var(--hive-space-3);
        padding-block: var(--hive-space-3);
      }
      .hive-shell__nav {
        order: 3;
        flex-basis: 100%;
      }
      .hive-shell__aside {
        margin-left: auto;
      }
      .hive-shell__main {
        padding-block: var(--hive-space-6);
      }
    }
  `,
})
export class HiveAppShell {
  readonly brand = input('Hive');
  readonly links = input<readonly HiveNavLink[]>([]);
  readonly navLabel = input('Primary');
  readonly homeLink = input<string | readonly unknown[]>('/');
}
