package hive.domain.model

import hive.domain.HiveClock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A note left by a user on a task.
 *
 * CM-4: the author is always the acting user, never a client-supplied value.
 * CM-5: the timestamp is server-assigned, UTC, and truncated to the **minute**
 * (`spec.md`); browsers render it in local time.
 * CM-6: comments are immutable -- there is no edit and no delete operation, by
 * design, because an audit trail is the safer default.
 */
data class Comment(
    val id: CommentId?,
    val taskId: TaskId,
    val author: UserId,
    val timestamp: Instant,
    val content: CommentContent,
) {
    companion object {
        /**
         * The only way comments are made in production.
         *
         * Takes the time from the injected [clock] -- no domain code calls
         * `Instant.now()` -- and truncates to the minute at the point of
         * creation so that CM-5 holds no matter what clock is supplied.
         */
        fun create(
            taskId: TaskId,
            author: UserId,
            content: CommentContent,
            clock: HiveClock,
        ): Comment =
            Comment(
                id = null,
                taskId = taskId,
                author = author,
                timestamp = clock.nowUtcToMinute().truncatedTo(ChronoUnit.MINUTES),
                content = content,
            )
    }
}
