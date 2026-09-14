# Hive Authorization Model

This document is the **normative source of truth** for who may do what in Hive.
It is derived line by line from `spec.md`. Where `spec.md` is silent, this
document records an explicit **[INTERPRETATION]** with its reasoning; those
interpretations are binding on the implementation.

Every rule here must be encoded in the domain `AuthorizationPolicy` and covered
by a test. The application, REST and UI layers consult the policy; they never
re-derive rules.

---

## 1. Role model

Roles in Hive are **contextual**, not global. A user holds a role *with respect
to a particular team or project*:

| Role | Held with respect to | Established by |
|------|----------------------|----------------|
| Project Owner (R01) | one Project | `Project.projectOwner` |
| Team Lead (R02) | one Team | `Team.teamLead` |
| Team Member (R03) | one Team | membership in `Team.members` |

Invariants from `spec.md`:

- **INV-1**: A Team Lead **is** a Team Member of that team. Membership is added
  automatically on lead assignment and cannot be removed while the user leads.
- **INV-2**: Being a Project Owner does **not** confer Team Membership of the
  project's team. A Project Owner may separately be a member; that is neither
  required nor forbidden.
- **INV-3**: A Team has exactly one lead; a Project has exactly one owner; a
  Project belongs to exactly one Team; a Task belongs to exactly one Project.
- **INV-4**: A User may lead any number of Teams, own any number of Projects,
  belong to any number of Teams, and be both a lead and an owner.

---

## 2. Task status state machine

Statuses: `Draft`, `Todo`, `In Progress`, `Completed`, `Canceled`.
New tasks start in `Draft`.

| From | To | Permitted actor |
|------|----|-----------------|
| Draft | Todo | Project Owner of the task's project |
| Draft | Canceled | Project Owner of the task's project |
| Todo | In Progress | The task's **Assignee** |
| Todo | Canceled | Project Owner of the task's project |
| In Progress | Completed | The task's **Assignee** |
| In Progress | Canceled | Project Owner of the task's project |
| Completed | -- | terminal, no transitions |
| Canceled | -- | terminal, no transitions |

Any transition not in this table is **illegal**.

### 2.1 Distinguishing 409 from 403

This distinction is mandatory and frequently gotten wrong:

- The transition **is not in the table at all** (e.g. `Todo -> Completed`, or any
  transition out of a terminal state) -> **409 Conflict**. The request is
  impossible for *anyone*; the actor's identity is irrelevant.
- The transition **is in the table**, but the actor does not hold the required
  role -> **403 Forbidden**.

Evaluation order: legality of the transition first (409), then actor
authorization (403).

- **[INTERPRETATION] TR-1**: `Todo -> In Progress` and `In Progress -> Completed`
  require the actor to be *the assignee of that task*, not merely some member of
  the team. `spec.md` writes "Team Member (Assignee)" and R03 says members are
  "responsible for updating the Status of a Task" they were assigned.
- **[INTERPRETATION] TR-2**: A `Todo` task with **no assignee** cannot move to
  `In Progress` by anyone -> **409 Conflict** (no valid actor exists).
- **[INTERPRETATION] TR-3**: A transition to the status a task already holds
  (e.g. `Todo -> Todo`) is **409 Conflict**.

---

## 3. Terminal state edit ban

From `spec.md`: *"Tasks cannot be edited (title, description, or status) once
they are in a terminal state (Completed or Canceled)."*

- **TE-1**: Name/title and description of a `Completed` or `Canceled` task cannot
  be changed -> **409 Conflict**.
- **TE-2**: Status of a terminal task cannot be changed -> **409 Conflict**.
- **[INTERPRETATION] TE-3**: The **assignee** of a terminal task cannot be
  changed -> **409 Conflict**. The spec's list is about mutation of a finished
  record; reassigning finished work is the same category of change and would
  silently rewrite the history of who completed it.
- **[INTERPRETATION] TE-4**: **Comments may still be added** to terminal tasks.
  The spec's ban enumerates title, description and status only. Post-mortem
  commentary on completed or canceled work is a normal and useful activity, and
  a comment does not mutate the task record.

