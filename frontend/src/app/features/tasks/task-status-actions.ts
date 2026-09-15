import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { TERMINAL_TASK_STATUSES, TaskStatus } from '../../core/api/models';
import { HiveButton, HiveStatusBadge } from '../../shared/ui';

/**
 * Wording for a transition. Presentation only: which entries are OFFERED comes
 * entirely from `allowedTransitions`, never from this table.
 */
const TRANSITION_LABELS: Record<TaskStatus, string> = {
  Draft: 'Return to Draft',
  Todo: 'Publish to Todo',
  'In Progress': 'Start work',
  Completed: 'Mark complete',
  Canceled: 'Cancel task',
};

/**
 * The status controls of a task.
 *
 * THE CONTROL SURFACE IS THE SERVER'S ANSWER, NOT A LOCAL DEDUCTION. The buttons
 * are a one-to-one rendering of `TaskDetail.permissions.allowedTransitions`,
 * which the server computes from the same policy it enforces with
 * (api-contract.md section 1.2). Nothing here knows that a project owner may
 * publish a Draft or that only the assignee may start work; if the server says
 * the actor may move the task to `In Progress`, that button appears, and if it
 * does not, the button does not exist in the DOM at all.
 *
 * `allowedTransitions` is empty for terminal tasks and for actors with no
 * transition rights. Those two cases read very differently to a user, so they
 * are worded differently: a terminal task is announced as finished and
 * read-only (`spec.md`: tasks "cannot be edited... once they are in a terminal
 * state"), while a live task the actor cannot move says so plainly.
 */
@Component({
  selector: 'hive-task-status-actions',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [HiveButton, HiveStatusBadge],
  template: `
    <section class="status" aria-labelledby="task-status-heading">
      <div class="status__head">
        <h2 class="status__heading" id="task-status-heading">Status</h2>
        <hive-status-badge size="lg" [status]="status()" />
      </div>

      @if (terminal()) {
        <p class="status__readonly" data-testid="terminal-notice">
          <span class="status__readonly-mark" aria-hidden="true">&#128274;</span>
          This task is {{ status() }}. It is read-only: the title, description, status and assignee
          can no longer be changed.
        </p>
      } @else if (transitions().length === 0) {
        <p class="status__note" data-testid="no-transitions">
          You cannot change the status of this task.
        </p>
      } @else {
        <div class="status__actions" role="group" aria-label="Change status">
          @for (target of transitions(); track target) {
            <hive-button
              size="sm"
              [variant]="target === 'Canceled' ? 'danger' : 'primary'"
              [disabled]="busy()"
              [attr.data-transition]="target"
              (clicked)="requested.emit(target)"
            >
              {{ label(target) }}
            </hive-button>
          }
        </div>
      }
    </section>
  `,
  styles: `
    :host {
      display: block;
    }

    .status__head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--hive-space-3);
    }

    .status__heading {
      margin: 0;
      font-size: var(--hive-font-size-lg);
      font-weight: var(--hive-font-weight-bold);
      color: var(--hive-color-text);
    }

    .status__actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--hive-space-3);
      margin-top: var(--hive-space-4);
    }

    .status__readonly {
      display: flex;
      align-items: flex-start;
      gap: var(--hive-space-2);
      margin: var(--hive-space-4) 0 0;
      padding: var(--hive-space-3) var(--hive-space-4);
      font-size: var(--hive-font-size-sm);
      color: var(--hive-color-text);
      background-color: var(--hive-color-surface-sunken);
      border-left: 4px solid var(--hive-color-border-strong);
      border-radius: var(--hive-radius-sm);
    }

    .status__note {
      margin: var(--hive-space-4) 0 0;
      font-size: var(--hive-font-size-sm);
      color: var(--hive-color-text-secondary);
    }
  `,
})
export class HiveTaskStatusActions {
  readonly status = input.required<TaskStatus>();
  /** Straight from `TaskPermissions.allowedTransitions`. */
  readonly transitions = input.required<readonly TaskStatus[]>();
  readonly busy = input(false);

  readonly requested = output<TaskStatus>();

  protected readonly terminal = computed(() => TERMINAL_TASK_STATUSES.includes(this.status()));

  protected label(target: TaskStatus): string {
    return TRANSITION_LABELS[target];
  }
}
