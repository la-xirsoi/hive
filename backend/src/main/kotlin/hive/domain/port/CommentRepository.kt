package hive.domain.port

import hive.domain.model.Comment
import hive.domain.model.Page
import hive.domain.model.PageRequest
import hive.domain.model.TaskId

/**
 * Outbound port for comment storage.
 *
 * CM-6 -- comments are immutable -- is expressed by omission: there is no update
 * and no delete, so no adapter can offer one.
 */
interface CommentRepository {
    /** CM-2: the comments of a task, oldest first. Visibility is checked before this is called. */
    fun findByTask(taskId: TaskId, page: PageRequest): Page<Comment>

    fun save(comment: Comment): Comment
}
