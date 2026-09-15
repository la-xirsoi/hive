import { ChangeDetectionStrategy, Component, booleanAttribute, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TaskSummary } from '../../core/api/models';
import { HiveStatusBadge } from '../../shared/ui';

/**
 * A list of tasks, used by the dashboard, the unassigned queue and a project's
 * task table.
 *
 * Purely presentational: it takes the rows it is given and renders a link per
 * task. Status is carried by `<hive-status-badge>`, which pairs colour with a
 * text label and a shape glyph, so the state is never colour-only. "Unassigned"
 * is rendered as words for the same reason.
 */
@Component({
  selector: 'hive-task-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, HiveStatusBadge],
  template: `
    <ul class="task-list" [attr.aria-label]="listLabel()">
      @for (task of tasks(); track task.id) {
        <li class="task-list__row" [attr.data-task-id]="task.id">
          <div class="task-list__main">
            <a class="task-list__link" [routerLink]="['/tasks', task.id]">{{ task.name }}</a>
            @if (showProject()) {
              <p class="task-list__project">{{ task.projectName }}</p>
            }
          </div>
          <hive-status-badge class="task-list__status" [status]="task.status" />
          <p class="task-list__assignee">
            @if (task.assignee; as assignee) {
              <span class="hive-sr-only">Assigned to </span>{{ assignee.name }}
            } @else {
              <span class="task-list__unassigned">Unassigned</span>
            }
          </p>
        </li>
      }
    </ul>
  `,
  styles: `
    :host {
      display: block;
    }

    .task-list {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .task-list__row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--hive-space-3);
      padding: var(--hive-space-3) 0;
      border-bottom: 1px solid var(--hive-color-border-subtle);
    }

    .task-list__row:last-child {
      border-bottom: none;
    }

    .task-list__main {
      flex: 1 1 16rem;
      min-width: 0;
    }

    .task-list__link {
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-color-text);
      text-decoration: none;
    }

    .task-list__link:hover {
      text-decoration: underline;
    }

    .task-list__link:focus-visible {
      outline: none;
      box-shadow: var(--hive-focus-ring);
      border-radius: var(--hive-radius-xs);
    }

    .task-list__project {
      margin: var(--hive-space-1) 0 0;
      font-size: var(--hive-font-size-xs);
      color: var(--hive-color-text-secondary);
    }

    .task-list__assignee {
      flex: 0 0 auto;
      margin: 0;
      min-width: 9rem;
      font-size: var(--hive-font-size-sm);
      color: var(--hive-color-text-secondary);
    }

    .task-list__unassigned {
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-color-text);
    }
  `,
})
export class HiveTaskList {
  readonly tasks = input.required<readonly TaskSummary[]>();
  /** Show the owning project under each task name. */
  readonly showProject = input(true, { transform: booleanAttribute });
  /** Accessible name for the list, e.g. "Tasks assigned to me". */
  readonly listLabel = input<string | null>(null);
}
