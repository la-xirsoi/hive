import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * The five task statuses of the frozen API contract (docs/api-contract.md 1.2).
 * Declared here so the design system has no dependency on the feature/core
 * layers; the strings are identical to the wire representation.
 */
export type TaskStatus = 'Draft' | 'Todo' | 'In Progress' | 'Completed' | 'Canceled';

/** Canonical ordering, useful for filters and legends. */
export const TASK_STATUSES: readonly TaskStatus[] = [
  'Draft',
  'Todo',
  'In Progress',
  'Completed',
  'Canceled',
] as const;

const STATUS_KEYS: Record<TaskStatus, string> = {
  Draft: 'draft',
  Todo: 'todo',
  'In Progress': 'in-progress',
  Completed: 'completed',
  Canceled: 'canceled',
};

/**
 * Status pill for a task.
 *
 * BRANDING_GUIDE.md: "Don't rely on color alone to communicate information."
 * Every badge therefore renders THREE redundant cues:
 *   1. the status text label - always present, never truncated away
 *   2. a distinct shape glyph (hollow / dashed / filled / tick / cross)
 *   3. the colour
 * A monochrome or colour-blind reader loses nothing.
 *
 * Contrast (all measured, see src/styles/CONTRAST.md):
 *   Draft        #424242 on #F0F0F0 ->  8.82:1
 *   Todo         #1A1A1A on #FFFFFF -> 17.40:1
 *   In Progress  #1A1A1A on #FFD700 -> 12.41:1
 *   Completed    #1B5E20 on #E8F5E9 ->  7.00:1
 *   Canceled     #424242 on #F0F0F0 ->  8.82:1
 */
@Component({
  selector: 'hive-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span [class]="classes()">
      <span class="hive-status__glyph" aria-hidden="true">{{ glyph() }}</span>
      <span class="hive-status__label">{{ status() }}</span>
    </span>
  `,
  styles: `
    :host {
      display: inline-flex;
      max-width: 100%;
    }

    .hive-status {
      display: inline-flex;
      align-items: center;
      gap: var(--hive-space-2);
      max-width: 100%;
      padding: 2px var(--hive-space-3) 2px var(--hive-space-2);
      min-height: 24px;
      font-size: var(--hive-font-size-xs);
      font-weight: var(--hive-font-weight-semibold);
      line-height: var(--hive-line-height-snug);
      letter-spacing: var(--hive-letter-spacing-wide);
      white-space: nowrap;
      border: 1px solid transparent;
      border-radius: var(--hive-radius-pill);
    }

    .hive-status--lg {
      padding: var(--hive-space-1) var(--hive-space-4) var(--hive-space-1) var(--hive-space-3);
      min-height: 32px;
      font-size: var(--hive-font-size-sm);
    }

    .hive-status__glyph {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 1em;
      font-size: 1.1em;
      line-height: 1;
    }

    .hive-status__label {
      overflow: hidden;
      text-overflow: ellipsis;
    }

    /* Draft - not started, not yet real work: muted well + dashed edge. */
    .hive-status--draft {
      background-color: var(--hive-color-surface-sunken);
      color: var(--hive-color-text-secondary);
      border-color: var(--hive-color-border);
      border-style: dashed;
    }

    /* Todo - queued: plain surface with a solid ink edge. */
    .hive-status--todo {
      background-color: var(--hive-color-surface);
      color: var(--hive-color-text);
      border-color: var(--hive-color-border-strong);
    }

    /* In Progress - the branding guide's "In Progress: Yellow". */
    .hive-status--in-progress {
      background-color: var(--hive-color-primary);
      color: var(--hive-color-on-primary);
      border-color: var(--hive-gold-active);
    }

    /* Completed - the branding guide's "Complete: Green". */
    .hive-status--completed {
      background-color: var(--hive-color-success-surface);
      color: var(--hive-color-success);
      border-color: var(--hive-color-success);
    }

    /* Canceled - terminal but not an error: muted, with a cross glyph. */
    .hive-status--canceled {
      background-color: var(--hive-color-surface-sunken);
      color: var(--hive-color-text-secondary);
      border-color: var(--hive-color-border);
    }

    @media (forced-colors: active) {
      .hive-status {
        border-color: CanvasText;
      }
    }
  `,
})
export class HiveStatusBadge {
  readonly status = input.required<TaskStatus>();
  readonly size = input<'md' | 'lg'>('md');

  /** Shape cue, redundant with the colour and the text label. */
  protected readonly glyph = computed(() => {
    switch (this.status()) {
      case 'Draft':
        return '◌'; // dotted circle
      case 'Todo':
        return '○'; // hollow circle
      case 'In Progress':
        return '◐'; // half-filled circle
      case 'Completed':
        return '✓'; // check mark
      case 'Canceled':
        return '✕'; // multiplication x
    }
  });

  protected readonly classes = computed(
    () => `hive-status hive-status--${STATUS_KEYS[this.status()]} hive-status--${this.size()}`,
  );
}
