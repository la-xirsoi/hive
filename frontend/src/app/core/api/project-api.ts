import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiBase } from './api-base';
import { normalizeErrors } from './api-error';
import {
  CreateProjectRequest,
  Page,
  PageRequest,
  ProjectNameRequest,
  ProjectSummary,
  TaskStatus,
  TaskSummary,
  UserIdRequest,
} from './models';

/** Optional `?status=` filter for {@link ProjectApi.listTasks}. */
export interface ProjectTaskQuery extends PageRequest {
  /** Narrows the caller's visible subset. It can never widen visibility. */
  readonly status?: readonly TaskStatus[];
}

/**
 * Projects - `docs/api-contract.md` section 4.
 *
 * | Method | Path | Method here |
 * |--------|------|-------------|
 * | POST   | `/projects`                 | {@link create} |
 * | GET    | `/projects/mine`            | {@link listMine} |
 * | GET    | `/projects/{id}`            | {@link getById} |
 * | PATCH  | `/projects/{id}`            | {@link rename} |
 * | PUT    | `/projects/{id}/owner`      | {@link transferOwner} |
 * | GET    | `/projects/{id}/tasks` *(paged)* | {@link listTasks} |
 * | DELETE | `/projects/{id}`            | {@link deleteProject} (always 405) |
 */
@Injectable({ providedIn: 'root' })
export class ProjectApi extends ApiBase {
  /** 201. 403 if the creator is not a member or lead of `teamId` (PR-2). */
  create(body: CreateProjectRequest): Observable<ProjectSummary> {
    return this.http.post<ProjectSummary>(this.url('/projects'), body).pipe(normalizeErrors());
  }

  /** Projects the caller owns plus those of teams they lead or belong to (PR-4). */
  listMine(): Observable<ProjectSummary[]> {
    return this.http.get<ProjectSummary[]>(this.url('/projects/mine')).pipe(normalizeErrors());
  }

  getById(id: number): Observable<ProjectSummary> {
    return this.http.get<ProjectSummary>(this.url(`/projects/${id}`)).pipe(normalizeErrors());
  }

  /** Project owner only (PR-5). */
  rename(id: number, body: ProjectNameRequest): Observable<ProjectSummary> {
    return this.http
      .patch<ProjectSummary>(this.url(`/projects/${id}`), body)
      .pipe(normalizeErrors());
  }

  /**
   * Current owner only (PR-6). 409 if the incoming owner is the assignee of live
   * tasks in this project (PR-8); the server's message names the blocking ids,
   * which is why the normalizer preserves it verbatim.
   */
  transferOwner(id: number, body: UserIdRequest): Observable<ProjectSummary> {
    return this.http
      .put<ProjectSummary>(this.url(`/projects/${id}/owner`), body)
      .pipe(normalizeErrors());
  }

  /**
   * The caller's **visible** subset of the project's tasks per VIS-1..VIS-5, not
   * the full task list. The optional `status` filter is sent as the contract's
   * comma-separated list (`?status=Todo,In Progress`).
   */
  listTasks(id: number, query?: ProjectTaskQuery): Observable<Page<TaskSummary>> {
    const params = this.pageParams(query, {
      status: query?.status?.length ? query.status.join(',') : undefined,
    });
    return this.http
      .get<Page<TaskSummary>>(this.url(`/projects/${id}/tasks`), { params })
      .pipe(normalizeErrors());
  }

  /** Not supported (PR-10): always 405, so this always errors. */
  deleteProject(id: number): Observable<never> {
    return this.http.delete<never>(this.url(`/projects/${id}`)).pipe(normalizeErrors());
  }
}
