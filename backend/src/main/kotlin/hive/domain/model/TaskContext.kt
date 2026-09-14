package hive.domain.model

/**
 * Everything the policy needs to judge one actor against one task.
 *
 * The policy is never handed "a role" as a string. It is handed the facts --
 * the task, its project, that project's team, and who is asking -- and derives
 * the roles itself. A caller therefore cannot get the answer wrong by claiming
 * the wrong role, which is the failure mode this design exists to prevent.
 *
 * Roles in Hive are contextual: the same user may be an owner here, a plain
 * member there, and a stranger somewhere else.
 */
data class TaskContext(
    val task: Task,
    val project: Project,
    val team: Team,
    val actor: UserId,
) {
    /** R01: the actor owns the project this task belongs to. */
    val isProjectOwner: Boolean get() = project.projectOwner == actor

    /** R02: the actor leads the team that works this task's project. */
    val isTeamLead: Boolean get() = team.teamLead == actor

    /** R03: the actor belongs to that team. Leads always do (INV-1). */
    val isTeamMember: Boolean get() = actor in team.memberIds

    /** The actor is the person this task is assigned to. */
    val isAssignee: Boolean get() = task.isAssignedTo(actor)
}
