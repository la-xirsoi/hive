package hive.domain.policy

import hive.domain.error.AuthorizationException
import hive.domain.error.ConflictException
import hive.domain.model.Project
import hive.domain.model.Task
import hive.domain.model.TaskContext
import hive.domain.model.TaskStatus
import hive.domain.model.Team
import hive.domain.model.UserId

/**
 * The single place where "who may do what" is decided.
 *
 * Every rule here is traceable to a rule id in `docs/authorization.md`, which is
 * in turn derived line by line from `spec.md`. The application, REST and UI
 * layers consult this object; they never re-derive a rule.
 *
 * The policy is stateless and performs no I/O: it is handed already-loaded
 * aggregates and answers immediately. Loading is the application layer's job.
 *
 * **Naming convention, and it is load-bearing:**
 * - `canX` returns a [Boolean] and is used for *visibility*. A `false` becomes a
 *   **404**, never a 403 -- a 403 would confirm to a stranger that the resource
 *   exists.
 * - `checkX` returns `Unit` or throws. [AuthorizationException] is **403**;
 *   [ConflictException] is **409**. These are used for *operations*.
 *
 * Mixing the two conventions is exactly what produces the wrong status code, so
 * the naming is deliberate.
 *
 * **Ordering, applied consistently:** visibility (404) -> state legality (409)
 * -> actor role (403). 409 comes before 403 because whether an operation is
 * possible at all does not depend on who is asking.
 */
object AuthorizationPolicy {

    // ---------------------------------------------------------------- tasks

    /**
     * VIS-1..VIS-5: may the actor see this task at all?
     *
     * ```
     * isAssignee                                    -> true   (VIS-1, VIS-5)
     * isProjectOwner                                -> true   (VIS-2, any status)
     * isTeamLead   && status != Draft               -> true   (VIS-3)
     * isTeamMember && status !in {Draft, Canceled}  -> true   (VIS-4)
     * otherwise                                     -> false
     * ```
     *
     * VIS-5 is why the assignee clause comes first: a user must be able to see
     * work that was assigned to them even after it was canceled.
     */
    fun canViewTask(ctx: TaskContext): Boolean {
        if (ctx.isAssignee) return true // VIS-1, VIS-5
        if (ctx.isProjectOwner) return true // VIS-2: every status, Draft included
        val status = ctx.task.status
        if (ctx.isTeamLead && status != TaskStatus.DRAFT) return true // VIS-3
        if (ctx.isTeamMember && status != TaskStatus.DRAFT && status != TaskStatus.CANCELED) return true // VIS-4
        return false
    }

    /**
     * CM-1/CM-2: a user may comment on, and read the comments of, exactly the
     * tasks they can see -- including terminal ones (CM-3/TE-4), since a comment
     * does not mutate the task record.
     */
    fun canCommentOnTask(ctx: TaskContext): Boolean = canViewTask(ctx)

    /**
     * TK-1: only the owner of the target project may create a task in it.
     *
     * @throws AuthorizationException (403) otherwise.
     */
    fun checkCreateTask(project: Project, actor: UserId) {
        if (!project.isOwnedBy(actor)) {
            throw AuthorizationException("Only the project owner may create tasks in this project.")
        }
    }

    /**
     * TK-3 and TE-1: edit a task's name or description.
     *
     * Terminal check first (409), then the role (403).
     *
     * @throws ConflictException (409) if the task is Completed or Canceled.
     * @throws AuthorizationException (403) if the actor does not own the project.
     */
    fun checkEditTaskFields(ctx: TaskContext) {
        if (ctx.task.status.isTerminal) { // TE-1
            throw ConflictException(
                "A ${ctx.task.status.wireName} task cannot be edited.",
            )
        }
        if (!ctx.isProjectOwner) { // TK-3
            throw AuthorizationException(
                "Only the project owner may change a task's name or description.",
            )
        }
    }

