package hive.adapter.out.persistence

import hive.adapter.out.persistence.entity.TaskEntity
import hive.adapter.out.persistence.jpa.TaskJpaRepository
import hive.domain.HiveClock
import hive.domain.model.Comment
import hive.domain.model.CommentContent
import hive.domain.model.EmailAddress
import hive.domain.model.PageRequest
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
import hive.domain.port.CommentRepository
import hive.domain.port.ProjectRepository
import hive.domain.port.TaskRepository
import hive.domain.port.TeamRepository
import hive.domain.port.UserRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * What the *schema* guarantees, as opposed to what the queries return.
 *
 * Every test here would still pass if the Kotlin were deleted and rewritten: the
 * subject is `V1__baseline.sql` and the entity mappings that must agree with it.
 * That the context starts at all is itself an assertion, because the `test`
 * profile runs Flyway and then sets `ddl-auto: validate` -- a column the
 * migration and the entities disagree about fails every test in this package,
 * loudly, at startup.
 */
@DisplayName("schema constraints")
class SchemaConstraintIT : PersistenceIntegrationTest() {

    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var teams: TeamRepository
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var tasks: TaskRepository
    @Autowired private lateinit var comments: CommentRepository
    @Autowired private lateinit var taskRows: TaskJpaRepository

    @PersistenceContext private lateinit var entityManager: EntityManager

    @Nested
    @DisplayName("users")
    inner class Users {

        @Test
        fun `US-2 -- a duplicate email is rejected by UQ_users_email`() {
            users.save(User(null, PersonName("First"), EmailAddress("clash@hive.example")))

            assertViolates("UQ_USERS_EMAIL") {
                users.save(User(null, PersonName("Second"), EmailAddress("clash@hive.example")))
            }
        }

        @Test
        fun `US-2 -- uniqueness is case-insensitive, because the stored address is normalized`() {
            users.save(User(null, PersonName("Lower"), EmailAddress("case@hive.example")))

            assertViolates("UQ_USERS_EMAIL") {
                users.save(User(null, PersonName("Upper"), EmailAddress("CASE@Hive.Example")))
            }
        }

        @Test
        fun `US-2 -- lookup by a differently-cased address finds the same user`() {
            val saved = users.save(User(null, PersonName("Mixed"), EmailAddress("Mixed.Case@Hive.example")))

            assertThat(users.findByEmail(EmailAddress("mixed.case@hive.EXAMPLE"))).isEqualTo(saved)
        }

        @Test
        fun `an id is allocated by the database, not by the application`() {
            val saved = users.save(User(null, PersonName("Fresh"), EmailAddress("fresh@hive.example")))

            assertThat(saved.id).isNotNull()
            assertThat(users.findById(saved.id!!)).isEqualTo(saved)
        }

        @Test
        fun `save updates in place when the user already has an id`() {
            val saved = users.save(User(null, PersonName("Before"), EmailAddress("rename@hive.example")))

            val renamed = users.save(saved.rename(PersonName("After")))

            assertThat(renamed.id).isEqualTo(saved.id)
            assertThat(users.findById(saved.id!!)!!.name).isEqualTo(PersonName("After"))
        }

        @Test
        fun `US-4 -- the directory searches name and email, case-insensitively`() {
            val ada = users.save(User(null, PersonName("Ada Apiarist"), EmailAddress("ada@hive.example")))
            users.save(User(null, PersonName("Bob Beekeeper"), EmailAddress("bob@elsewhere.example")))

            assertThat(users.search("APIAR", PageRequest.DEFAULT).content).containsExactly(ada)
            assertThat(users.search("ada@HIVE", PageRequest.DEFAULT).content).containsExactly(ada)
            assertThat(users.search("nobody-matches-this", PageRequest.DEFAULT).content).isEmpty()
        }

        @Test
        fun `US-4 -- a null or blank query matches everyone`() {
            users.save(User(null, PersonName("Only One"), EmailAddress("only@hive.example")))

            assertThat(users.search(null, PageRequest.DEFAULT).totalElements).isEqualTo(1)
            assertThat(users.search("   ", PageRequest.DEFAULT).totalElements).isEqualTo(1)
        }

        @Test
        fun `findAllById of an empty set does not reach the database`() {
            assertThat(users.findAllById(emptySet())).isEmpty()
        }

        @Test
        fun `findAllById returns exactly the users asked for`() {
            val a = users.save(User(null, PersonName("A"), EmailAddress("a@hive.example")))
            val b = users.save(User(null, PersonName("B"), EmailAddress("b@hive.example")))
            users.save(User(null, PersonName("C"), EmailAddress("c@hive.example")))

            assertThat(users.findAllById(setOf(a.id!!, b.id!!))).containsExactlyInAnyOrder(a, b)
        }

        @Test
        fun `an unknown id is a null, not an exception`() {
            assertThat(users.findById(UserId(987_654))).isNull()
        }
    }

