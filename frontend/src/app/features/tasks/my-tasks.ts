import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { TaskApi } from '../../core/api/task-api';
import { Page, TaskSummary } from '../../core/api/models';
import { HiveButton, HiveCard, HivePageHeader } from '../../shared/ui';
import { HiveLoadState } from '../shared/load-state';
import { loadable } from '../shared/loadable';
import { HiveTaskList } from '../shared/task-list';

/**
 * Every task assigned to the acting user (`GET /tasks/assigned-to-me`).
 *
 * `spec.md` Common Needs: "They need to be able to see all Tasks assigned to
 * them." VIS-1 plus VIS-5 make that literal - assigned tasks are visible in
 * **every** status, including ones that were later `Canceled` - so this list is
 * not filtered by status at all. The dashboard shows the first page of it; this
 * screen pages through the whole thing.
 */
@Component({
  selector: 'app-my-tasks',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [HiveButton, HiveCard, HivePageHeader, HiveLoadState, HiveTaskList],
  template: `
    <hive-page-header
      heading="My tasks"
      eyebrow="Assigned to me"
      subtitle="Everything assigned to you, in every status."
    />

    <hive-card padding="lg">
      <hive-load-state
        [loading]="tasks.isLoading()"
        [error]="tasks.errorMessage()"
        [empty]="tasks.isReady() && list().length === 0"
        loadingLabel="Loading tasks assigned to me"
        emptyHeading="No tasks are assigned to you"
        emptyMessage="When a team lead assigns you work it will appear here."
        (retried)="load()"
      >
        <hive-task-list [tasks]="list()" listLabel="Tasks assigned to me" />
      </hive-load-state>

      @if (totalPages() > 1) {
        <nav hive-card-footer class="my-tasks__pager" aria-label="Task pages">
          <hive-button
            size="sm"
            variant="secondary"
            [disabled]="page() === 0"
            data-testid="previous-page"
            (clicked)="goTo(page() - 1)"
          >
            Previous
          </hive-button>
          <span aria-live="polite">Page {{ page() + 1 }} of {{ totalPages() }}</span>
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

    .my-tasks__pager {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--hive-space-3);
      font-size: var(--hive-font-size-sm);
      color: var(--hive-color-text-secondary);
    }
  `,
})
export class MyTasksPage {
  private readonly api = inject(TaskApi);

  protected readonly tasks = loadable<Page<TaskSummary>>();
  protected readonly page = signal(0);

  protected readonly list = computed(() => this.tasks.value()?.content ?? []);
  protected readonly totalPages = computed(() => this.tasks.value()?.totalPages ?? 0);

  constructor() {
    this.load();
  }

  protected load(): void {
    this.tasks.load(this.api.listAssignedToMe({ page: this.page(), size: 20 }));
  }

  protected goTo(page: number): void {
    this.page.set(Math.max(0, page));
    this.load();
  }
}
