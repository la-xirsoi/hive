package hive.adapter.out.persistence.mapper

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
import hive.domain.error.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Round-trip tests for the entity <-> domain mappers.
 *
 * These need no database: the mappers are pure functions, and the property that
 * matters -- that `toDomain(toEntity(x)) == x` -- is a property of those
 * functions alone. The database's part of the contract (that a `DATETIME2(0)`
 * column really does return what was written) is proved separately in
 * `SchemaConstraintIT`.
 */
@DisplayName("PersistenceMappers")
class PersistenceMappersTest {

    @Nested
    @DisplayName("users")
    inner class Users {

        @Test
        fun `round-trips a persisted user`() {
            val user = User(UserId(7), PersonName("Bee Keeper"), EmailAddress("bee@hive.example"))

            assertThat(PersistenceMappers.toDomain(PersistenceMappers.toEntity(user))).isEqualTo(user)
        }

        @Test
        fun `round-trips an unsaved user, keeping the null id`() {
            val user = User(null, PersonName("Nobody Yet"), EmailAddress("new@hive.example"))

            val entity = PersistenceMappers.toEntity(user)

            assertThat(entity.id).isNull()
            assertThat(PersistenceMappers.toDomain(entity)).isEqualTo(user)
        }

        @Test
        fun `US-2 -- the stored email is normalized to lower case`() {
            val user = User(UserId(1), PersonName("Loud"), EmailAddress("SHOUTING@Hive.Example"))

            assertThat(PersistenceMappers.toEntity(user).email).isEqualTo("shouting@hive.example")
        }

        @Test
        fun `US-2 -- normalizing loses nothing the domain can observe, because email equality ignores case`() {
            val user = User(UserId(1), PersonName("Loud"), EmailAddress("SHOUTING@Hive.Example"))

            val roundTripped = PersistenceMappers.toDomain(PersistenceMappers.toEntity(user))

            assertThat(roundTripped).isEqualTo(user)
            assertThat(roundTripped.email).isEqualTo(user.email)
        }

        @Test
        fun `a corrupt row cannot enter the domain quietly`() {
            val corrupt = PersistenceMappers.toEntity(
                User(UserId(1), PersonName("Fine"), EmailAddress("fine@hive.example")),
            )
            corrupt.email = "not-an-email"

            assertThatThrownBy { PersistenceMappers.toDomain(corrupt) }
                .isInstanceOf(ValidationException::class.java)
        }
    }

    @Nested
    @DisplayName("teams")
    inner class Teams {

        @Test
        fun `round-trips a team with its membership set`() {
            val team = Team(
                id = TeamId(3),
                name = TeamName("Hive Core"),
                teamLead = UserId(2),
                memberIds = setOf(UserId(2), UserId(4), UserId(5)),
            )

            assertThat(PersistenceMappers.toDomain(PersistenceMappers.toEntity(team))).isEqualTo(team)
        }

        @Test
        fun `INV-1 -- a row set missing the lead still yields a team whose lead is a member`() {
            val entity = PersistenceMappers.toEntity(
                Team(TeamId(3), TeamName("Hive Core"), UserId(2), setOf(UserId(2), UserId(4))),
            )
            entity.memberIds.remove(2L)

            val team = PersistenceMappers.toDomain(entity)

            assertThat(team.memberIds).containsExactlyInAnyOrder(UserId(2), UserId(4))
            assertThat(team.hasMember(UserId(2))).isTrue()
        }

        @Test
        fun `round-trips an unsaved team, keeping the null id`() {
            val team = Team(null, TeamName("Brand New"), UserId(2), setOf(UserId(4)))

            val entity = PersistenceMappers.toEntity(team)

            assertThat(entity.id).isNull()
            assertThat(PersistenceMappers.toDomain(entity)).isEqualTo(team)
        }

        @Test
        fun `the lead is written into team_members`() {
            val team = Team(TeamId(3), TeamName("Hive Core"), UserId(2), setOf(UserId(4)))

            assertThat(PersistenceMappers.toEntity(team).memberIds).containsExactlyInAnyOrder(2L, 4L)
        }
    }

    @Nested
    @DisplayName("projects")
    inner class Projects {

        @Test
        fun `round-trips a project`() {
            val project = Project(ProjectId(9), ProjectName("Apiary"), TeamId(3), UserId(1))

            assertThat(PersistenceMappers.toDomain(PersistenceMappers.toEntity(project))).isEqualTo(project)
        }

        @Test
        fun `round-trips an unsaved project, keeping the null id`() {
            val project = Project(null, ProjectName("Apiary"), TeamId(3), UserId(1))

            val entity = PersistenceMappers.toEntity(project)

            assertThat(entity.id).isNull()
            assertThat(PersistenceMappers.toDomain(entity)).isEqualTo(project)
        }
    }

