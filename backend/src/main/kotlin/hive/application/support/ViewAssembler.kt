package hive.application.support

import hive.application.view.CommentView
import hive.application.view.ProjectView
import hive.application.view.TaskDetailView
import hive.application.view.TaskPermissions
import hive.application.view.TaskSummaryView
import hive.application.view.TeamView
import hive.domain.error.DomainException
import hive.domain.error.NotFoundException
import hive.domain.model.Comment
import hive.domain.model.Page
import hive.domain.model.Project
import hive.domain.model.ProjectId
import hive.domain.model.Task
import hive.domain.model.TaskContext
import hive.domain.model.Team
import hive.domain.model.TeamId
import hive.domain.model.User
import hive.domain.model.UserId
import hive.domain.policy.AuthorizationPolicy
import hive.domain.policy.TaskTransitions
import hive.domain.port.ProjectRepository
import hive.domain.port.TeamRepository
import hive.domain.port.UserRepository
import org.springframework.stereotype.Component

/**
 * Turns domain aggregates into the read models the inbound ports publish.
 *
 * The frozen API contract hands the client *resolved* payloads -- a
 * `TeamSummary` carries a `UserSummary` for its lead, not a bare id -- while the
 * domain entities carry ids only. Somebody has to resolve them, and it cannot be
 * the REST adapter: an adapter that reaches for a repository to finish its own
 * response has stopped being an adapter. So it happens here, once, in batch.
 *
 * "In batch" is the other reason this class exists. Building fifty
 * `TaskSummaryView`s one at a time would be fifty project lookups and fifty user
 * lookups; every method here collects the ids it needs across the whole result
 * set first and issues one query per repository.
 */
