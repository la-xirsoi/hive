import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { TaskApi } from '../../core/api/task-api';
import { Page, TaskSummary } from '../../core/api/models';
import { HiveButton, HiveCard, HivePageHeader } from '../../shared/ui';
import { HiveLoadState } from '../shared/load-state';
import { loadable } from '../shared/loadable';
import { HiveTaskList } from '../shared/task-list';

/**
 * The unassigned queue (`GET /tasks/unassigned`).
 *
 * `spec.md` R02: "Unassigned Tasks should be brought to the Team Lead's
 * attention; assigning these are a priority." UQ-1 makes the server the arbiter
 * of what belongs here: for each team the caller leads, the `Todo` tasks in that
 * team's projects with no assignee - `Draft` excluded (leads cannot see them,
 * VIS-3) and terminal tasks excluded.
 *
 * Nothing on this screen asks "is this user a team lead?". A user who leads no
 * team gets an empty page from the server and the empty state explains why,
 * which is both simpler and impossible to get wrong.
 */
@Component({
  selector: 'app-unassigned-queue',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [HiveButton, HiveCard, HivePageHeader, HiveLoadState, HiveTaskList],
  template: `
    <hive-page-header
      heading="Unassigned tasks"
      eyebrow="Needs a lead's attention"
      subtitle="Published work in the teams you lead that nobody is holding yet. Assigning these is a priority."
    />

    <hive-card padding="lg" accent>
      <hive-load-state
        [loading]="tasks.isLoading()"
        [error]="tasks.errorMessage()"
        [empty]="tasks.isReady() && list().length === 0"
        loadingLabel="Loading the unassigned queue"
        emptyHeading="The queue is clear"
        emptyMessage="Every published task in the teams you lead has an assignee. If you do not lead a team, this queue is always empty."
        emptyGlyph="✓"
        (retried)="load()"
      >
        <hive-task-list [tasks]="list()" listLabel="Unassigned tasks" />
      </hive-load-state>

      @if (totalPages() > 1) {
        <nav hive-card-footer class="queue__pager" aria-label="Queue pages">
          <hive-button
            size="sm"
            variant="secondary"
            [disabled]="page() === 0"
            data-testid="previous-page"
            (clicked)="goTo(page() - 1)"
          >
            Previous
          </hive-button>
          <span aria-live="polite">
            Page {{ page() + 1 }} of {{ totalPages() }} &middot; {{ total() }} waiting
          </span>
          <hive-button
            size="sm"
            variant="secondary"
            [disabled]="page() + 1 >= totalPages()"
            data-testid="next-page"
            (clicked)="goTo(page() + 1)"
          >
            Next
          </hive-button>
        </nav>
      }
    </hive-card>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: var(--hive-space-6);
    }

    .queue__pager {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--hive-space-3);
      font-size: var(--hive-font-size-sm);
      color: var(--hive-color-text-secondary);
    }
  `,
})
export class UnassignedQueuePage {
  private readonly api = inject(TaskApi);

  protected readonly tasks = loadable<Page<TaskSummary>>();
  protected readonly page = signal(0);

  protected readonly list = computed(() => this.tasks.value()?.content ?? []);
  protected readonly totalPages = computed(() => this.tasks.value()?.totalPages ?? 0);
  protected readonly total = computed(() => this.tasks.value()?.totalElements ?? 0);

  constructor() {
    this.load();
  }

  protected load(): void {
    this.tasks.load(this.api.listUnassigned({ page: this.page(), size: 20 }));
  }

  protected goTo(page: number): void {
    this.page.set(Math.max(0, page));
    this.load();
  }
}
