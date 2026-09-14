package hive.domain.policy

import hive.domain.DomainFixtures
import hive.domain.DomainFixtures.ASSIGNEE
import hive.domain.DomainFixtures.LEAD
import hive.domain.DomainFixtures.MEMBER
import hive.domain.DomainFixtures.OUTSIDER
import hive.domain.DomainFixtures.OWNER
import hive.domain.model.ProjectName
import hive.domain.model.TaskStatus
import hive.domain.model.TaskStatus.CANCELED
import hive.domain.model.TaskStatus.COMPLETED
import hive.domain.model.TaskStatus.DRAFT
import hive.domain.model.TaskStatus.IN_PROGRESS
import hive.domain.model.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * The state machine test.
 *
 * The centrepiece is a table-driven sweep of the **full cross product** of
 * 5 from-statuses x 5 to-statuses x 5 actor roles -- 125 cases -- each asserting
 * the exact [TransitionResult] variant. The expectation is derived from a table
 * transcribed independently from `spec.md`, not from
 * [TaskTransitions.TABLE], so the test can actually disagree with the
 * implementation.
 */
@DisplayName("TaskTransitions")
class TaskTransitionsTest {

    /** The five relationships an actor can have to the fixture task. */
    enum class ActorRole(val userId: UserId) {
        PROJECT_OWNER(OWNER),
        ASSIGNEE_OF_TASK(DomainFixtures.ASSIGNEE),
        TEAM_LEAD_NOT_ASSIGNEE(LEAD),
        PLAIN_MEMBER(MEMBER),
        OUTSIDER(DomainFixtures.OUTSIDER),
    }

    /** The variant we expect back, without caring about the message. */
    enum class Variant { ALLOWED, ILLEGAL, FORBIDDEN }

    @Nested
    @DisplayName("the full cross product of from-status x to-status x actor role")
    inner class CrossProduct {

        @ParameterizedTest(name = "[{0} -> {1}] by {2} is {3}")
        @MethodSource("hive.domain.policy.TaskTransitionsTest#crossProduct")
        fun `every from-status, to-status and actor role combination`(
            from: TaskStatus,
            to: TaskStatus,
            role: ActorRole,
            expected: Variant,
        ) {
            val ctx = DomainFixtures.context(status = from, actor = role.userId)

            val result = TaskTransitions.attempt(ctx, to)

            assertEquals(expected, variantOf(result), "[$from -> $to] by $role produced $result")
            if (result is TransitionResult.Allowed) {
                assertEquals(to, result.task.status, "the returned task must carry the new status")
                assertEquals(ctx.task.copy(status = to), result.task, "nothing else may change")
            }
            // Every non-Allowed result must explain itself to the user.
            if (result is TransitionResult.Illegal) assertTrue(result.reason.isNotBlank())
            if (result is TransitionResult.Forbidden) assertTrue(result.reason.isNotBlank())
        }
    }

    @Nested
    @DisplayName("TE-2: terminal states admit no transition, by anyone")
    inner class TerminalStates {

        @ParameterizedTest(name = "{0} task is final for {1}")
        @MethodSource("hive.domain.policy.TaskTransitionsTest#terminalCases")
        fun `TE-2 a terminal task cannot change status`(from: TaskStatus, to: TaskStatus, role: ActorRole) {
            val ctx = DomainFixtures.context(status = from, actor = role.userId)

            val result = TaskTransitions.attempt(ctx, to)

            assertTrue(result is TransitionResult.Illegal, "expected 409 Illegal, got $result")
        }

        @Test
        fun `TE-2 terminality is decided before authorization, so an outsider gets 409 and not 403`() {
            // If the order were reversed this would be Forbidden: an outsider has
            // no transition rights at all. 409 first is the documented order.
            val ctx = DomainFixtures.context(status = COMPLETED, actor = OUTSIDER)

            val result = TaskTransitions.attempt(ctx, CANCELED)

            assertTrue(result is TransitionResult.Illegal, "expected 409 Illegal, got $result")
        }
    }

