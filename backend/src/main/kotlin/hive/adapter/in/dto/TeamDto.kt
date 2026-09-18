package hive.adapter.`in`.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/** Teams -- `docs/api-contract.md` section 3. */

/** `TeamSummary` (contract section 1.2). */
data class TeamSummaryDto(
    val id: Long,
    val name: String,
    val teamLead: UserSummaryDto,
    val memberCount: Int,
)

/** `TeamDetail` (contract section 1.2). */
data class TeamDetailDto(
    val id: Long,
    val name: String,
    val teamLead: UserSummaryDto,
    val members: List<UserSummaryDto>,
    val permissions: TeamPermissionsDto,
)

/** `TeamPermissions` (contract section 1.2) -- what the caller may do with this team. */
data class TeamPermissionsDto(
    val canRename: Boolean,
    val canAddMember: Boolean,
    val canRemoveMember: Boolean,
    val canTransferLead: Boolean,
)

/** `POST /teams` and `PATCH /teams/{id}` -- both take `{ "name": string }`. */
data class TeamNameRequest(
    @field:NotBlank(message = MSG_NOT_BLANK)
    @field:Size(min = NAME_MIN, max = NAME_MAX, message = MSG_NAME_SIZE)
    val name: String? = null,
)

/**
 * `POST /teams/{id}/members`, `PUT /teams/{id}/lead` and
 * `PUT /projects/{id}/owner` -- all three take `{ "userId": number }`.
 *
 * Whether the named user exists is not a question this DTO can answer; the use
 * case answers it with the 400 the contract promises (AS-2, PR-7).
 */
data class UserIdRequest(
    @field:NotNull(message = MSG_REQUIRED)
    val userId: Long? = null,
)
