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
import { TaskApi } from '../../core/api/task-api';
import { TERMINAL_TASK_STATUSES, TaskDetail, TaskStatus } from '../../core/api/models';
import {
  HiveButton,
  HiveCard,
  HiveFormField,
  HivePageHeader,
  HiveStatusBadge,
  HiveToast,
  HiveUserChip,
} from '../../shared/ui';
import { inputValue } from '../shared/dom';
import { HiveLoadState } from '../shared/load-state';
import { loadable } from '../shared/loadable';
import { mutation } from '../shared/mutation';
import { HiveCommentThread } from './comment-thread';
import { HiveTaskAssignment } from './task-assignment';
import { HiveTaskStatusActions } from './task-status-actions';

/**
 * The task workspace: one task, everything known about it, and exactly the
 * controls the acting user is allowed to operate.
 *
 * THE CONTROL SURFACE IS `TaskPermissions`, FULL STOP. `GET /tasks/{id}` returns
 * a `permissions` block computed server-side from the same policy the server
 * enforces with (api-contract.md section 1.2), and this component is a direct
 * rendering of it:
 *
 * | Field | Renders |
 * |-------|---------|
 * | `allowedTransitions` | one button per entry, and nothing else |
 * | `canEdit`            | the title/description form (TK-3, blocked in terminal states by TK-4) |
 * | `canAssign`          | the assignment panel (AS-1) |
 * | `canComment`         | the add-comment form (CM-1; true even on terminal tasks, TE-4) |
 *
 * There is no role check anywhere in this file. Nothing asks whether the viewer
 * owns the project or leads the team; the server already answered, and a control
 * whose flag is false is **absent from the DOM**, not disabled - so it cannot be
 * re-enabled from the console and never appears in the tab order.
 *
 * STALENESS. Permissions are a snapshot of the moment the task was read. If the
 * task moves underneath the viewer - published, completed, canceled, reassigned
 * - the next write returns **409 Conflict**. Every mutation here routes that
 * case through {@link Mutation}, which shows the server's explanation under a
 * "this changed underneath you" heading and triggers a re-read, so the screen
 * corrects itself to the new reality (and its new permissions) in one step.
 */
@Component({
  selector: 'app-task-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    HiveButton,
    HiveCard,
    HiveFormField,
    HivePageHeader,
    HiveStatusBadge,
    HiveToast,
    HiveUserChip,
    HiveLoadState,
    HiveCommentThread,
    HiveTaskAssignment,
    HiveTaskStatusActions,
  ],
  template: `
    <hive-load-state
      [loading]="task.isLoading() && task.value() === null"
      [error]="task.errorMessage()"
      loadingLabel="Loading the task"
      (retried)="load()"
    >
      @if (task.value(); as detail) {
        <hive-page-header [heading]="detail.name" eyebrow="Task" [subtitle]="detail.description">
          <div hive-page-header-meta class="task__meta">
            <hive-status-badge [status]="detail.status" />
            <a class="task__link" [routerLink]="['/projects', detail.project.id]">
              {{ detail.project.name }}
            </a>
            <span class="hive-text-caption">created by {{ detail.creator.name }}</span>
            @if (detail.assignee; as assignee) {
              <hive-user-chip size="sm" [name]="assignee.name" />
            } @else {
              <span class="task__unassigned">Unassigned</span>
            }
          </div>
        </hive-page-header>

        @if (save.errorMessage(); as message) {
          <hive-toast
            variant="error"
            [heading]="save.heading()"
            dismissible
            data-testid="task-error"
            (dismissed)="save.clear()"
          >
            {{ message }}
          </hive-toast>
        }

        <div class="task" [class.task--readonly]="terminal()">
          <hive-card padding="lg">
            <hive-task-status-actions
              [status]="detail.status"
              [transitions]="detail.permissions.allowedTransitions"
              [busy]="save.busy()"
              (requested)="changeStatus($event)"
            />
          </hive-card>

          @if (detail.permissions.canEdit) {
            <hive-card padding="lg" data-testid="edit-card">
              <h2 hive-card-header class="task__title">Edit the task</h2>
              <form class="task__form" (submit)="saveEdits($event)">
                <hive-form-field label="Title" required fieldId="task-title">
                  <input
                    class="hive-input"
                    id="task-title"
                    name="name"
                    data-testid="task-title"
                    [value]="name()"
                    (input)="name.set(value($event))"
                  />
                </hive-form-field>
                <hive-form-field label="Description" required fieldId="task-description">
                  <textarea
                    class="hive-textarea"
                    id="task-description"
                    name="description"
                    rows="4"
                    data-testid="task-description"
                    [value]="description()"
                    (input)="description.set(value($event))"
                  ></textarea>
                </hive-form-field>
                <hive-button type="submit" [loading]="save.busy()" data-testid="save-task">
                  Save changes
                </hive-button>
              </form>
            </hive-card>
          }

          @if (detail.permissions.canAssign) {
            <hive-card padding="lg" data-testid="assignment-card">
              <hive-task-assignment
                [task]="detail"
                [busy]="save.busy()"
                (assigned)="assign($event)"
              />
            </hive-card>
          }

          <hive-card padding="lg">
            <hive-comment-thread
              [taskId]="detail.id"
              [canComment]="detail.permissions.canComment"
            />
          </hive-card>
        </div>
      }
    </hive-load-state>
  `,
  styles: `
    :host {
      display: block;
    }

    .task {
      display: flex;
      flex-direction: column;
      gap: var(--hive-space-6);
      margin-top: var(--hive-space-6);
    }

    /*
     * A terminal task is read-only, and that has to be visible without reading
     * the banner: the whole workspace is desaturated and given a quiet left
     * rule. Colour is never the only signal - the banner says so in words, and
     * the status badge carries its own label and glyph.
     */
    .task--readonly {
      filter: saturate(0.55);
      border-left: 4px solid var(--hive-color-border-strong);
      padding-left: var(--hive-space-4);
    }

    .task__meta {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--hive-space-3);
    }

    .task__title {
      margin: 0;
      font-size: var(--hive-font-size-lg);
      font-weight: var(--hive-font-weight-bold);
      color: var(--hive-color-text);
    }

    .task__form {
      display: flex;
      flex-direction: column;
      gap: var(--hive-space-3);
      align-items: stretch;
    }

    .task__form hive-button {
      align-self: flex-start;
    }

    .task__link {
      font-weight: var(--hive-font-weight-semibold);
      font-size: var(--hive-font-size-sm);
    }

    .task__unassigned {
      font-size: var(--hive-font-size-sm);
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-color-text);
    }
  `,
})
export class TaskDetailPage {
  private readonly api = inject(TaskApi);

