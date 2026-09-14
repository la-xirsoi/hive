package hive.adapter.`in`.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/** Projects -- `docs/api-contract.md` section 4. */

/** `ProjectSummary` (contract section 1.2). */
data class ProjectSummaryDto(
    val id: Long,
    val name: String,
    val team: TeamSummaryDto,
    val projectOwner: UserSummaryDto,
)

/** `POST /projects`. The creator becomes the owner (PR-1); it is never in the body. */
data class CreateProjectRequest(
    @field:NotBlank(message = MSG_NOT_BLANK)
    @field:Size(min = NAME_MIN, max = NAME_MAX, message = MSG_NAME_SIZE)
    val name: String? = null,
    @field:NotNull(message = MSG_REQUIRED)
    val teamId: Long? = null,
)

/** `PATCH /projects/{id}`. */
data class ProjectNameRequest(
    @field:NotBlank(message = MSG_NOT_BLANK)
    @field:Size(min = NAME_MIN, max = NAME_MAX, message = MSG_NAME_SIZE)
    val name: String? = null,
)
