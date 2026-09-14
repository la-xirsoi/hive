import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  input,
  output,
  viewChild,
} from '@angular/core';

export type HiveModalSize = 'sm' | 'md' | 'lg';

let nextModalId = 0;

const FOCUSABLE =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Modal dialog shell.
 *
 * Slots: (default) = body, [hive-modal-footer] = action row.
 *
 * Accessibility, all implemented here so features do not have to:
 *   * `role="dialog"` + `aria-modal="true"`, labelled by the rendered heading
 *     via `aria-labelledby` (and optionally described by the body)
 *   * focus moves into the dialog when it opens and is restored to the element
 *     that had it when the dialog closes
 *   * Tab/Shift+Tab are trapped inside the dialog
 *   * Escape closes (unless `dismissible` is false, e.g. destructive confirms)
 *   * the backdrop click closes only when dismissible; the dialog itself stops
 *     propagation so an inner click never dismisses
 *   * the whole dialog is removed from the DOM when closed, so nothing behind
 *     the scrim is reachable by keyboard
 */
@Component({
  selector: 'hive-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (open()) {
      <div class="hive-modal__backdrop" (click)="onBackdrop()">
        <div
          #dialog
          [class]="dialogClasses()"
          role="dialog"
          aria-modal="true"
          [attr.aria-labelledby]="headingId"
          tabindex="-1"
          (click)="$event.stopPropagation()"
          (keydown)="onKeydown($event)"
        >
          <header class="hive-modal__header">
            <h2 class="hive-modal__title" [id]="headingId">{{ heading() }}</h2>
            @if (dismissible()) {
              <button
                type="button"
                class="hive-modal__close"
                [attr.aria-label]="closeLabel()"
                (click)="requestClose('close-button')"
              >
                <span aria-hidden="true">&#10005;</span>
              </button>
            }
          </header>

          <div class="hive-modal__body"><ng-content /></div>

          <footer class="hive-modal__footer"><ng-content select="[hive-modal-footer]" /></footer>
        </div>
      </div>
    }
  `,
  styles: `
    :host {
      display: contents;
    }

    .hive-modal__backdrop {
      position: fixed;
      inset: 0;
      z-index: var(--hive-z-modal);
      display: flex;
      align-items: center;
      justify-content: center;
      padding: var(--hive-space-4);
      background-color: var(--hive-color-overlay);
      overflow-y: auto;
      animation: hive-modal-fade var(--hive-duration-fast) var(--hive-ease-out);
    }

    .hive-modal {
      position: relative;
      display: flex;
      flex-direction: column;
      width: 100%;
      max-height: calc(100vh - var(--hive-space-8));
      background-color: var(--hive-color-surface);
      border-radius: var(--hive-radius-lg);
      box-shadow: var(--hive-elevation-4);
      /* Gold cap: the brand accent as a fill at the top of the sheet. */
      border-top: 4px solid var(--hive-gold);
      overflow: hidden;
      animation: hive-modal-rise var(--hive-duration-base) var(--hive-ease-out);
    }

    .hive-modal:focus {
      outline: none;
    }
    .hive-modal:focus-visible {
      box-shadow: var(--hive-elevation-4), var(--hive-focus-ring-tight);
    }

    .hive-modal--sm {
      max-width: 420px;
    }
    .hive-modal--md {
      max-width: 560px;
    }
    .hive-modal--lg {
      max-width: 800px;
    }

    .hive-modal__header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--hive-space-4);
      padding: var(--hive-space-5) var(--hive-space-6);
      border-bottom: 1px solid var(--hive-color-border-subtle);
    }

    .hive-modal__title {
      margin: 0;
      font-size: var(--hive-font-size-xl);
      font-weight: var(--hive-font-weight-bold);
      color: var(--hive-color-text); /* 17.40:1 on white */
    }

    .hive-modal__close {
      flex: 0 0 auto;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 32px;
      height: 32px;
      padding: 0;
      font-size: var(--hive-font-size-sm);
      color: var(--hive-color-text-secondary);
      background: transparent;
      border: 1px solid transparent;
      border-radius: var(--hive-radius-sm);
      cursor: pointer;
    }

    .hive-modal__close:hover {
      color: var(--hive-color-text);
      background-color: var(--hive-color-surface-sunken);
    }

    .hive-modal__close:focus-visible {
      outline: none;
      box-shadow: var(--hive-focus-ring-tight);
    }

    .hive-modal__body {
      flex: 1 1 auto;
      padding: var(--hive-space-6);
      overflow-y: auto;
      font-size: var(--hive-font-size-md);
      color: var(--hive-color-text);
    }

    .hive-modal__footer:empty {
      display: none;
    }

    .hive-modal__footer {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: flex-end;
      gap: var(--hive-space-3);
      padding: var(--hive-space-4) var(--hive-space-6);
      background-color: var(--hive-color-surface-sunken);
      border-top: 1px solid var(--hive-color-border-subtle);
    }

    @keyframes hive-modal-fade {
      from {
        opacity: 0;
      }
    }

    @keyframes hive-modal-rise {
      from {
        opacity: 0;
        transform: translateY(12px);
      }
    }
  `,
})
export class HiveModal {
  readonly open = input(false, { transform: booleanAttribute });
  readonly heading = input.required<string>();
  readonly size = input<HiveModalSize>('md');
  /** Allow Escape, backdrop click and the close button to dismiss. */
  readonly dismissible = input(true, { transform: booleanAttribute });
  readonly closeLabel = input('Close dialog');

  /** Emitted with the reason the dialog asked to close. */
  readonly closed = output<'escape' | 'backdrop' | 'close-button'>();

  protected readonly headingId = `hive-modal-title-${nextModalId++}`;

  private readonly dialog = viewChild<ElementRef<HTMLElement>>('dialog');
  private previouslyFocused: HTMLElement | null = null;

  protected readonly dialogClasses = computed(() => `hive-modal hive-modal--${this.size()}`);

  constructor() {
    effect(() => {
      const dialogRef = this.dialog();
      if (this.open() && dialogRef) {
        this.previouslyFocused = this.activeElement();
        const target = this.firstFocusable(dialogRef.nativeElement) ?? dialogRef.nativeElement;
        target.focus();
      } else if (!this.open() && this.previouslyFocused) {
        this.previouslyFocused.focus();
        this.previouslyFocused = null;
      }
    });
  }

  protected onBackdrop(): void {
    this.requestClose('backdrop');
  }

  protected requestClose(reason: 'escape' | 'backdrop' | 'close-button'): void {
    if (!this.dismissible()) {
      return;
    }
    this.closed.emit(reason);
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.requestClose('escape');
      return;
    }
    if (event.key === 'Tab') {
      this.trapTab(event);
    }
  }

  /** Keeps Tab/Shift+Tab cycling inside the dialog (WCAG 2.1.2 no keyboard trap
   *  out, 2.4.3 focus order - the dialog is modal so focus must not escape). */
  private trapTab(event: KeyboardEvent): void {
    const dialogEl = this.dialog()?.nativeElement;
    if (!dialogEl) {
      return;
    }
    const focusable = Array.from(dialogEl.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (focusable.length === 0) {
      event.preventDefault();
      dialogEl.focus();
      return;
    }
    const first = focusable[0]!;
    const last = focusable[focusable.length - 1]!;
    const active = this.activeElement();

    if (event.shiftKey && (active === first || active === dialogEl)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }

  private firstFocusable(root: HTMLElement): HTMLElement | null {
    return root.querySelector<HTMLElement>(FOCUSABLE);
  }

  private activeElement(): HTMLElement | null {
    const active = typeof document === 'undefined' ? null : document.activeElement;
    return active instanceof HTMLElement ? active : null;
  }
}
