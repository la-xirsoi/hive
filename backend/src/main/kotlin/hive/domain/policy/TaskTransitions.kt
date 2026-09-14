package hive.domain.policy

import hive.domain.model.Task
import hive.domain.model.TaskContext
import hive.domain.model.TaskStatus

/**
 * The outcome of attempting a status change.
 *
 * The three variants exist to keep 409 and 403 apart at the type level, because
 * collapsing them is the mistake this part of the system is most prone to.
 */
sealed interface TransitionResult {
    /** The move is legal and the actor may make it. Carries the updated task. */
    data class Allowed(val task: Task) : TransitionResult

    /**
     * The move is impossible for *anyone* -- it is not in the table, or the task
     * is already terminal, or it is a no-op. Maps to **409 Conflict**; the
     * actor's identity is irrelevant.
     */
    data class Illegal(val reason: String) : TransitionResult

    /**
     * The move is in the table, but this actor does not hold the required role.
     * Maps to **403 Forbidden**.
     */
    data class Forbidden(val reason: String) : TransitionResult
}

/**
 * The task status state machine from `spec.md` section "Status Transitions".
 *
 * ```
 * Draft        -> Todo         Project Owner
 * Draft        -> Canceled     Project Owner
 * Todo         -> In Progress  the Assignee
 * Todo         -> Canceled     Project Owner
 * In Progress  -> Completed    the Assignee
 * In Progress  -> Canceled     Project Owner
 * Completed    -> --           terminal
 * Canceled     -> --           terminal
 * ```
 *
 * Anything not in that table is illegal.
 */
object TaskTransitions {

    /** Who a transition demands. Deliberately not a general role type: only two answers occur. */
    internal enum class RequiredActor {
        /** R01, the owner of the task's project. */
        PROJECT_OWNER,

        /** TR-1: the assignee of *that task*, not merely a member of the team. */
        ASSIGNEE,
    }

    /** The transition table itself: legal `(from, to)` pairs and who may perform each. */
    internal val TABLE: Map<Pair<TaskStatus, TaskStatus>, RequiredActor> =
        mapOf(
            (TaskStatus.DRAFT to TaskStatus.TODO) to RequiredActor.PROJECT_OWNER,
            (TaskStatus.DRAFT to TaskStatus.CANCELED) to RequiredActor.PROJECT_OWNER,
            (TaskStatus.TODO to TaskStatus.IN_PROGRESS) to RequiredActor.ASSIGNEE,
            (TaskStatus.TODO to TaskStatus.CANCELED) to RequiredActor.PROJECT_OWNER,
            (TaskStatus.IN_PROGRESS to TaskStatus.COMPLETED) to RequiredActor.ASSIGNEE,
            (TaskStatus.IN_PROGRESS to TaskStatus.CANCELED) to RequiredActor.PROJECT_OWNER,
        )

    /**
     * Attempt to move `ctx.task` to [target] as `ctx.actor`.
     *
     * **The evaluation order is mandatory**: legality of the move (409) is
     * decided before the actor's authority to make it (403). Whether an
     * operation is possible at all does not depend on who is asking, and
     * answering "that task is already Completed" leaks nothing to somebody who
     * can already see the task.
     *
     * 1. the task is terminal -> [TransitionResult.Illegal] (TE-2)
     * 2. the target is the status it already holds -> Illegal (TR-3)
     * 3. the pair is not in [TABLE] -> Illegal
     * 4. `Todo -> In Progress` with no assignee -> Illegal (TR-2): no valid
     *    actor exists, so this is a conflict and not a forbidden request
     * 5. the required actor is not satisfied -> [TransitionResult.Forbidden]
     * 6. otherwise -> [TransitionResult.Allowed]
     */
    fun attempt(ctx: TaskContext, target: TaskStatus): TransitionResult {
        val from = ctx.task.status

        // 1. TE-2: terminal tasks admit no status change, by anyone.
        if (from.isTerminal) {
            return TransitionResult.Illegal(
                "A ${from.wireName} task is final and its status cannot be changed.",
            )
        }

        // 2. TR-3: a transition to the current status is a conflict, not a no-op.
        if (target == from) {
            return TransitionResult.Illegal("The task is already ${from.wireName}.")
        }

        // 3. Not in the table at all.
        val required =
            TABLE[from to target]
                ?: return TransitionResult.Illegal(
                    "A task cannot move from ${from.wireName} to ${target.wireName}.",
                )

        // 4. TR-2: an unassigned Todo task cannot start, because nobody could start it.
        if (from == TaskStatus.TODO && target == TaskStatus.IN_PROGRESS && ctx.task.assignee == null) {
            return TransitionResult.Illegal(
                "An unassigned task cannot be started. It must be assigned first.",
            )
        }

        // 5. Only now does it matter who is asking.
        val satisfied =
            when (required) {
                RequiredActor.PROJECT_OWNER -> ctx.isProjectOwner
                RequiredActor.ASSIGNEE -> ctx.isAssignee
            }
        if (!satisfied) {
            val who =
                when (required) {
                    RequiredActor.PROJECT_OWNER -> "the owner of this task's project"
                    RequiredActor.ASSIGNEE -> "the assignee of this task"
                }
            return TransitionResult.Forbidden(
                "Only $who may move it from ${from.wireName} to ${target.wireName}.",
            )
        }

        // 6.
        return TransitionResult.Allowed(ctx.task.withStatus(target))
    }

    /**
     * Every status `ctx.actor` may move `ctx.task` to right now.
     *
     * Feeds `TaskPermissions.allowedTransitions` in the API contract, computed
     * from the same function that enforces the rules so the UI can never be
     * shown a control the server would reject. Empty for terminal tasks and for
     * actors with no transition rights.
     */
    fun allowedTransitions(ctx: TaskContext): Set<TaskStatus> =
        TaskStatus.entries
            .filter { attempt(ctx, it) is TransitionResult.Allowed }
            .toSet()
}
