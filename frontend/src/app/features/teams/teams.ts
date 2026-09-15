import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TeamApi } from '../../core/api/team-api';
import { TeamSummary } from '../../core/api/models';
import { HiveButton, HiveCard, HiveFormField, HivePageHeader, HiveToast } from '../../shared/ui';
import { CurrentUser } from '../shared/current-user';
import { inputValue } from '../shared/dom';
import { HiveLoadState } from '../shared/load-state';
import { loadable } from '../shared/loadable';
import { mutation } from '../shared/mutation';

/**
 * Teams the acting user leads or belongs to (`GET /teams/mine`, TM-4), plus the
 * create form.
 *
 * Creating a team needs no permission check at all: TM-1 makes it available to
 * **any authenticated user**, and the creator becomes the lead and (INV-1) a
 * member. So the form is unconditional here, and every narrower control lives
 * on the team's own page where the server has told us who the lead is.
 */
@Component({
  selector: 'app-teams',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    HiveButton,
    HiveCard,
    HiveFormField,
    HivePageHeader,
    HiveToast,
    HiveLoadState,
  ],
  template: `
    <hive-page-header
      heading="Teams"
      eyebrow="People"
      subtitle="Teams you lead or belong to. Anyone may start a new one."
    >
      <hive-button
        hive-page-header-actions
        [ariaExpanded]="creating() ? 'true' : 'false'"
        ariaControls="team-create-form"
        data-testid="new-team"
        (clicked)="creating.set(!creating())"
      >
        {{ creating() ? 'Cancel' : 'New team' }}
      </hive-button>
    </hive-page-header>

    @if (created(); as name) {
      <hive-toast variant="success" dismissible (dismissed)="created.set(null)">
        {{ name }} was created. You are its team lead.
      </hive-toast>
    }

    @if (creating()) {
      <hive-card padding="lg" accent>
        <h2 hive-card-header class="teams__title">Create a team</h2>
        <form id="team-create-form" class="teams__form" (submit)="create($event)">
          <hive-form-field
            label="Team name"
            required
            hint="1 to 200 characters."
            [error]="save.errorMessage()"
          >
            <input
              class="hive-input"
              name="name"
              autocomplete="off"
              data-testid="team-name"
              [value]="name()"
              (input)="name.set(value($event))"
            />
          </hive-form-field>
          <hive-button type="submit" [loading]="save.busy()" data-testid="create-team">
            Create team
          </hive-button>
        </form>
      </hive-card>
    }

    <hive-card padding="lg">
      <h2 hive-card-header class="teams__title">My teams</h2>
      <hive-load-state
        [loading]="teams.isLoading()"
        [error]="teams.errorMessage()"
        [empty]="teams.isReady() && list().length === 0"
        loadingLabel="Loading my teams"
        emptyHeading="You are not in a team yet"
        emptyMessage="Create a team to start grouping people and projects."
        (retried)="load()"
      >
        <ul class="teams__list" aria-label="My teams">
          @for (team of list(); track team.id) {
            <li class="teams__item" [attr.data-team-id]="team.id">
              <a class="teams__link" [routerLink]="['/teams', team.id]">{{ team.name }}</a>
              <p class="teams__meta">
                <span class="teams__role">{{ role(team) }}</span>
                &middot; {{ team.memberCount }} members &middot; led by {{ team.teamLead.name }}
              </p>
            </li>
          }
        </ul>
      </hive-load-state>
    </hive-card>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: var(--hive-space-6);
    }

    .teams__title {
      margin: 0;
      font-size: var(--hive-font-size-lg);
      font-weight: var(--hive-font-weight-bold);
      color: var(--hive-color-text);
    }

    .teams__form {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: var(--hive-space-3);
    }

    .teams__form hive-form-field {
      flex: 1 1 18rem;
    }

    .teams__list {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .teams__item {
      padding: var(--hive-space-3) 0;
      border-bottom: 1px solid var(--hive-color-border-subtle);
    }

    .teams__item:last-child {
      border-bottom: none;
    }

    .teams__link {
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-color-text);
      text-decoration: none;
    }

    .teams__link:hover {
      text-decoration: underline;
    }

    .teams__meta {
      margin: var(--hive-space-1) 0 0;
      font-size: var(--hive-font-size-xs);
      color: var(--hive-color-text-secondary);
    }

    .teams__role {
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-color-text);
    }
  `,
})
export class TeamsPage {
  private readonly api = inject(TeamApi);
  private readonly currentUser = inject(CurrentUser);

  protected readonly teams = loadable<readonly TeamSummary[]>();
  protected readonly save = mutation();
  protected readonly creating = signal(false);
  protected readonly name = signal('');
  protected readonly created = signal<string | null>(null);
  protected readonly value = inputValue;

  protected readonly list = computed(() => this.teams.value() ?? []);

  constructor() {
    this.currentUser.load().subscribe({ error: () => undefined });
    this.load();
  }

  protected load(): void {
    this.teams.load(this.api.listMine());
  }

  protected create(event: Event): void {
    event.preventDefault();
    const name = this.name().trim();
    if (!name) {
      return;
    }
    this.save.run(this.api.create({ name }), {
      next: (team) => {
        this.name.set('');
        this.creating.set(false);
        this.created.set(team.name);
        this.load();
      },
    });
  }

  /** Context from the record the server returned, not a policy decision. */
  protected role(team: TeamSummary): string {
    return this.currentUser.isMe(team.teamLead.id) ? 'Team lead' : 'Member';
  }
}
