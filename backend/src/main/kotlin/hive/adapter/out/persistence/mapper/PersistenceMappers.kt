package hive.adapter.out.persistence.mapper

import hive.adapter.out.persistence.entity.CommentEntity
import hive.adapter.out.persistence.entity.ProjectEntity
import hive.adapter.out.persistence.entity.TaskEntity
import hive.adapter.out.persistence.entity.TeamEntity
import hive.adapter.out.persistence.entity.UserEntity
import hive.domain.model.Comment
import hive.domain.model.CommentContent
import hive.domain.model.CommentId
import hive.domain.model.EmailAddress
import hive.domain.model.PersonName
import hive.domain.model.Project
import hive.domain.model.ProjectId
import hive.domain.model.ProjectName
import hive.domain.model.Task
import hive.domain.model.TaskDescription
import hive.domain.model.TaskId
import hive.domain.model.TaskName
import hive.domain.model.TaskStatus
import hive.domain.model.Team
import hive.domain.model.TeamId
import hive.domain.model.TeamName
import hive.domain.model.User
import hive.domain.model.UserId
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * The translation between JPA rows and domain objects, in both directions.
 *
 * This is the *only* place the two models meet. Keeping it in one file means the
 * question "can a row become a domain object that violates an invariant?" has a
 * single place to look, and the round-trip tests have a single thing to
 * exercise.
 *
 * Going **out of** the database is the interesting direction: every value object
 * re-validates in its constructor, so a row that was corrupted by hand, by an
 * out-of-band script, or by a future migration cannot enter the domain quietly
 * as a blank task name or an unparseable status -- it throws at the boundary.
 *
 * Going **into** the database, note that ids are unwrapped to nullable `Long`:
 * a domain entity with a null id has never been persisted, and the schema's
 * `IDENTITY(1,1)` columns allocate on insert.
 */
object PersistenceMappers {

    // --- users ---------------------------------------------------------------

    fun toDomain(entity: UserEntity): User =
        User(
            id = entity.id?.let(::UserId),
            name = PersonName(entity.name),
            email = EmailAddress(entity.email),
        )

    /**
     * US-2: [UserEntity.email] stores the **normalized** address, because
     * `EmailAddress` defines equality case-insensitively and the schema enforces
     * uniqueness with a plain unique constraint. Normalizing here rather than in
     * SQL keeps the rule out of the database's collation settings.
     */
    fun toEntity(user: User): UserEntity =
        UserEntity(
            id = user.id?.value,
            name = user.name.value,
            email = user.email.normalized,
        )

    // --- teams ---------------------------------------------------------------

    /**
     * INV-1 holds on the way out for free: the [Team] constructor adds the lead
     * to the member set, so even a `team_members` table that somehow lost the
     * lead's row yields a correct aggregate.
     */
    fun toDomain(entity: TeamEntity): Team =
        Team(
            id = entity.id?.let(::TeamId),
            name = TeamName(entity.name),
            teamLead = UserId(entity.teamLead),
            memberIds = entity.memberIds.mapTo(mutableSetOf(), ::UserId),
        )

    fun toEntity(team: Team): TeamEntity =
        TeamEntity(
            id = team.id?.value,
            name = team.name.value,
            teamLead = team.teamLead.value,
            memberIds = team.memberIds.mapTo(mutableSetOf()) { it.value },
        )

    // --- projects ------------------------------------------------------------

    fun toDomain(entity: ProjectEntity): Project =
        Project(
            id = entity.id?.let(::ProjectId),
            name = ProjectName(entity.name),
            teamId = TeamId(entity.teamId),
            projectOwner = UserId(entity.projectOwner),
        )

    fun toEntity(project: Project): ProjectEntity =
        ProjectEntity(
            id = project.id?.value,
            name = project.name.value,
            teamId = project.teamId.value,
            projectOwner = project.projectOwner.value,
        )

    // --- tasks ---------------------------------------------------------------

    /**
     * [TaskStatus.fromWireName] throws on an unknown spelling rather than
     * defaulting to `Draft`. A row the `CK_tasks_status` constraint should have
     * rejected must not be silently reinterpreted as the most permissive status.
     */
    fun toDomain(entity: TaskEntity): Task =
        Task(
            id = entity.id?.let(::TaskId),
            name = TaskName(entity.name),
            description = TaskDescription(entity.description),
            projectId = ProjectId(entity.projectId),
            creator = UserId(entity.creator),
            assignee = entity.assignee?.let(::UserId),
            status = TaskStatus.fromWireName(entity.status),
        )

    fun toEntity(task: Task): TaskEntity =
        TaskEntity(
            id = task.id?.value,
            name = task.name.value,
            description = task.description.value,
            projectId = task.projectId.value,
            creator = task.creator.value,
            assignee = task.assignee?.value,
            status = task.status.wireName,
        )

    // --- comments ------------------------------------------------------------

    /**
     * CM-5: the stored `DATETIME2(0)` is UTC wall-clock, so it is read back at
     * [ZoneOffset.UTC] and truncated to the minute again. The second truncation
     * is belt and braces -- the column cannot hold sub-second precision anyway --
     * but it makes the domain's guarantee independent of the column's declared
     * scale, which a future migration could widen.
     */
    fun toDomain(entity: CommentEntity): Comment =
        Comment(
            id = entity.id?.let(::CommentId),
            taskId = TaskId(entity.taskId),
            author = UserId(entity.author),
            timestamp = entity.createdAt.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES),
            content = CommentContent(entity.content),
        )

    fun toEntity(comment: Comment): CommentEntity =
        CommentEntity(
            id = comment.id?.value,
            taskId = comment.taskId.value,
            author = comment.author.value,
            createdAt = comment.timestamp.toUtcMinute(),
            content = comment.content.value,
        )

    private fun Instant.toUtcMinute(): LocalDateTime =
        LocalDateTime.ofInstant(truncatedTo(ChronoUnit.MINUTES), ZoneOffset.UTC)
}
