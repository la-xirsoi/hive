package hive.domain.model

import hive.domain.error.ConflictException

/**
 * A unit of work inside a project.
 *
 * The entity enforces the terminal-state ban from `spec.md` ("Tasks cannot be
 * edited once they are in a terminal state") for every mutating operation it
 * offers -- TE-1 for name and description, TE-3 for the assignee. Status changes
 * do not go through the entity at all: they go through
 * [hive.domain.policy.TaskTransitions], which owns the state machine and the
 * 409-before-403 ordering.
 */
data class Task(
    val id: TaskId?,
    val name: TaskName,
    val description: TaskDescription,
    val projectId: ProjectId,
    val creator: UserId,
    val assignee: UserId?,
    val status: TaskStatus,
) {
    /** Whether [userId] is this task's assignee. Unassigned tasks match nobody. */
    fun isAssignedTo(userId: UserId): Boolean = assignee != null && assignee == userId

    /** Whether the task is "live": awaiting or undergoing work (TM-8, PR-8, UQ-1). */
    val isLive: Boolean get() = status == TaskStatus.TODO || status == TaskStatus.IN_PROGRESS

    /**
     * TE-1: rename the task.
     *
     * @throws ConflictException in a terminal state.
     */
    fun rename(newName: TaskName): Task {
        requireNotTerminal("renamed")
        return copy(name = newName)
    }

    /**
     * TE-1: replace the description.
     *
     * @throws ConflictException in a terminal state.
     */
    fun changeDescription(newDescription: TaskDescription): Task {
        requireNotTerminal("edited")
        return copy(description = newDescription)
    }

    /**
     * AS-6/AS-7/TE-3: assign, reassign, or (with `null`) unassign.
     *
     * The role and state rules around *who* may do this and *when* live in
     * [hive.domain.policy.AuthorizationPolicy.checkAssign]; the entity guards
     * only the invariant it can see on its own.
     *
     * @throws ConflictException in a terminal state.
     */
    fun assignTo(newAssignee: UserId?): Task {
        requireNotTerminal("reassigned")
        return copy(assignee = newAssignee)
    }

    /**
     * Move to [newStatus] without re-checking anything.
     *
     * Internal to the state machine -- call
     * [hive.domain.policy.TaskTransitions.attempt] instead.
     */
    internal fun withStatus(newStatus: TaskStatus): Task = copy(status = newStatus)

    private fun requireNotTerminal(verb: String) {
        if (status.isTerminal) {
            throw ConflictException("A ${status.wireName} task cannot be $verb.")
        }
    }

    companion object {
        /**
         * TK-2: a new task starts in `Draft`, is credited to its creator, and
         * has no assignee.
         */
        fun create(
            name: TaskName,
            description: TaskDescription,
            projectId: ProjectId,
            creator: UserId,
        ): Task =
            Task(
                id = null,
                name = name,
                description = description,
                projectId = projectId,
                creator = creator,
                assignee = null,
                status = TaskStatus.DEFAULT,
            )
    }
}
