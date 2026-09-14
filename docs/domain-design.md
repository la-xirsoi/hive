# Domain Layer Design

Implementation guide for `backend/src/main/kotlin/hive/domain/`. Binding on all
domain work so that independently written pieces fit together.

## Hard rule: no framework imports

Nothing under `hive.domain` may import `org.springframework.*`, `jakarta.*`,
`org.hibernate.*`, or any other framework. The package compiles against the
Kotlin standard library alone. This is checked mechanically in the final
verification pass.

## Package layout

```
hive/domain/
  model/        entities and value objects
  policy/       AuthorizationPolicy, TaskTransitions
  port/         repository interfaces (outbound ports)
  error/        DomainException hierarchy
  Clock.kt      time abstraction
```

## Identifiers

Inline value classes over `Long`, one per aggregate:

```kotlin
@JvmInline value class UserId(val value: Long)
@JvmInline value class TeamId(val value: Long)
@JvmInline value class ProjectId(val value: Long)
@JvmInline value class TaskId(val value: Long)
@JvmInline value class CommentId(val value: Long)
```

Entities not yet persisted carry `null` for their id rather than a sentinel.

## Value objects

Each validates in `init` and throws `ValidationException` with the offending
field name. Each exposes `value` and a sensible `toString`.

| Type | Constraint |
|------|-----------|
| `PersonName` | trimmed, 1..200, not blank |
| `EmailAddress` | trimmed, lowercased for comparison, 5..254, matches a pragmatic RFC 5322 subset; equality and uniqueness are case-insensitive |
| `TeamName`, `ProjectName` | trimmed, 1..200, not blank |
| `TaskName` | trimmed, 1..200, not blank |
| `TaskDescription` | 0..4000; may be empty but not null |
| `CommentContent` | trimmed, 1..4000, not blank |

Email comparison is case-insensitive because uniqueness that treats
`A@x.com` and `a@x.com` as different addresses is a defect, not a feature.

## Exceptions

```kotlin
sealed class DomainException(message: String) : RuntimeException(message)

class ValidationException(val fieldErrors: List<FieldError>) : DomainException(...)
data class FieldError(val field: String, val message: String)

class NotFoundException(val resource: String, val id: Any?) : DomainException(...)
class ConflictException(message: String) : DomainException(...)
class AuthorizationException(message: String) : DomainException(...)
```

Messages are end-user readable and free of implementation detail. `401` has no
domain exception: authentication is a security-adapter concern.

## Entities

Entities are immutable data holders with operations returning **new instances**
rather than mutating. This keeps the state machine honest and makes tests
trivial.

```kotlin
data class User(val id: UserId?, val name: PersonName, val email: EmailAddress)

data class Team(
    val id: TeamId?,
    val name: TeamName,
    val teamLead: UserId,
    val memberIds: Set<UserId>,     // INV-1: always contains teamLead
)

data class Project(
    val id: ProjectId?,
    val name: ProjectName,
    val teamId: TeamId,
    val projectOwner: UserId,
)

data class Task(
    val id: TaskId?,
    val name: TaskName,
    val description: TaskDescription,
    val projectId: ProjectId,
    val creator: UserId,
    val assignee: UserId?,
    val status: TaskStatus,
)

data class Comment(
    val id: CommentId?,
    val taskId: TaskId,
    val author: UserId,
    val timestamp: Instant,          // UTC, truncated to the minute
    val content: CommentContent,
)
```

`Team.init` enforces INV-1 by construction: if `teamLead !in memberIds` the
constructor adds it rather than throwing, so the invariant cannot be violated
anywhere in the system. `Team.transferLeadTo(newLead)` returns a team with the
new lead added to `memberIds`; the outgoing lead stays a member (TM-10).
`Team.removeMember(userId)` throws `ConflictException` when `userId` is the lead
(TM-7).

## TaskStatus and transitions

```kotlin
enum class TaskStatus(val wireName: String) {
    DRAFT("Draft"), TODO("Todo"), IN_PROGRESS("In Progress"),
    COMPLETED("Completed"), CANCELED("Canceled");

    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELED
}
```

`wireName` is the exact string in `spec.md` and on the wire; the enum name is
the Kotlin-idiomatic form. Parsing an unknown wire name is a `ValidationException`.

### The actor's relationship to a task

The policy never receives "a role" as a string. It receives the facts and
derives the role:

```kotlin
data class TaskContext(
    val task: Task,
    val project: Project,
    val team: Team,
    val actor: UserId,
) {
    val isProjectOwner: Boolean get() = project.projectOwner == actor
    val isTeamLead: Boolean get() = team.teamLead == actor
    val isTeamMember: Boolean get() = actor in team.memberIds
    val isAssignee: Boolean get() = task.assignee == actor
}
```

### The transition function

```kotlin
sealed interface TransitionResult {
    data class Allowed(val task: Task) : TransitionResult
    data class Illegal(val reason: String) : TransitionResult      // -> 409
    data class Forbidden(val reason: String) : TransitionResult    // -> 403
}

fun TaskTransitions.attempt(ctx: TaskContext, target: TaskStatus): TransitionResult
```

**Order is mandatory and must be tested:** legality first, then authorization.

