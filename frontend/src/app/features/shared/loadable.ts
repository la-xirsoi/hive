import { DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable } from 'rxjs';
import { ApiError, normalizeApiError } from '../../core/api/api-error';

/** Lifecycle of one asynchronous read. */
export type LoadState = 'idle' | 'loading' | 'ready' | 'error';

/**
 * The four-state async cell every feature screen is built from.
 *
 * Each screen on the dashboard loads independently, so "loading", "empty",
 * "error" and "ready" have to be expressible per list rather than per page.
 * Holding that state in signals keeps the components OnPush and zoneless-safe:
 * a late HTTP response writes a signal and the view refreshes itself.
 *
 * Errors are normalized to {@link ApiError} on the way in, so a template only
 * ever renders `errorMessage()` - the server's own human-readable text when it
 * sent one (api-contract.md section 1.1).
 */
export class Loadable<T> {
  private readonly state = signal<LoadState>('idle');
  private readonly data = signal<T | null>(null);
  private readonly failure = signal<ApiError | null>(null);

  readonly status = this.state.asReadonly();
  readonly value = this.data.asReadonly();
  readonly error = this.failure.asReadonly();

  readonly isLoading = computed(() => this.state() === 'loading');
  readonly isReady = computed(() => this.state() === 'ready');
  readonly hasError = computed(() => this.state() === 'error');
  readonly errorMessage = computed(() => this.failure()?.message ?? null);

  constructor(private readonly destroyRef: DestroyRef) {}

  /**
   * Subscribes to `source` and projects it into the four states.
   *
   * @param options.keepValue retain the previously loaded value while the next
   * request is in flight, so a refresh does not blank the screen.
   */
  load(source: Observable<T>, options?: { readonly keepValue?: boolean }): void {
    this.state.set('loading');
    this.failure.set(null);
    if (!options?.keepValue) {
      this.data.set(null);
    }
    source.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (value) => {
        this.data.set(value);
        this.state.set('ready');
      },
      error: (cause: unknown) => {
        this.failure.set(normalizeApiError(cause));
        this.state.set('error');
      },
    });
  }

  /** Adopts a value already in hand, e.g. the body of a successful mutation. */
  set(value: T): void {
    this.data.set(value);
    this.failure.set(null);
    this.state.set('ready');
  }
}

/** Creates a {@link Loadable} bound to the current injection context's lifetime. */
export function loadable<T>(): Loadable<T> {
  return new Loadable<T>(inject(DestroyRef));
}
