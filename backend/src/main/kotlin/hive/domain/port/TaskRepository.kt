package hive.domain.port

import hive.domain.model.Page
import hive.domain.model.PageRequest
import hive.domain.model.ProjectId
import hive.domain.model.Task
import hive.domain.model.TaskId
import hive.domain.model.TaskStatus
import hive.domain.model.UserId

/**
 * Outbound port for task storage.
 *
 * The role-scoped queries are ports rather than in-memory filters so the adapter
 * can push the visibility rules into SQL. Filtering a whole table in the
 * application layer would be both slow and a second place for the rules to
 * drift from [hive.domain.policy.AuthorizationPolicy].
 */
interface TaskRepository {
    fun findById(id: TaskId): Task?

    /** VIS-1/VIS-5: every task assigned to [userId], in every status. */
    fun findAssignedTo(userId: UserId, page: PageRequest): Page<Task>

    /**
     * VIS-1..VIS-5 for one project: the tasks of [projectId] that [viewer] may
     * see. The implementation must mirror
     * [hive.domain.policy.AuthorizationPolicy.canViewTask] exactly:
     * everything if they own the project, non-`Draft` if they lead the team,
     * neither `Draft` nor `Canceled` if they are a member, plus their own
     * assignments regardless of status.
     *
     * [statuses], when non-null and non-empty, narrows the result further. It is
     * a parameter of the query rather than a filter applied to its result
     * because a page filtered after the fact reports the *unfiltered* total and
     * can return fewer rows than the requested size. Narrowing can only ever
     * remove rows the viewer was already entitled to see; it never widens
     * visibility.
     */
    fun findVisibleInProject(
        projectId: ProjectId,
        viewer: UserId,
        statuses: Set<TaskStatus>? = null,
        page: PageRequest,
    ): Page<Task>

    /**
     * UQ-1: the unassigned queue -- `Todo` tasks with no assignee across the
     * projects of every team [leadId] leads. `Draft` tasks are excluded (leads
     * cannot see them, VIS-3) and terminal tasks are excluded.
     */
    fun findUnassignedForLead(leadId: UserId, page: PageRequest): Page<Task>

    /**
     * TM-8 and PR-8: the live (`Todo` / `In Progress`) tasks assigned to
     * [userId], narrowed to [projectId] when it is non-null.
     *
     * Unpaged on purpose: both callers act on the whole set -- TM-8 unassigns
     * them, PR-8 refuses the transfer and names them.
     */
    fun findLiveTasksAssignedTo(userId: UserId, projectId: ProjectId?): List<Task>

    fun save(task: Task): Task

    /** TM-8 unassigns a batch of tasks at once; this keeps that one round trip. */
    fun saveAll(tasks: List<Task>): List<Task>
}