    @Nested
    @DisplayName("TR-2: an unassigned Todo task cannot start")
    inner class UnassignedTodo {

        @ParameterizedTest(name = "{0} cannot start an unassigned Todo task")
        @EnumSource(ActorRole::class)
        fun `TR-2 Todo to In Progress with no assignee is a conflict for every actor`(role: ActorRole) {
            val ctx = DomainFixtures.context(status = TaskStatus.TODO, actor = role.userId, assignee = null)

            val result = TaskTransitions.attempt(ctx, IN_PROGRESS)

            assertTrue(result is TransitionResult.Illegal, "expected 409 Illegal, got $result")
        }

        @Test
        fun `TR-2 is a conflict rather than forbidden because no valid actor exists`() {
            val ctx = DomainFixtures.context(status = TaskStatus.TODO, actor = LEAD, assignee = null)

            val result = TaskTransitions.attempt(ctx, IN_PROGRESS)

            assertTrue(result is TransitionResult.Illegal)
            assertTrue(
                (result as TransitionResult.Illegal).reason.contains("unassigned", ignoreCase = true),
                "the reason should say why: ${result.reason}",
            )
        }

        @Test
        fun `an unassigned Todo task may still be canceled by the project owner`() {
            val ctx = DomainFixtures.context(status = TaskStatus.TODO, actor = OWNER, assignee = null)

            val result = TaskTransitions.attempt(ctx, CANCELED)

            assertTrue(result is TransitionResult.Allowed, "expected Allowed, got $result")
        }
    }

    @Nested
    @DisplayName("TR-3: a transition to the current status is a conflict")
    inner class NoOpTransition {

        @ParameterizedTest(name = "{0} -> {0} is a conflict")
        @EnumSource(TaskStatus::class)
        fun `TR-3 moving a task to the status it already holds is illegal`(status: TaskStatus) {
            // The project owner is used because for the non-terminal statuses they
            // are the actor most likely to hold a right here; the answer is 409
            // regardless.
            val ctx = DomainFixtures.context(status = status, actor = OWNER)

            val result = TaskTransitions.attempt(ctx, status)

            assertTrue(result is TransitionResult.Illegal, "expected 409 Illegal, got $result")
        }
    }

    @Nested
    @DisplayName("TR-1: the assignee, not merely a team member, moves work along")
    inner class AssigneeOnly {

        @Test
        fun `TR-1 a plain team member who is not the assignee cannot start the task`() {
            val ctx = DomainFixtures.context(status = TaskStatus.TODO, actor = MEMBER)

            val result = TaskTransitions.attempt(ctx, IN_PROGRESS)

            assertTrue(result is TransitionResult.Forbidden, "expected 403 Forbidden, got $result")
        }

        @Test
        fun `TR-1 the team lead cannot complete a task assigned to somebody else`() {
            val ctx = DomainFixtures.context(status = IN_PROGRESS, actor = LEAD)

            val result = TaskTransitions.attempt(ctx, COMPLETED)

            assertTrue(result is TransitionResult.Forbidden, "expected 403 Forbidden, got $result")
        }

        @Test
        fun `TR-1 the team lead may complete a task assigned to themselves`() {
            val ctx = DomainFixtures.context(status = IN_PROGRESS, actor = LEAD, assignee = LEAD)

            val result = TaskTransitions.attempt(ctx, COMPLETED)

            assertTrue(result is TransitionResult.Allowed, "expected Allowed, got $result")
        }

        @Test
        fun `the project owner cannot complete a task, only cancel it`() {
            val ctx = DomainFixtures.context(status = IN_PROGRESS, actor = OWNER)

            assertTrue(TaskTransitions.attempt(ctx, COMPLETED) is TransitionResult.Forbidden)
            assertTrue(TaskTransitions.attempt(ctx, CANCELED) is TransitionResult.Allowed)
        }
    }

    @Nested
    @DisplayName("the transition table matches spec.md exactly")
    inner class Table {

        @Test
        fun `there are exactly six legal transitions`() {
            assertEquals(SPEC_TABLE.keys, TaskTransitions.TABLE.keys)
        }

        @Test
        fun `no transition leaves a terminal state`() {
            assertTrue(TaskTransitions.TABLE.keys.none { it.first.isTerminal })
        }

        @Test
        fun `Completed and Canceled are the terminal statuses and nothing else is`() {
            assertEquals(
                setOf(COMPLETED, CANCELED),
                TaskStatus.entries.filter { it.isTerminal }.toSet(),
            )
        }
    }

    @Nested
    @DisplayName("allowedTransitions, which drives TaskPermissions in the API")
    inner class AllowedTransitions {

        @Test
        fun `the project owner of a Draft task may publish or cancel it`() {
            val ctx = DomainFixtures.context(status = DRAFT, actor = OWNER)

            assertEquals(setOf(TaskStatus.TODO, CANCELED), TaskTransitions.allowedTransitions(ctx))
        }

        @Test
        fun `the assignee of a Todo task may only start it`() {
            val ctx = DomainFixtures.context(status = TaskStatus.TODO, actor = ASSIGNEE)

            assertEquals(setOf(IN_PROGRESS), TaskTransitions.allowedTransitions(ctx))
        }

        @Test
        fun `a terminal task offers no transitions to anyone`() {
            ActorRole.entries.forEach { role ->
                assertEquals(
                    emptySet<TaskStatus>(),
                    TaskTransitions.allowedTransitions(DomainFixtures.context(COMPLETED, role.userId)),
                    "role $role",
                )
                assertEquals(
                    emptySet<TaskStatus>(),
                    TaskTransitions.allowedTransitions(DomainFixtures.context(CANCELED, role.userId)),
                    "role $role",
                )
            }
        }

        @Test
        fun `an outsider is offered no transitions at all`() {
            TaskStatus.entries.forEach { status ->
                assertEquals(
                    emptySet<TaskStatus>(),
                    TaskTransitions.allowedTransitions(DomainFixtures.context(status, OUTSIDER)),
                    "status $status",
                )
            }
        }
    }

