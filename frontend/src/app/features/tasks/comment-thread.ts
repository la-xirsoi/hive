import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { CommentApi } from '../../core/api/comment-api';
import { Comment, Page } from '../../core/api/models';
import { HiveAvatar, HiveButton, HiveFormField } from '../../shared/ui';
import { inputValue } from '../shared/dom';
import { formatLocalTimestamp, machineTimestamp } from '../shared/format-time';
import { HiveLoadState } from '../shared/load-state';
import { loadable } from '../shared/loadable';
import { mutation } from '../shared/mutation';

/**
 * The comment thread of one task (`GET`/`POST /tasks/{taskId}/comments`).
 *
 * TIME. `spec.md`: "TimeStamp precision is to the minute. TimeZone is UTC on the
 * server; browsers translate to local time." The wire value is an ISO-8601 UTC
 * instant (CM-5); the text node shows it in the **viewer's** zone and the
 * `<time datetime>` attribute keeps the original UTC instant, so screen readers
 * and machines get the unambiguous value. {@link timeZone} exists only so a spec
 * can pin a zone and assert the conversion actually happens.
 *
 * PERMISSION. The add-comment form is rendered from
 * `TaskPermissions.canComment`, the server's answer. Note that it is `true` on
 * terminal tasks (TE-4/CM-3): a `Completed` task is read-only for title,
 * description, status and assignee, and still open for commentary. The form is
 * therefore **not** tied to the terminal read-only banner.
 *
 * Author and timestamp are never sent: CM-4 and CM-5 make them server-assigned,
 * and `CreateCommentRequest` has no field for either.
 */
@Component({
  selector: 'hive-comment-thread',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [HiveAvatar, HiveButton, HiveFormField, HiveLoadState],
  template: `
    <section class="thread" aria-labelledby="task-comments-heading">
      <h2 class="thread__heading" id="task-comments-heading">
        Comments
        @if (comments.isReady()) {
          <span class="thread__count">({{ list().length }})</span>
        }
      </h2>

      <hive-load-state
        [loading]="comments.isLoading()"
        [error]="comments.errorMessage()"
        [empty]="comments.isReady() && list().length === 0"
        loadingLabel="Loading comments"
        emptyHeading="No comments yet"
        emptyMessage="Be the first to say something about this task."
        emptyGlyph="💬"
        (retried)="load()"
      >
        <ol class="thread__list" aria-label="Comments, oldest first">
          @for (comment of list(); track comment.id) {
            <li class="thread__item" [attr.data-comment-id]="comment.id">
              <hive-avatar size="sm" [name]="comment.author.name" decorative />
              <div class="thread__body">
                <p class="thread__byline">
                  <span class="thread__author">{{ comment.author.name }}</span>
                  <time class="thread__time" [attr.datetime]="machine(comment.timestamp)">
                    {{ when(comment.timestamp) }}
                  </time>
                </p>
                <p class="thread__content">{{ comment.content }}</p>
              </div>
            </li>
          }
        </ol>
      </hive-load-state>

      @if (canComment()) {
        <form class="thread__form" data-testid="comment-form" (submit)="add($event)">
          <hive-form-field
            label="Add a comment"
            required
            [error]="save.errorMessage()"
            fieldId="comment-content"
          >
            <textarea
              class="hive-textarea"
              id="comment-content"
              name="content"
              rows="3"
              data-testid="comment-input"
              [value]="draft()"
              (input)="draft.set(value($event))"
            ></textarea>
          </hive-form-field>
          <hive-button
            type="submit"
            [loading]="save.busy()"
            [disabled]="draft().trim().length === 0"
            data-testid="add-comment"
          >
            Post comment
          </hive-button>
        </form>
      } @else {
        <p class="thread__note" data-testid="comments-read-only">
          You can read this discussion but not add to it.
        </p>
      }
    </section>
  `,
  styles: `
    :host {
      display: block;
    }

    .thread__heading {
      margin: 0 0 var(--hive-space-4);
      font-size: var(--hive-font-size-lg);
      font-weight: var(--hive-font-weight-bold);
      color: var(--hive-color-text);
    }

    .thread__count {
      font-weight: var(--hive-font-weight-regular);
      color: var(--hive-color-text-secondary);
    }

    .thread__list {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .thread__item {
      display: flex;
      gap: var(--hive-space-3);
      padding: var(--hive-space-4) 0;
      border-bottom: 1px solid var(--hive-color-border-subtle);
    }

    .thread__item:last-child {
      border-bottom: none;
    }

    .thread__body {
      min-width: 0;
    }

    .thread__byline {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: var(--hive-space-2);
      margin: 0;
    }

    .thread__author {
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-color-text);
    }

    .thread__time {
      font-size: var(--hive-font-size-xs);
      color: var(--hive-color-text-secondary);
    }

    .thread__content {
      margin: var(--hive-space-1) 0 0;
      color: var(--hive-color-text);
      white-space: pre-wrap;
    }

    .thread__form {
      margin-top: var(--hive-space-5);
    }

    .thread__form hive-button {
      margin-top: var(--hive-space-3);
    }

    .thread__note {
      margin: var(--hive-space-5) 0 0;
      font-size: var(--hive-font-size-sm);
      color: var(--hive-color-text-secondary);
    }
  `,
})
export class HiveCommentThread {
  private readonly api = inject(CommentApi);

  readonly taskId = input.required<number>();
  /** Straight from `TaskPermissions.canComment`. */
  readonly canComment = input(false);
  /** An IANA zone name. Omitted means the viewer's own zone; a spec pins one. */
  readonly timeZone = input<string | undefined>(undefined);

  protected readonly comments = loadable<Page<Comment>>();
  protected readonly save = mutation();
  protected readonly draft = signal('');
  protected readonly value = inputValue;
  protected readonly machine = machineTimestamp;

  protected readonly list = computed(() => this.comments.value()?.content ?? []);

  constructor() {
    // An effect, not a constructor call: `taskId` is a required input and is
    // not readable until the first change detection pass has bound it.
    effect(() => {
      this.taskId();
      this.load();
    });
  }

  protected load(): void {
    this.comments.load(this.api.list(this.taskId(), { size: 100 }));
  }

  protected when(iso: string): string {
    return formatLocalTimestamp(iso, this.timeZone());
  }

  protected add(event: Event): void {
    event.preventDefault();
    const content = this.draft().trim();
    if (!content) {
      return;
    }
    this.save.run(this.api.add(this.taskId(), { content }), {
      next: () => {
        this.draft.set('');
        this.load();
      },
    });
  }
}
