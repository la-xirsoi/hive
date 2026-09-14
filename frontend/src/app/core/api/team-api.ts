import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiBase } from './api-base';
import { normalizeErrors } from './api-error';
import { ProjectSummary, TeamDetail, TeamNameRequest, TeamSummary, UserIdRequest } from './models';

/**
 * Teams - `docs/api-contract.md` section 3.
 *
 * | Method | Path | Method here |
 * |--------|------|-------------|
 * | POST   | `/teams`                          | {@link create} |
 * | GET    | `/teams/mine`                     | {@link listMine} |
 * | GET    | `/teams/{id}`                     | {@link getById} |
 * | PATCH  | `/teams/{id}`                     | {@link rename} |
 * | POST   | `/teams/{id}/members`             | {@link addMember} |
 * | DELETE | `/teams/{id}/members/{userId}`    | {@link removeMember} |
 * | PUT    | `/teams/{id}/lead`                | {@link transferLead} |
 * | GET    | `/teams/{id}/projects`            | {@link listProjects} |
 * | DELETE | `/teams/{id}`                     | {@link deleteTeam} (always 405) |
 */
@Injectable({ providedIn: 'root' })
export class TeamApi extends ApiBase {
  /** 201. The creator becomes lead and, by INV-1, a member (TM-1). */
  create(body: TeamNameRequest): Observable<TeamDetail> {
    return this.http.post<TeamDetail>(this.url('/teams'), body).pipe(normalizeErrors());
  }

  /** Teams the acting user leads or belongs to (TM-4). Bare array, not paged. */
  listMine(): Observable<TeamSummary[]> {
    return this.http.get<TeamSummary[]>(this.url('/teams/mine')).pipe(normalizeErrors());
  }

  getById(id: number): Observable<TeamDetail> {
    return this.http.get<TeamDetail>(this.url(`/teams/${id}`)).pipe(normalizeErrors());
  }

  /** Team lead only (TM-5). */
  rename(id: number, body: TeamNameRequest): Observable<TeamDetail> {
    return this.http.patch<TeamDetail>(this.url(`/teams/${id}`), body).pipe(normalizeErrors());
  }

  /** Team lead only (TM-6). */
  addMember(id: number, body: UserIdRequest): Observable<TeamDetail> {
    return this.http
      .post<TeamDetail>(this.url(`/teams/${id}/members`), body)
      .pipe(normalizeErrors());
  }

  /**
   * Team lead only. 409 if `userId` is the current lead (TM-7). Live tasks
   * assigned to the removed member are unassigned server-side (TM-8).
   */
  removeMember(id: number, userId: number): Observable<TeamDetail> {
    return this.http
      .delete<TeamDetail>(this.url(`/teams/${id}/members/${userId}`))
      .pipe(normalizeErrors());
  }

  /** Current lead only (TM-9). The new lead auto-joins as a member (TM-10). */
  transferLead(id: number, body: UserIdRequest): Observable<TeamDetail> {
    return this.http.put<TeamDetail>(this.url(`/teams/${id}/lead`), body).pipe(normalizeErrors());
  }

  /** Only the projects of this team the caller may see (PR-3). Bare array. */
  listProjects(id: number): Observable<ProjectSummary[]> {
    return this.http
      .get<ProjectSummary[]>(this.url(`/teams/${id}/projects`))
      .pipe(normalizeErrors());
  }

  /**
   * Team deletion is not supported (TM-11): this endpoint always answers 405 and
   * this method therefore always errors with a `METHOD_NOT_ALLOWED` ApiError. It
   * exists so the contract is covered exhaustively and so any UI that wires a
   * delete affordance fails loudly rather than silently inventing a request.
   */
  deleteTeam(id: number): Observable<never> {
    return this.http.delete<never>(this.url(`/teams/${id}`)).pipe(normalizeErrors());
  }
}
