package hive.domain.model

import hive.domain.HiveClock
import hive.domain.error.ValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Comment, and CM-5: the timestamp is server-assigned UTC truncated to the
 * minute. The clock is always injected, so these assertions are exact rather
 * than approximate.
 */
@DisplayName("Comment")
class CommentTest {

    private val taskId = TaskId(1)
    private val author = UserId(2)
    private val content = CommentContent("The queen has been introduced.")

    /** A clock deliberately carrying seconds and nanos, to prove they are dropped. */
    private val messyInstant: Instant = Instant.parse("2026-09-13T18:30:47.123456789Z")
    private val fixedClock = HiveClock { messyInstant }

    @Test
    fun `CM-5 the timestamp is truncated to the minute`() {
        val comment = Comment.create(taskId, author, content, fixedClock)

        assertEquals(Instant.parse("2026-09-13T18:30:00Z"), comment.timestamp)
        assertEquals(0, comment.timestamp.nano)
        assertEquals(comment.timestamp, comment.timestamp.truncatedTo(ChronoUnit.MINUTES))
    }

    @Test
    fun `CM-5 truncation happens at creation regardless of what the clock returns`() {
        // Even a badly behaved clock cannot produce a sub-minute comment timestamp.
        val sloppyClock = HiveClock { Instant.parse("2026-01-01T00:00:59.999Z") }

        val comment = Comment.create(taskId, author, content, sloppyClock)

        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), comment.timestamp)
    }

    @Test
    fun `CM-5 a fixed clock makes the timestamp deterministic across creations`() {
        val first = Comment.create(taskId, author, content, fixedClock)
        val second = Comment.create(taskId, author, CommentContent("Second"), fixedClock)

        assertEquals(first.timestamp, second.timestamp)
    }

    @Test
    fun `CM-4 the author and task are taken from the arguments, and the comment is unsaved`() {
        val comment = Comment.create(taskId, author, content, fixedClock)

        assertNull(comment.id)
        assertEquals(taskId, comment.taskId)
        assertEquals(author, comment.author)
        assertEquals(content, comment.content)
    }

    @Test
    fun `content validation is enforced by the value object`() {
        assertThrows<ValidationException> { CommentContent("   ") }
        assertThrows<ValidationException> { CommentContent("x".repeat(4001)) }
    }

    @Test
    fun `comments compare structurally`() {
        val a = Comment.create(taskId, author, content, fixedClock)
        val b = Comment.create(taskId, author, content, fixedClock)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `CM-6 there is no edit or delete operation on a comment`() {
        // Expressed by the type: the only mutation available is `copy`, which the
        // adapters use for id assignment. No domain operation changes content.
        val comment = Comment.create(taskId, author, content, fixedClock)
        val persisted = comment.copy(id = CommentId(9))

        assertEquals(content, persisted.content)
        assertEquals(comment.timestamp, persisted.timestamp)
    }
}
