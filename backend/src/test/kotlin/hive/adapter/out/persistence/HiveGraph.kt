package hive.adapter.out.persistence

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
import hive.domain.port.ProjectRepository
import hive.domain.port.TaskRepository
import hive.domain.port.TeamRepository
import hive.domain.port.UserRepository

/**
 * The fixture graph the role-scoped query tests assert against.
 *
 * It is built to make a visibility leak *visible*. Every one of the following is
 * true of it, and each exists to catch a specific way the queries could be
 * wrong:
 *
 * * **Two teams with overlapping membership.** `cross` belongs to both and
 *   `assignee` belongs to both; a query that forgets to correlate its team
 *   subquery to the right project will hand one team's tasks to the other's
 *   members.
 * * **A project owner who is not in the project's team** (`owner`, INV-2). If
 *   visibility were derived from membership alone, the owner would lose their
 *   own project; if ownership were assumed to imply membership, the owner would
 *   gain sight of the *other* Alpha project they do not own.
 * * **A project whose owner is also the team lead** (`nectar`). Overlapping
 *   roles must union, not shadow one another.
 * * **Tasks in all five statuses in one project**, including an unassigned
 *   `Canceled` task and a `Canceled` task that still has an assignee -- the
 *   VIS-5 case, where the assignee must still see work that was canceled under
 *   them.
 * * **An outsider** who is in nothing and must see nothing anywhere.
 *
 * ```
 * TEAM ALPHA  lead=lead        members={lead, member, assignee, cross}
 *   apiary    owner=owner      (owner is NOT an Alpha member -- INV-2)
 *      draft            Draft         unassigned
 *      todoOpen         Todo          unassigned      <- lead's queue
 *      todoAssigned     Todo          assignee
 *      inProgress       In Progress   assignee
 *      completed        Completed     member
 *      canceledOpen     Canceled      unassigned
 *      canceledAssigned Canceled      member          <- VIS-5
 *   nectar    owner=lead       (owner and team lead are the same person)
 *      nectarDraft      Draft         unassigned
 *      nectarTodo       Todo          unassigned      <- lead's queue
 *
 * TEAM BETA   lead=betaLead    members={betaLead, cross, assignee}
 *   pollen    owner=betaLead
 *      pollenTodo       Todo          unassigned      <- betaLead's queue
 *      pollenInProgress In Progress   assignee
 * ```
 */
class HiveGraph(
    users: UserRepository,
    teams: TeamRepository,
    projects: ProjectRepository,
    tasks: TaskRepository,
) {
    // --- people --------------------------------------------------------------

    /** Owns `apiary`, and is deliberately not a member of Team Alpha (INV-2). */
    val owner: UserId = users.newUser("Olive Owner", "olive@hive.example")

    /** Leads Team Alpha and owns `nectar`. */
    val lead: UserId = users.newUser("Lena Lead", "lena@hive.example")

    /** A plain member of Team Alpha; holds the completed and canceled-assigned tasks. */
    val member: UserId = users.newUser("Mo Member", "mo@hive.example")

    /** A member of both teams; holds live work in both. */
    val assignee: UserId = users.newUser("Ash Assignee", "ash@hive.example")

    /** A member of both teams, holding nothing. */
    val cross: UserId = users.newUser("Cass Cross", "cass@hive.example")

    /** Leads Team Beta and owns `pollen`. */
    val betaLead: UserId = users.newUser("Bex Beta", "bex@hive.example")

    /** In no team, owning nothing, assigned nothing. Must see nothing. */
    val outsider: UserId = users.newUser("Otto Outsider", "otto@hive.example")

    // --- teams ---------------------------------------------------------------

    val alpha: TeamId = teams.newTeam("Alpha", lead, setOf(member, assignee, cross))
    val beta: TeamId = teams.newTeam("Beta", betaLead, setOf(cross, assignee))

    // --- projects ------------------------------------------------------------

    val apiary: ProjectId = projects.newProject("Apiary", alpha, owner)
    val nectar: ProjectId = projects.newProject("Nectar", alpha, lead)
    val pollen: ProjectId = projects.newProject("Pollen", beta, betaLead)

    // --- tasks ---------------------------------------------------------------

    val draft: TaskId = tasks.newTask("draft", apiary, owner, TaskStatus.DRAFT, null)
    val todoOpen: TaskId = tasks.newTask("todoOpen", apiary, owner, TaskStatus.TODO, null)
    val todoAssigned: TaskId = tasks.newTask("todoAssigned", apiary, owner, TaskStatus.TODO, assignee)
    val inProgress: TaskId = tasks.newTask("inProgress", apiary, owner, TaskStatus.IN_PROGRESS, assignee)
    val completed: TaskId = tasks.newTask("completed", apiary, owner, TaskStatus.COMPLETED, member)
    val canceledOpen: TaskId = tasks.newTask("canceledOpen", apiary, owner, TaskStatus.CANCELED, null)
    val canceledAssigned: TaskId = tasks.newTask("canceledAssigned", apiary, owner, TaskStatus.CANCELED, member)

    val nectarDraft: TaskId = tasks.newTask("nectarDraft", nectar, lead, TaskStatus.DRAFT, null)
    val nectarTodo: TaskId = tasks.newTask("nectarTodo", nectar, lead, TaskStatus.TODO, null)

    val pollenTodo: TaskId = tasks.newTask("pollenTodo", pollen, betaLead, TaskStatus.TODO, null)
    val pollenInProgress: TaskId =
        tasks.newTask("pollenInProgress", pollen, betaLead, TaskStatus.IN_PROGRESS, assignee)

    /** Every task of `apiary`, for tests that assert "all of them". */
    val allApiaryTasks: List<TaskId> =
        listOf(draft, todoOpen, todoAssigned, inProgress, completed, canceledOpen, canceledAssigned)

    private companion object {
        fun UserRepository.newUser(name: String, email: String): UserId =
            save(User(null, PersonName(name), EmailAddress(email))).id!!

        fun TeamRepository.newTeam(name: String, lead: UserId, members: Set<UserId>): TeamId =
            save(Team(null, TeamName(name), lead, members)).id!!

        fun ProjectRepository.newProject(name: String, team: TeamId, owner: UserId): ProjectId =
            save(Project(null, ProjectName(name), team, owner)).id!!

        /**
         * Saves a task directly in [status].
         *
         * The domain creates every task in `Draft` (TK-2) and only the
         * transition machine moves it, but these tests are about *queries*, not
         * about transitions -- driving each fixture task through a legal
         * sequence of transitions would test the state machine a second time and
         * make the fixture's shape much harder to read.
         */
        fun TaskRepository.newTask(
            name: String,
            project: ProjectId,
            creator: UserId,
            status: TaskStatus,
            assignee: UserId?,
        ): TaskId =
            save(
                Task(
                    id = null,
                    name = TaskName(name),
                    description = TaskDescription("Fixture task $name."),
                    projectId = project,
                    creator = creator,
                    assignee = assignee,
                    status = status,
                ),
            ).id!!
    }
}