1. `ctx.task.status.isTerminal` -> `Illegal` (TE-2)
2. `target == ctx.task.status` -> `Illegal` (TR-3)
3. the pair `(from, to)` is not in the table -> `Illegal`
4. `TODO -> IN_PROGRESS` with `task.assignee == null` -> `Illegal` (TR-2)
5. required actor for that pair is not satisfied -> `Forbidden`
6. otherwise -> `Allowed(task.copy(status = target))`

Required actors: `DRAFT->TODO`, `DRAFT->CANCELED`, `TODO->CANCELED`,
`IN_PROGRESS->CANCELED` require `isProjectOwner`; `TODO->IN_PROGRESS` and
`IN_PROGRESS->COMPLETED` require `isAssignee`.

The unit test for this function must be table-driven over the full cross product
of 5 from-states x 5 to-states x the actor-role combinations, asserting the exact
result variant. That is the single most important test in the codebase.

## AuthorizationPolicy

A stateless object taking already-loaded aggregates -- it performs no I/O, so it
is trivially unit testable. The application layer loads what the policy needs.

```kotlin
object AuthorizationPolicy {
    fun canViewTask(ctx: TaskContext): Boolean                 // VIS-1..VIS-5
    fun canEditTaskFields(ctx: TaskContext): Unit-or-throw     // TK-3, TE-1
    fun checkAssign(ctx: TaskContext, newAssignee: UserId?): Unit-or-throw
                                                               // AS-1..AS-7, TE-3
    fun canViewProject(project: Project, team: Team, actor: UserId): Boolean
    fun checkRenameProject(...); fun checkTransferProjectOwner(...)
    fun canViewTeam(team: Team, actor: UserId, ownsAProjectOfTeam: Boolean): Boolean
    fun checkRenameTeam(...); fun checkAddMember(...);
    fun checkRemoveMember(...); fun checkTransferLead(...)
    fun canCommentOnTask(ctx: TaskContext): Boolean            // == canViewTask
}
```

Convention: `canX` returns `Boolean` and is used for visibility decisions that
become 404. `checkX` throws `AuthorizationException` (403) or
`ConflictException` (409) and is used for operations. Mixing the two conventions
is what produces the wrong status code, so the naming is deliberate.

### canViewTask, precisely

```
isAssignee                                  -> true   (VIS-1, VIS-5)
isProjectOwner                              -> true   (VIS-2, any status)
isTeamLead   && status != DRAFT             -> true   (VIS-3)
isTeamMember && status !in {DRAFT, CANCELED}-> true   (VIS-4)
otherwise                                   -> false
```

### checkAssign, precisely

```
task.status.isTerminal            -> Conflict  (TE-3)
task.status == DRAFT              -> Conflict  (AS-3)
newAssignee == null && status != TODO -> Conflict  (AS-7)
!isTeamLead                       -> Forbidden (AS-1)
newAssignee == project.projectOwner -> Forbidden (AS-4)
newAssignee !in team.memberIds    -> Forbidden (AS-2)
otherwise                         -> allowed             (AS-5 needs no clause:
                                     a lead assigning to themselves passes the
                                     membership check via INV-1)
```

Order matters here too: state conflicts precede role checks.

## Repository ports

Return domain types only. Role-scoped queries are ports, not in-memory filters,
so the adapter can push them into SQL.

```kotlin
interface UserRepository {
    fun findById(id: UserId): User?
    fun findByEmail(email: EmailAddress): User?
    fun findAllById(ids: Set<UserId>): List<User>
    fun search(query: String?, page: PageRequest): Page<User>
    fun save(user: User): User
}

interface TeamRepository {
    fun findById(id: TeamId): Team?
    fun findTeamsForMember(userId: UserId): List<Team>
    fun findTeamsLedBy(userId: UserId): List<Team>
    fun save(team: Team): Team
}

interface ProjectRepository {
    fun findById(id: ProjectId): Project?
    fun findByTeam(teamId: TeamId): List<Project>
    fun findVisibleTo(userId: UserId): List<Project>     // PR-4
    fun save(project: Project): Project
}

interface TaskRepository {
    fun findById(id: TaskId): Task?
    fun findAssignedTo(userId: UserId, page: PageRequest): Page<Task>       // VIS-1
    fun findVisibleInProject(projectId: ProjectId, viewer: UserId,
                             page: PageRequest): Page<Task>                 // VIS-*
    fun findUnassignedForLead(leadId: UserId, page: PageRequest): Page<Task>// UQ-1
    fun findLiveTasksAssignedTo(userId: UserId, projectId: ProjectId?): List<Task>
                                                                            // TM-8, PR-8
    fun save(task: Task): Task
    fun saveAll(tasks: List<Task>): List<Task>
}

interface CommentRepository {
    fun findByTask(taskId: TaskId, page: PageRequest): Page<Comment>
    fun save(comment: Comment): Comment
}
```

`PageRequest` and `Page` are **domain-owned** types (a small data class and a
generic holder), not Spring Data's. The domain must not depend on Spring, and
the persistence adapter converts at its boundary.

## Clock

```kotlin
fun interface HiveClock { fun nowUtcToMinute(): Instant }
```

The production implementation truncates `Instant.now()` to
`ChronoUnit.MINUTES`. Tests inject a fixed clock. No domain code calls
`Instant.now()` directly.
