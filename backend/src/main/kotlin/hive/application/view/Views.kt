package hive.application.view

import hive.domain.model.Comment
import hive.domain.model.Project
import hive.domain.model.ProjectName
import hive.domain.model.Task
import hive.domain.model.TaskStatus
import hive.domain.model.Team
import hive.domain.model.User

/**
 * Read models returned by the inbound ports.
 *
 * These exist because the frozen API contract publishes *resolved* payloads --
 * `TeamSummary` carries a `UserSummary` for the lead and a member count, not a
 * bare id -- while the domain entities carry ids only. Resolving those ids is a
 * loading concern, which is the application layer's job; the inbound adapter
 * must not reach for a repository to finish the job itself.
 *
 * They are deliberately *not* DTOs: no JSON annotations, no wire names. The
 * REST adapter maps these to its own DTOs at its own boundary, so the contract's
 * JSON shape can change without touching this layer.
 */

/**
 * What the acting user may do with one task, computed by the same
 * [hive.domain.policy.AuthorizationPolicy] and
 * [hive.domain.policy.TaskTransitions] that enforce the rules.
 *
 * The API contract requires this on `TaskDetail` so the UI renders exactly the
 * controls that will work. It is a convenience for the client and never an
 * enforcement point: every request is authorized independently.
 */
data class TaskPermissions(
    /** TK-3/TE-1: may the actor change this task's name or description right now? */
    val canEdit: Boolean,
    /** AS-1..AS-7: may the actor perform *any* assignment change on this task right now? */
    val canAssign: Boolean,
    /** CM-1: may the actor add a comment (true for terminal tasks too, TE-4)? */
    val canComment: Boolean,
    /** Every status the actor may move this task to right now; empty for terminal tasks. */
    val allowedTransitions: Set<TaskStatus>,
)

/** A team with its lead and members resolved. Feeds both `TeamSummary` and `TeamDetail`. */
data class TeamView(
    val team: Team,
    val lead: User,
    val members: List<User>,
) {
    val memberCount: Int get() = members.size
}

/** A project with its owner and team resolved. Feeds `ProjectSummary`. */
data class ProjectView(
    val project: Project,
    val owner: User,
    val team: TeamView,
)

/** One row of a task list. Feeds `TaskSummary`. */
data class TaskSummaryView(
    val task: Task,
    val projectName: ProjectName,
    val assignee: User?,
)

/** One task in full, including the acting user's [permissions]. Feeds `TaskDetail`. */
data class TaskDetailView(
    val task: Task,
    val project: ProjectView,
    val creator: User,
    val assignee: User?,
    val permissions: TaskPermissions,
)

/** A comment with its author resolved. Feeds `Comment`. */
data class CommentView(
    val comment: Comment,
    val author: User,
)
