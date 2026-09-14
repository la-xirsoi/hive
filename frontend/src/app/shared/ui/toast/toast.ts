import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from '@angular/core';

export type HiveToastVariant = 'info' | 'success' | 'error';

const VARIANT_META: Record<HiveToastVariant, { glyph: string; prefix: string; role: string }> = {
  // `role="alert"` is assertive and interrupts - correct for errors only.
  info: { glyph: 'ℹ', prefix: 'Information', role: 'status' },
  success: { glyph: '✓', prefix: 'Success', role: 'status' },
  error: { glyph: '⚠', prefix: 'Error', role: 'alert' },
};

/**
 * Inline alert / toast message.
 *
 * Colour is never the only signal: each variant ships a distinct glyph AND a
 * visually hidden textual prefix ("Error:", "Success:", "Information:") so the
 * severity is in the accessible name as well as in the palette.
 *
 * Contrast:
 *   body copy  #1A1A1A on #E7F1FB / #E8F5E9 / #FFEBEE -> 15.0-16.0:1
 *   title/icon #0B4F8A on #E7F1FB -> 7.35:1
 *              #1B5E20 on #E8F5E9 -> 7.00:1
 *              #B3261E on #FFEBEE -> 5.72:1
 *
 * Slot: [hive-toast-actions]
 */
@Component({
  selector: 'hive-toast',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div [class]="classes()" [attr.role]="role()">
      <span class="hive-toast__icon" aria-hidden="true">{{ glyph() }}</span>
      <div class="hive-toast__body">
        <span class="hive-sr-only">{{ prefix() }}: </span>
        @if (heading(); as headingText) {
          <p class="hive-toast__heading">{{ headingText }}</p>
        }
        <div class="hive-toast__message"><ng-content /></div>
        <div class="hive-toast__actions"><ng-content select="[hive-toast-actions]" /></div>
      </div>
      @if (dismissible()) {
        <button
          type="button"
          class="hive-toast__dismiss"
          [attr.aria-label]="dismissLabel()"
          (click)="dismissed.emit()"
        >
          <span aria-hidden="true">&#10005;</span>
        </button>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    .hive-toast {
      display: flex;
      align-items: flex-start;
      gap: var(--hive-space-3);
      padding: var(--hive-space-4);
      color: var(--hive-color-text);
      background-color: var(--hive-toast-surface);
      border: 1px solid var(--hive-toast-accent);
      border-left-width: 4px;
      border-radius: var(--hive-radius-md);
      box-shadow: var(--hive-elevation-1);
    }

    .hive-toast--info {
      --hive-toast-surface: var(--hive-color-info-surface);
      --hive-toast-accent: var(--hive-color-info);
    }
    .hive-toast--success {
      --hive-toast-surface: var(--hive-color-success-surface);
      --hive-toast-accent: var(--hive-color-success);
    }
    .hive-toast--error {
      --hive-toast-surface: var(--hive-color-danger-surface);
      --hive-toast-accent: var(--hive-color-danger-text);
    }

    .hive-toast__icon {
      flex: 0 0 auto;
      display: flex;
      align-items: center;
      justify-content: center;
      width: 22px;
      height: 22px;
      font-size: var(--hive-font-size-sm);
      font-weight: var(--hive-font-weight-bold);
      line-height: 1;
      color: var(--hive-toast-accent);
      border: 1.5px solid currentColor;
      border-radius: var(--hive-radius-circle);
    }

    .hive-toast__body {
      flex: 1 1 auto;
      min-width: 0;
      font-size: var(--hive-font-size-sm);
      line-height: var(--hive-line-height-normal);
    }

    .hive-toast__heading {
      margin: 0 0 var(--hive-space-1);
      font-size: var(--hive-font-size-md);
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-toast-accent);
    }

    .hive-toast__message:empty {
      display: none;
    }

    .hive-toast__actions:empty {
      display: none;
    }

    .hive-toast__actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--hive-space-2);
      margin-top: var(--hive-space-3);
    }

    .hive-toast__dismiss {
      flex: 0 0 auto;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 28px;
      height: 28px;
      padding: 0;
      font-size: var(--hive-font-size-sm);
      color: var(--hive-color-text-secondary);
      background: transparent;
      border: 1px solid transparent;
      border-radius: var(--hive-radius-sm);
      cursor: pointer;
    }

    .hive-toast__dismiss:hover {
      color: var(--hive-color-text);
      background-color: rgb(26 26 26 / 6%);
    }

    .hive-toast__dismiss:focus-visible {
      outline: none;
      box-shadow: var(--hive-focus-ring-tight);
    }

    @media (forced-colors: active) {
      .hive-toast {
        border-color: CanvasText;
      }
    }
  `,
})
export class HiveToast {
  readonly variant = input<HiveToastVariant>('info');
  readonly heading = input<string | null>(null);
  readonly dismissible = input(false, { transform: booleanAttribute });
  readonly dismissLabel = input('Dismiss message');

  readonly dismissed = output<void>();

  protected readonly glyph = computed(() => VARIANT_META[this.variant()].glyph);
  protected readonly prefix = computed(() => VARIANT_META[this.variant()].prefix);
  protected readonly role = computed(() => VARIANT_META[this.variant()].role);
  protected readonly classes = computed(() => `hive-toast hive-toast--${this.variant()}`);
}
