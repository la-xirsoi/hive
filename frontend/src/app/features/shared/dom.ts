/**
 * Reading values out of native controls.
 *
 * The feature screens bind native `<input>`/`<select>`/`<textarea>` elements to
 * signals directly rather than going through `FormsModule`. The forms package
 * brings a second change-propagation mechanism into a zoneless, signal-based
 * application for no benefit at this size, and `<hive-form-field>` already owns
 * the label, hint, error and ARIA wiring that a form directive would otherwise
 * provide.
 */
export function inputValue(event: Event): string {
  const target = event.target;
  if (
    target instanceof HTMLInputElement ||
    target instanceof HTMLTextAreaElement ||
    target instanceof HTMLSelectElement
  ) {
    return target.value;
  }
  return '';
}