---

## 4. Task visibility

Visibility is the union of every rule that applies to the acting user. A user
who matches none of them cannot see the task at all.

| Rule | Actor | Sees |
|------|-------|------|
| VIS-1 | Any user | Tasks where they are the **assignee** (`spec.md` Common Needs: "see all Tasks assigned to them") |
| VIS-2 | Project Owner | **All** tasks in projects they own, in **every** status including `Draft` and `Canceled` |
| VIS-3 | Team Lead | All tasks in projects of teams they lead whose status is **not `Draft`** |
| VIS-4 | Team Member | All tasks in projects of teams they belong to whose status is **neither `Draft` nor `Canceled`** |

Notes:

- VIS-3 and VIS-4 compose: a Team Lead is also a Team Member (INV-1), so a lead
  effectively sees non-`Draft` tasks -- the broader of the two.
- **[INTERPRETATION] VIS-5**: VIS-1 overrides the status filters. If a user is
  the assignee of a task that was later `Canceled`, they still see it. A user
  must be able to see the work that was assigned to them, and a canceled
  assignment is information they need. `Draft` tasks cannot have this conflict in
  practice because assignment happens after publication (see AS-3).
- A task invisible to the actor is reported as **404 Not Found**, never 403.
  Returning 403 would confirm the task exists to a user with no right to know.

---

## 5. Task operations

| Op | Rule | Permitted actor | Failure |
|----|------|-----------------|---------|
| Create task | TK-1 | Project Owner of the target project **only** (`spec.md` R01: "They are responsible for creating new Tasks") | 403 |
| | TK-2 | Created in `Draft`; `creator` = acting user; `assignee` = null | -- |
| Update title/description | TK-3 | Project Owner of the task's project **only** (R01) | 403 |
| | TK-4 | Blocked in terminal states (TE-1) | 409 |
| Transition status | TK-5 | Per the state machine in section 2 | 409 then 403 |
| Assign / reassign | AS-1 | Team Lead of the project's team **only** (R02) | 403 |
| Delete task | TK-6 | **Not supported.** The spec provides `Canceled` as the disposal path and defines no deletion semantics. | 405 |

### 5.1 Assignment rules

- **AS-1**: Only the Team Lead of the team that owns the task's project may
  assign or reassign.
