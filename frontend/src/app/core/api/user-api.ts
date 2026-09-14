import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiBase } from './api-base';
import { normalizeErrors } from './api-error';
import { Page, PageRequest, UpdateMeRequest, UserSummary } from './models';

/**
 * Users - `docs/api-contract.md` section 2.
 *
 * | Method | Path | Method here |
 * |--------|------|-------------|
 * | GET    | `/users/me`              | {@link getMe} |
 * | PATCH  | `/users/me`              | {@link updateMe} |
 * | GET    | `/users?query=` *(paged)*| {@link search} |
 * | GET    | `/users/{id}`            | {@link getById} |
 */
@Injectable({ providedIn: 'root' })
export class UserApi extends ApiBase {
  /**
   * The acting user, provisioned from the token claims on first sight (US-3),
   * which makes this safe as the application's very first request.
   */
  getMe(): Observable<UserSummary> {
    return this.http.get<UserSummary>(this.url('/users/me')).pipe(normalizeErrors());
  }

  /** Updates the acting user's own name. Sending an email is a 400 (US-5). */
  updateMe(body: UpdateMeRequest): Observable<UserSummary> {
    return this.http.patch<UserSummary>(this.url('/users/me'), body).pipe(normalizeErrors());
  }

  /**
   * Directory lookup (US-4). `query` is a case-insensitive substring match over
   * name or email; omitting it lists all users.
   */
  search(query?: string, page?: PageRequest): Observable<Page<UserSummary>> {
    const params = this.pageParams(page, { query });
    return this.http
      .get<Page<UserSummary>>(this.url('/users'), { params })
      .pipe(normalizeErrors());
  }

  getById(id: number): Observable<UserSummary> {
    return this.http.get<UserSummary>(this.url(`/users/${id}`)).pipe(normalizeErrors());
  }
}
