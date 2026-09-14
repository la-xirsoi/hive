package hive.application.support

import hive.domain.error.NotFoundException
import hive.domain.model.Project
import hive.domain.model.ProjectId
import hive.domain.model.TaskContext
import hive.domain.model.TaskId
import hive.domain.model.Team
import hive.domain.model.TeamId
import hive.domain.model.UserId
import hive.domain.policy.AuthorizationPolicy
import hive.domain.port.ProjectRepository
import hive.domain.port.TaskRepository
import hive.domain.port.TeamRepository
import org.springframework.stereotype.Component

/** A project together with the team that works it -- the pair every project rule needs. */
data class ProjectContext(val project: Project, val team: Team)

/**
 * The one place the **404 gate** is implemented.
 *
 * Every operation in this layer begins the same way: load the aggregates the
 * rule needs, then ask [AuthorizationPolicy] whether the actor may see them at
 * all. An invisible resource is reported as *not found*, never as forbidden --
 * a 403 would confirm to a stranger that the resource exists.
 *
 * Centralising it here is what keeps the five services from each growing their
 * own slightly different version of the gate. Note what this class does *not*
 * do: it never decides a rule. It decides which aggregates to load and in what
 * order, and hands the facts to the policy.
 */
@Component
class ContextLoader(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val teamRepository: TeamRepository,
) {

    /** Load a team by id, with no visibility check. For paths that have already gated. */
    fun requireTeam(teamId: TeamId): Team =
        teamRepository.findById(teamId) ?: throw NotFoundException(TEAM, teamId)

    /**
     * TM-2/TM-3: load a team [actor] is allowed to see.
     *
     * The policy is consulted twice on purpose. The first call answers the
     * common case -- members and the lead -- from the team alone. Only when that
     * fails is the project table touched to answer TM-3's "and the owner of any
     * project of this team". The *rule* is still entirely the policy's; the
     * laziness is a loading decision, which is this layer's business.
     *
     * @throws NotFoundException (404) if the team is missing or invisible.
     */
    fun requireVisibleTeam(teamId: TeamId, actor: UserId): Team {
        val team = requireTeam(teamId)
        if (AuthorizationPolicy.canViewTeam(team, actor, ownsAProjectOfTeam = false)) {
            return team
        }
        val ownsAProjectOfTeam = projectRepository.findByTeam(teamId).any { it.isOwnedBy(actor) }
        if (AuthorizationPolicy.canViewTeam(team, actor, ownsAProjectOfTeam)) {
            return team
        }
        throw NotFoundException(TEAM, teamId)
    }

    /**
     * PR-3: load a project [actor] is allowed to see, together with its team.
     *
     * @throws NotFoundException (404) if the project is missing or invisible.
     */
    fun requireVisibleProject(projectId: ProjectId, actor: UserId): ProjectContext {
        val project = projectRepository.findById(projectId) ?: throw NotFoundException(PROJECT, projectId)
        val team = requireTeam(project.teamId)
        if (!AuthorizationPolicy.canViewProject(project, team, actor)) {
            throw NotFoundException(PROJECT, projectId)
        }
        return ProjectContext(project, team)
    }

    /**
     * VIS-1..VIS-5: load the full [TaskContext] for a task [actor] may see.
     *
     * @throws NotFoundException (404) if the task is missing or invisible.
     */
    fun requireVisibleTask(taskId: TaskId, actor: UserId): TaskContext {
        val context = loadTaskContext(taskId, actor)
        if (!AuthorizationPolicy.canViewTask(context)) {
            throw NotFoundException(TASK, taskId)
        }
        return context
    }

    /**
     * CM-1/CM-2: load the context of a task [actor] may comment on or read the
     * comments of.
     *
     * This exists rather than reusing [requireVisibleTask] so that the comment
     * rule is enforced by the function that *states* the comment rule. The two
     * answers coincide today -- `canCommentOnTask` delegates to `canViewTask` --
     * and if that ever stops being true, the comment endpoints follow the policy
     * automatically instead of quietly keeping the old answer.
     *
     * @throws NotFoundException (404) if the task is missing or invisible.
     */
    fun requireCommentableTask(taskId: TaskId, actor: UserId): TaskContext {
        val context = loadTaskContext(taskId, actor)
        if (!AuthorizationPolicy.canCommentOnTask(context)) {
            throw NotFoundException(TASK, taskId)
        }
        return context
    }

    private fun loadTaskContext(taskId: TaskId, actor: UserId): TaskContext {
        val task = taskRepository.findById(taskId) ?: throw NotFoundException(TASK, taskId)
        val project = projectRepository.findById(task.projectId)
            ?: throw NotFoundException(PROJECT, task.projectId)
        val team = requireTeam(project.teamId)
        return TaskContext(task, project, team, actor)
    }

    private companion object {
        const val TEAM = "Team"
        const val PROJECT = "Project"
        const val TASK = "Task"
    }
}
