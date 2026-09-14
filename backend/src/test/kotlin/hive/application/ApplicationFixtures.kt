package hive.application

import hive.application.support.ContextLoader
import hive.application.support.ViewAssembler
import hive.domain.HiveClock
import hive.domain.model.Comment
import hive.domain.model.CommentContent
import hive.domain.model.CommentId
import hive.domain.model.EmailAddress
import hive.domain.model.Page
import hive.domain.model.PageRequest
import hive.domain.model.PersonName
import hive.domain.model.Project
import hive.domain.model.ProjectId
import hive.domain.model.ProjectName
import hive.domain.model.Task
import hive.domain.model.TaskContext
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
import io.mockk.every
import io.mockk.mockk
import java.time.Instant

/**
 * One cast of characters for the whole application layer, so that "the lead" and
 * "an outsider" mean the same thing in every test file.
 *
 * It mirrors `hive.domain.DomainFixtures` deliberately -- same five people, same
 * relationships -- with the [User] rows the view assembler needs added on top.
 *
 * ```
 * OWNER     owns PROJECT and is deliberately NOT a member of TEAM (INV-2)
 * LEAD      leads TEAM; a member by INV-1; holds no task
 * ASSIGNEE  a plain member; holds the fixture task
 * MEMBER    a plain member holding nothing
 * OUTSIDER  a stranger to both
 * ```
 */
object AppFixtures {

    val OWNER_ID = UserId(1)
    val LEAD_ID = UserId(2)
    val ASSIGNEE_ID = UserId(3)
    val MEMBER_ID = UserId(4)
    val OUTSIDER_ID = UserId(5)
    val GHOST_ID = UserId(999)

    val OWNER = user(OWNER_ID, "Olive Owner", "olive@hive.test")
    val LEAD = user(LEAD_ID, "Lena Lead", "lena@hive.test")
    val ASSIGNEE = user(ASSIGNEE_ID, "Amos Assignee", "amos@hive.test")
    val MEMBER = user(MEMBER_ID, "Mira Member", "mira@hive.test")
    val OUTSIDER = user(OUTSIDER_ID, "Otto Outsider", "otto@hive.test")

    val EVERYONE = listOf(OWNER, LEAD, ASSIGNEE, MEMBER, OUTSIDER)

    val TEAM_ID = TeamId(10)
    val OTHER_TEAM_ID = TeamId(11)
    val PROJECT_ID = ProjectId(20)
    val SIBLING_PROJECT_ID = ProjectId(21)
    val FOREIGN_PROJECT_ID = ProjectId(22)
    val TASK_ID = TaskId(30)

    /** INV-1 puts [LEAD_ID] in the member set even though it is listed here explicitly. */
    val TEAM = Team(TEAM_ID, TeamName("Hive Core"), LEAD_ID, setOf(LEAD_ID, ASSIGNEE_ID, MEMBER_ID))

    /** INV-2: [OWNER_ID] owns this without belonging to [TEAM]. */
    val PROJECT = Project(PROJECT_ID, ProjectName("Apiary"), TEAM_ID, OWNER_ID)

    /** A second project of the same team, owned by somebody else. Used for TM-8 and PR-3 narrowing. */
    val SIBLING_PROJECT = Project(SIBLING_PROJECT_ID, ProjectName("Foraging"), TEAM_ID, LEAD_ID)

    /** A project of a different team entirely. TM-8 must leave its tasks alone. */
    val FOREIGN_PROJECT = Project(FOREIGN_PROJECT_ID, ProjectName("Swarm"), OTHER_TEAM_ID, OUTSIDER_ID)

    fun user(id: UserId, name: String, email: String): User =
        User(id, PersonName(name), EmailAddress(email))

    fun task(
        status: TaskStatus,
        assignee: UserId? = ASSIGNEE_ID,
        id: TaskId? = TASK_ID,
        projectId: ProjectId = PROJECT_ID,
        name: String = "Requeen hive 4",
    ): Task =
        Task(
            id = id,
            name = TaskName(name),
            description = TaskDescription("The colony is queenless."),
            projectId = projectId,
            creator = OWNER_ID,
            assignee = assignee,
            status = status,
        )