    @Nested
    @DisplayName("referential integrity")
    inner class References {

        @Test
        fun `FK_tasks_project rejects a task in a project that does not exist`() {
            val creator = users.save(User(null, PersonName("C"), EmailAddress("fk1@hive.example"))).id!!

            assertViolates("FK_TASKS_PROJECT") {
                tasks.save(
                    Task(
                        id = null,
                        name = TaskName("Orphan"),
                        description = TaskDescription(""),
                        projectId = ProjectId(999_999),
                        creator = creator,
                        assignee = null,
                        status = TaskStatus.DRAFT,
                    ),
                )
            }
        }

        @Test
        fun `FK_tasks_assignee rejects an assignee that does not exist`() {
            val fixture = SmallFixture()

            assertViolates("FK_TASKS_ASSIGNEE") {
                tasks.save(fixture.task(TaskStatus.TODO, assignee = UserId(999_999)))
            }
        }

        @Test
        fun `a null assignee is accepted -- the only nullable foreign key in the schema`() {
            val fixture = SmallFixture()

            val saved = tasks.save(fixture.task(TaskStatus.TODO, assignee = null))
            entityManager.flush()

            assertThat(saved.assignee).isNull()
            assertThat(tasks.findById(saved.id!!)!!.assignee).isNull()
        }

        @Test
        fun `FK_team_members_user rejects a member who does not exist`() {
            val lead = users.save(User(null, PersonName("L"), EmailAddress("fk2@hive.example"))).id!!

            assertViolates("FK_TEAM_MEMBERS_USER") {
                teams.save(Team(null, TeamName("Ghosts"), lead, setOf(UserId(999_999))))
            }
        }

        @Test
        fun `FK_projects_team rejects a project on a team that does not exist`() {
            val owner = users.save(User(null, PersonName("O"), EmailAddress("fk3@hive.example"))).id!!

            assertViolates("FK_PROJECTS_TEAM") {
                projects.save(Project(null, ProjectName("Nowhere"), TeamId(999_999), owner))
            }
        }

        @Test
        fun `updating a team replaces its membership rows rather than accumulating them`() {
            val fixture = SmallFixture()
            val extra = users.save(User(null, PersonName("Extra"), EmailAddress("extra@hive.example"))).id!!

            val grown = teams.save(teams.findById(fixture.teamId)!!.addMember(extra))
            assertThat(grown.memberIds).contains(extra)

            val shrunk = teams.save(grown.removeMember(extra))
            entityManager.flush()

            assertThat(teams.findById(fixture.teamId)!!.memberIds)
                .isEqualTo(shrunk.memberIds)
                .doesNotContain(extra)
        }
    }

    @Nested
    @DisplayName("CK_tasks_status")
    inner class StatusCheck {

        @Test
        fun `every status the domain defines is accepted by the check constraint`() {
            val fixture = SmallFixture()

            TaskStatus.entries.forEach { status ->
                tasks.save(fixture.task(status, assignee = null))
            }
            entityManager.flush()

            assertThat(tasks.findVisibleInProject(fixture.projectId, fixture.ownerId, PageRequest.DEFAULT).content)
                .hasSize(TaskStatus.entries.size)
        }

        @Test
        fun `a status the domain does not define is rejected by the database`() {
            val fixture = SmallFixture()
            val row = TaskEntity(
                id = null,
                name = "Smuggled",
                description = "",
                projectId = fixture.projectId.value,
                creator = fixture.ownerId.value,
                assignee = null,
                status = "Archived",
            )

            assertViolates("CK_TASKS_STATUS") { taskRows.save(row) }
        }

        @Test
        fun `the wire spelling with a space survives the round trip`() {
            val fixture = SmallFixture()

            val savedId = tasks.save(fixture.task(TaskStatus.IN_PROGRESS, assignee = null)).id!!
            entityManager.flush()
            entityManager.clear()

            assertThat(tasks.findById(savedId)!!.status).isEqualTo(TaskStatus.IN_PROGRESS)
            assertThat(taskRows.findById(savedId.value).orElseThrow().status).isEqualTo("In Progress")
        }
    }

