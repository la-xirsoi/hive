import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { ProjectApi } from '../../core/api/project-api';
import { TaskApi } from '../../core/api/task-api';
import {
  TASK_STATUSES,
  Page,
  ProjectSummary,
  TaskStatus,
  TaskSummary,
} from '../../core/api/models';
import { UserSummary } from '../../core/api/models';
import {
  HiveButton,
  HiveCard,
  HiveFormField,
  HivePageHeader,
  HiveToast,
  HiveUserChip,
} from '../../shared/ui';
import { CurrentUser } from '../shared/current-user';
import { inputValue } from '../shared/dom';
import { HiveLoadState } from '../shared/load-state';
import { loadable } from '../shared/loadable';
import { mutation } from '../shared/mutation';
import { HiveTaskList } from '../shared/task-list';
import { HiveUserSearch } from '../shared/user-search';

/**
 * One project: its team, its owner, its tasks, and - for its owner - the
 * controls that change them.
 *
 * WHICH CONTROLS APPEAR. Every control here is gated on
 * `ProjectSummary.permissions`, computed by the same domain policy that
 * enforces TK-1, PR-5 and PR-6, exactly as the task screens are gated on
 * `TaskPermissions`. Nothing on this screen re-derives a rule or compares ids
 * to decide what may be done.
 *
 * Transfer of ownership can fail with a **409** when the incoming owner is the
 * assignee of live tasks here (PR-8) - completing it would break AS-4. That
 * message is rendered verbatim, and the screen re-reads itself so the owner can
 * see the tasks that have to be reassigned first.
 */