@Component
class ViewAssembler(
    private val userRepository: UserRepository,
    private val teamRepository: TeamRepository,
    private val projectRepository: ProjectRepository,
) {

    // ---------------------------------------------------------------- teams

    /** One team with its lead and members resolved. */
    fun teamView(team: Team): TeamView = teamView(team, usersOf(team.memberIds))

    /** Many teams, resolving every member across all of them in a single lookup. */
    fun teamViews(teams: List<Team>): List<TeamView> {
        val users = usersOf(teams.flatMapTo(mutableSetOf()) { it.memberIds })
        return teams.map { teamView(it, users) }
    }

    // ------------------------------------------------------------- projects

    /** One project whose team has already been loaded by the caller's authorization step. */
    fun projectView(project: Project, team: Team): ProjectView {
        val users = usersOf(team.memberIds + project.projectOwner)
        return projectView(project, team, users)
    }

    /** Many projects, loading each distinct team once and every referenced user once. */
    fun projectViews(projects: List<Project>): List<ProjectView> {
        val teams = teamsOf(projects.mapTo(mutableSetOf()) { it.teamId })
        val userIds = projects.mapTo(mutableSetOf()) { it.projectOwner }
        teams.values.forEach { userIds += it.memberIds }
        val users = usersOf(userIds)
        return projects.map { projectView(it, teams.getValue(it.teamId), users) }
    }

    // ---------------------------------------------------------------- tasks

    /**
     * One task in full, including what [TaskContext.actor] may do with it.
     *
     * The context already holds the project and team, so the only lookup left is
     * the people: the owner, the members, the creator and the assignee.
     */
    fun taskDetail(context: TaskContext): TaskDetailView {
        val userIds = context.team.memberIds +
            context.project.projectOwner +
            context.task.creator +
            setOfNotNull(context.task.assignee)
        val users = usersOf(userIds)
        return TaskDetailView(
            task = context.task,
            project = projectView(context.project, context.team, users),
            creator = users.getValue(context.task.creator),
            assignee = context.task.assignee?.let(users::getValue),
            permissions = permissions(context),
        )
    }

    /** A page of task rows, resolving every distinct project and assignee once. */
    fun taskSummaries(tasks: Page<Task>): Page<TaskSummaryView> {
        val projects = projectsOf(tasks.content.mapTo(mutableSetOf()) { it.projectId })
        val users = usersOf(tasks.content.mapNotNullTo(mutableSetOf()) { it.assignee })
        return tasks.map { task ->
            TaskSummaryView(
                task = task,
                projectName = projects.getValue(task.projectId).name,
                assignee = task.assignee?.let(users::getValue),
            )
        }
    }

    /**
     * What the acting user may do with this task **right now**, answered by the
     * same [AuthorizationPolicy] and [TaskTransitions] that enforce the rules.
     *
     * The contract is explicit that this is a convenience for the UI and never
     * an enforcement point -- every request is authorized independently. Deriving
     * it from the enforcing functions rather than from a second copy of the rules
     * is what guarantees the UI is never shown a control the server would reject.
     */
    fun permissions(context: TaskContext): TaskPermissions =
        TaskPermissions(
            canEdit = permitted { AuthorizationPolicy.checkEditTaskFields(context) },
            canAssign = canChangeAssignment(context),
            canComment = AuthorizationPolicy.canCommentOnTask(context),
            allowedTransitions = TaskTransitions.allowedTransitions(context),
        )

    // ------------------------------------------------------------- comments

    /** A page of comments with their authors resolved. */
    fun commentViews(comments: Page<Comment>): Page<CommentView> {
        val users = usersOf(comments.content.mapTo(mutableSetOf()) { it.author })
        return comments.map { CommentView(it, users.getValue(it.author)) }
    }

    /** One comment, for the create response. */
    fun commentView(comment: Comment): CommentView =
        CommentView(comment, usersOf(setOf(comment.author)).getValue(comment.author))

    // -------------------------------------------------------------- interna

    /**
     * `canAssign` asks whether *any* assignment change is open to this actor, but
     * [AuthorizationPolicy.checkAssign] judges one concrete target. So it is asked
     * twice: once about unassigning, once about a team member who is not the
     * project's owner. Which member does not matter -- AS-2 and AS-4 give the same
     * answer for every one of them -- so the first eligible one is a faithful probe.
     *
     * Note that no rule is decided here. Both answers come from the policy; this
     * only chooses what to ask it about.
     */
    private fun canChangeAssignment(context: TaskContext): Boolean {
        if (permitted { AuthorizationPolicy.checkAssign(context, null) }) {
            return true
        }
        val eligible = context.team.memberIds.firstOrNull { it != context.project.projectOwner } ?: return false
        return permitted { AuthorizationPolicy.checkAssign(context, eligible) }
    }

    /**
     * Runs a `checkX` and reports whether it passed.
     *
     * Which exception it threw is deliberately ignored: a permission flag has
     * one bit of room, and the distinction between "impossible right now" (409)
     * and "not yours to do" (403) belongs to the request that actually attempts
     * the operation, not to the hint the UI renders from.
     */
    private fun permitted(check: () -> Unit): Boolean =
        try {
            check()
            true
        } catch (refused: DomainException) {
            refused.let { false }
        }

    private fun teamView(team: Team, users: Map<UserId, User>): TeamView =
        TeamView(
            team = team,
            lead = users.getValue(team.teamLead),
            members = team.memberIds.map(users::getValue).sortedWith(BY_DISPLAY_ORDER),
        )

    private fun projectView(project: Project, team: Team, users: Map<UserId, User>): ProjectView =
        ProjectView(
            project = project,
            owner = users.getValue(project.projectOwner),
            team = teamView(team, users),
        )

    /**
     * Every referenced user, in one query.
     *
     * A referenced id that has no row is a broken foreign key, not a normal
     * outcome -- it is reported as a 404 rather than silently rendered as a hole
     * in the payload.
     */
    private fun usersOf(ids: Set<UserId>): Map<UserId, User> {
        if (ids.isEmpty()) {
            return emptyMap()
        }
        val found = userRepository.findAllById(ids).mapNotNull { user -> user.id?.let { it to user } }.toMap()
        (ids - found.keys).firstOrNull()?.let { throw NotFoundException("User", it) }
        return found
    }

    private fun teamsOf(ids: Set<TeamId>): Map<TeamId, Team> =
        ids.associateWith { teamRepository.findById(it) ?: throw NotFoundException("Team", it) }

    private fun projectsOf(ids: Set<ProjectId>): Map<ProjectId, Project> =
        ids.associateWith { projectRepository.findById(it) ?: throw NotFoundException("Project", it) }
}

/**
 * Member lists are rendered in a stable order so the UI does not reshuffle
 * between requests. Email is the tiebreak rather than the id because US-2 makes
 * it unique and it is never null.
 */
private val BY_DISPLAY_ORDER: Comparator<User> =
    compareBy({ it.name.value.lowercase() }, { it.email.normalized })
