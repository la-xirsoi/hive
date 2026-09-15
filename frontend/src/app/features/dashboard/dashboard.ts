import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ProjectApi } from '../../core/api/project-api';
import { TaskApi } from '../../core/api/task-api';
import { TeamApi } from '../../core/api/team-api';
import { Page, ProjectSummary, TaskSummary, TeamSummary } from '../../core/api/models';
import { HiveCard, HivePageHeader } from '../../shared/ui';
import { CurrentUser } from '../shared/current-user';
import { HiveLoadState } from '../shared/load-state';
import { loadable } from '../shared/loadable';
import { HiveTaskList } from '../shared/task-list';

/**
 * The landing page for every role - `spec.md` Common Needs: "All Users need to
 * be able to see a list of their Projects and Teams. They need to be able to see
 * all Tasks assigned to them."
 *
 * Four regions load independently so one failing endpoint cannot blank the page,
 * and each carries its own loading, empty and error state.
 *
 * The unassigned queue is placed FIRST and given the gold accent because
 * `spec.md` R02 says unassigned tasks "should be brought to the Team Lead's
 * attention; assigning these are a priority". The server decides who has one:
 * `GET /tasks/unassigned` returns the `Todo`, unassigned tasks of every team the
 * caller leads and an empty page for everyone else (UQ-1), so the queue is not
 * gated on any client-side role check. It is hidden only in the one case where
 * it would be noise: a user who leads no team and has an empty queue.
 */
@Component({
  selector: 'app-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, HiveCard, HivePageHeader, HiveLoadState, HiveTaskList],
  template: `
    <hive-page-header
      heading="Dashboard"
      [eyebrow]="greeting()"
      subtitle="Your work, your teams and the projects you can see."
    />

    <div class="dashboard">
      @if (showQueue()) {
        <hive-card accent padding="lg" data-testid="unassigned-queue">
          <div hive-card-header class="dashboard__section-head">
            <h2 class="dashboard__title">Unassigned tasks</h2>
            <p class="dashboard__hint">Waiting for a team lead. Assigning these is a priority.</p>
          </div>

          <hive-load-state
            [loading]="unassigned.isLoading()"
            [error]="unassigned.errorMessage()"
            [empty]="unassigned.isReady() && unassignedTasks().length === 0"
            loadingLabel="Loading the unassigned queue"
            emptyHeading="The queue is clear"
            emptyMessage="Every task in the teams you lead has an assignee."
            emptyGlyph="✓"
            (retried)="loadUnassigned()"
          >
            <hive-task-list [tasks]="unassignedTasks()" listLabel="Unassigned tasks" />
          </hive-load-state>

          @if (unassignedTotal() > unassignedTasks().length) {
            <a hive-card-footer class="dashboard__more" routerLink="/tasks/unassigned">
              View all {{ unassignedTotal() }} unassigned tasks
            </a>
          }
        </hive-card>
      }

      <hive-card padding="lg" data-testid="my-tasks">
        <div hive-card-header class="dashboard__section-head">
          <h2 class="dashboard__title">Assigned to me</h2>
          <a class="dashboard__more" routerLink="/tasks">See all my tasks</a>
        </div>

        <hive-load-state
          [loading]="assigned.isLoading()"
          [error]="assigned.errorMessage()"
          [empty]="assigned.isReady() && assignedTasks().length === 0"
          loadingLabel="Loading tasks assigned to me"
          emptyHeading="No tasks are assigned to you"
          emptyMessage="When a team lead assigns you work it will appear here."
          (retried)="loadAssigned()"
        >
          <hive-task-list [tasks]="assignedTasks()" listLabel="Tasks assigned to me" />
        </hive-load-state>
      </hive-card>

      <div class="dashboard__columns">
        <hive-card padding="lg" data-testid="my-teams">
          <div hive-card-header class="dashboard__section-head">
            <h2 class="dashboard__title">My teams</h2>
            <a class="dashboard__more" routerLink="/teams">Manage teams</a>
          </div>

          <hive-load-state
            [loading]="teams.isLoading()"
            [error]="teams.errorMessage()"
            [empty]="teams.isReady() && teamList().length === 0"
            loadingLabel="Loading my teams"
            emptyHeading="You are not in a team yet"
            emptyMessage="Create a team and you become its lead."
            (retried)="loadTeams()"
          >
            <ul class="dashboard__list" aria-label="My teams">
              @for (team of teamList(); track team.id) {
                <li class="dashboard__item">
                  <a class="dashboard__item-link" [routerLink]="['/teams', team.id]">
                    {{ team.name }}
                  </a>
                  <p class="dashboard__meta">
                    <span class="dashboard__role">{{ roleInTeam(team) }}</span>
                    &middot; {{ team.memberCount }} members &middot; led by
                    {{ team.teamLead.name }}
                  </p>
                </li>
              }
            </ul>
          </hive-load-state>

          <a hive-card-footer class="dashboard__more" routerLink="/teams">Create a team</a>
        </hive-card>

        <hive-card padding="lg" data-testid="my-projects">
          <div hive-card-header class="dashboard__section-head">
            <h2 class="dashboard__title">My projects</h2>
            <a class="dashboard__more" routerLink="/projects">Manage projects</a>
          </div>

          <hive-load-state
            [loading]="projects.isLoading()"
            [error]="projects.errorMessage()"
            [empty]="projects.isReady() && projectList().length === 0"
            loadingLabel="Loading my projects"
            emptyHeading="No projects yet"
            emptyMessage="Create a project on one of your teams to start tracking work."
            (retried)="loadProjects()"
          >
            <ul class="dashboard__list" aria-label="My projects">
              @for (project of projectList(); track project.id) {
                <li class="dashboard__item">
                  <a class="dashboard__item-link" [routerLink]="['/projects', project.id]">
                    {{ project.name }}
                  </a>
                  <p class="dashboard__meta">
                    <span class="dashboard__role">{{ roleInProject(project) }}</span>
                    &middot; {{ project.team.name }} &middot; owned by
                    {{ project.projectOwner.name }}
                  </p>
                </li>
              }
            </ul>
          </hive-load-state>
        </hive-card>
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    .dashboard {
      display: flex;
      flex-direction: column;
      gap: var(--hive-space-6);
    }

    .dashboard__columns {
      display: grid;
      gap: var(--hive-space-6);
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 20rem), 1fr));
    }

    .dashboard__section-head {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--hive-space-3);
      width: 100%;
    }

    .dashboard__title {
      margin: 0;
      font-size: var(--hive-font-size-lg);
      font-weight: var(--hive-font-weight-bold);
      color: var(--hive-color-text);
    }

    .dashboard__hint,
    .dashboard__meta {
      margin: 0;
      font-size: var(--hive-font-size-xs);
      color: var(--hive-color-text-secondary);
    }

    .dashboard__meta {
      margin-top: var(--hive-space-1);
    }

    .dashboard__role {
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-color-text);
    }

    .dashboard__more {
      font-size: var(--hive-font-size-sm);
      font-weight: var(--hive-font-weight-semibold);
    }

    .dashboard__list {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .dashboard__item {
      padding: var(--hive-space-3) 0;
      border-bottom: 1px solid var(--hive-color-border-subtle);
    }

    .dashboard__item:last-child {
      border-bottom: none;
    }

    .dashboard__item-link {
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-color-text);
      text-decoration: none;
    }

    .dashboard__item-link:hover {
      text-decoration: underline;
    }
  `,
})
export class DashboardPage {
  private readonly taskApi = inject(TaskApi);
  private readonly teamApi = inject(TeamApi);
  private readonly projectApi = inject(ProjectApi);
  private readonly currentUser = inject(CurrentUser);

