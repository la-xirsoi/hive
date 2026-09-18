import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, shareReplay, tap } from 'rxjs';
import { UserApi } from '../../core/api/user-api';
import { UserSummary } from '../../core/api/models';

/**
 * The acting user, fetched once and shared by every screen.
 *
 * `GET /users/me` also provisions the Hive user from the token claims on first
 * sight (US-3), so it is the application's natural first request. The response
 * is cached with `shareReplay` because several sibling components ask for it
 * during the same render.
 *
 * WHAT THIS IS FOR, and what it is NOT for: identity, not authorization. Every
 * control in the application is rendered from the server-computed permission
 * block on the record it acts on - `TaskPermissions`, `TeamPermissions`,
 * `ProjectPermissions` - and never from this id. What is left for this service
 * is wording and labelling: "You lead this team" rather than "Led by Bob Ito",
 * the `Owner` / `Team project` badge on a list row. Nothing gated on those
 * answers changes what may be done, only how it reads.
 */
@Injectable({ providedIn: 'root' })
export class CurrentUser {
  private readonly api = inject(UserApi);
  private readonly loaded = signal<UserSummary | null>(null);
  private request: Observable<UserSummary> | null = null;

  /** The acting user once known, else null. */
  readonly user = this.loaded.asReadonly();
  readonly id = computed(() => this.loaded()?.id ?? null);

  /** Loads (or replays) `GET /users/me`. */
  load(): Observable<UserSummary> {
    this.request ??= this.api.getMe().pipe(
      tap((user) => this.loaded.set(user)),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.request;
  }

  /** True when `userId` is the acting user. Null ids never match. */
  isMe(userId: number | null | undefined): boolean {
    const me = this.id();
    return me !== null && userId !== null && userId !== undefined && me === userId;
  }

  /** Drops the cache, e.g. after sign-out. */
  reset(): void {
    this.request = null;
    this.loaded.set(null);
  }
}