  /** Bound from the `:id` route parameter by `withComponentInputBinding`. */
  readonly id = input.required<string>();

  protected readonly task = loadable<TaskDetail>();
  protected readonly save = mutation();
  protected readonly name = signal('');
  protected readonly description = signal('');
  protected readonly value = inputValue;

  /**
   * Presentation only. The *controls* come from `allowedTransitions`, which the
   * server leaves empty for terminal tasks; this drives the read-only styling of
   * the workspace as a whole (`spec.md`: a terminal task "cannot be edited").
   */
  protected readonly terminal = computed(() => {
    const status = this.task.value()?.status;
    return status !== undefined && TERMINAL_TASK_STATUSES.includes(status);
  });

  constructor() {
    effect(() => {
      this.id();
      untracked(() => this.load());
    });
    // Keep the edit fields showing the stored values. Re-runs only when a new
    // TaskDetail arrives, so it never clobbers typing mid-edit.
    effect(() => {
      const detail = this.task.value();
      if (detail) {
        this.name.set(detail.name);
        this.description.set(detail.description);
      }
    });
  }

  private taskId(): number {
    return Number(this.id());
  }

  protected load(): void {
    this.task.load(this.api.getById(this.taskId()));
  }

  /**
   * Re-read after a 409. `keepValue` holds the stale record on screen while the
   * fresh one is fetched, so the conflict message the user needs to read stays
   * visible instead of being replaced by a spinner.
   */
  private refresh(): void {
    this.task.load(this.api.getById(this.taskId()), { keepValue: true });
  }

  /** `target` is one of the entries the server put in `allowedTransitions`. */
  protected changeStatus(target: TaskStatus): void {
    this.save.run(this.api.updateStatus(this.taskId(), { status: target }), {
      next: (detail) => this.task.set(detail),
      conflict: () => this.refresh(),
    });
  }

  protected saveEdits(event: Event): void {
    event.preventDefault();
    const name = this.name().trim();
    const description = this.description().trim();
    if (!name || !description) {
      return;
    }
    this.save.run(this.api.update(this.taskId(), { name, description }), {
      next: (detail) => this.task.set(detail),
      conflict: () => this.refresh(),
    });
  }

  /** `null` unassigns, which AS-7 permits only from `Todo`; a 409 explains. */
  protected assign(userId: number | null): void {
    this.save.run(this.api.updateAssignee(this.taskId(), { userId }), {
      next: (detail) => this.task.set(detail),
      conflict: () => this.refresh(),
    });
  }
}
