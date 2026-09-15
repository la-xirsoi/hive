import { ChangeDetectionStrategy, Component, booleanAttribute, input, output } from '@angular/core';
import { HiveButton, HiveEmptyState, HiveSpinner, HiveToast } from '../../shared/ui';

/**
 * Renders the loading / error / empty / content states of one asynchronous
 * region, so that every list in the application communicates them identically.
 *
 * Pair it with a {@link Loadable}:
 *
 * ```html
 * <hive-load-state
 *   [loading]="teams.isLoading()"
 *   [error]="teams.errorMessage()"
 *   [empty]="teams.isReady() && teams.value()?.length === 0"
 *   (retried)="loadTeams()">
 *   <hive-team-list [teams]="teams.value() ?? []" />
 * </hive-load-state>
 * ```
 *
 * Accessibility: the spinner is a polite live region with a real label, the
 * error is an assertive `role="alert"` carrying the server's own message plus a
 * keyboard-reachable retry, and the empty state is a labelled section. None of
 * the three relies on colour to say what it is.
 */
@Component({
  selector: 'hive-load-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [HiveButton, HiveEmptyState, HiveSpinner, HiveToast],
  template: `
    @if (loading()) {
      <p class="load-state__loading"><hive-spinner [label]="loadingLabel()" /></p>
    } @else if (error(); as message) {
      <hive-toast variant="error" [heading]="errorHeading()">
        {{ message }}
        @if (retryable()) {
          <hive-button
            hive-toast-actions
            size="sm"
            variant="secondary"
            data-testid="retry"
            (clicked)="retried.emit()"
          >
            Try again
          </hive-button>
        }
      </hive-toast>
    } @else if (empty()) {
      <hive-empty-state
        compact
        [heading]="emptyHeading()"
        [message]="emptyMessage()"
        [glyph]="emptyGlyph()"
      >
        <ng-content select="[hive-load-state-actions]" ngProjectAs="[hive-empty-state-actions]" />
      </hive-empty-state>
    } @else {
      <ng-content />
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .load-state__loading {
      display: flex;
      justify-content: center;
      margin: 0;
      padding: var(--hive-space-6) 0;
      color: var(--hive-color-text-secondary);
    }
  `,
})
export class HiveLoadState {
  readonly loading = input(false, { transform: booleanAttribute });
  /** The server's message, already normalized by `ApiError`. */
  readonly error = input<string | null>(null);
  readonly empty = input(false, { transform: booleanAttribute });

  readonly loadingLabel = input('Loading');
  readonly errorHeading = input<string | null>('Could not load this');
  readonly emptyHeading = input('Nothing here yet');
  readonly emptyMessage = input<string | null>(null);
  readonly emptyGlyph = input('✱');
  readonly retryable = input(true, { transform: booleanAttribute });

  readonly retried = output<void>();
}
