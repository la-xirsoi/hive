import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from '@angular/core';

export type HiveCardPadding = 'none' | 'sm' | 'md' | 'lg';
export type HiveCardElevation = 'flat' | 'raised' | 'floating';

/**
 * Surface container.
 *
 * Content projection slots:
 *   [hive-card-header] - title row, usually a heading plus actions
 *   (default)          - body
 *   [hive-card-footer] - actions / metadata
 *
 * Empty slots collapse via `:empty`, so a card with only a body renders no
 * stray padding.
 *
 * `accent` draws the brand gold as a 4px left bar - gold used as a fill, never
 * as text, which keeps the 1.40:1 gold-on-white pairing off the page.
 */
@Component({
  selector: 'hive-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div [class]="classes()">
      <div class="hive-card__header"><ng-content select="[hive-card-header]" /></div>
      <div class="hive-card__body"><ng-content /></div>
      <div class="hive-card__footer"><ng-content select="[hive-card-footer]" /></div>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    .hive-card {
      display: flex;
      flex-direction: column;
      height: 100%;
      background-color: var(--hive-color-surface);
      border: 1px solid var(--hive-color-border-subtle);
      border-radius: var(--hive-radius-lg);
      overflow: hidden;
      transition:
        box-shadow var(--hive-duration-fast) var(--hive-ease-standard),
        border-color var(--hive-duration-fast) var(--hive-ease-standard);
    }

    .hive-card--flat {
      box-shadow: var(--hive-elevation-0);
    }
    .hive-card--raised {
      box-shadow: var(--hive-elevation-1);
    }
    .hive-card--floating {
      box-shadow: var(--hive-elevation-2);
    }

    .hive-card--accent {
      border-left: 4px solid var(--hive-gold);
    }

    .hive-card--interactive {
      cursor: pointer;
    }
    .hive-card--interactive:hover {
      border-color: var(--hive-color-border);
      box-shadow: var(--hive-elevation-2);
    }
    /* The focus ring is raised by the *contained* control (link/button), so the
       card highlights along with it rather than swallowing the indicator. */
    .hive-card--interactive:focus-within {
      border-color: var(--hive-color-border-strong);
      box-shadow: var(--hive-focus-ring-tight);
    }

    .hive-card__header,
    .hive-card__body,
    .hive-card__footer {
      padding: var(--hive-card-padding, var(--hive-space-5));
    }

    .hive-card__header:empty,
    .hive-card__body:empty,
    .hive-card__footer:empty {
      display: none;
    }

    .hive-card__header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--hive-space-3);
      padding-bottom: 0;
    }

    .hive-card__body {
      flex: 1 1 auto;
      min-width: 0;
    }

    .hive-card__footer {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--hive-space-3);
      padding-top: 0;
      border-top: 0;
    }

    .hive-card--divided .hive-card__footer {
      border-top: 1px solid var(--hive-color-border-subtle);
      padding-top: var(--hive-card-padding, var(--hive-space-5));
      background-color: var(--hive-color-surface-sunken);
    }

    .hive-card--pad-none {
      --hive-card-padding: 0;
    }
    .hive-card--pad-sm {
      --hive-card-padding: var(--hive-space-3);
    }
    .hive-card--pad-md {
      --hive-card-padding: var(--hive-space-5);
    }
    .hive-card--pad-lg {
      --hive-card-padding: var(--hive-space-8);
    }
  `,
})
export class HiveCard {
  readonly padding = input<HiveCardPadding>('md');
  readonly elevation = input<HiveCardElevation>('raised');
  /** Adds hover/focus-within affordances for cards that wrap a link. */
  readonly interactive = input(false, { transform: booleanAttribute });
  /** 4px gold bar down the leading edge. */
  readonly accent = input(false, { transform: booleanAttribute });
  /** Separate the footer with a rule and a sunken background. */
  readonly divided = input(false, { transform: booleanAttribute });

  protected readonly classes = computed(() => {
    const classes = [
      'hive-card',
      `hive-card--${this.elevation()}`,
      `hive-card--pad-${this.padding()}`,
    ];
    if (this.interactive()) {
      classes.push('hive-card--interactive');
    }
    if (this.accent()) {
      classes.push('hive-card--accent');
    }
    if (this.divided()) {
      classes.push('hive-card--divided');
    }
    return classes.join(' ');
  });
}
