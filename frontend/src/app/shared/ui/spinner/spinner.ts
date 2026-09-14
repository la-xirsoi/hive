import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from '@angular/core';

export type HiveSpinnerSize = 'sm' | 'md' | 'lg';

/** Colour of the rotating arc. Gold is the brand default per BRANDING_GUIDE.md
 *  ("Loading/progress indicators: Yellow"). */
export type HiveSpinnerTone = 'gold' | 'ink' | 'inverse';

/**
 * Indeterminate loading indicator.
 *
 * Accessibility: the spinner is a live region (`role="status"`) carrying a
 * visually hidden label, so assistive tech announces "Loading…" rather than
 * nothing. The graphic itself is `aria-hidden`. When the spinner sits inside
 * another control that already announces busy-ness (e.g. `<hive-button>` with
 * `aria-busy`), pass `announce=false` to avoid a duplicate announcement.
 */
@Component({
  selector: 'hive-spinner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span [class]="classes()" [attr.role]="announce() ? 'status' : null">
      <svg class="hive-spinner__svg" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <circle class="hive-spinner__track" cx="12" cy="12" r="9" />
        <circle class="hive-spinner__arc" cx="12" cy="12" r="9" />
      </svg>
      @if (announce()) {
        <span class="hive-sr-only">{{ label() }}</span>
      }
    </span>
  `,
  styles: `
    :host {
      display: inline-flex;
    }

    .hive-spinner {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      line-height: 0;
    }

    .hive-spinner__svg {
      width: var(--hive-spinner-size, 24px);
      height: var(--hive-spinner-size, 24px);
      animation: hive-spinner-rotate 900ms linear infinite;
    }

    .hive-spinner--sm {
      --hive-spinner-size: 16px;
    }
    .hive-spinner--md {
      --hive-spinner-size: 24px;
    }
    .hive-spinner--lg {
      --hive-spinner-size: 40px;
    }

    .hive-spinner__track,
    .hive-spinner__arc {
      fill: none;
      stroke-width: 3;
      stroke-linecap: round;
    }

    .hive-spinner__track {
      stroke: currentColor;
      opacity: 0.18;
    }

    .hive-spinner__arc {
      stroke-dasharray: 56.5;
      stroke-dashoffset: 42;
    }

    /* The arc carries the brand colour; the track is a faded currentColor so the
       ring reads on both light and dark surfaces. */
    .hive-spinner--gold {
      color: var(--hive-color-text-secondary);
    }
    .hive-spinner--gold .hive-spinner__arc {
      stroke: var(--hive-gold);
    }

    .hive-spinner--ink {
      color: var(--hive-color-text-secondary);
    }
    .hive-spinner--ink .hive-spinner__arc {
      stroke: var(--hive-ink);
    }

    .hive-spinner--inverse {
      color: var(--hive-color-text-inverse);
    }
    .hive-spinner--inverse .hive-spinner__arc {
      stroke: var(--hive-color-text-inverse);
    }

    @keyframes hive-spinner-rotate {
      to {
        transform: rotate(360deg);
      }
    }

    /* Respect reduced-motion: pulse the opacity instead of spinning. */
    @media (prefers-reduced-motion: reduce) {
      .hive-spinner__svg {
        animation: hive-spinner-pulse 1.4s ease-in-out infinite;
      }
      @keyframes hive-spinner-pulse {
        0%,
        100% {
          opacity: 1;
        }
        50% {
          opacity: 0.45;
        }
      }
    }
  `,
})
export class HiveSpinner {
  readonly size = input<HiveSpinnerSize>('md');
  readonly tone = input<HiveSpinnerTone>('gold');
  /** Text announced to assistive technology while the spinner is visible. */
  readonly label = input('Loading');
  /** Set false when a parent control already communicates the busy state. */
  readonly announce = input(true, { transform: booleanAttribute });

  protected readonly classes = computed(
    () => `hive-spinner hive-spinner--${this.size()} hive-spinner--${this.tone()}`,
  );
}
