# Hive REST API Contract

**Frozen contract.** The backend implements exactly these endpoints; the
frontend consumes exactly these endpoints. Changes require updating this
document first and notifying both sides.

Companion documents: `authorization.md` (who may call what),
`toolchain.md` (pinned versions).

- Base path: `/api/v1`
- Transport: **HTTPS only** (AU-3)
- Authentication: `Authorization: Bearer <JWT>` on every endpoint below (AU-1)
- Content type: `application/json; charset=utf-8`
- The acting user is always resolved from the token (AU-2). No endpoint accepts
  a caller-supplied acting user id.

---

## 1. Common types

### 1.1 Error response

Every non-2xx response uses this shape and no other:

```json
{
  "status": 409,
  "error": "CONFLICT",
  "message": "Task 42 is Completed and can no longer be edited.",
  "path": "/api/v1/tasks/42",
  "timestamp": "2026-09-13T18:30:00Z",
  "fieldErrors": [
    { "field": "email", "message": "must be a valid email address" }
  ]
}
```

- `fieldErrors` is present **only** for 400 validation failures; omitted
  otherwise.
- `message` is always human-readable and must never contain stack traces, SQL,
  class names or other implementation detail (spec.md: 500 "must not leak
  implementation details").
- `error` is one of `BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`,
  `METHOD_NOT_ALLOWED`, `CONFLICT`, `INTERNAL_ERROR`.

### 1.2 Shared representations

```
UserSummary   { "id": number, "name": string, "email": string }

TeamSummary   { "id": number, "name": string, "teamLead": UserSummary,
                "memberCount": number }

TeamDetail    { "id": number, "name": string, "teamLead": UserSummary,
                "members": UserSummary[],
                "permissions": TeamPermissions }

TeamPermissions { "canRename": boolean, "canAddMember": boolean,
                  "canRemoveMember": boolean, "canTransferLead": boolean }

ProjectSummary{ "id": number, "name": string, "team": TeamSummary,
                "projectOwner": UserSummary,
                "permissions": ProjectPermissions }

ProjectPermissions { "canRename": boolean, "canTransferOwnership": boolean,
                     "canCreateTask": boolean }

TaskStatus    "Draft" | "Todo" | "In Progress" | "Completed" | "Canceled"

TaskSummary   { "id": number, "name": string, "status": TaskStatus,
                "projectId": number, "projectName": string,
                "assignee": UserSummary | null }

TaskDetail    { "id": number, "name": string, "description": string,
                "status": TaskStatus, "project": ProjectSummary,
                "creator": UserSummary, "assignee": UserSummary | null,
                "permissions": TaskPermissions }

TaskPermissions { "canEdit": boolean, "canAssign": boolean,
                  "canComment": boolean,
                  "allowedTransitions": TaskStatus[] }

Comment       { "id": number, "taskId": number, "author": UserSummary,
                "timestamp": string,   // ISO-8601 UTC, minute precision:
                                       // "2026-09-13T18:30:00Z"
                "content": string }
```

`TaskPermissions`, `TeamPermissions` and `ProjectPermissions` are computed
server-side from the same domain policy that enforces the rules, and tell the UI
which controls to render. The UI must use them rather than re-deriving roles
client-side; the server still enforces independently (the fields are a
convenience, never the enforcement point).

- `allowedTransitions` lists the statuses this actor may move the task to right
  now -- empty for terminal tasks and for actors with no transition rights.
- `TeamPermissions` answers TM-5, TM-6, TM-7 and TM-9. `canRemoveMember` and
  `canTransferLead` ask whether *some* such operation is open right now, so a
  team whose only member is its lead reports `false` for both: INV-1 makes the
  lead unremovable (TM-7) and there is nobody to hand the team to.
- `ProjectPermissions` answers PR-5, PR-6 and TK-1. `canTransferOwnership`
  reports only whether the actor may transfer at all; PR-8's 409 -- the incoming
  owner still holds live tasks here -- is a fact about the candidate and is
  reported by the transfer request itself.
- `TeamSummary` carries no permission block: it is a list row, and the detail
  endpoint is where the controls live.

### 1.3 Pagination

List endpoints that can grow unbounded accept `?page=<0-based>&size=<1..200>`
(default `page=0&size=50`) and return:

```json
{ "content": [ ... ], "page": 0, "size": 50, "totalElements": 123, "totalPages": 3 }
```

Endpoints marked *(paged)* below use this envelope. All others return a bare
JSON array.

---

## 2. Users

| Method | Path | Body | 200/201 response | Errors |
|--------|------|------|------------------|--------|
| GET | `/users/me` | -- | `UserSummary` | 401 |
| PATCH | `/users/me` | `{ "name": string }` | `UserSummary` | 400, 401 |
| GET | `/users?query=<substring>` *(paged)* | -- | `UserSummary` page | 400, 401 |
| GET | `/users/{id}` | -- | `UserSummary` | 401, 404 |

- `GET /users/me` provisions the Hive user on first sight from the token claims
  (US-3) and is therefore safe to call as the frontend's first request.
- `query` matches name or email, case-insensitive substring. Omitted = all users.
- Email changes are rejected (US-5): sending `email` in the PATCH body is a 400.

---

## 3. Teams

| Method | Path | Body | Success | Errors |
|--------|------|------|---------|--------|
| POST | `/teams` | `{ "name": string }` | 201 `TeamDetail` | 400, 401 |
| GET | `/teams/mine` | -- | 200 `TeamSummary[]` | 401 |
| GET | `/teams/{id}` | -- | 200 `TeamDetail` | 401, 404 |
| PATCH | `/teams/{id}` | `{ "name": string }` | 200 `TeamDetail` | 400, 401, 403, 404 |
| POST | `/teams/{id}/members` | `{ "userId": number }` | 200 `TeamDetail` | 400, 401, 403, 404 |
| DELETE | `/teams/{id}/members/{userId}` | -- | 200 `TeamDetail` | 401, 403, 404, 409 |
| PUT | `/teams/{id}/lead` | `{ "userId": number }` | 200 `TeamDetail` | 400, 401, 403, 404 |
| GET | `/teams/{id}/projects` | -- | 200 `ProjectSummary[]` | 401, 404 |
| DELETE | `/teams/{id}` | -- | 405 | always 405 (TM-11) |

- `POST /teams`: creator becomes lead and member (TM-1, INV-1).
- `DELETE .../members/{userId}`: 409 if `userId` is the current lead (TM-7);
  unassigns that user's live tasks in the team's projects (TM-8).
- `PUT /teams/{id}/lead`: lead only (TM-9); new lead auto-joins as member
  (TM-10); 400 if the user does not exist.
- `GET /teams/{id}/projects` returns only projects the caller may see (PR-3).

---

## 4. Projects

| Method | Path | Body | Success | Errors |
|--------|------|------|---------|--------|
| POST | `/projects` | `{ "name": string, "teamId": number }` | 201 `ProjectSummary` | 400, 401, 403 |
| GET | `/projects/mine` | -- | 200 `ProjectSummary[]` | 401 |
| GET | `/projects/{id}` | -- | 200 `ProjectSummary` | 401, 404 |
| PATCH | `/projects/{id}` | `{ "name": string }` | 200 `ProjectSummary` | 400, 401, 403, 404 |
| PUT | `/projects/{id}/owner` | `{ "userId": number }` | 200 `ProjectSummary` | 400, 401, 403, 404, 409 |
| GET | `/projects/{id}/tasks` *(paged)* | -- | 200 `TaskSummary` page | 401, 404 |
| DELETE | `/projects/{id}` | -- | 405 | always 405 (PR-10) |

- `POST /projects`: 403 if the creator is not a member or lead of `teamId`
  (PR-2); 404 if the team is invisible to them.
- `PUT /projects/{id}/owner`: 409 if the incoming owner is the assignee of live
  tasks in this project (PR-8); the message names the blocking task ids.
- `GET /projects/{id}/tasks` returns the caller's **visible** subset per VIS-1
  through VIS-5, not the project's full task list. Optional filter
  `?status=Todo,In Progress` narrows further. It never widens visibility.
  403 is **not** a possible response here: an invisible project is 404, and a
  visible one filters the task list rather than refusing the request.

---

## 5. Tasks

| Method | Path | Body | Success | Errors |
|--------|------|------|---------|--------|
| POST | `/tasks` | `CreateTask` | 201 `TaskDetail` | 400, 401, 403, 404 |
| GET | `/tasks/{id}` | -- | 200 `TaskDetail` | 401, 404 |
| PATCH | `/tasks/{id}` | `{ "name"?: string, "description"?: string }` | 200 `TaskDetail` | 400, 401, 403, 404, 409 |
| PUT | `/tasks/{id}/status` | `{ "status": TaskStatus }` | 200 `TaskDetail` | 400, 401, 403, 404, 409 |
| PUT | `/tasks/{id}/assignee` | `{ "userId": number \| null }` | 200 `TaskDetail` | 400, 401, 403, 404, 409 |
| GET | `/tasks/assigned-to-me` *(paged)* | -- | 200 `TaskSummary` page | 401 |
| GET | `/tasks/unassigned` *(paged)* | -- | 200 `TaskSummary` page | 401 |
| DELETE | `/tasks/{id}` | -- | 405 | always 405 (TK-6) |

```
CreateTask { "projectId": number, "name": string, "description": string }
```

- `POST /tasks`: project owner only (TK-1); always created `Draft` with no
  assignee (TK-2). 404 if the project is invisible to the caller.
- `PATCH /tasks/{id}`: project owner only (TK-3); 409 in terminal states (TE-1).
  At least one field must be present or 400.
- `PUT /tasks/{id}/status`: the **only** way to change status. Evaluation order
  is 404 visibility -> 409 legality -> 403 actor role (see authorization.md
  section 2.1 and section 11).
- `PUT /tasks/{id}/assignee`: team lead only (AS-1). `userId: null` unassigns and
  is legal only from `Todo` (AS-7). 403 if the target is not a member of the
  project's team (AS-2) or is the project's owner (AS-4). 409 if the task is
  `Draft` (AS-3) or terminal (TE-3).
- `GET /tasks/unassigned` returns `Todo` tasks with no assignee across every team
  the caller **leads** (UQ-1). Non-leads get an empty page, not a 403.

---

## 6. Comments

| Method | Path | Body | Success | Errors |
|--------|------|------|---------|--------|
| GET | `/tasks/{taskId}/comments` *(paged)* | -- | 200 `Comment` page | 401, 404 |
| POST | `/tasks/{taskId}/comments` | `{ "content": string }` | 201 `Comment` | 400, 401, 404 |

- Both require the caller to be able to see the task (CM-1, CM-2); otherwise 404.
- Ordered oldest first. Author and timestamp are server-assigned (CM-4, CM-5).
- Permitted on terminal tasks (CM-3). No update or delete endpoints (CM-6).

---

## 7. Non-API endpoints

| Path | Purpose | Auth |
|------|---------|------|
| `/actuator/health` | liveness/readiness for the container healthcheck | public |
| `/v3/api-docs` | generated OpenAPI 3 document | public |
| `/swagger-ui.html` | API explorer | public |

---

## 8. Validation rules applied at the edge

Mirrors of the domain constraints, enforced on DTOs so malformed input fails
fast with field-level 400 detail:

| Field | Constraint |
|-------|-----------|
| `User.name` | required, 1..200 chars, not blank |
| `User.email` | required, 5..254 chars, valid address format, unique (409 on duplicate) |
| `Team.name` | required, 1..200 chars, not blank |
| `Project.name` | required, 1..200 chars, not blank |
| `Task.name` | required, 1..200 chars, not blank |
| `Task.description` | required, 0..4000 chars |
| `Comment.content` | required, 1..4000 chars, not blank |
| `status` | must be one of the five literal `TaskStatus` values; anything else is 400 |

> `Task.description`, `Team.name` and `Project.name` are typed only as "String"
> in spec.md. The 200-char cap on names mirrors the explicit cap the spec sets on
> `User.Name` and `Task.Name`; 4000 chars for free text is the practical
> `NVARCHAR` limit before switching to LOB storage on SQL Server.

---

## 9. Appendix: dev-profile authentication endpoint

**This endpoint exists only under the `dev` Spring profile and must be provably
absent in `prod`.** It exists because this project was built on a machine with no
container runtime, so no real identity provider could be hosted locally (see
`docs/toolchain.md`, "Known environment gaps"). It lets the full stack be
demonstrated end to end without one.

| Method | Path | Body | Success | Errors |
|--------|------|------|---------|--------|
| POST | `/api/v1/dev/token` | `{ "email": string, "name"?: string }` | 200 `{ "accessToken": string, "expiresIn": number }` | 400, 404 (prod) |

- Mints a locally signed JWT whose `sub`, `email` and `name` claims identify the
  user, signed with a dev-only key pair generated at startup, and validated by
  the same resource-server configuration that validates real tokens. The token
  path through the application is therefore identical in dev and prod; only the
  issuer differs.
- The endpoint is registered by a `@Profile("dev")` configuration. Under any
  other profile the route does not exist and returns 404.
- The frontend calls it only when `environment.devAuth` is true.

Both sides of the stack must implement exactly this shape: the Angular dev login
and the Spring dev issuer are written by different agents and this appendix is
the only thing keeping them in agreement.