- **AS-2**: The assignee must be a **member of that project's team**
  (`spec.md` R02: "assign Tasks from their Teams' Projects to Members of that
  Project's Team") -> otherwise **400** if the user does not exist, **403** if the
  user exists but is not a member of the team.
- **AS-3**: A task in `Draft` cannot be assigned -> **409 Conflict**. Assignment
  is a Team Lead action and leads cannot even see `Draft` tasks (VIS-3); work
  must be published to `Todo` by the Project Owner first.
- **AS-4**: The **Project Owner of the task's project may never be the assignee**
  (`spec.md` R01: "They cannot be assigned a Task in their own project") ->
  **403**. This holds even when the project owner is also a member of the team,
  and even when the project owner is the team lead performing the assignment.
- **AS-5**: A Team Lead **may assign a task to themselves** (R02: "They *can*
  assign Tasks to themselves"), subject to AS-4.
- **[INTERPRETATION] AS-6**: Reassignment is permitted while the task is `Todo`
  or `In Progress`. Reassigning an `In Progress` task leaves the status
  unchanged. In terminal states, TE-3 applies -> 409.
- **[INTERPRETATION] AS-7**: **Unassignment** (setting assignee to null) is
  permitted only while the task is `Todo`. An `In Progress` task with no assignee
  could never reach `Completed` (TR-1) -> 409.

### 5.2 The unassigned queue

`spec.md` R02: *"Unassigned Tasks should be brought to the Team Lead's attention;
assigning these are a priority."*

- **UQ-1**: For each team a user leads, the system exposes the tasks in that
  team's projects with status `Todo` and no assignee, as a dedicated
  attention-demanding list. `Draft` tasks are excluded (they are invisible to
  leads); terminal tasks are excluded.

---

## 6. Comment operations

`spec.md` does not state comment permissions. The following are
**[INTERPRETATION]**s consistent with the visibility model.

- **CM-1**: A user may add a comment to a task **if and only if** they can see
  that task under section 4. Otherwise 404 (invisible) -- never 403, for the same
  non-disclosure reason.
- **CM-2**: A user may read the comments of a task if and only if they can see
  that task.
- **CM-3**: Comments may be added to tasks in terminal states (TE-4).
- **CM-4**: `Author` is always the acting user; it cannot be set by the client.
- **CM-5**: `TimeStamp` is server-assigned UTC, truncated to **minute**
  precision (`spec.md`). Clients render it in local time. Clients cannot set it.
- **CM-6**: Comments are immutable: no edit, no delete. The spec defines no such
  operations and an audit trail is the safer default.

---

## 7. Team operations

| Op | Rule | Permitted actor | Failure |
|----|------|-----------------|---------|
| Create team | TM-1 | **Any authenticated user** (`spec.md` Common Needs). Creator becomes Team Lead and, by INV-1, a member. | -- |
| View team | TM-2 | Members and the lead of that team | 404 |
| | TM-3 | **[INTERPRETATION]** Also the owner of any project belonging to the team -- read-only. An owner whose project is worked by a team needs to see who that team is. | 404 |
| List my teams | TM-4 | Any user: teams they lead or belong to | -- |
| Rename team | TM-5 | **[INTERPRETATION]** Team Lead only. Naming is a stewardship act and the lead is the team's steward. | 403 |
| Add member | TM-6 | **[INTERPRETATION]** Team Lead only. The spec makes the lead responsible for the team's composition by making them responsible for distributing its work. | 403 |
| Remove member | TM-7 | Team Lead only. **Cannot remove the current lead** (INV-1) -> 409. | 403 / 409 |
| | TM-8 | **[INTERPRETATION]** Removing a member who is the assignee of live (`Todo` / `In Progress`) tasks in that team's projects unassigns those tasks and returns them to the unassigned queue. Silently leaving work assigned to a non-member would corrupt AS-2. | -- |
| Transfer lead | TM-9 | Current Team Lead only (`spec.md`: "Team Leads may transfer ownership of a Team to another User") | 403 |
| | TM-10 | The new lead is automatically added as a member if not already one (INV-1). The outgoing lead **remains a member**. | -- |
| Delete team | TM-11 | **Not supported.** No deletion semantics in the spec. | 405 |

---

## 8. Project operations

| Op | Rule | Permitted actor | Failure |
|----|------|-----------------|---------|
| Create project | PR-1 | **Any authenticated user** (`spec.md` Common Needs). Creator becomes Project Owner. | -- |
| | PR-2 | **[INTERPRETATION]** The creator must be a **member or lead of the target team**. Without this, any user could attach a project to any team in the system and force work onto strangers. "Any user can create a Project" remains true in effect: any user may create a team (TM-1) and then a project on it. | 403 |
| View project | PR-3 | The Project Owner, and members/lead of the project's team | 404 |
| List my projects | PR-4 | Any user: projects they own, plus projects of teams they lead or belong to | -- |
| Rename project | PR-5 | **[INTERPRETATION]** Project Owner only -- the analogue of TM-5. | 403 |
| Transfer ownership | PR-6 | Current Project Owner only (`spec.md`: "Project Owners may transfer ownership of a Project to another User") | 403 |
| | PR-7 | **[INTERPRETATION]** The new owner may be **any existing user**; team membership is not required (INV-2). | 400 if the user does not exist |
| | PR-8 | **[INTERPRETATION]** If the incoming owner is currently the assignee of live tasks in this project, the transfer is rejected with **409**, naming the conflicting tasks. Completing it would violate AS-4. The lead must reassign those tasks first. | 409 |
| Move project to another team | PR-9 | **Not supported.** `spec.md` gives no semantics for the fate of in-flight assignments. | 405 |
| Delete project | PR-10 | **Not supported.** | 405 |

---

## 9. User operations

- **US-1**: `Name` is required, 1..200 characters.
- **US-2**: `Email` is required, 5..254 characters, must be a valid address
  format, and must be **unique** across all users -> duplicate is **409**.
- **US-3**: Users are provisioned from the authenticated OAuth principal on first
  sight (subject + email + name claims). There is no public user-registration
  endpoint; identity is owned by the identity provider.
- **[INTERPRETATION] US-4**: Any authenticated user may look up any other user's
  id, name and email. Assignment, ownership transfer and lead transfer all
  require naming another user, so a directory is functionally required.
- **[INTERPRETATION] US-5**: A user may update their own name only. Email is
  identity-provider owned and cannot be changed through Hive.

---

## 10. Authentication and transport

- **AU-1**: Every endpoint except health and OpenAPI requires a valid **JWT
  bearer token**. Missing, malformed, or expired -> **401 Unauthorized**.
- **AU-2**: The acting user is resolved from the token, never from a request
  body or query parameter. No endpoint accepts an `actingUserId`.
- **AU-3**: **HTTPS is enforced.** Plaintext requests are redirected or rejected
  at every tier.

---

## 11. Error mapping summary

| Situation | Status |
|-----------|--------|
| Malformed body, failed field validation, referenced user does not exist | 400 |
| No token / invalid token / expired token | 401 |
| Actor is known and the resource is visible, but the actor lacks the role for this operation | 403 |
| Resource does not exist, **or exists but is invisible to the actor** (section 4, TM-2, PR-3) | 404 |
| Operation is impossible in the resource's current state: illegal transition, terminal-state edit, assigning a Draft task, removing the lead, transferring ownership to a live assignee, duplicate email | 409 |
| Unsupported operation on an existing resource (delete, move) | 405 |
| Anything else | 500, with no implementation detail in the body |

**Ordering rule**, applied consistently everywhere: authenticate (401) ->
resolve visibility (404) -> check state legality (409) -> check actor role (403)
-> validate payload (400).

> Note the deliberate placement of 409 before 403: whether an operation is
> possible at all does not depend on who is asking, and answering "that task is
> already Completed" leaks nothing to a user who is already permitted to see the
> task.

---

## 12. Clarifications settled during implementation

These questions were raised by the implementation and are answered here so the
answer lives with the rules rather than in a commit message.

- **AS-2's 400-vs-403 split versus the ordering rule.** Section 11 places 400
  last, but AS-2 wants 400 for a *nonexistent* assignee and 403 for a real user
  who is not a member. The policy sees only ids, so it answers 403 for both.
  Implementation therefore calls `checkAssign` first (preserving 409-then-403)
  and *refines* a 403 to a 400 only when the named user genuinely does not
  exist. Consequence: a non-lead who names a nonexistent user receives 400
  rather than 403. This discloses nothing, because US-4 already makes the user
  directory readable by any authenticated caller.

- **AS-3's 409 is reachable only by a project owner.** A Team Lead cannot see a
  `Draft` task (VIS-3), so for a lead `PUT /tasks/{id}/assignee` answers 404
  before AS-3's 409 is ever considered. That is correct under the ordering rule
  in section 11; it simply means "409 if the task is Draft" describes the
  owner's experience, not the lead's. Both paths are tested.

- **The OAuth subject claim is not stored.** `User` has no subject field, so
  US-3 matches a principal to a Hive user **by email**, which is sound only
  because US-2 makes email unique. The consequence worth knowing: if a user's
  address changes at the identity provider, they become a new Hive user rather
  than the same one. Fixing that means adding a subject column to the domain
  model, and is deliberately out of scope here.

- **Repeat sightings do not refresh the stored name.** `provisionFromPrincipal`
  sets the name only on first creation. Refreshing it from the token on every
  login would silently undo US-5, which grants the user control of their own
  display name.

- **Commenting on a task the actor can see is never 403.** CM-1 makes
  commentability identical to visibility, so the only failure is 404. This is
  why `TaskPermissions.canComment` is true whenever the task was returned at
  all.
