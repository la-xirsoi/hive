import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from '@angular/core';
import { HiveSpinner } from '../spinner/spinner';

export type HiveButtonVariant = 'primary' | 'secondary' | 'tertiary' | 'danger';
export type HiveButtonSize = 'sm' | 'md' | 'lg';
export type HiveButtonType = 'button' | 'submit' | 'reset';

/**
 * The Hive button.
 *
 * Variants follow BRANDING_GUIDE.md:
 *   primary   - gold fill with black text (12.41:1)
 *   secondary - transparent fill, ink border and ink text (17.40:1 on white)
 *   tertiary  - ghost, ink text, tinted hover (17.40:1 on white); on an
 *               inverse surface context it inherits the on-ink roles and
 *               becomes white text on the near-black chrome (17.40:1)
 *   danger    - #FF5252 fill with black text (5.45:1); the guide specifies
 *               "Error states: Red (#FF5252) with black text"
 *
 * Accessibility:
 *   * always a real <button>, so Enter/Space and the button role come for free
 *   * `loading` sets `aria-busy` and keeps the label visible, so the accessible
 *     name never changes underneath a screen-reader user mid-action
 *   * disabled/loading buttons are inert; `clicked` is only emitted when active
 *   * icon-only buttons require `ariaLabel`, which is asserted by the spec
 */
