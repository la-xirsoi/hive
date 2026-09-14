import {
  booleanAttribute,
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  input,
} from '@angular/core';

let nextFormFieldId = 0;

const CONTROL_SELECTOR = 'input, select, textarea, [hive-form-control]';

/**
 * Label + hint + error wrapper for a single form control.
 *
 * Usage - just project the native control, no extra wiring:
 *
 *   <hive-form-field label="Task name" hint="Shown on the board" [error]="nameError()">
 *     <input class="hive-input" type="text" [formControl]="name" />
 *   </hive-form-field>
 *
 * The component finds the projected control and applies `id`,
 * `aria-describedby` (hint and/or error), `aria-invalid` and `aria-required`
 * itself, because those attributes must live on the control, not the wrapper.
 *
 * Gold focus ring: the control's ring comes from the global `_forms.scss` rules
 * (`.hive-input:focus-visible`), since Angular's emulated encapsulation cannot
 * style projected content. The wrapper additionally darkens the label on
 * `:focus-within` so the active field is obvious.
 *
 * Accessibility:
 *   * the error is a `role="alert"`, so it is announced when it appears
 *   * the error text is prefixed with a visible warning glyph and the hidden
 *     word "Error" - never red text alone
 *   * `required` renders a visible asterisk AND the word "required" for AT
 */
@Component({
  selector: 'hive-form-field',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="hive-field" [class.hive-field--invalid]="!!error()">
      <label class="hive-field__label" [attr.for]="controlId()">
        {{ label() }}
        @if (required()) {
          <span class="hive-field__required" aria-hidden="true">*</span>
          <span class="hive-sr-only">(required)</span>
        }
      </label>

      <div class="hive-field__control"><ng-content /></div>

      @if (hint() && !error()) {
        <p class="hive-field__hint" [id]="hintId()">{{ hint() }}</p>
      }

      @if (error(); as errorText) {
        <p class="hive-field__error" [id]="errorId()" role="alert">
          <span class="hive-field__error-glyph" aria-hidden="true">&#9888;</span>
          <span class="hive-sr-only">Error: </span>{{ errorText }}
        </p>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    .hive-field {
      display: flex;
      flex-direction: column;
      gap: var(--hive-space-2);
    }

    .hive-field__label {
      font-size: var(--hive-font-size-sm);
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-color-text); /* 17.40:1 on white */
      transition: color var(--hive-duration-fast) var(--hive-ease-standard);
    }

    /* Gold is an accent, never the label's text colour - on focus we underline
       the label with gold and keep the text ink. */
    .hive-field:focus-within .hive-field__label {
      text-decoration: underline;
      text-decoration-color: var(--hive-gold);
      text-decoration-thickness: 3px;
      text-underline-offset: 3px;
    }

    .hive-field__required {
      margin-left: 2px;
      color: var(--hive-color-danger-text); /* 6.54:1 on white */
      font-weight: var(--hive-font-weight-bold);
    }

    .hive-field__control {
      display: block;
      min-width: 0;
    }

    .hive-field__hint {
      margin: 0;
      font-size: var(--hive-font-size-xs);
      color: var(--hive-color-text-secondary); /* 10.05:1 on white */
    }

    .hive-field__error {
      display: flex;
      align-items: flex-start;
      gap: var(--hive-space-1);
      margin: 0;
      font-size: var(--hive-font-size-xs);
      font-weight: var(--hive-font-weight-medium);
      color: var(--hive-color-danger-text); /* 6.54:1 on white */
    }

    .hive-field__error-glyph {
      line-height: var(--hive-line-height-snug);
    }
  `,
})
export class HiveFormField implements AfterViewInit {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly label = input.required<string>();
  readonly hint = input<string | null>(null);
  readonly error = input<string | null>(null);
  readonly required = input(false, { transform: booleanAttribute });
  /** Override the generated id when the caller already owns one. */
  readonly fieldId = input<string | null>(null);

  private readonly autoId = `hive-field-${nextFormFieldId++}`;

  readonly controlId = computed(() => this.fieldId() ?? this.autoId);
  readonly hintId = computed(() => `${this.controlId()}-hint`);
  readonly errorId = computed(() => `${this.controlId()}-error`);

  /** `aria-describedby` value for the projected control, or null. */
  readonly describedBy = computed(() => {
    const ids: string[] = [];
    if (this.error()) {
      ids.push(this.errorId());
    } else if (this.hint()) {
      ids.push(this.hintId());
    }
    return ids.length > 0 ? ids.join(' ') : null;
  });

  constructor() {
    // Re-apply whenever the described state changes. On the very first run the
    // view does not exist yet, so `syncControl` no-ops and `ngAfterViewInit`
    // performs the initial wiring.
    effect(() => {
      // Track the inputs that affect the control's ARIA state.
      this.controlId();
      this.describedBy();
      this.error();
      this.required();
      this.syncControl();
    });
  }

  ngAfterViewInit(): void {
    this.syncControl();
  }

  /** Applies id/ARIA wiring to the projected native control, if present. */
  private syncControl(): void {
    const element = this.host.nativeElement as HTMLElement | undefined;
    const control = element?.querySelector<HTMLElement>(CONTROL_SELECTOR);
    if (!control) {
      return;
    }

    // The wrapper owns the id so that <label for> always resolves; callers who
    // need a specific id pass `fieldId`.
    control.id = this.controlId();

    const describedBy = this.describedBy();
    if (describedBy) {
      control.setAttribute('aria-describedby', describedBy);
    } else {
      control.removeAttribute('aria-describedby');
    }

    if (this.error()) {
      control.setAttribute('aria-invalid', 'true');
    } else {
      control.removeAttribute('aria-invalid');
    }

    if (this.required()) {
      control.setAttribute('aria-required', 'true');
    } else {
      control.removeAttribute('aria-required');
    }
  }
}
