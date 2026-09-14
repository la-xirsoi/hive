package hive.adapter.`in`.controller

import hive.adapter.`in`.dto.CommentDto
import hive.adapter.`in`.dto.CreateCommentRequest
import hive.adapter.`in`.dto.PageResponse
import hive.adapter.`in`.dto.pageRequestOf
import hive.adapter.`in`.mapper.toDto
import hive.adapter.`in`.mapper.toResponse
import hive.adapter.`in`.security.ActingUser
import hive.adapter.`in`.security.CurrentUser
import hive.application.usecase.AddCommentCommand
import hive.application.usecase.CommentUseCases
import hive.domain.model.PageRequest
import hive.domain.model.TaskId
import hive.domain.model.UserId
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Comments -- `docs/api-contract.md` section 6.
 *
 * | Endpoint | Use case |
 * |----------|----------|
 * | `GET /tasks/{taskId}/comments` | [CommentUseCases.list] |
 * | `POST /tasks/{taskId}/comments` | [CommentUseCases.add] |
 *
 * There is no update and no delete, because CM-6 makes comments immutable and
 * [CommentUseCases] offers no such operation to call.
 *
 * 403 never appears on either endpoint: CM-1 makes commentability identical to
 * visibility, so a caller who cannot comment cannot see the task either and
 * gets a 404 (`docs/authorization.md` section 12).
 */
@RestController
@RequestMapping("/api/v1/tasks/{taskId}/comments", produces = [MediaType.APPLICATION_JSON_VALUE])
class CommentController(
    private val comments: CommentUseCases,
) {

    /** `GET /tasks/{taskId}/comments` -- CM-2, oldest first. */
    @GetMapping
    fun list(
        @CurrentUser actor: ActingUser,
        @PathVariable taskId: Long,
        @RequestParam(defaultValue = "${PageRequest.DEFAULT_PAGE}") page: Int,
        @RequestParam(defaultValue = "${PageRequest.DEFAULT_SIZE}") size: Int,
    ): PageResponse<CommentDto> =
        comments.list(actor.id, TaskId(taskId), pageRequestOf(page, size)).toResponse { it.toDto() }

    /** `POST /tasks/{taskId}/comments` -- CM-1/CM-3/CM-4/CM-5. Permitted on terminal tasks. */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun add(
        @CurrentUser actor: ActingUser,
        @PathVariable taskId: Long,
        @Valid @RequestBody request: CreateCommentRequest,
    ): CommentDto =
        comments.add(actor.id, TaskId(taskId), AddCommentCommand(requireNotNull(request.content))).toDto()
}