    @Nested
    @DisplayName("roles are contextual, not global")
    inner class ContextualRoles {

        @Test
        fun `owning a different project confers nothing here`() {
            val otherProject = DomainFixtures.PROJECT.copy(name = ProjectName("Other"), projectOwner = OUTSIDER)
            val ctx = DomainFixtures.context(status = DRAFT, actor = OUTSIDER, project = otherProject)

            // The actor owns *this* project in this context, so they may publish.
            assertTrue(TaskTransitions.attempt(ctx, TaskStatus.TODO) is TransitionResult.Allowed)
            // But in the standard fixture, where they own nothing, they may not.
            assertTrue(
                TaskTransitions.attempt(
                    DomainFixtures.context(status = DRAFT, actor = OUTSIDER),
                    TaskStatus.TODO,
                ) is TransitionResult.Forbidden,
            )
        }

        @Test
        fun `the Allowed result returns a new instance and never mutates the original`() {
            val ctx = DomainFixtures.context(status = DRAFT, actor = OWNER)
            val before = ctx.task

            val result = TaskTransitions.attempt(ctx, TaskStatus.TODO) as TransitionResult.Allowed

            assertEquals(DRAFT, before.status, "the original task must be untouched")
            assertEquals(TaskStatus.TODO, result.task.status)
            assertSame(before.name, result.task.name)
        }
    }

    companion object {
        /**
         * The transition table transcribed by hand from `spec.md`, kept separate
         * from the production table on purpose: a test that reads the
         * implementation's own table would pass no matter what that table said.
         *
         * The value is the [ActorRole] that is permitted -- exactly one per pair.
         */
        private val SPEC_TABLE: Map<Pair<TaskStatus, TaskStatus>, ActorRole> =
            mapOf(
                (DRAFT to TaskStatus.TODO) to ActorRole.PROJECT_OWNER,
                (DRAFT to CANCELED) to ActorRole.PROJECT_OWNER,
                (TaskStatus.TODO to IN_PROGRESS) to ActorRole.ASSIGNEE_OF_TASK,
                (TaskStatus.TODO to CANCELED) to ActorRole.PROJECT_OWNER,
                (IN_PROGRESS to COMPLETED) to ActorRole.ASSIGNEE_OF_TASK,
                (IN_PROGRESS to CANCELED) to ActorRole.PROJECT_OWNER,
            )

        /**
         * The expected variant for one cell of the cross product, derived from
         * `docs/authorization.md` section 2 and its 409-before-403 ordering rule.
         */
        private fun expectedFor(from: TaskStatus, to: TaskStatus, role: ActorRole): Variant =
            when {
                from.isTerminal -> Variant.ILLEGAL // TE-2
                from == to -> Variant.ILLEGAL // TR-3
                (from to to) !in SPEC_TABLE -> Variant.ILLEGAL // not in the table
                SPEC_TABLE.getValue(from to to) == role -> Variant.ALLOWED
                else -> Variant.FORBIDDEN
            }

        private fun variantOf(result: TransitionResult): Variant =
            when (result) {
                is TransitionResult.Allowed -> Variant.ALLOWED
                is TransitionResult.Illegal -> Variant.ILLEGAL
                is TransitionResult.Forbidden -> Variant.FORBIDDEN
            }

        /** 5 x 5 x 5 = 125 cases. */
        @JvmStatic
        fun crossProduct(): Stream<Arguments> =
            TaskStatus.entries
                .flatMap { from ->
                    TaskStatus.entries.flatMap { to ->
                        ActorRole.entries.map { role ->
                            Arguments.of(from, to, role, expectedFor(from, to, role))
                        }
                    }
                }.stream()

        @JvmStatic
        fun terminalCases(): Stream<Arguments> =
            listOf(COMPLETED, CANCELED)
                .flatMap { from ->
                    TaskStatus.entries.flatMap { to ->
                        ActorRole.entries.map { role -> Arguments.of(from, to, role) }
                    }
                }.stream()
    }
}
