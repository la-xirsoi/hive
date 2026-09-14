package hive.adapter.`in`.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * The dev-profile token endpoint -- `docs/api-contract.md` section 9.
 *
 * This shape is the only thing keeping the Angular dev sign-in and the Spring
 * dev issuer in agreement, so it is fixed field for field. The endpoint itself
 * exists only under the `dev` profile; see
 * [hive.adapter.`in`.security.DevTokenController].
 */

/** `POST /api/v1/dev/token` request. `name` is used only when the user is provisioned. */
data class DevTokenRequest(
    @field:NotBlank(message = MSG_NOT_BLANK)
    @field:Size(min = EMAIL_MIN, max = EMAIL_MAX, message = MSG_EMAIL_SIZE)
    @field:Email(message = MSG_EMAIL_FORMAT)
    val email: String? = null,
    @field:Size(min = NAME_MIN, max = NAME_MAX, message = MSG_NAME_SIZE)
    val name: String? = null,
)

/** `POST /api/v1/dev/token` response. [expiresIn] is a lifetime in seconds. */
data class DevTokenResponse(
    val accessToken: String,
    val expiresIn: Long,
)
