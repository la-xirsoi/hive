import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from '@angular/core';

export type HiveAvatarSize = 'xs' | 'sm' | 'md' | 'lg';
export type HiveAvatarTone = 'gold' | 'ink' | 'neutral' | 'auto';

const TONES = ['gold', 'ink', 'neutral'] as const;

/** Deterministic initials: first letter of the first and last word, max two. */
export function initialsFor(name: string): string {
  const words = name
    .trim()
    .split(/\s+/)
    .filter((word) => word.length > 0);
  if (words.length === 0) {
    return '?';
  }
  const first = words[0]![0]!;
  const last = words.length > 1 ? words[words.length - 1]![0]! : '';
  return (first + last).toUpperCase();
}

/**
 * Initial-based avatar with an optional photo.
 *
 * Every tone pairs a fill with text that clears AA by a wide margin - the
 * "auto" tone only ever picks from that verified set:
 *   gold     #1A1A1A on #FFD700 -> 12.41:1
 *   ink      #FFD700 on #1A1A1A -> 12.41:1
 *   neutral  #1A1A1A on #F0F0F0 -> 15.27:1
 *
 * Accessibility: the avatar is `role="img"` labelled with the person's name, so
 * a screen reader says "Ada Lovelace" rather than spelling out "AL". When the
 * avatar sits next to a visible name (see `<hive-user-chip>`), pass
 * `decorative=true` to hide it from the accessibility tree and avoid a
 * duplicate reading.
 */
@Component({
  selector: 'hive-avatar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span
      [class]="classes()"
      [attr.role]="decorative() ? null : 'img'"
      [attr.aria-label]="decorative() ? null : name()"
      [attr.aria-hidden]="decorative() ? 'true' : null"
    >
      @if (imageUrl(); as src) {
        <img class="hive-avatar__image" [src]="src" alt="" />
      } @else {
        <span class="hive-avatar__initials">{{ initials() }}</span>
      }
    </span>
  `,
  styles: `
    :host {
      display: inline-flex;
      flex: 0 0 auto;
    }

    .hive-avatar {
      position: relative;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: var(--hive-avatar-size, 40px);
      height: var(--hive-avatar-size, 40px);
      font-size: var(--hive-avatar-font, var(--hive-font-size-sm));
      font-weight: var(--hive-font-weight-bold);
      letter-spacing: var(--hive-letter-spacing-wide);
      line-height: 1;
      border-radius: var(--hive-radius-circle);
      overflow: hidden;
      user-select: none;
    }

    .hive-avatar--xs {
      --hive-avatar-size: 24px;
      --hive-avatar-font: 10px;
    }
    .hive-avatar--sm {
      --hive-avatar-size: 32px;
      --hive-avatar-font: var(--hive-font-size-xs);
    }
    .hive-avatar--md {
      --hive-avatar-size: 40px;
      --hive-avatar-font: var(--hive-font-size-sm);
    }
    .hive-avatar--lg {
      --hive-avatar-size: 56px;
      --hive-avatar-font: var(--hive-font-size-lg);
    }

    .hive-avatar--gold {
      background-color: var(--hive-gold);
      color: var(--hive-ink);
    }
    .hive-avatar--ink {
      background-color: var(--hive-ink);
      color: var(--hive-gold);
    }
    .hive-avatar--neutral {
      background-color: var(--hive-color-surface-sunken);
      color: var(--hive-color-text);
      box-shadow: inset 0 0 0 1px var(--hive-color-border-subtle);
    }

    .hive-avatar__image {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .hive-avatar__initials {
      display: block;
      transform: translateY(0.03em);
    }
  `,
})
export class HiveAvatar {
  readonly name = input.required<string>();
  readonly size = input<HiveAvatarSize>('md');
  readonly tone = input<HiveAvatarTone>('auto');
  readonly imageUrl = input<string | null>(null);
  /** Hide from assistive tech when an adjacent element already names the user. */
  readonly decorative = input(false, { transform: booleanAttribute });

  readonly initials = computed(() => initialsFor(this.name()));

  protected readonly resolvedTone = computed(() => {
    const tone = this.tone();
    if (tone !== 'auto') {
      return tone;
    }
    const name = this.name();
    let hash = 0;
    for (let i = 0; i < name.length; i++) {
      hash = (hash * 31 + name.charCodeAt(i)) >>> 0;
    }
    return TONES[hash % TONES.length]!;
  });

  protected readonly classes = computed(
    () => `hive-avatar hive-avatar--${this.size()} hive-avatar--${this.resolvedTone()}`,
  );
}
