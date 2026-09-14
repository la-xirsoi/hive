import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiBase } from './api-base';
import { normalizeErrors } from './api-error';
import { Comment, CreateCommentRequest, Page, PageRequest } from './models';

/**
 * Comments - `docs/api-contract.md` section 6.
 *
 * | Method | Path | Method here |
 * |--------|------|-------------|
 * | GET  | `/tasks/{taskId}/comments` *(paged)* | {@link list} |
 * | POST | `/tasks/{taskId}/comments`           | {@link add} |
 *
 * Both require the caller to be able to see the task (CM-1, CM-2); otherwise
 * 404. Comments are permitted on terminal tasks (CM-3) and are immutable - the
 * contract defines no update or delete (CM-6), so this service has neither.
 */
@Injectable({ providedIn: 'root' })
export class CommentApi extends ApiBase {
  /** Ordered oldest first (CM-4/CM-5 assign author and timestamp server-side). */
  list(taskId: number, page?: PageRequest): Observable<Page<Comment>> {
    return this.http
      .get<Page<Comment>>(this.url(`/tasks/${taskId}/comments`), { params: this.pageParams(page) })
      .pipe(normalizeErrors());
  }

  /** 201. Author and timestamp are server-assigned and cannot be set here. */
  add(taskId: number, body: CreateCommentRequest): Observable<Comment> {
    return this.http
      .post<Comment>(this.url(`/tasks/${taskId}/comments`), body)
      .pipe(normalizeErrors());
  }
}