    /**
     * AS-1..AS-7 and TE-3: assign, reassign or unassign a task.
     *
     * ```
     * status.isTerminal                      -> 409  (TE-3)
     * status == Draft                        -> 409  (AS-3)
     * newAssignee == null && status != Todo  -> 409  (AS-7)
     * !isTeamLead                            -> 403  (AS-1)
     * newAssignee == project.projectOwner    -> 403  (AS-4)
     * newAssignee !in team.memberIds         -> 403  (AS-2)
     * ```
     *
     * AS-5 -- a lead assigning to themselves -- needs no clause of its own: the
     * lead passes the membership check by INV-1, and is stopped only if they
     * also happen to own the project (AS-4).
     *
     * AS-2's other half -- the user does not exist at all, which is a 400 -- is
     * the application layer's to detect, since the policy is given ids, not a
     * directory.
     *
     * @param newAssignee the user to assign to, or `null` to unassign.
     */
    fun checkAssign(ctx: TaskContext, newAssignee: UserId?) {
        val status = ctx.task.status

        if (status.isTerminal) { // TE-3
            throw ConflictException(
                "The assignee of a ${status.wireName} task cannot be changed.",
            )
        }
        if (status == TaskStatus.DRAFT) { // AS-3
            throw ConflictException(
                "A Draft task cannot be assigned. It must be moved to Todo first.",
            )
        }
        if (newAssignee == null && status != TaskStatus.TODO) { // AS-7
            throw ConflictException(
                "A task can only be unassigned while it is Todo.",
            )
        }
        if (!ctx.isTeamLead) { // AS-1
            throw AuthorizationException(
                "Only the team lead may assign tasks in this team's projects.",
            )
        }
        if (newAssignee != null) {
            if (newAssignee == ctx.project.projectOwner) { // AS-4
                throw AuthorizationException(
                    "The project owner cannot be assigned a task in their own project.",
                )
            }
            if (newAssignee !in ctx.team.memberIds) { // AS-2
                throw AuthorizationException(
                    "A task can only be assigned to a member of the project's team.",
                )
            }
        }
    }

    // ------------------------------------------------------------- projects

    /**
     * PR-3: the project owner, and the members and lead of the project's team,
     * may see a project. Everyone else gets a 404.
     */
    fun canViewProject(project: Project, team: Team, actor: UserId): Boolean =
        project.isOwnedBy(actor) || team.hasMember(actor)

    /**
     * PR-2: the creator of a project must be a member or the lead of the target
     * team, otherwise anyone could attach a project to any team in the system
     * and force work onto strangers. "Any user can create a Project" still holds
     * in effect: any user may create a team (TM-1) and then a project on it.
     *
     * @throws AuthorizationException (403) if the creator is a stranger to the team.
     */
    fun checkCreateProject(team: Team, creator: UserId) {
        if (!team.hasMember(creator)) {
            throw AuthorizationException(
                "You must be a member of a team to create a project for it.",
            )
        }
    }

    /**
     * PR-5: only the project owner may rename a project.
     *
     * @throws AuthorizationException (403) otherwise.
     */
    fun checkRenameProject(project: Project, actor: UserId) {
        if (!project.isOwnedBy(actor)) {
            throw AuthorizationException("Only the project owner may rename this project.")
        }
    }

