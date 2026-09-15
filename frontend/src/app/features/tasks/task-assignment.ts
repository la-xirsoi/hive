import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { TeamApi } from '../../core/api/team-api';
import { TaskDetail, TeamDetail, UserSummary } from '../../core/api/models';
import { HiveButton, HiveFormField, HiveSpinner, HiveToast, HiveUserChip } from '../../shared/ui';
import { inputValue } from '../shared/dom';
import { loadable } from '../shared/loadable';

/**
 * Assign or reassign one task.
 *
 * WHETHER THIS PANEL EXISTS AT ALL is the parent's decision, and the parent
 * makes it from `TaskPermissions.canAssign` - the server's answer, computed from
 * the same policy it enforces with (AS-1: the team lead of the project's team,
 * and nobody else). Nothing here inspects roles.
 *
 * WHO MAY BE CHOSEN is the part the contract leaves to the client, because there
 * is no "candidate assignees" endpoint. The rule has two halves and both are
 * applied to data the **server** returned:
 *
 *  - **AS-2**: the assignee must be a member of the project's team, so the list
 *    is exactly `GET /teams/{id}` members for `task.project.team.id` - never a
 *    directory search.
 *  - **AS-4**: the project owner may never be the assignee of a task in their
 *    own project, so `task.project.projectOwner.id` is filtered out. This holds
 *    even when the owner is a member of the team, and even when the owner is the
 *    lead doing the assigning - so the lead can genuinely be missing from their
 *    own candidate list.
 *
 * The current assignee is also filtered out: reassigning someone to themselves
 * is a no-op the server would have to reject. A lead may assign work to
 * themselves (AS-5), which needs no special case - they are a member of the team
 * they lead (INV-1), so they are simply in the list.
 */
@Component({
  selector: 'hive-task-assignment',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [HiveButton, HiveFormField, HiveSpinner, HiveToast, HiveUserChip],
  template: `
    <section class="assign" aria-labelledby="task-assign-heading">
      <h2 class="assign__heading" id="task-assign-heading">Assignment</h2>

      <p class="assign__current">
        @if (task().assignee; as assignee) {
          <span class="hive-text-caption">Currently assigned to</span>
          <hive-user-chip size="sm" [name]="assignee.name" [secondary]="assignee.email" />
        } @else {
          <span class="assign__unassigned" data-testid="currently-unassigned">
            Nobody is assigned to this task.
          </span>
        }
      </p>

      @if (members.isLoading()) {
        <hive-spinner label="Loading the team's members" />
      } @else if (members.errorMessage(); as message) {
        <hive-toast variant="error">{{ message }}</hive-toast>
      } @else if (candidates().length === 0) {
        <p class="assign__note" data-testid="no-candidates">
          No one on {{ task().project.team.name }} can take this task. The project owner cannot be
          assigned work in their own project, so add another member to the team first.
        </p>
      } @else {
        <form class="assign__form" (submit)="submit($event)">
          <hive-form-field
            label="Assign to"
            required
            hint="Members of {{ task().project.team.name }}, excluding the project owner."
          >
            <select
              class="hive-select"
              name="assignee"
              data-testid="assignee-select"
              [value]="chosen()"
              (change)="chosen.set(value($event))"
            >
              <option value="">Choose a member</option>
              @for (candidate of candidates(); track candidate.id) {
                <option [value]="candidate.id">{{ candidate.name }}</option>
              }
            </select>
          </hive-form-field>
          <hive-button
            type="submit"
            [loading]="busy()"
            [disabled]="!chosen()"
            data-testid="assign-task"
          >
            {{ task().assignee ? 'Reassign' : 'Assign' }}
          </hive-button>
        </form>
      }

      @if (task().assignee) {
        <hive-button
          variant="tertiary"
          size="sm"
          [disabled]="busy()"
          data-testid="unassign-task"
          (clicked)="assigned.emit(null)"
        >
          Unassign, returning it to the queue
        </hive-button>
      }
    </section>
  `,
  styles: `
    :host {
      display: block;
    }

    .assign__heading {
      margin: 0;
      font-size: var(--hive-font-size-lg);
      font-weight: var(--hive-font-weight-bold);
      color: var(--hive-color-text);
    }

    .assign__current {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--hive-space-2);
      margin: var(--hive-space-3) 0 0;
    }

    .assign__unassigned {
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-color-text);
    }

    .assign__form {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: var(--hive-space-3);
      margin-top: var(--hive-space-4);
    }

    .assign__form hive-form-field {
      flex: 1 1 16rem;
    }

    .assign__note {
      margin: var(--hive-space-4) 0 0;
      font-size: var(--hive-font-size-sm);
      color: var(--hive-color-text-secondary);
    }

    .assign > hive-button {
      margin-top: var(--hive-space-3);
    }
  `,
})
export class HiveTaskAssignment {
  private readonly teamApi = inject(TeamApi);

  readonly task = input.required<TaskDetail>();
  /** True while an assignment request is in flight. */
  readonly busy = input(false);

  /** The chosen user id, or `null` to unassign. */
  readonly assigned = output<number | null>();

  protected readonly members = loadable<TeamDetail>();
  protected readonly chosen = signal('');
  protected readonly value = inputValue;

  protected readonly candidates = computed<readonly UserSummary[]>(() => {
    const detail = this.members.value();
    if (!detail) {
      return [];
    }
    const task = this.task();
    const excluded = new Set<number>([task.project.projectOwner.id]);
    if (task.assignee) {
      excluded.add(task.assignee.id);
    }
    return detail.members.filter((member) => !excluded.has(member.id));
  });

  /**
   * Isolated so the effect below depends on the team id alone. Reading
   * `task()` there would re-fetch the roster every time the task object is
   * replaced - which happens after every status change and every assignment.
   */
  private readonly teamId = computed(() => this.task().project.team.id);

  constructor() {
    effect(() => {
      this.members.load(this.teamApi.getById(this.teamId()));
    });
  }

  protected submit(event: Event): void {
    event.preventDefault();
    const userId = Number(this.chosen());
    if (!userId) {
      return;
    }
    this.chosen.set('');
    this.assigned.emit(userId);
  }
}
