/**
 * Wire representations from `docs/api-contract.md`.
 *
 * These interfaces mirror section 1.2 (shared representations), 1.3 (pagination)
 * and the request bodies named in sections 2 through 6 exactly. The contract is
 * frozen: nothing here may drift from that document without amending it first.
 */

// ---------------------------------------------------------------------------
// 1.2 Shared representations
// ---------------------------------------------------------------------------

export interface UserSummary {
  readonly id: number;
  readonly name: string;
  readonly email: string;
}

export interface TeamSummary {
  readonly id: number;
  readonly name: string;
  readonly teamLead: UserSummary;
  readonly memberCount: number;
}

/**
 * Server-computed capability flags for the acting user on one team (TM-5, TM-6,
 * TM-7, TM-9).
 *
 * `canRemoveMember` and `canTransferLead` answer "is *some* such operation open
 * to me right now", so a team whose only member is its lead reports `false` for
 * both: INV-1 makes the lead unremovable and there is nobody to hand the team
 * to.
 */
export interface TeamPermissions {
  readonly canRename: boolean;
  readonly canAddMember: boolean;
  readonly canRemoveMember: boolean;
  readonly canTransferLead: boolean;
}

export interface TeamDetail {
  readonly id: number;
  readonly name: string;
  readonly teamLead: UserSummary;
  readonly members: readonly UserSummary[];
  readonly permissions: TeamPermissions;
}

/**
 * Server-computed capability flags for the acting user on one project (PR-5,
 * PR-6, TK-1).
 *
 * `canTransferOwnership` reports only whether the actor may transfer at all;
 * PR-8's 409 - the incoming owner still holds live tasks here - is a fact about
 * the candidate and is reported by the transfer request itself.
 */
export interface ProjectPermissions {
  readonly canRename: boolean;
  readonly canTransferOwnership: boolean;
  readonly canCreateTask: boolean;
}

export interface ProjectSummary {
  readonly id: number;
  readonly name: string;
  readonly team: TeamSummary;
  readonly projectOwner: UserSummary;
  readonly permissions: ProjectPermissions;
}

export type TaskStatus = 'Draft' | 'Todo' | 'In Progress' | 'Completed' | 'Canceled';

/** Every `TaskStatus` literal, in state-machine order. Useful for filters and selects. */
export const TASK_STATUSES: readonly TaskStatus[] = [
  'Draft',
  'Todo',
  'In Progress',
  'Completed',
  'Canceled',
] as const;

/** The two terminal statuses (authorization.md section 3). */
export const TERMINAL_TASK_STATUSES: readonly TaskStatus[] = ['Completed', 'Canceled'] as const;

export interface TaskSummary {
  readonly id: number;
  readonly name: string;
  readonly status: TaskStatus;
  readonly projectId: number;
  readonly projectName: string;
  readonly assignee: UserSummary | null;
}

/**
 * Server-computed capability flags for the acting user on one task.
 *
 * The UI renders controls from this and never re-derives roles client-side; the
 * server still enforces independently (api-contract.md section 1.2).
 */
export interface TaskPermissions {
  readonly canEdit: boolean;
  readonly canAssign: boolean;
  readonly canComment: boolean;
  /** Statuses this actor may move the task to right now; empty for terminal tasks. */
  readonly allowedTransitions: readonly TaskStatus[];
}

export interface TaskDetail {
  readonly id: number;
  readonly name: string;
  readonly description: string;
  readonly status: TaskStatus;
  readonly project: ProjectSummary;
  readonly creator: UserSummary;
  readonly assignee: UserSummary | null;
  readonly permissions: TaskPermissions;
}

export interface Comment {
  readonly id: number;
  readonly taskId: number;
  readonly author: UserSummary;
  /** ISO-8601 UTC, minute precision: `2026-09-13T18:30:00Z`. Server-assigned. */
  readonly timestamp: string;
  readonly content: string;
}

// ---------------------------------------------------------------------------
// 1.3 Pagination
// ---------------------------------------------------------------------------

/** Envelope returned by every endpoint marked *(paged)* in the contract. */
export interface Page<T> {
  readonly content: readonly T[];
  /** 0-based page index. */
  readonly page: number;
  readonly size: number;
  readonly totalElements: number;
  readonly totalPages: number;
}

/** Query parameters accepted by paged endpoints: `page` 0-based, `size` 1..200. */
export interface PageRequest {
  readonly page?: number;
  readonly size?: number;
}

/** Contract defaults for paged endpoints (`page=0&size=50`). */
export const DEFAULT_PAGE_SIZE = 50;
export const MAX_PAGE_SIZE = 200;

/** An empty page, useful as a loading/initial value. */
export function emptyPage<T>(size: number = DEFAULT_PAGE_SIZE): Page<T> {
  return { content: [], page: 0, size, totalElements: 0, totalPages: 0 };
}

// ---------------------------------------------------------------------------
// Request bodies
// ---------------------------------------------------------------------------

/** `PATCH /users/me`. Sending `email` is a 400 (US-5); the type forbids it. */
export interface UpdateMeRequest {
  readonly name: string;
}

/** `POST /teams` and `PATCH /teams/{id}`. */
export interface TeamNameRequest {
  readonly name: string;
}

/** `POST /teams/{id}/members`, `PUT /teams/{id}/lead`, `PUT /projects/{id}/owner`. */
export interface UserIdRequest {
  readonly userId: number;
}

/** `POST /projects`. */
export interface CreateProjectRequest {
  readonly name: string;
  readonly teamId: number;
}

/** `PATCH /projects/{id}`. */
export interface ProjectNameRequest {
  readonly name: string;
}

/** `POST /tasks`. Always created `Draft` with no assignee (TK-2). */
export interface CreateTaskRequest {
  readonly projectId: number;
  readonly name: string;
  readonly description: string;
}

/** `PATCH /tasks/{id}`. At least one field must be present or the server returns 400. */
export interface UpdateTaskRequest {
  readonly name?: string;
  readonly description?: string;
}

/** `PUT /tasks/{id}/status`. */
export interface UpdateTaskStatusRequest {
  readonly status: TaskStatus;
}

/** `PUT /tasks/{id}/assignee`. `userId: null` unassigns, legal only from `Todo` (AS-7). */
export interface UpdateTaskAssigneeRequest {
  readonly userId: number | null;
}

/** `POST /tasks/{taskId}/comments`. */
export interface CreateCommentRequest {
  readonly content: string;
}