  protected readonly assigned = loadable<Page<TaskSummary>>();
  protected readonly unassigned = loadable<Page<TaskSummary>>();
  protected readonly teams = loadable<readonly TeamSummary[]>();
  protected readonly projects = loadable<readonly ProjectSummary[]>();

  protected readonly assignedTasks = computed(() => this.assigned.value()?.content ?? []);
  protected readonly unassignedTasks = computed(() => this.unassigned.value()?.content ?? []);
  protected readonly unassignedTotal = computed(() => this.unassigned.value()?.totalElements ?? 0);
  protected readonly teamList = computed(() => this.teams.value() ?? []);
  protected readonly projectList = computed(() => this.projects.value() ?? []);

  protected readonly greeting = computed(() => {
    const name = this.currentUser.user()?.name;
    return name ? `Welcome back, ${name}` : 'Welcome back';
  });

  /** True when the acting user leads at least one of the teams they are in. */
  private readonly leadsATeam = computed(() =>
    this.teamList().some((team) => this.currentUser.isMe(team.teamLead.id)),
  );

  protected readonly showQueue = computed(
    () =>
      this.unassigned.isLoading() ||
      this.unassigned.hasError() ||
      this.unassignedTasks().length > 0 ||
      this.leadsATeam(),
  );

  constructor() {
    this.currentUser.load().subscribe({ error: () => undefined });
    this.loadUnassigned();
    this.loadAssigned();
    this.loadTeams();
    this.loadProjects();
  }

  protected loadUnassigned(): void {
    this.unassigned.load(this.taskApi.listUnassigned({ size: 10 }));
  }

  protected loadAssigned(): void {
    this.assigned.load(this.taskApi.listAssignedToMe({ size: 10 }));
  }

  protected loadTeams(): void {
    this.teams.load(this.teamApi.listMine());
  }

  protected loadProjects(): void {
    this.projects.load(this.projectApi.listMine());
  }

  /** Role context from the record the server already returned - not a policy call. */
  protected roleInTeam(team: TeamSummary): string {
    return this.currentUser.isMe(team.teamLead.id) ? 'Team lead' : 'Member';
  }

  protected roleInProject(project: ProjectSummary): string {
    return this.currentUser.isMe(project.projectOwner.id) ? 'Owner' : 'Team project';
  }
}
