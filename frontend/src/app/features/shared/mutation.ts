import { DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable } from 'rxjs';
import { ApiError, normalizeApiError } from '../../core/api/api-error';

/** Heading shown above a 409. Exported so specs assert the exact wording. */
export const CONFLICT_HEADING = 'This changed underneath you';

/**
 * Appended to the server's 409 message. The server explains *what* conflicted
 * (api-contract.md section 1.1 guarantees a human-readable `message`); this
 * says what the screen did about it.
 */
export const CONFLICT_SUFFIX = 'The latest version has been loaded.';

/**
 * The state of one in-flight write, as a counterpart to {@link Loadable}.
 *
 * Every mutating screen needs the same four things: a busy flag to disable the
 * control, the server's error message, a way to tell a **409 Conflict** apart
 * from every other failure, and a refresh hook for that case.
 *
 * The 409 is singled out because it is the one error where the screen is
 * *stale* rather than wrong: the task moved to a terminal state, or its status
 * changed, between the render and the click (authorization.md sections 2.1 and
 * 3). Re-reading the record is the only way to recover, so `run()` invokes
 * `conflict` automatically and the caller re-fetches - the user sees both the
 * explanation and the corrected screen.
 */
export class Mutation {
  private readonly running = signal(false);
  private readonly failure = signal<ApiError | null>(null);

  readonly busy = this.running.asReadonly();
  readonly error = this.failure.asReadonly();

  /** True when the last failure was a 409. */
  readonly isConflict = computed(() => this.failure()?.status === 409);

  /** The message to render: the server's own text, plus the refresh note on a 409. */
  readonly errorMessage = computed(() => {
    const failure = this.failure();
    if (!failure) {
      return null;
    }
    return failure.status === 409 ? `${failure.message} ${CONFLICT_SUFFIX}` : failure.message;
  });

  readonly heading = computed(() => (this.isConflict() ? CONFLICT_HEADING : 'That did not work'));

  constructor(private readonly destroyRef: DestroyRef) {}

  /**
   * Runs `source`, tracking busy/error state.
   *
   * @param handlers.next called with the successful response body.
   * @param handlers.conflict called instead of nothing when the server answered
   *   409, so the screen can re-read the record it is now known to be stale on.
   */
  run<T>(
    source: Observable<T>,
    handlers: { readonly next?: (value: T) => void; readonly conflict?: () => void } = {},
  ): void {
    this.running.set(true);
    this.failure.set(null);
    source.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (value) => {
        this.running.set(false);
        handlers.next?.(value);
      },
      error: (cause: unknown) => {
        const failure = normalizeApiError(cause);
        this.running.set(false);
        this.failure.set(failure);
        if (failure.status === 409) {
          handlers.conflict?.();
        }
      },
    });
  }

  /** Clears the error, e.g. when the user dismisses the toast. */
  clear(): void {
    this.failure.set(null);
  }
}

/** Creates a {@link Mutation} bound to the current injection context's lifetime. */
export function mutation(): Mutation {
  return new Mutation(inject(DestroyRef));
}