    @Nested
    @DisplayName("comments -- CM-5, CM-2")
    inner class Comments {

        @Test
        fun `a minute-precision timestamp round-trips through DATETIME2(0) exactly`() {
            val fixture = SmallFixture()
            val taskId = tasks.save(fixture.task(TaskStatus.TODO, assignee = null)).id!!
            val instant = Instant.parse("2026-09-14T10:31:00Z")

            val saved = comments.save(
                Comment.create(taskId, fixture.ownerId, CommentContent("Noted."), HiveClock.fixedAt(instant)),
            )
            entityManager.flush()
            entityManager.clear()

            val read = comments.findByTask(taskId, PageRequest.DEFAULT).content.single()

            assertThat(read.timestamp).isEqualTo(instant)
            assertThat(read).isEqualTo(saved)
        }

        @Test
        fun `CM-5 -- sub-minute precision is truncated, not rounded, and cannot survive the column`() {
            val fixture = SmallFixture()
            val taskId = tasks.save(fixture.task(TaskStatus.TODO, assignee = null)).id!!
            val ragged = Instant.parse("2026-09-14T10:31:59.987654321Z")

            comments.save(
                Comment.create(taskId, fixture.ownerId, CommentContent("Ragged."), HiveClock.fixedAt(ragged)),
            )
            entityManager.flush()
            entityManager.clear()

            val read = comments.findByTask(taskId, PageRequest.DEFAULT).content.single()

            assertThat(read.timestamp).isEqualTo(Instant.parse("2026-09-14T10:31:00Z"))
            assertThat(read.timestamp.truncatedTo(ChronoUnit.MINUTES)).isEqualTo(read.timestamp)
        }

        @Test
        fun `CM-2 -- a task's comments come back oldest first and paged`() {
            val fixture = SmallFixture()
            val taskId = tasks.save(fixture.task(TaskStatus.TODO, assignee = null)).id!!
            val base = Instant.parse("2026-09-14T09:00:00Z")

            repeat(5) { i ->
                comments.save(
                    Comment.create(
                        taskId,
                        fixture.ownerId,
                        CommentContent("Comment $i"),
                        HiveClock.fixedAt(base.plusSeconds(60L * i)),
                    ),
                )
            }
            entityManager.flush()

            val firstPage = comments.findByTask(taskId, PageRequest(0, 2))

            assertThat(firstPage.content.map { it.content.value }).containsExactly("Comment 0", "Comment 1")
            assertThat(firstPage.totalElements).isEqualTo(5)
            assertThat(firstPage.totalPages).isEqualTo(3)

            val lastPage = comments.findByTask(taskId, PageRequest(2, 2))

            assertThat(lastPage.content.map { it.content.value }).containsExactly("Comment 4")
        }

        @Test
        fun `comments of one task are not comments of another`() {
            val fixture = SmallFixture()
            val one = tasks.save(fixture.task(TaskStatus.TODO, assignee = null)).id!!
            val two = tasks.save(fixture.task(TaskStatus.TODO, assignee = null)).id!!
            val clock = HiveClock.fixedAt(Instant.parse("2026-09-14T09:00:00Z"))

            comments.save(Comment.create(one, fixture.ownerId, CommentContent("On one."), clock))
            entityManager.flush()

            assertThat(comments.findByTask(two, PageRequest.DEFAULT).content).isEmpty()
            assertThat(comments.findByTask(one, PageRequest.DEFAULT).content).hasSize(1)
        }
    }

