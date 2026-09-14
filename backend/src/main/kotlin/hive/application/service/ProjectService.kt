package hive.application.service

import hive.application.support.ContextLoader
import hive.application.support.ViewAssembler
import hive.application.usecase.CreateProjectCommand
import hive.application.usecase.ProjectUseCases
import hive.application.usecase.RenameProjectCommand
import hive.application.view.ProjectView
import hive.application.view.TaskSummaryView
import hive.domain.error.ValidationException
import hive.domain.model.Page
import hive.domain.model.PageRequest
import hive.domain.model.Project
import hive.domain.model.ProjectId
import hive.domain.model.ProjectName
import hive.domain.model.TaskStatus
import hive.domain.model.UserId
import hive.domain.policy.AuthorizationPolicy
import hive.domain.port.ProjectRepository
import hive.domain.port.TaskRepository
import hive.domain.port.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * PR-1..PR-10.
 */
@Service
class ProjectService(
    private val projectRepository: ProjectRepository,
    private val taskRepository: TaskRepository,
    private val userRepository: UserRepository,
    private val loader: ContextLoader,
    private val views: ViewAssembler,
) : ProjectUseCases {

    /**
     * PR-1/PR-2.
     *
     * The team is gated for visibility first, so a stranger to the team gets a
     * 404 and never learns it exists; somebody who *can* see the team but does
     * not belong to it -- a TM-3 project owner -- gets the policy's 403.
     */
    @Transactional
    override fun create(actor: UserId, command: CreateProjectCommand): ProjectView {
        val team = loader.requireVisibleTeam(command.teamId, actor)
        AuthorizationPolicy.checkCreateProject(team, actor)

        val project = Project(
            id = null,
            name = ProjectName(command.name),
            teamId = command.teamId,
            projectOwner = actor,
        )
        return views.projectView(projectRepository.save(project), team)
    }

    /** PR-4: the union of owned projects and the projects of teams [actor] leads or belongs to, resolved in SQL. */
    @Transactional(readOnly = true)
    override fun listMine(actor: UserId): List<ProjectView> =
        views.projectViews(projectRepository.findVisibleTo(actor))

    @Transactional(readOnly = true)
    override fun get(actor: UserId, projectId: ProjectId): ProjectView {
        val (project, team) = loader.requireVisibleProject(projectId, actor)
        return views.projectView(project, team)
    }

    /** PR-5. */
    @Transactional
    override fun rename(actor: UserId, projectId: ProjectId, command: RenameProjectCommand): ProjectView {
        val (project, team) = loader.requireVisibleProject(projectId, actor)
        AuthorizationPolicy.checkRenameProject(project, actor)
        return views.projectView(projectRepository.save(project.rename(ProjectName(command.name))), team)
    }

    /**
     * PR-6/PR-7/PR-8.
     *
     * The live-task lookup happens *before* the policy call because the policy
     * needs it to answer PR-8, and PR-8 is a 409 that precedes PR-6's 403: a
     * transfer onto a live assignee is impossible for anyone, so the answer does
     * not depend on who is asking. PR-7's 400 for an unknown user comes last, as
     * the ordering rule requires.
     */
    @Transactional
    override fun transferOwner(actor: UserId, projectId: ProjectId, newOwner: UserId): ProjectView {
        val (project, team) = loader.requireVisibleProject(projectId, actor)

        val liveTasks = taskRepository.findLiveTasksAssignedTo(newOwner, projectId)
        AuthorizationPolicy.checkTransferProjectOwner(project, actor, newOwner, liveTasks)

        if (userRepository.findById(newOwner) == null) { // PR-7
            throw ValidationException("userId", "must be an existing user.")
        }
        return views.projectView(projectRepository.save(project.transferOwnershipTo(newOwner)), team)
    }

    /**
     * The caller's visible slice of one project's tasks.
     *
     * Visibility is resolved by the port, which mirrors
     * [AuthorizationPolicy.canViewTask] in SQL; loading the project's tasks and
     * sieving them here would be both slow and a second place for VIS-1..VIS-5
     * to drift from the policy.
     *
     * The optional [statuses] filter is applied to the page that comes back,
     * because [hive.domain.port.TaskRepository] has no status predicate. That is
     * a deliberate, contained inaccuracy: the filter can only ever *remove* rows
     * the caller was already entitled to see, so it never widens visibility --
     * but a filtered page reports the unfiltered `totalElements`. See the note
     * in `docs/api-contract.md` section 4.
     */
    @Transactional(readOnly = true)
    override fun listTasks(
        actor: UserId,
        projectId: ProjectId,
        statuses: Set<TaskStatus>?,
        page: PageRequest,
    ): Page<TaskSummaryView> {
        loader.requireVisibleProject(projectId, actor)

        val visible = taskRepository.findVisibleInProject(projectId, actor, page)
        val narrowed =
            if (statuses.isNullOrEmpty()) {
                visible
            } else {
                visible.copy(content = visible.content.filter { it.status in statuses })
            }
        return views.taskSummaries(narrowed)
    }
}
