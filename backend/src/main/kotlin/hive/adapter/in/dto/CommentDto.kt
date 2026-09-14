package hive.adapter.`in`.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** Comments -- `docs/api-contract.md` section 6. */

/**
 * `Comment` (contract section 1.2).
 *
 * Neither `author` nor `timestamp` has a request counterpart: CM-4 and CM-5
 * make both server-assigned, so there is nowhere for a client to set them.
 */
data class CommentDto(
    val id: Long,
    val taskId: Long,
    val author: UserSummaryDto,
    /** ISO-8601 UTC, minute precision -- `2026-09-13T18:30:00Z`. */
    val timestamp: String,
    val content: String,
)

/** `POST /tasks/{taskId}/comments`. */
data class CreateCommentRequest(
    @field:NotBlank(message = MSG_NOT_BLANK)
    @field:Size(min = CONTENT_MIN, max = CONTENT_MAX, message = MSG_CONTENT_SIZE)
    val content: String? = null,
)
