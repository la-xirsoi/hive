package hive.domain.model

import hive.domain.error.ConflictException
import hive.domain.error.ValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Task, TaskStatus, and the terminal-state edit ban the entity enforces on its
 * own -- TE-1 for name and description, TE-3 for the assignee.
 */
@DisplayName("Task and TaskStatus")
class TaskTest {

    private val owner = UserId(1)
    private val member = UserId(2)
    private val other = UserId(3)

    private fun task(status: TaskStatus, assignee: UserId? = member) =
        Task(
            id = TaskId(1),
            name = TaskName("Requeen hive 4"),
            description = TaskDescription("The colony is queenless."),
            projectId = ProjectId(10),
            creator = owner,
            assignee = assignee,
            status = status,
        )

    @Nested
    @DisplayName("TaskStatus")
    inner class Statuses {

        @Test
        fun `the wire names are exactly the five strings from spec md`() {
            assertEquals(
                listOf("Draft", "Todo", "In Progress", "Completed", "Canceled"),
                TaskStatus.entries.map { it.wireName },
            )
        }

        @Test
        fun `Completed and Canceled are terminal and the rest are not`() {
            assertTrue(TaskStatus.COMPLETED.isTerminal)
            assertTrue(TaskStatus.CANCELED.isTerminal)
            assertFalse(TaskStatus.DRAFT.isTerminal)
            assertFalse(TaskStatus.TODO.isTerminal)
            assertFalse(TaskStatus.IN_PROGRESS.isTerminal)
        }

        @ParameterizedTest
        @EnumSource(TaskStatus::class)
        fun `every status round-trips through its wire name`(status: TaskStatus) {
            assertEquals(status, TaskStatus.fromWireName(status.wireName))
            assertEquals(status.wireName, status.toString())
        }

        @Test
        fun `an unknown wire name is a validation failure naming the status field`() {
            val thrown = assertThrows<ValidationException> { TaskStatus.fromWireName("Finished") }

            assertEquals("status", thrown.fieldErrors.single().field)
            assertTrue(thrown.message!!.contains("In Progress"), "the message should list the legal values")
        }

        @Test
        fun `parsing is exact, so the wrong casing or spacing is rejected`() {
            assertThrows<ValidationException> { TaskStatus.fromWireName("in progress") }
            assertThrows<ValidationException> { TaskStatus.fromWireName("IN_PROGRESS") }
            assertThrows<ValidationException> { TaskStatus.fromWireName("") }
        }

        @Test
        fun `the lenient parser returns null instead of throwing`() {
            assertEquals(TaskStatus.TODO, TaskStatus.fromWireNameOrNull("Todo"))
            assertNull(TaskStatus.fromWireNameOrNull("Nope"))
        }

        @Test
        fun `TK-2 the default status is Draft`() {
            assertEquals(TaskStatus.DRAFT, TaskStatus.DEFAULT)
        }
    }

    @Nested
    @DisplayName("TK-2: creation")
    inner class Creation {

        @Test
        fun `TK-2 a new task is Draft, unassigned, credited to its creator and unsaved`() {
            val created =
                Task.create(
                    name = TaskName("New"),
                    description = TaskDescription.EMPTY,
                    projectId = ProjectId(10),
                    creator = owner,
                )

            assertNull(created.id)
            assertEquals(TaskStatus.DRAFT, created.status)
            assertNull(created.assignee)
            assertEquals(owner, created.creator)
        }
    }

    @Nested
    @DisplayName("TE-1 / TE-3: the terminal-state edit ban")
    inner class TerminalBan {

        @ParameterizedTest(name = "a {0} task cannot be renamed")
        @EnumSource(TaskStatus::class, names = ["COMPLETED", "CANCELED"])
        fun `TE-1 a terminal task cannot be renamed`(status: TaskStatus) {
            val thrown = assertThrows<ConflictException> { task(status).rename(TaskName("New name")) }

            assertTrue(thrown.message!!.contains(status.wireName))
        }

        @ParameterizedTest(name = "a {0} task's description cannot be changed")
        @EnumSource(TaskStatus::class, names = ["COMPLETED", "CANCELED"])
        fun `TE-1 a terminal task's description cannot be changed`(status: TaskStatus) {
            assertThrows<ConflictException> { task(status).changeDescription(TaskDescription("New")) }
        }

        @ParameterizedTest(name = "a {0} task cannot be reassigned")
        @EnumSource(TaskStatus::class, names = ["COMPLETED", "CANCELED"])
        fun `TE-3 a terminal task cannot be reassigned`(status: TaskStatus) {
            assertThrows<ConflictException> { task(status).assignTo(other) }
        }

        @ParameterizedTest(name = "a {0} task cannot be unassigned")
        @EnumSource(TaskStatus::class, names = ["COMPLETED", "CANCELED"])
        fun `TE-3 a terminal task cannot be unassigned either`(status: TaskStatus) {
            assertThrows<ConflictException> { task(status).assignTo(null) }
        }

        @ParameterizedTest(name = "a {0} task is still editable by the entity")
        @EnumSource(TaskStatus::class, names = ["DRAFT", "TODO", "IN_PROGRESS"])
        fun `non-terminal tasks remain editable`(status: TaskStatus) {
            val subject = task(status)

            assertEquals(TaskName("Renamed"), subject.rename(TaskName("Renamed")).name)
            assertEquals(TaskDescription("D"), subject.changeDescription(TaskDescription("D")).description)
            assertEquals(other, subject.assignTo(other).assignee)
        }
    }

    @Nested
    @DisplayName("entity behaviour")
    inner class Behaviour {

        @Test
        fun `isAssignedTo is false for an unassigned task`() {
            assertFalse(task(TaskStatus.TODO, assignee = null).isAssignedTo(member))
            assertTrue(task(TaskStatus.TODO).isAssignedTo(member))
            assertFalse(task(TaskStatus.TODO).isAssignedTo(other))
        }

        @Test
        fun `isLive covers exactly Todo and In Progress (TM-8, PR-8, UQ-1)`() {
            assertTrue(task(TaskStatus.TODO).isLive)
            assertTrue(task(TaskStatus.IN_PROGRESS).isLive)
            assertFalse(task(TaskStatus.DRAFT).isLive)
            assertFalse(task(TaskStatus.COMPLETED).isLive)
            assertFalse(task(TaskStatus.CANCELED).isLive)
        }

        @Test
        fun `operations return new instances and never mutate the original`() {
            val original = task(TaskStatus.TODO)

            val renamed = original.rename(TaskName("Renamed"))
            val reassigned = original.assignTo(other)

            assertEquals(TaskName("Requeen hive 4"), original.name)
            assertEquals(member, original.assignee)
            assertEquals(TaskName("Renamed"), renamed.name)
            assertEquals(other, reassigned.assignee)
        }

        @Test
        fun `AS-7 unassigning sets the assignee to null`() {
            assertNull(task(TaskStatus.TODO).assignTo(null).assignee)
        }

        @Test
        fun `tasks compare structurally`() {
            assertEquals(task(TaskStatus.TODO), task(TaskStatus.TODO))
            assertEquals(task(TaskStatus.TODO).hashCode(), task(TaskStatus.TODO).hashCode())
            assertTrue(task(TaskStatus.TODO) != task(TaskStatus.DRAFT))
        }

        @Test
        fun `PR-9 there is no operation to move a task's project`() {
            // The entity exposes no way to change projectId; only `copy` could,
            // and that is the adapter's business, not an application operation.
            assertEquals(ProjectId(10), task(TaskStatus.TODO).projectId)
        }
    }
}
