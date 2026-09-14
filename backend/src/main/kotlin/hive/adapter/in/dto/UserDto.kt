package hive.adapter.`in`.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Null
import jakarta.validation.constraints.Size

/**
 * Users -- `docs/api-contract.md` section 2.
 *
 * Request fields are declared nullable on purpose. A non-null Kotlin property
 * whose JSON key is absent fails inside Jackson, which can only report "the
 * body was unreadable"; a nullable property with `@NotNull`/`@NotBlank` fails
 * inside the validator, which reports *which field* was missing. Per-field 400
 * detail is the whole point of validating at the edge, so every request DTO in
 * this package is written that way.
 */

/** `UserSummary` (contract section 1.2). */
data class UserSummaryDto(
    val id: Long,
    val name: String,
    val email: String,
)

/**
 * `PATCH /users/me`.
 *
 * [email] exists here **so that it can be rejected**. US-5 makes the address
 * identity-provider owned, and the contract says sending it is a 400; declaring
 * it `@Null` turns that into a field error naming `email` rather than a
 * silently ignored property, which is what an unmapped field would give.
 */
data class UpdateMeRequest(
    @field:NotBlank(message = MSG_NOT_BLANK)
    @field:Size(min = NAME_MIN, max = NAME_MAX, message = MSG_NAME_SIZE)
    val name: String? = null,
    @field:Null(message = "cannot be changed; it is owned by the identity provider.")
    val email: String? = null,
)
