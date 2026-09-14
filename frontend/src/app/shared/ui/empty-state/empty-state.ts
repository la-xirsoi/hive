import { booleanAttribute, ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Empty / zero-result state.
 *
 * BRANDING_GUIDE.md lists white and off-white as the empty-state colours, with
 * gold reserved for the accent - here the hex-shaped icon plate is a gold fill
 * carrying an ink glyph (12.41:1), and all copy is ink or dark gray.
 *
 * Accessibility: rendered as a `<section>` labelled by its own heading. The
 * decorative glyph is `aria-hidden`; `live` upgrades the block to a polite live
 * region for the "your filter returned nothing" case, where the change happens
 * after the page has already loaded.
 *
 * Slot: [hive-empty-state-actions]
 */
@Component({
  selector: 'hive-empty-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section
      class="hive-empty"
      [class.hive-empty--compact]="compact()"
      [attr.aria-label]="heading()"
      [attr.aria-live]="live() ? 'polite' : null"
    >
      <span class="hive-empty__plate" aria-hidden="true">{{ glyph() }}</span>
      <h2 class="hive-empty__heading">{{ heading() }}</h2>
      @if (message(); as messageText) {
        <p class="hive-empty__message">{{ messageText }}</p>
      }
      <div class="hive-empty__actions"><ng-content select="[hive-empty-state-actions]" /></div>
    </section>
  `,
  styles: `
    :host {
      display: block;
    }

    .hive-empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      text-align: center;
      padding: var(--hive-space-10) var(--hive-space-6);
      background-color: var(--hive-color-surface);
      border: 1px dashed var(--hive-color-border);
      border-radius: var(--hive-radius-lg);
    }

    /* Hexagon plate - the Hive motif. Gold fill, ink glyph: 12.41:1. */
    .hive-empty__plate {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 56px;
      height: 56px;
      margin-bottom: var(--hive-space-5);
      font-size: var(--hive-font-size-2xl);
      line-height: 1;
      color: var(--hive-ink);
      background-color: var(--hive-gold);
      clip-path: polygon(25% 3%, 75% 3%, 100% 50%, 75% 97%, 25% 97%, 0% 50%);
    }

    .hive-empty__heading {
      margin: 0;
      font-size: var(--hive-font-size-xl);
      font-weight: var(--hive-font-weight-bold);
      color: var(--hive-color-text); /* 17.40:1 on white */
    }

    .hive-empty__message {
      margin: var(--hive-space-3) 0 0;
      max-width: 46ch;
      font-size: var(--hive-font-size-md);
      color: var(--hive-color-text-secondary); /* 10.05:1 on white */
    }

    .hive-empty__actions:empty {
      display: none;
    }

    .hive-empty__actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: center;
      gap: var(--hive-space-3);
      margin-top: var(--hive-space-6);
    }

    .hive-empty--compact {
      padding: var(--hive-space-6) var(--hive-space-4);
    }
  `,
})
export class HiveEmptyState {
  readonly heading = input.required<string>();
  readonly message = input<string | null>(null);
  /** Decorative glyph shown on the gold plate. Purely visual. */
  readonly glyph = input('✱'); // heavy asterisk, reads as a honeycomb cell
  /** Announce politely when the state appears after an interaction. */
  readonly live = input(false, { transform: booleanAttribute });
  /** Tighter padding, for empty states inside a card or panel. */
  readonly compact = input(false, { transform: booleanAttribute });
}
