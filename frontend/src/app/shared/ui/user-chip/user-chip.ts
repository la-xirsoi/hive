import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from '@angular/core';
import { HiveAvatar, type HiveAvatarSize } from '../avatar/avatar';

/** Minimal shape of the contract's `UserSummary` that this chip needs. */
export interface HiveChipUser {
  readonly name: string;
  readonly email?: string;
}

/**
 * Avatar + name (+ optional secondary line), with an optional remove button.
 *
 * Used for assignees, team members and mention targets.
 *
 * Accessibility: the avatar is marked decorative because the name is already
 * visible text beside it - otherwise every chip would be announced twice. The
 * remove button carries an explicit, name-bearing label ("Remove Ada Lovelace")
 * so it is unambiguous in a list of identical-looking buttons.
 */
@Component({
  selector: 'hive-user-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [HiveAvatar],
  template: `
    <span [class]="classes()">
      <hive-avatar [name]="name()" [size]="avatarSize()" [imageUrl]="imageUrl()" decorative />
      <span class="hive-chip__text">
        <span class="hive-chip__name">{{ name() }}</span>
        @if (secondary(); as secondaryText) {
          <span class="hive-chip__secondary">{{ secondaryText }}</span>
        }
      </span>
      @if (removable()) {
        <button
          type="button"
          class="hive-chip__remove"
          [attr.aria-label]="removeLabel()"
          (click)="removed.emit()"
        >
          <span aria-hidden="true">&#10005;</span>
        </button>
      }
    </span>
  `,
  styles: `
    :host {
      display: inline-flex;
      max-width: 100%;
    }

    .hive-chip {
      display: inline-flex;
      align-items: center;
      gap: var(--hive-space-2);
      max-width: 100%;
      min-width: 0;
      padding: var(--hive-space-1) var(--hive-space-2);
      border-radius: var(--hive-radius-pill);
    }

    .hive-chip--outlined {
      padding-right: var(--hive-space-3);
      background-color: var(--hive-color-surface);
      border: 1px solid var(--hive-color-border-subtle);
    }

    .hive-chip__text {
      display: flex;
      flex-direction: column;
      min-width: 0;
      line-height: var(--hive-line-height-snug);
    }

    .hive-chip__name {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: var(--hive-font-size-sm);
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-color-text); /* 17.40:1 on white */
    }

    .hive-chip__secondary {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: var(--hive-font-size-xs);
      color: var(--hive-color-text-secondary); /* 10.05:1 on white */
    }

    .hive-chip__remove {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 24px;
      height: 24px;
      margin-left: var(--hive-space-1);
      padding: 0;
      font-size: var(--hive-font-size-xs);
      color: var(--hive-color-text-secondary);
      background: transparent;
      border: 1px solid transparent;
      border-radius: var(--hive-radius-circle);
      cursor: pointer;
    }

    .hive-chip__remove:hover {
      background-color: var(--hive-color-surface-sunken);
      color: var(--hive-color-text);
    }

    .hive-chip__remove:focus-visible {
      outline: none;
      box-shadow: var(--hive-focus-ring-tight);
    }

    :host(.hive-user-chip--on-dark) .hive-chip__name {
      color: var(--hive-color-text-inverse); /* 17.40:1 on #1A1A1A */
    }
    :host(.hive-user-chip--on-dark) .hive-chip__secondary {
      color: var(--hive-color-text-inverse-secondary); /* 9.26:1 on #1A1A1A */
    }
    :host(.hive-user-chip--on-dark) .hive-chip__remove {
      color: var(--hive-color-text-inverse-secondary);
    }
  `,
})
export class HiveUserChip {
  readonly name = input.required<string>();
  /** Second line, typically the email from `UserSummary`. */
  readonly secondary = input<string | null>(null);
  readonly imageUrl = input<string | null>(null);
  readonly size = input<'sm' | 'md'>('md');
  /** Draw a bordered pill around the chip. */
  readonly outlined = input(false, { transform: booleanAttribute });
  readonly removable = input(false, { transform: booleanAttribute });

  readonly removed = output<void>();

  protected readonly avatarSize = computed<HiveAvatarSize>(() =>
    this.size() === 'sm' ? 'xs' : 'sm',
  );

  protected readonly removeLabel = computed(() => `Remove ${this.name()}`);

  protected readonly classes = computed(() =>
    this.outlined() ? 'hive-chip hive-chip--outlined' : 'hive-chip',
  );
}