@Component({
  selector: 'app-project-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    HiveButton,
    HiveCard,
    HiveFormField,
    HivePageHeader,
    HiveToast,
    HiveUserChip,
    HiveLoadState,
    HiveTaskList,
    HiveUserSearch,
  ],
  template: `
    <hive-load-state
      [loading]="project.isLoading() && project.value() === null"
      [error]="project.errorMessage()"
      loadingLabel="Loading the project"
      (retried)="load()"
    >
      @if (project.value(); as detail) {
        <hive-page-header
          [heading]="detail.name"
          eyebrow="Project"
          [subtitle]="'Worked by ' + detail.team.name"
        >
          <div hive-page-header-meta class="project__owner">
            <span class="hive-text-caption">Project owner</span>
            <hive-user-chip size="sm" [name]="detail.projectOwner.name" />
            <a class="project__more" [routerLink]="['/teams', detail.team.id]">View the team</a>
          </div>
        </hive-page-header>

        @if (save.errorMessage(); as message) {
          <hive-toast
            variant="error"
            [heading]="save.heading()"
            dismissible
            (dismissed)="save.clear()"
          >
            {{ message }}
          </hive-toast>
        }

        @if (createdTask(); as taskName) {
          <hive-toast variant="success" dismissible (dismissed)="createdTask.set(null)">
            {{ taskName }} was created as a Draft. Open it to publish it to Todo.
          </hive-toast>
        }

        <div class="project">
          @if (showOwnerControls()) {
            <hive-card padding="lg" accent data-testid="owner-controls">
              <h2 hive-card-header class="project__title">Owner controls</h2>

              @if (canCreateTask()) {
                <section class="project__section" aria-labelledby="project-task-heading">
                  <h3 class="project__subtitle" id="project-task-heading">Create a task</h3>
                  <p class="hive-text-secondary">
                    New tasks start as Draft with no assignee. Publish one to Todo to make it
                    visible to the team and available for assignment.
                  </p>
                  <form class="project__form project__form--stacked" (submit)="createTask($event)">
                    <hive-form-field label="Task name" required>
                      <input
                        class="hive-input"
                        name="taskName"
                        data-testid="task-name"
                        [value]="taskName()"
                        (input)="taskName.set(value($event))"
                      />
                    </hive-form-field>
                    <hive-form-field label="Description" required>
                      <textarea
                        class="hive-textarea"
                        name="taskDescription"
                        rows="3"
                        data-testid="task-description"
                        [value]="taskDescription()"
                        (input)="taskDescription.set(value($event))"
                      ></textarea>
                    </hive-form-field>
                    <hive-button type="submit" [loading]="save.busy()" data-testid="create-task">
                      Create task
                    </hive-button>
                  </form>
                </section>
              }

              @if (canRename()) {
                <section class="project__section" aria-labelledby="project-rename-heading">
                  <h3 class="project__subtitle" id="project-rename-heading">Rename the project</h3>
                  <form class="project__form" (submit)="rename($event)">
                    <hive-form-field label="Project name" required>
                      <input
                        class="hive-input"
                        name="name"
                        data-testid="rename-input"
                        [value]="name()"
                        (input)="name.set(value($event))"
                      />
                    </hive-form-field>
                    <hive-button type="submit" variant="secondary" [loading]="save.busy()">
                      Rename
                    </hive-button>
                  </form>
                </section>
              }

              @if (canTransferOwnership()) {
                <section class="project__section" aria-labelledby="project-transfer-heading">
                  <h3 class="project__subtitle" id="project-transfer-heading">
                    Transfer ownership
                  </h3>
                  <p class="hive-text-secondary">
                    The new owner may be any Hive user; they do not have to be on
                    {{ detail.team.name }}. You keep your team membership.
                  </p>
                  <hive-user-search
                    label="Find the new owner"
                    selectLabel="Make owner"
                    [excludeIds]="[detail.projectOwner.id]"
                    [busy]="save.busy()"
                    (picked)="transferOwner($event)"
                  />
                </section>
              }
            </hive-card>
          }

          <hive-card padding="lg">
            <div hive-card-header class="project__head">
              <h2 class="project__title">Tasks</h2>
              <hive-form-field label="Filter by status" fieldId="project-task-status">
                <select
                  class="hive-select"
                  id="project-task-status"
                  name="status"
                  data-testid="status-filter"
                  [value]="statusFilter()"
                  (change)="setStatus(value($event))"
                >
                  <option value="">All statuses I can see</option>
                  @for (status of statuses; track status) {
                    <option [value]="status">{{ status }}</option>
                  }
                </select>
              </hive-form-field>
            </div>

            <hive-load-state
              [loading]="tasks.isLoading()"
              [error]="tasks.errorMessage()"
              [empty]="tasks.isReady() && taskList().length === 0"
              loadingLabel="Loading the project's tasks"
              emptyHeading="No tasks to show"
              [emptyMessage]="emptyTaskMessage()"
              (retried)="loadTasks()"
            >
              <hive-task-list
                [tasks]="taskList()"
                [showProject]="false"
                listLabel="Tasks in this project"
              />
            </hive-load-state>
          </hive-card>
        </div>
      }
    </hive-load-state>
  `,
  styles: `
    :host {
      display: block;
    }

    .project {
      display: flex;
      flex-direction: column;
      gap: var(--hive-space-6);
      margin-top: var(--hive-space-6);
    }

    .project__owner {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--hive-space-3);
    }

    .project__title {
      margin: 0;
      font-size: var(--hive-font-size-lg);
      font-weight: var(--hive-font-weight-bold);
      color: var(--hive-color-text);
    }

    .project__subtitle {
      margin: 0 0 var(--hive-space-2);
      font-size: var(--hive-font-size-md);
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-color-text);
    }

    .project__head {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      justify-content: space-between;
      gap: var(--hive-space-3);
      width: 100%;
    }

    .project__section {
      padding-top: var(--hive-space-5);
      margin-top: var(--hive-space-5);
      border-top: 1px solid var(--hive-color-border-subtle);
    }

    .project__section:first-of-type {
      padding-top: 0;
      margin-top: 0;
      border-top: none;
    }

    .project__form {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: var(--hive-space-3);
      margin-top: var(--hive-space-3);
    }

    .project__form hive-form-field {
      flex: 1 1 16rem;
    }

    .project__form--stacked {
      flex-direction: column;
      align-items: stretch;
    }

    .project__more {
      font-size: var(--hive-font-size-sm);
      font-weight: var(--hive-font-weight-semibold);
    }
  `,
})
export class ProjectDetailPage {
  private readonly api = inject(ProjectApi);
  private readonly taskApi = inject(TaskApi);
  private readonly currentUser = inject(CurrentUser);

