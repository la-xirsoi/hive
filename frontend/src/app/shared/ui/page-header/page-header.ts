import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Standard page title block: optional eyebrow, heading, optional subtitle, and
 * an actions slot that wraps below the title on narrow screens.
 *
 * Accessibility: `level` controls the real heading element so a feature page can
 * keep a single <h1> and nest <h2> sections without faking heading semantics
 * with font sizes. The gold rule under the title is a decorative fill.
 *
 * Slots: [hive-page-header-actions], [hive-page-header-meta]
 */
@Component({
  selector: 'hive-page-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="hive-page-header">
      <div class="hive-page-header__text">
        @if (eyebrow(); as eyebrowText) {
          <p class="hive-page-header__eyebrow">{{ eyebrowText }}</p>
        }

        @switch (level()) {
          @case (1) {
            <h1 class="hive-page-header__title">{{ heading() }}</h1>
          }
          @case (2) {
            <h2 class="hive-page-header__title">{{ heading() }}</h2>
          }
          @default {
            <h3 class="hive-page-header__title">{{ heading() }}</h3>
          }
        }

        <span class="hive-page-header__rule" aria-hidden="true"></span>

        @if (subtitle(); as subtitleText) {
          <p class="hive-page-header__subtitle">{{ subtitleText }}</p>
        }

        <div class="hive-page-header__meta"><ng-content select="[hive-page-header-meta]" /></div>
      </div>

      <div class="hive-page-header__actions">
        <ng-content select="[hive-page-header-actions]" />
      </div>
    </header>
  `,
  styles: `
    :host {
      display: block;
    }

    .hive-page-header {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--hive-space-4);
      padding-bottom: var(--hive-space-5);
      margin-bottom: var(--hive-space-6);
      border-bottom: 1px solid var(--hive-color-border-subtle);
    }

    .hive-page-header__text {
      min-width: 0;
      flex: 1 1 320px;
    }

    .hive-page-header__eyebrow {
      margin: 0 0 var(--hive-space-1);
      font-size: var(--hive-font-size-xs);
      font-weight: var(--hive-font-weight-semibold);
      letter-spacing: var(--hive-letter-spacing-caps);
      text-transform: uppercase;
      color: var(--hive-color-text-secondary); /* 9.22:1 on #F5F5F5 */
    }

    .hive-page-header__title {
      margin: 0;
      font-size: var(--hive-font-size-3xl);
      font-weight: var(--hive-font-weight-bold);
      line-height: var(--hive-line-height-tight);
      letter-spacing: var(--hive-letter-spacing-tight);
      color: var(--hive-color-text);
    }

    /* Gold as a decorative fill under the title - never as text colour. */
    .hive-page-header__rule {
      display: block;
      width: var(--hive-space-10);
      height: 3px;
      margin-top: var(--hive-space-3);
      background-color: var(--hive-gold);
      border-radius: var(--hive-radius-pill);
    }

    .hive-page-header__subtitle {
      margin: var(--hive-space-3) 0 0;
      max-width: 68ch;
      font-size: var(--hive-font-size-md);
      color: var(--hive-color-text-secondary);
    }

    .hive-page-header__meta:empty {
      display: none;
    }

    .hive-page-header__meta {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--hive-space-3);
      margin-top: var(--hive-space-3);
    }

    .hive-page-header__actions:empty {
      display: none;
    }

    .hive-page-header__actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--hive-space-3);
    }

    @media (max-width: 640px) {
      .hive-page-header__title {
        font-size: var(--hive-font-size-2xl);
      }
    }
  `,
})
export class HivePageHeader {
  readonly heading = input.required<string>();
  readonly eyebrow = input<string | null>(null);
  readonly subtitle = input<string | null>(null);
  /** Real heading level: 1 for a page title, 2-3 for a nested section. */
  readonly level = input<1 | 2 | 3>(1);
}