    fun context(
        status: TaskStatus,
        actor: UserId,
        assignee: UserId? = ASSIGNEE_ID,
        project: Project = PROJECT,
        team: Team = TEAM,
    ): TaskContext = TaskContext(task(status, assignee), project, team, actor)

    fun comment(id: Int, author: UserId = MEMBER_ID, content: String = "Noted."): Comment =
        Comment(CommentId(id.toLong()), TASK_ID, author, AT, CommentContent(content))

    val AT: Instant = Instant.parse("2026-09-13T18:30:00Z")
    val CLOCK: HiveClock = HiveClock.fixedAt(AT)

    fun <T> pageOf(vararg items: T, request: PageRequest = PageRequest.DEFAULT): Page<T> =
        Page.of(items.toList(), request, items.size.toLong())
}

/**
 * The mocked outbound ports plus the two real support collaborators.
 *
 * [ContextLoader] and [ViewAssembler] are the genuine articles rather than
 * mocks: they are where the 404 gate and the permission computation live, so
 * stubbing them out would hollow out exactly the behaviour these tests exist to
 * pin down. Only the ports -- the edge of the hexagon -- are faked.
 *
 * One MockK wrinkle to know about: Kotlin compiles an inline value class
 * parameter down to the value it wraps, so an `answers` block is handed a bare
 * [Long] where the port's signature says [TeamId]. Every id stub below therefore
 * re-wraps `firstArg()` instead of asking for `firstArg<TeamId>()`, which would
 * fail with a [ClassCastException] at run time.
 */
class Harness {
    val userRepository: UserRepository = mockk()
    val teamRepository: TeamRepository = mockk()
    val projectRepository: ProjectRepository = mockk()
    val taskRepository: TaskRepository = mockk()
    val commentRepository: CommentRepository = mockk()

    val loader = ContextLoader(taskRepository, projectRepository, teamRepository)
    val views = ViewAssembler(userRepository, teamRepository, projectRepository)

    init {
        // The directory is the one stub nearly every test needs, so it is set up
        // once here; a test that cares about a missing row overrides it.
        every { userRepository.findAllById(any()) } answers {
            val ids = firstArg<Set<UserId>>()
            AppFixtures.EVERYONE.filter { it.id in ids }
        }
        every { userRepository.findById(any()) } answers {
            val id = UserId(firstArg())
            AppFixtures.EVERYONE.firstOrNull { it.id == id }
        }
    }

    /** Stub `teamRepository.findById` for the given teams; anything else answers null. */
    fun withTeams(vararg teams: Team) = apply {
        every { teamRepository.findById(any()) } answers {
            val id = TeamId(firstArg())
            teams.firstOrNull { it.id == id }
        }
    }

    /** Stub `projectRepository.findById` and `findByTeam` from one list of projects. */
    fun withProjects(vararg projects: Project) = apply {
        every { projectRepository.findById(any()) } answers {
            val id = ProjectId(firstArg())
            projects.firstOrNull { it.id == id }
        }
        every { projectRepository.findByTeam(any()) } answers {
            val id = TeamId(firstArg())
            projects.filter { it.teamId == id }
        }
    }

    /** Stub `taskRepository.findById` from one list of tasks. */
    fun withTasks(vararg tasks: Task) = apply {
        every { taskRepository.findById(any()) } answers {
            val id = TaskId(firstArg())
            tasks.firstOrNull { it.id == id }
        }
    }

    /** The standard world: the fixture team, its two projects, and the directory. */
    fun withStandardWorld() = withTeams(AppFixtures.TEAM).withProjects(AppFixtures.PROJECT)

    /** `save` echoes its argument back, which is what a repository does for an already-keyed aggregate. */
    fun echoTeamSaves() = apply { every { teamRepository.save(any()) } answers { firstArg() } }

    fun echoProjectSaves() = apply { every { projectRepository.save(any()) } answers { firstArg() } }

    fun echoTaskSaves() = apply { every { taskRepository.save(any()) } answers { firstArg() } }

    fun echoUserSaves() = apply { every { userRepository.save(any()) } answers { firstArg() } }
}