  /** Bound from the `:id` route parameter by `withComponentInputBinding`. */
  readonly id = input.required<string>();

  protected readonly statuses = TASK_STATUSES;
  protected readonly project = loadable<ProjectSummary>();
  protected readonly tasks = loadable<Page<TaskSummary>>();
  protected readonly save = mutation();

  protected readonly name = signal('');
  protected readonly taskName = signal('');
  protected readonly taskDescription = signal('');
  protected readonly statusFilter = signal('');
  protected readonly createdTask = signal<string | null>(null);
  protected readonly value = inputValue;

  protected readonly taskList = computed(() => this.tasks.value()?.content ?? []);

  /** TK-1, PR-5 and PR-6, as computed by the server for this caller. */
  protected readonly canCreateTask = computed(
    () => this.project.value()?.permissions.canCreateTask ?? false,
  );
  protected readonly canRename = computed(
    () => this.project.value()?.permissions.canRename ?? false,
  );
  protected readonly canTransferOwnership = computed(
    () => this.project.value()?.permissions.canTransferOwnership ?? false,
  );

  /** The card exists only if at least one of the controls inside it does. */
  protected readonly showOwnerControls = computed(
    () => this.canCreateTask() || this.canRename() || this.canTransferOwnership(),
  );

  protected readonly emptyTaskMessage = computed(() =>
    this.statusFilter()
      ? `No ${this.statusFilter()} tasks are visible to you in this project.`
      : 'No tasks in this project are visible to you yet.',
  );

  constructor() {
    effect(() => {
      const detail = this.project.value();
      if (detail) {
        this.name.set(detail.name);
      }
    });
    // An effect, not a direct call: `id` is a required input, readable only
    // once the router has bound it.
    effect(() => {
      this.id();
      // `untracked`, or the effect would also depend on the status filter that
      // `loadTasks` reads and re-fetch the project on every filter change.
      untracked(() => {
        this.load();
        this.loadTasks();
      });
    });
    // Not for gating - every control here is gated on the server's
    // `permissions` block. This is the US-3 provisioning call, which any screen
    // may be the first to make.
    this.currentUser.load().subscribe({ error: () => undefined });
  }

  private projectId(): number {
    return Number(this.id());
  }

  protected load(): void {
    this.project.load(this.api.getById(this.projectId()));
  }

  /**
   * Re-read after a 409. `keepValue` holds the stale record on screen so the
   * conflict message stays readable while the fresh one is fetched.
   */
  private refresh(): void {
    this.project.load(this.api.getById(this.projectId()), { keepValue: true });
  }

  protected loadTasks(): void {
    const status = this.statusFilter();
    this.tasks.load(
      this.api.listTasks(this.projectId(), status ? { status: [status as TaskStatus] } : undefined),
    );
  }

  protected setStatus(status: string): void {
    this.statusFilter.set(status);
    this.loadTasks();
  }

  protected rename(event: Event): void {
    event.preventDefault();
    const name = this.name().trim();
    if (!name) {
      return;
    }
    this.save.run(this.api.rename(this.projectId(), { name }), {
      next: (detail) => this.project.set(detail),
      conflict: () => this.refresh(),
    });
  }

  protected createTask(event: Event): void {
    event.preventDefault();
    const name = this.taskName().trim();
    const description = this.taskDescription().trim();
    if (!name || !description) {
      return;
    }
    this.save.run(this.taskApi.create({ projectId: this.projectId(), name, description }), {
      next: (task) => {
        this.taskName.set('');
        this.taskDescription.set('');
        this.createdTask.set(task.name);
        this.loadTasks();
      },
    });
  }

  protected transferOwner(user: UserSummary): void {
    this.save.run(this.api.transferOwner(this.projectId(), { userId: user.id }), {
      next: (detail) => this.project.set(detail),
      conflict: () => {
        this.refresh();
        this.loadTasks();
      },
    });
  }
}
