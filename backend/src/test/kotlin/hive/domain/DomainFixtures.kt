package hive.domain

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
import hive.domain.model.UserId

/**
 * One cast of characters shared by the policy tests, so that every test means
 * the same thing by "the lead" or "an outsider".
 *
 * ```
 * OWNER     owns the project, and is deliberately NOT a member of its team (INV-2)
 * LEAD      leads the team; a member by INV-1; not the assignee of the fixture task
 * ASSIGNEE  a plain member who holds the fixture task
 * MEMBER    a plain member holding nothing
 * OUTSIDER  a stranger to both the project and the team
 * ```
 */
object DomainFixtures {
    val OWNER = UserId(1)
    val LEAD = UserId(2)
    val ASSIGNEE = UserId(3)
    val MEMBER = UserId(4)
    val OUTSIDER = UserId(5)

    val TEAM_ID = TeamId(10)
    val PROJECT_ID = ProjectId(20)
    val TASK_ID = TaskId(30)

    /** INV-1: the lead is in the member set; the constructor would add them anyway. */
    val TEAM = Team(TEAM_ID, TeamName("Hive Core"), LEAD, setOf(LEAD, ASSIGNEE, MEMBER))

    /** INV-2: [OWNER] owns this project without belonging to [TEAM]. */
    val PROJECT = Project(PROJECT_ID, ProjectName("Apiary"), TEAM_ID, OWNER)

    fun task(
        status: TaskStatus,
        assignee: UserId? = ASSIGNEE,
        projectId: ProjectId = PROJECT_ID,
        id: TaskId? = TASK_ID,
    ): Task =
        Task(
            id = id,
            name = TaskName("Requeen hive 4"),
            description = TaskDescription("The colony is queenless."),
            projectId = projectId,
            creator = OWNER,
            assignee = assignee,
            status = status,
        )

    fun context(
        status: TaskStatus,
        actor: UserId,
        assignee: UserId? = ASSIGNEE,
        project: Project = PROJECT,
        team: Team = TEAM,
    ): TaskContext = TaskContext(task(status, assignee), project, team, actor)
}