    @Nested
    @DisplayName("lookups that find nothing")
    inner class Absences {

        /**
         * Every `findById` returns `null` rather than throwing or returning an
         * empty optional. The application layer turns that `null` into the 404
         * the spec requires; a repository that threw would force a try/catch at
         * every call site and make "not found" indistinguishable from a real
         * failure.
         */
        @Test
        fun `an unknown id is null for every aggregate`() {
            assertThat(teams.findById(TeamId(987_654))).isNull()
            assertThat(projects.findById(ProjectId(987_654))).isNull()
            assertThat(tasks.findById(TaskId(987_654))).isNull()
        }

        @Test
        fun `an unknown email is null, not an empty user`() {
            assertThat(users.findByEmail(EmailAddress("nobody@nowhere.example"))).isNull()
        }

        @Test
        fun `an empty page still reports the request's paging metadata`() {
            val page = comments.findByTask(TaskId(987_654), PageRequest(3, 10))

            assertThat(page.isEmpty).isTrue()
            assertThat(page.page).isEqualTo(3)
            assertThat(page.size).isEqualTo(10)
            assertThat(page.totalElements).isZero()
            assertThat(page.totalPages).isZero()
        }
    }

    @Nested
    @DisplayName("batch save")
    inner class BatchSave {

        @Test
        fun `TM-8 -- saveAll writes every task and returns them with their ids`() {
            val fixture = SmallFixture()
            val assigneeId = fixture.memberId
            val saved = tasks.saveAll(
                listOf(
                    fixture.task(TaskStatus.TODO, assignee = assigneeId),
                    fixture.task(TaskStatus.IN_PROGRESS, assignee = assigneeId),
                ),
            )
            entityManager.flush()

            assertThat(saved.map { it.id }).doesNotContainNull().hasSize(2)

            val unassigned = tasks.saveAll(saved.map { it.assignTo(null) })
            entityManager.flush()
            entityManager.clear()

            assertThat(unassigned.map { it.assignee }).containsOnlyNulls()
            assertThat(tasks.findLiveTasksAssignedTo(assigneeId, null)).isEmpty()
        }

        @Test
        fun `saveAll of nothing does nothing`() {
            assertThat(tasks.saveAll(emptyList())).isEmpty()
        }
    }

    /**
     * Asserts that [block] -- together with the flush that follows it -- breaches
     * the database constraint called [constraint].
     *
     * Naming the constraint is the point. "Some integrity error occurred" would
     * pass if a *different* constraint fired, which is exactly how a test stops
     * testing what its name claims. It is also why every constraint in
     * `V1__baseline.sql` is named explicitly: an auto-generated name could not be
     * asserted on here, and would change under us.
     *
     * The exception may surface either as Spring's
     * `DataIntegrityViolationException` (when the breach happens inside a
     * repository call, which for an IDENTITY key is most of them, since the
     * insert must run to allocate the id) or as Hibernate's raw
     * `ConstraintViolationException` (from a direct `flush`, which bypasses
     * Spring's exception translation). The assertion deliberately does not care
     * which: the subject under test is the schema, not the wrapper.
     */
    private fun assertViolates(constraint: String, block: () -> Unit) {
        val thrown = catchThrowable {
            block()
            entityManager.flush()
        }

        assertThat(thrown)
            .describedAs("expected constraint %s to be violated", constraint)
            .isNotNull()
        assertThat(causeChainOf(thrown).mapNotNull { it.message }.joinToString(" | "))
            .containsIgnoringCase(constraint)
    }

    private fun causeChainOf(throwable: Throwable): List<Throwable> =
        generateSequence(throwable) { if (it.cause === it) null else it.cause }.toList()

    /**
     * The smallest graph that satisfies the foreign keys: one owner, one member,
     * one team, one project. Used by the tests above that are about a constraint
     * rather than about visibility -- the full cast lives in [HiveGraph].
     */
    private inner class SmallFixture {
        val ownerId: UserId = users.save(
            User(null, PersonName("Owner"), EmailAddress("owner-${counter()}@hive.example")),
        ).id!!
        val memberId: UserId = users.save(
            User(null, PersonName("Member"), EmailAddress("member-${counter()}@hive.example")),
        ).id!!
        val teamId = teams.save(Team(null, TeamName("Team"), ownerId, setOf(memberId))).id!!
        val projectId = projects.save(Project(null, ProjectName("Project"), teamId, ownerId)).id!!

        fun task(status: TaskStatus, assignee: UserId?): Task =
            Task(
                id = null,
                name = TaskName("Task ${status.wireName}"),
                description = TaskDescription("."),
                projectId = projectId,
                creator = ownerId,
                assignee = assignee,
                status = status,
            )
    }

    private companion object {
        private var seq = 0

        /** Unique email suffixes, so two fixtures in one test do not collide on UQ_users_email. */
        fun counter(): Int = ++seq
    }
}
