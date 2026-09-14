package hive.application.usecase

import hive.application.view.CommentView
import hive.domain.model.Page
import hive.domain.model.PageRequest
import hive.domain.model.TaskId
import hive.domain.model.UserId

/**
 * Inbound port for section 6 of `docs/api-contract.md`.
 *
 * | Endpoint | Method |
 * |----------|--------|
 * | `GET /tasks/{taskId}/comments` | [list] |
 * | `POST /tasks/{taskId}/comments` | [add] |
 *
 * CM-6 -- comments are immutable -- is expressed by omission, exactly as it is
 * in [hive.domain.port.CommentRepository]: there is no update and no delete on
 * this port, so no adapter can offer one.
 */
interface CommentUseCases {

    /**
     * `GET /tasks/{taskId}/comments` -- CM-2: readable by exactly the users who
     * can see the task. Oldest first.
     *
     * @throws hive.domain.error.NotFoundException (404) if the task is missing
     *   or invisible -- never 403, which would confirm it exists.
     */
    fun list(actor: UserId, taskId: TaskId, page: PageRequest = PageRequest.DEFAULT): Page<CommentView>

    /**
     * `POST /tasks/{taskId}/comments` -- CM-1/CM-3/CM-4/CM-5: anyone who can see
     * the task may comment on it, terminal tasks included (TE-4), and the author
     * and timestamp are server-assigned.
     *
     * @throws hive.domain.error.NotFoundException (404) missing or invisible task.
     * @throws hive.domain.error.ValidationException (400) blank or over-long content.
     */
    fun add(actor: UserId, taskId: TaskId, command: AddCommentCommand): CommentView
}