@Component({
  selector: 'hive-button',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [HiveSpinner],
  host: { '[class.hive-button--block]': 'block()' },
  template: `
    <button
      [class]="classes()"
      [attr.type]="type()"
      [disabled]="isInert()"
      [attr.aria-busy]="loading() ? 'true' : null"
      [attr.aria-label]="ariaLabel()"
      [attr.aria-expanded]="ariaExpanded()"
      [attr.aria-controls]="ariaControls()"
      (click)="onClick($event)"
    >
      @if (loading()) {
        <hive-spinner
          class="hive-btn__spinner"
          size="sm"
          [tone]="spinnerTone()"
          [announce]="false"
        />
      }
      <span class="hive-btn__label"><ng-content /></span>
      @if (loading()) {
        <span class="hive-sr-only">, loading</span>
      }
    </button>
  `,
  styles: `
    :host {
      display: inline-flex;
      max-width: 100%;
    }

    :host(.hive-button--block) {
      display: flex;
      width: 100%;
    }

    .hive-btn {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: var(--hive-space-2);
      width: 100%;
      font-family: inherit;
      font-weight: var(--hive-font-weight-semibold);
      line-height: var(--hive-line-height-snug);
      text-align: center;
      white-space: nowrap;
      border: 2px solid transparent;
      border-radius: var(--hive-radius-md);
      cursor: pointer;
      user-select: none;
      transition:
        background-color var(--hive-duration-fast) var(--hive-ease-standard),
        border-color var(--hive-duration-fast) var(--hive-ease-standard),
        color var(--hive-duration-fast) var(--hive-ease-standard),
        box-shadow var(--hive-duration-fast) var(--hive-ease-standard),
        transform var(--hive-duration-instant) var(--hive-ease-standard);
    }

    .hive-btn__label {
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .hive-btn:focus-visible {
      outline: none;
      box-shadow: var(--hive-focus-ring);
    }

    .hive-btn:not(:disabled):active {
      transform: translateY(1px);
    }

    .hive-btn:disabled {
      cursor: not-allowed;
    }

    /* ---- sizes (all >= 32px; md/lg clear the 44px comfortable target) ---- */
    .hive-btn--sm {
      min-height: var(--hive-control-height-sm);
      padding: 0 var(--hive-space-3);
      font-size: var(--hive-font-size-sm);
    }
    .hive-btn--md {
      min-height: var(--hive-control-height-md);
      padding: 0 var(--hive-space-4);
      font-size: var(--hive-font-size-md);
    }
    .hive-btn--lg {
      min-height: var(--hive-control-height-lg);
      padding: 0 var(--hive-space-6);
      font-size: var(--hive-font-size-lg);
    }

    /* ---- primary: gold fill, black text ---- */
    .hive-btn--primary {
      background-color: var(--hive-color-primary);
      color: var(--hive-color-on-primary);
      border-color: var(--hive-color-primary);
    }
    .hive-btn--primary:not(:disabled):hover {
      background-color: var(--hive-color-primary-hover);
      border-color: var(--hive-color-primary-hover);
    }
    .hive-btn--primary:not(:disabled):active {
      background-color: var(--hive-color-primary-active);
      border-color: var(--hive-color-primary-active);
    }
    .hive-btn--primary:disabled {
      background-color: var(--hive-color-surface-sunken);
      border-color: var(--hive-color-border-subtle);
      color: var(--hive-color-text-muted);
    }

    /* ---- secondary: outlined ---- */
    .hive-btn--secondary {
      background-color: var(--hive-color-surface);
      color: var(--hive-color-text);
      border-color: var(--hive-color-border-strong);
    }
    /* Both interaction states paint a gold fill, and the one colour that ever
       sits on gold is ink - 16.29:1 on the tint, 12.41:1 on the fill - so they
       pin their own text colour instead of inheriting the surface context. */
    .hive-btn--secondary:not(:disabled):hover {
      background-color: var(--hive-gold-tint);
      color: var(--hive-color-on-primary);
      border-color: var(--hive-color-border-strong);
    }
    .hive-btn--secondary:not(:disabled):active {
      background-color: var(--hive-gold);
      color: var(--hive-color-on-primary);
    }
    .hive-btn--secondary:disabled {
      background-color: transparent;
      border-color: var(--hive-color-border-subtle);
      color: var(--hive-color-text-muted);
    }

    /* ---- tertiary: ghost ----
       Paints no surface of its own, so it reads the surface roles rather than
       raw palette values: inside .hive-surface-inverse those resolve to the
       on-ink set and the button turns white-on-near-black by itself. */
    .hive-btn--tertiary {
      background-color: transparent;
      color: var(--hive-color-text); /* 17.40:1 on white, 17.40:1 on #1A1A1A */
      border-color: transparent;
    }
    .hive-btn--tertiary:not(:disabled):hover {
      background-color: var(--hive-color-surface-hover); /* 15.27:1 light, 14.16:1 dark */
    }
    .hive-btn--tertiary:not(:disabled):active {
      background-color: var(--hive-color-surface-pressed); /* 16.29:1 light, 10.86:1 dark */
    }
    .hive-btn--tertiary:disabled {
      color: var(--hive-color-text-muted);
    }

    /* ---- danger: red fill, black text ---- */
    .hive-btn--danger {
      background-color: var(--hive-color-danger);
      color: var(--hive-color-on-danger);
      border-color: var(--hive-color-danger);
    }
    .hive-btn--danger:not(:disabled):hover {
      background-color: var(--hive-error-hover);
      border-color: var(--hive-error-hover);
    }
    .hive-btn--danger:disabled {
      background-color: var(--hive-color-surface-sunken);
      border-color: var(--hive-color-border-subtle);
      color: var(--hive-color-text-muted);
    }

    /* ---- icon-only ---- */
    .hive-btn--icon-only {
      padding: 0;
      aspect-ratio: 1;
    }
    .hive-btn--icon-only.hive-btn--sm {
      width: var(--hive-control-height-sm);
    }
    .hive-btn--icon-only.hive-btn--md {
      width: var(--hive-control-height-md);
    }
    .hive-btn--icon-only.hive-btn--lg {
      width: var(--hive-control-height-lg);
    }

    @media (forced-colors: active) {
      .hive-btn {
        border-color: ButtonBorder;
      }
    }
  `,
})
export class HiveButton {
  readonly variant = input<HiveButtonVariant>('primary');
  readonly size = input<HiveButtonSize>('md');
  readonly type = input<HiveButtonType>('button');
  readonly disabled = input(false, { transform: booleanAttribute });
  readonly loading = input(false, { transform: booleanAttribute });
  /** Stretch to the width of the parent. */
  readonly block = input(false, { transform: booleanAttribute });
  /** Square button with no visible label - `ariaLabel` becomes mandatory. */
  readonly iconOnly = input(false, { transform: booleanAttribute });
  readonly ariaLabel = input<string | null>(null);
  readonly ariaExpanded = input<string | null>(null);
  readonly ariaControls = input<string | null>(null);

  /** Emitted only when the button is neither disabled nor loading. */
  readonly clicked = output<MouseEvent>();

  protected readonly isInert = computed(() => this.disabled() || this.loading());

  protected readonly spinnerTone = computed(() =>
    this.variant() === 'primary' || this.variant() === 'danger'
      ? ('ink' as const)
      : ('gold' as const),
  );

  protected readonly classes = computed(() => {
    const classes = ['hive-btn', `hive-btn--${this.variant()}`, `hive-btn--${this.size()}`];
    if (this.iconOnly()) {
      classes.push('hive-btn--icon-only');
    }
    if (this.block()) {
      classes.push('hive-btn--block');
    }
    return classes.join(' ');
  });

  protected onClick(event: MouseEvent): void {
    if (this.isInert()) {
      event.preventDefault();
      event.stopPropagation();
      return;
    }
    this.clicked.emit(event);
  }
}