    /**
     * PR-6/PR-8: transfer project ownership.
     *
     * PR-8 first (409): if the incoming owner currently holds live (`Todo` or
     * `In Progress`) tasks in this project, the transfer would immediately
     * violate AS-4, so it is rejected and the conflicting tasks are named. The
     * team lead must reassign them first.
     *
     * PR-7 -- that the new owner may be any existing user, team membership not
     * required -- is why there is no membership check here. Whether that user
     * exists (a 400 if not) is the application layer's to verify.
     *
     * @param liveTasksAssignedToNewOwner live tasks in *this* project assigned
     *   to [newOwner]; supply the result of
     *   [hive.domain.port.TaskRepository.findLiveTasksAssignedTo].
     * @throws ConflictException (409) if [newOwner] holds live tasks here.
     * @throws AuthorizationException (403) if the actor does not own the project.
     */
    fun checkTransferProjectOwner(
        project: Project,
        actor: UserId,
        newOwner: UserId,
        liveTasksAssignedToNewOwner: List<Task>,
    ) {
        val conflicting =
            liveTasksAssignedToNewOwner.filter {
                it.isLive && it.isAssignedTo(newOwner) && it.projectId == project.id
            }
        if (conflicting.isNotEmpty()) { // PR-8
            throw ConflictException(
                "The new owner is still assigned to " +
                    conflicting.joinToString(", ") { "\"${it.name.value}\"" } +
                    " in this project. Reassign those tasks before transferring ownership.",
            )
        }
        if (!project.isOwnedBy(actor)) { // PR-6
            throw AuthorizationException("Only the project owner may transfer this project.")
        }
    }

    // ---------------------------------------------------------------- teams

    /**
     * TM-2/TM-3: the members and lead of a team may see it, and so may the owner
     * of any project belonging to the team -- read-only. An owner whose project
     * is worked by a team needs to see who that team is.
     *
     * @param ownsAProjectOfTeam whether [actor] owns any project of this team;
     *   the policy cannot discover this on its own, so the caller supplies it.
     */
    fun canViewTeam(team: Team, actor: UserId, ownsAProjectOfTeam: Boolean): Boolean =
        team.hasMember(actor) || ownsAProjectOfTeam

    /**
     * TM-5: only the team lead may rename a team. Naming is a stewardship act
     * and the lead is the team's steward.
     *
     * @throws AuthorizationException (403) otherwise.
     */
    fun checkRenameTeam(team: Team, actor: UserId) {
        if (!team.isLedBy(actor)) {
            throw AuthorizationException("Only the team lead may rename this team.")
        }
    }

    /**
     * TM-6: only the team lead may add a member. The spec makes the lead
     * responsible for the team's composition by making them responsible for
     * distributing its work.
     *
     * Adding somebody who is already a member is a no-op, not a conflict.
     *
     * @throws AuthorizationException (403) if the actor does not lead the team.
     */
    @Suppress("UNUSED_PARAMETER")
    fun checkAddMember(team: Team, actor: UserId, newMember: UserId) {
        if (!team.isLedBy(actor)) {
            throw AuthorizationException("Only the team lead may add members to this team.")
        }
    }

    /**
     * TM-7: only the team lead may remove a member, and the current lead can
     * never be removed (INV-1) -- the lead must be transferred away first.
     *
     * Conflict before role, as everywhere else.
     *
     * TM-8 -- unassigning the removed member's live tasks -- is a consequence
     * the application layer applies after this check passes.
     *
     * @throws ConflictException (409) if [member] is the current lead.
     * @throws AuthorizationException (403) if the actor does not lead the team.
     */
    fun checkRemoveMember(team: Team, actor: UserId, member: UserId) {
        if (team.isLedBy(member)) { // TM-7 / INV-1
            throw ConflictException(
                "The team lead cannot be removed from the team. Transfer the lead role first.",
            )
        }
        if (!team.isLedBy(actor)) {
            throw AuthorizationException("Only the team lead may remove members from this team.")
        }
    }

    /**
     * TM-9: only the current team lead may hand the team on. TM-10 -- the new
     * lead becomes a member and the outgoing lead stays one -- is enforced by
     * [Team.transferLeadTo], so there is no membership precondition here.
     *
     * @throws AuthorizationException (403) if the actor does not lead the team.
     */
    @Suppress("UNUSED_PARAMETER")
    fun checkTransferLead(team: Team, actor: UserId, newLead: UserId) {
        if (!team.isLedBy(actor)) {
            throw AuthorizationException("Only the team lead may transfer this team.")
        }
    }
}
