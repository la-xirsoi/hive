package hive.domain.error

/**
 * Root of every error the domain layer raises.
 *
 * Each subtype maps to exactly one HTTP status at the REST boundary. The
 * mapping lives with the subtype so that no adapter has to guess:
 *
 * | Exception                | Status |
 * |--------------------------|--------|
 * | [ValidationException]    | 400    |
 * | [AuthorizationException] | 403    |
 * | [NotFoundException]      | 404    |
 * | [ConflictException]      | 409    |
 *
 * There is deliberately no domain exception for `401`: authentication is a
 * security-adapter concern and the domain never sees an unauthenticated actor.
 *
 * Messages are end-user readable and must never contain implementation detail
 * (no SQL, no class names, no stack traces).
 */
sealed class DomainException(message: String) : RuntimeException(message)

/** One invalid field, reported back to the caller so the UI can highlight it. */
data class FieldError(val field: String, val message: String)

/**
 * Input failed validation. Maps to **400 Bad Request**.
 *
 * Carries per-field detail so the API can answer "which fields are invalid".
 */
class ValidationException(val fieldErrors: List<FieldError>) :
    DomainException(renderMessage(fieldErrors)) {

    constructor(field: String, message: String) : this(listOf(FieldError(field, message)))

    private companion object {
        fun renderMessage(fieldErrors: List<FieldError>): String =
            if (fieldErrors.isEmpty()) {
                "Validation failed."
            } else {
                fieldErrors.joinToString(separator = "; ") { "${it.field}: ${it.message}" }
            }
    }
}

/**
 * The resource does not exist, **or** exists but is invisible to the actor.
 * Maps to **404 Not Found**.
 *
 * Invisible resources are reported as not-found rather than forbidden: a 403
 * would confirm the resource exists to someone with no right to know.
 */
class NotFoundException(val resource: String, val id: Any?) :
    DomainException(if (id == null) "$resource was not found." else "$resource $id was not found.")

/**
 * The operation is impossible in the resource's current state -- an illegal
 * status transition, an edit to a terminal task, assigning a Draft task,
 * removing a team's lead, a duplicate email. Maps to **409 Conflict**.
 *
 * Note that a conflict does not depend on who is asking, which is why it is
 * evaluated *before* the actor's role.
 */
class ConflictException(message: String) : DomainException(message)

/**
 * The actor is known and the resource is visible to them, but they do not hold
 * the role this operation requires. Maps to **403 Forbidden**.
 */
class AuthorizationException(message: String) : DomainException(message)
