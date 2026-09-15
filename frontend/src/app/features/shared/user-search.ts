import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { UserApi } from '../../core/api/user-api';
import { Page, UserSummary } from '../../core/api/models';
import { HiveButton, HiveFormField, HiveSpinner, HiveToast } from '../../shared/ui';
import { inputValue } from './dom';
import { loadable } from './loadable';

/**
 * Directory lookup for the places where an operation names another user: adding
 * a team member (TM-6) and transferring project ownership (PR-6/PR-7). US-4
 * makes the directory readable by any authenticated caller, which is what makes
 * a search box the right control here rather than a fixed list.
 *
 * The search runs on explicit submit rather than on every keystroke: it is a
 * real `<form>`, so Enter works, the button is reachable by Tab, and no result
 * set ever changes underneath a keyboard user mid-selection.
 */
@Component({
  selector: 'hive-user-search',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [HiveButton, HiveFormField, HiveSpinner, HiveToast],
  template: `
    <form class="user-search" (submit)="search($event)">
      <hive-form-field [label]="label()" [hint]="hint()">
        <input
          class="hive-input"
          type="search"
          name="query"
          autocomplete="off"
          [value]="query()"
          [placeholder]="placeholder()"
          (input)="query.set(value($event))"
        />
      </hive-form-field>
      <hive-button type="submit" variant="secondary" [loading]="results.isLoading()">
        Search
      </hive-button>
    </form>

    @if (results.isLoading()) {
      <hive-spinner label="Searching for people" />
    } @else if (results.hasError()) {
      <hive-toast variant="error">{{ results.errorMessage() }}</hive-toast>
    } @else if (results.isReady()) {
      @if (candidates().length === 0) {
        <p class="user-search__empty" role="status">No matching people were found.</p>
      } @else {
        <ul class="user-search__results">
          @for (user of candidates(); track user.id) {
            <li class="user-search__result">
              <span class="user-search__identity">
                <span class="user-search__name">{{ user.name }}</span>
                <span class="user-search__email">{{ user.email }}</span>
              </span>
              <hive-button
                size="sm"
                [attr.data-user-id]="user.id"
                [disabled]="busy()"
                [ariaLabel]="selectLabel() + ' ' + user.name"
                (clicked)="picked.emit(user)"
              >
                {{ selectLabel() }}
              </hive-button>
            </li>
          }
        </ul>
      }
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .user-search {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: var(--hive-space-3);
    }

    .user-search hive-form-field {
      flex: 1 1 16rem;
    }

    .user-search__results {
      margin: var(--hive-space-4) 0 0;
      padding: 0;
      list-style: none;
    }

    .user-search__result {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--hive-space-3);
      padding: var(--hive-space-2) 0;
      border-bottom: 1px solid var(--hive-color-border-subtle);
    }

    .user-search__identity {
      display: flex;
      flex-direction: column;
      min-width: 0;
    }

    .user-search__name {
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-color-text);
    }

    .user-search__email {
      font-size: var(--hive-font-size-xs);
      color: var(--hive-color-text-secondary);
    }

    .user-search__empty {
      margin: var(--hive-space-4) 0 0;
      color: var(--hive-color-text-secondary);
    }
  `,
})
export class HiveUserSearch {
  private readonly users = inject(UserApi);

  readonly label = input('Find a person');
  readonly hint = input<string | null>('Search by name or email address.');
  readonly placeholder = input('e.g. alice or alice@hive.test');
  readonly selectLabel = input('Select');
  /** Ids to hide from the results, e.g. people already on the team. */
  readonly excludeIds = input<readonly number[]>([]);
  /** Disables selection while an operation is in flight. */
  readonly busy = input(false);

  readonly picked = output<UserSummary>();

  protected readonly query = signal('');
  protected readonly results = loadable<Page<UserSummary>>();
  protected readonly value = inputValue;

  protected readonly candidates = computed(() => {
    const excluded = new Set(this.excludeIds());
    return (this.results.value()?.content ?? []).filter((user) => !excluded.has(user.id));
  });

  protected search(event?: Event): void {
    event?.preventDefault();
    this.results.load(this.users.search(this.query().trim() || undefined, { size: 20 }));
  }
}