    @Nested
    @DisplayName("tasks")
    inner class Tasks {

        @ParameterizedTest
        @EnumSource(TaskStatus::class)
        fun `round-trips a task in every status`(status: TaskStatus) {
            val task = Task(
                id = TaskId(11),
                name = TaskName("Requeen hive 4"),
                description = TaskDescription("The colony is queenless.\n  Indented line."),
                projectId = ProjectId(9),
                creator = UserId(1),
                assignee = UserId(3),
                status = status,
            )

            assertThat(PersistenceMappers.toDomain(PersistenceMappers.toEntity(task))).isEqualTo(task)
        }

        @Test
        fun `stores the spec wire spelling, not the Kotlin constant name`() {
            val task = fixtureTask(TaskStatus.IN_PROGRESS)

            assertThat(PersistenceMappers.toEntity(task).status).isEqualTo("In Progress")
        }

        @Test
        fun `round-trips an unassigned task`() {
            val task = fixtureTask(TaskStatus.TODO, assignee = null)

            val entity = PersistenceMappers.toEntity(task)

            assertThat(entity.assignee).isNull()
            assertThat(PersistenceMappers.toDomain(entity)).isEqualTo(task)
        }

        @Test
        fun `round-trips an unsaved task, keeping the null id`() {
            val task = fixtureTask(TaskStatus.DRAFT, assignee = null).copy(id = null)

            val entity = PersistenceMappers.toEntity(task)

            assertThat(entity.id).isNull()
            assertThat(PersistenceMappers.toDomain(entity)).isEqualTo(task)
        }

        @Test
        fun `round-trips an empty description without turning it into null`() {
            val task = fixtureTask(TaskStatus.DRAFT).copy(description = TaskDescription.EMPTY)

            val roundTripped = PersistenceMappers.toDomain(PersistenceMappers.toEntity(task))

            assertThat(roundTripped.description).isEqualTo(TaskDescription.EMPTY)
        }

        @Test
        fun `an unknown status is rejected rather than defaulted`() {
            val entity = PersistenceMappers.toEntity(fixtureTask(TaskStatus.TODO))
            entity.status = "IN_PROGRESS" // the constant name -- what @Enumerated would have written

            assertThatThrownBy { PersistenceMappers.toDomain(entity) }
                .isInstanceOf(ValidationException::class.java)
        }

        private fun fixtureTask(status: TaskStatus, assignee: UserId? = UserId(3)) =
            Task(
                id = TaskId(11),
                name = TaskName("Requeen hive 4"),
                description = TaskDescription("The colony is queenless."),
                projectId = ProjectId(9),
                creator = UserId(1),
                assignee = assignee,
                status = status,
            )
    }

    @Nested
    @DisplayName("comments")
    inner class Comments {

        @Test
        fun `round-trips a comment`() {
            val comment = Comment(
                id = CommentId(5),
                taskId = TaskId(11),
                author = UserId(3),
                timestamp = Instant.parse("2026-09-14T10:31:00Z"),
                content = CommentContent("Requeened this morning."),
            )

            assertThat(PersistenceMappers.toDomain(PersistenceMappers.toEntity(comment))).isEqualTo(comment)
        }

        @Test
        fun `round-trips an unsaved comment, keeping the null id`() {
            val comment = Comment(
                id = null,
                taskId = TaskId(11),
                author = UserId(3),
                timestamp = Instant.parse("2026-09-14T10:31:00Z"),
                content = CommentContent("Fresh."),
            )

            val entity = PersistenceMappers.toEntity(comment)

            assertThat(entity.id).isNull()
            assertThat(PersistenceMappers.toDomain(entity)).isEqualTo(comment)
        }

        @Test
        fun `CM-5 -- the entity holds UTC wall-clock, independent of the JVM default zone`() {
            val comment = Comment(
                id = null,
                taskId = TaskId(11),
                author = UserId(3),
                timestamp = Instant.parse("2026-09-14T23:45:00Z"),
                content = CommentContent("Late note."),
            )

            val entity = PersistenceMappers.toEntity(comment)

            assertThat(entity.createdAt)
                .isEqualTo(Instant.parse("2026-09-14T23:45:00Z").atZone(ZoneOffset.UTC).toLocalDateTime())
        }

        @Test
        fun `CM-5 -- a sub-minute instant is truncated on the way in`() {
            val comment = Comment(
                id = null,
                taskId = TaskId(11),
                author = UserId(3),
                timestamp = Instant.parse("2026-09-14T10:31:59.999Z"),
                content = CommentContent("Nearly the next minute."),
            )

            val roundTripped = PersistenceMappers.toDomain(PersistenceMappers.toEntity(comment))

            assertThat(roundTripped.timestamp).isEqualTo(Instant.parse("2026-09-14T10:31:00Z"))
            assertThat(roundTripped.timestamp.truncatedTo(ChronoUnit.MINUTES))
                .isEqualTo(roundTripped.timestamp)
        }
    }
}
