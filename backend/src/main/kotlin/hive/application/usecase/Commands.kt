package hive.application.usecase

import hive.domain.model.ProjectId
import hive.domain.model.TaskStatus
import hive.domain.model.TeamId
import hive.domain.model.UserId

/**
 * The command and query types the inbound ports accept.
 *
 * **They carry raw `String`s, not domain value objects.** Constructing a
 * [hive.domain.model.TaskName] or an [hive.domain.model.EmailAddress] validates
 * it, and validation is a domain rule; letting the REST adapter build them would
 * put the domain's constraints on the far side of the port and make the use case
 * trust its caller. The services build the value objects themselves, so a
 * malformed string becomes a [hive.domain.error.ValidationException] (400) no
 * matter which adapter called.
 *
 * Identifiers *are* typed: an id is not validated, it is looked up, and typing
 * it is what stops a [TeamId] from being passed where a [ProjectId] belongs.
 *
 * **The acting user is never in a command.** It is a separate, explicit
 * parameter on every operation (AU-2). Putting it in the payload would make it
 * look caller-supplied, which is exactly what the contract forbids.
 */

/**
 * The claims of an authenticated OAuth principal, used to provision a Hive user
 * on first sight (US-3).
 *
 * @param subject the token's `sub` claim -- the identity provider's stable id
 *   for this person.
 * @param email the `email` claim. This is the key Hive matches on, because
 *   [hive.domain.model.User] has no column for a subject: identity is owned by
 *   the provider and Hive stores only what the contract publishes.
 * @param name the `name` claim, absent for providers that do not supply one.
 */
data class PrincipalClaims(
    val subject: String,
    val email: String,
    val name: String?,
)

/** `PATCH /users/me`. US-5: the name only -- email is identity-provider owned. */
data class RenameUserCommand(val name: String)

/** `POST /teams`. TM-1: the creator becomes lead and, by INV-1, a member. */
data class CreateTeamCommand(val name: String)

/** `PATCH /teams/{id}`. */
data class RenameTeamCommand(val name: String)

/** `POST /projects`. PR-1/PR-2: the creator owns it and must belong to [teamId]. */
data class CreateProjectCommand(val name: String, val teamId: TeamId)

/** `PATCH /projects/{id}`. */
data class RenameProjectCommand(val name: String)

/** `POST /tasks`. TK-2 fixes the status and assignee, so neither appears here. */
data class CreateTaskCommand(
    val projectId: ProjectId,
    val name: String,
    val description: String,
)

/**
 * `PATCH /tasks/{id}`. Both fields are optional; `null` means "leave alone".
 *
 * A command with neither field set is a 400 -- a request that asks for nothing
 * is malformed, not a successful no-op.
 */
data class UpdateTaskCommand(
    val name: String? = null,
    val description: String? = null,
) {
    /** Whether this command asks for any change at all. */
    val isEmpty: Boolean get() = name == null && description == null
}

/**
 * `PUT /tasks/{id}/status`.
 *
 * Typed as a [TaskStatus] rather than a string: the adapter parses the wire
 * spelling with [TaskStatus.fromWireName], which already answers an unknown
 * literal with a 400.
 */
data class TransitionTaskCommand(val target: TaskStatus)

/** `PUT /tasks/{id}/assignee`. `null` unassigns, which AS-7 permits only from `Todo`. */
data class AssignTaskCommand(val assignee: UserId?)

/** `POST /tasks/{taskId}/comments`. CM-4/CM-5: author and timestamp are server-assigned. */
data class AddCommentCommand(val content: String)
