import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ProjectApi } from '../../core/api/project-api';
import { TeamApi } from '../../core/api/team-api';
import { ProjectSummary, TeamSummary } from '../../core/api/models';
import { HiveButton, HiveCard, HiveFormField, HivePageHeader, HiveToast } from '../../shared/ui';
import { CurrentUser } from '../shared/current-user';
import { inputValue } from '../shared/dom';
import { HiveLoadState } from '../shared/load-state';
import { loadable } from '../shared/loadable';
import { mutation } from '../shared/mutation';

/**
 * Projects the acting user owns or can see through a team (`GET /projects/mine`,
 * PR-4), plus the create form.
 *
 * A project is always created **on a team** (`CreateProjectRequest.teamId`), and
 * PR-2 requires the creator to be a member or lead of that team. The team picker
 * is therefore populated from `GET /teams/mine` - the exact set of teams the
 * server will accept - so the only way to hit that 403 is for membership to
 * change between the two requests. When the user is in no team at all the form
 * says so and points at the teams screen, because TM-1 lets anyone create one.
 */
@Component({
  selector: 'app-projects',
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
      heading="Projects"
      eyebrow="Work"
      subtitle="Projects you own, and the projects of your teams."
    >
      <hive-button
        hive-page-header-actions
        [ariaExpanded]="creating() ? 'true' : 'false'"
        ariaControls="project-create-form"
        data-testid="new-project"
        (clicked)="creating.set(!creating())"
      >
        {{ creating() ? 'Cancel' : 'New project' }}
      </hive-button>
    </hive-page-header>

    @if (created(); as name) {
      <hive-toast variant="success" dismissible (dismissed)="created.set(null)">
        {{ name }} was created. You are its project owner.
      </hive-toast>
    }

    @if (creating()) {
      <hive-card padding="lg" accent>
        <h2 hive-card-header class="projects__title">Create a project</h2>

        @if (teams.isReady() && teamList().length === 0) {
          <p data-testid="no-teams">
            A project belongs to a team, and you are not in one yet.
            <a routerLink="/teams">Create a team first</a>
            - you will become its lead.
          </p>
        } @else {
          <form id="project-create-form" class="projects__form" (submit)="create($event)">
            <hive-form-field label="Project name" required [error]="save.errorMessage()">
              <input
                class="hive-input"
                name="name"
                autocomplete="off"
                data-testid="project-name"
                [value]="name()"
                (input)="name.set(value($event))"
              />
            </hive-form-field>
            <hive-form-field label="Team" required hint="Only teams you belong to are listed.">
              <select
                class="hive-select"
                name="teamId"
                data-testid="project-team"
                [value]="teamId()"
                (change)="teamId.set(value($event))"
              >
                <option value="">Choose a team</option>
                @for (team of teamList(); track team.id) {
                  <option [value]="team.id">{{ team.name }}</option>
                }
              </select>
            </hive-form-field>
            <hive-button
              type="submit"
              [loading]="save.busy()"
              [disabled]="!teamId()"
              data-testid="create-project"
            >
              Create project
            </hive-button>
          </form>
        }
      </hive-card>
    }

    <hive-card padding="lg">
      <h2 hive-card-header class="projects__title">My projects</h2>
      <hive-load-state
        [loading]="projects.isLoading()"
        [error]="projects.errorMessage()"
        [empty]="projects.isReady() && list().length === 0"
        loadingLabel="Loading my projects"
        emptyHeading="No projects yet"
        emptyMessage="Create a project on one of your teams to start tracking work."
        (retried)="load()"
      >
        <ul class="projects__list" aria-label="My projects">
          @for (project of list(); track project.id) {
            <li class="projects__item" [attr.data-project-id]="project.id">
              <a class="projects__link" [routerLink]="['/projects', project.id]">
                {{ project.name }}
              </a>
              <p class="projects__meta">
                <span class="projects__role">{{ role(project) }}</span>
                &middot; {{ project.team.name }} &middot; owned by {{ project.projectOwner.name }}
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

    .projects__title {
      margin: 0;
      font-size: var(--hive-font-size-lg);
      font-weight: var(--hive-font-weight-bold);
      color: var(--hive-color-text);
    }

    .projects__form {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: var(--hive-space-3);
    }

    .projects__form hive-form-field {
      flex: 1 1 16rem;
    }

    .projects__list {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .projects__item {
      padding: var(--hive-space-3) 0;
      border-bottom: 1px solid var(--hive-color-border-subtle);
    }

    .projects__item:last-child {
      border-bottom: none;
    }

    .projects__link {
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-color-text);
      text-decoration: none;
    }

    .projects__link:hover {
      text-decoration: underline;
    }

    .projects__meta {
      margin: var(--hive-space-1) 0 0;
      font-size: var(--hive-font-size-xs);
      color: var(--hive-color-text-secondary);
    }

    .projects__role {
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-color-text);
    }
  `,
})
export class ProjectsPage {
  private readonly api = inject(ProjectApi);
  private readonly teamApi = inject(TeamApi);
  private readonly currentUser = inject(CurrentUser);

  protected readonly projects = loadable<readonly ProjectSummary[]>();
  protected readonly teams = loadable<readonly TeamSummary[]>();
  protected readonly save = mutation();
  protected readonly creating = signal(false);
  protected readonly name = signal('');
  protected readonly teamId = signal('');
  protected readonly created = signal<string | null>(null);
  protected readonly value = inputValue;

  protected readonly list = computed(() => this.projects.value() ?? []);
  protected readonly teamList = computed(() => this.teams.value() ?? []);

  constructor() {
    this.currentUser.load().subscribe({ error: () => undefined });
    this.load();
    this.teams.load(this.teamApi.listMine());
  }

  protected load(): void {
    this.projects.load(this.api.listMine());
  }

  protected create(event: Event): void {
    event.preventDefault();
    const name = this.name().trim();
    const teamId = Number(this.teamId());
    if (!name || !teamId) {
      return;
    }
    this.save.run(this.api.create({ name, teamId }), {
      next: (project) => {
        this.name.set('');
        this.teamId.set('');
        this.creating.set(false);
        this.created.set(project.name);
        this.load();
      },
    });
  }

  /** Context from the record the server returned, not a policy decision. */
  protected role(project: ProjectSummary): string {
    return this.currentUser.isMe(project.projectOwner.id) ? 'Owner' : 'Team project';
  }
}
