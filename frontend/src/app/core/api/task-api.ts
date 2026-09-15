import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiBase } from './api-base';
import { normalizeErrors } from './api-error';
import {
  CreateTaskRequest,
  Page,
  PageRequest,
  TaskDetail,
  TaskSummary,
  UpdateTaskAssigneeRequest,
  UpdateTaskRequest,
  UpdateTaskStatusRequest,
} from './models';

/**
 * Tasks - `docs/api-contract.md` section 5.
 *
 * | Method | Path | Method here |
 * |--------|------|-------------|
 * | POST   | `/tasks`                      | {@link create} |
 * | GET    | `/tasks/{id}`                 | {@link getById} |
 * | PATCH  | `/tasks/{id}`                 | {@link update} |
 * | PUT    | `/tasks/{id}/status`          | {@link updateStatus} |
 * | PUT    | `/tasks/{id}/assignee`        | {@link updateAssignee} |
 * | GET    | `/tasks/assigned-to-me` *(paged)* | {@link listAssignedToMe} |
 * | GET    | `/tasks/unassigned` *(paged)*     | {@link listUnassigned} |
 * | DELETE | `/tasks/{id}`                 | {@link deleteTask} (always 405) |
 */
@Injectable({ providedIn: 'root' })
export class TaskApi extends ApiBase {
  /** 201. Project owner only (TK-1); always created `Draft`, unassigned (TK-2). */
  create(body: CreateTaskRequest): Observable<TaskDetail> {
    return this.http.post<TaskDetail>(this.url('/tasks'), body).pipe(normalizeErrors());
  }

  /** 404 when the task is invisible to the caller - never 403 (section 4). */
  getById(id: number): Observable<TaskDetail> {
    return this.http.get<TaskDetail>(this.url(`/tasks/${id}`)).pipe(normalizeErrors());
  }

  /**
   * Project owner only (TK-3); 409 in terminal states (TE-1). At least one of
   * `name` / `description` must be present or the server answers 400.
   */
  update(id: number, body: UpdateTaskRequest): Observable<TaskDetail> {
    return this.http.patch<TaskDetail>(this.url(`/tasks/${id}`), body).pipe(normalizeErrors());
  }

  /**
   * The only way to change status. Server evaluation order is
   * 404 visibility -> 409 legality -> 403 actor role; the UI should offer only
   * the statuses in `TaskDetail.permissions.allowedTransitions`.
   */
  updateStatus(id: number, body: UpdateTaskStatusRequest): Observable<TaskDetail> {
    return this.http.put<TaskDetail>(this.url(`/tasks/${id}/status`), body).pipe(normalizeErrors());
  }

  /**
   * Team lead only (AS-1). `userId: null` unassigns and is legal only from
   * `Todo` (AS-7); 409 while `Draft` (AS-3) or terminal (TE-3).
   */
  updateAssignee(id: number, body: UpdateTaskAssigneeRequest): Observable<TaskDetail> {
    return this.http
      .put<TaskDetail>(this.url(`/tasks/${id}/assignee`), body)
      .pipe(normalizeErrors());
  }

  /** Every task the caller is the assignee of (VIS-1), in any status (VIS-5). */
  listAssignedToMe(page?: PageRequest): Observable<Page<TaskSummary>> {
    return this.http
      .get<Page<TaskSummary>>(this.url('/tasks/assigned-to-me'), { params: this.pageParams(page) })
      .pipe(normalizeErrors());
  }

  /**
   * The lead's attention queue (UQ-1): `Todo` tasks with no assignee across
   * every team the caller leads. Non-leads get an empty page, not a 403.
   */
  listUnassigned(page?: PageRequest): Observable<Page<TaskSummary>> {
    return this.http
      .get<Page<TaskSummary>>(this.url('/tasks/unassigned'), { params: this.pageParams(page) })
      .pipe(normalizeErrors());
  }

  /** Not supported (TK-6): `Canceled` is the disposal path. Always 405. */
  deleteTask(id: number): Observable<never> {
    return this.http.delete<never>(this.url(`/tasks/${id}`)).pipe(normalizeErrors());
  }
}
