package hive.domain.policy

import hive.domain.DomainFixtures
import hive.domain.DomainFixtures.ASSIGNEE
import hive.domain.DomainFixtures.LEAD
import hive.domain.DomainFixtures.MEMBER
import hive.domain.DomainFixtures.OUTSIDER
import hive.domain.DomainFixtures.OWNER
import hive.domain.DomainFixtures.PROJECT
import hive.domain.DomainFixtures.TEAM
import hive.domain.DomainFixtures.TEAM_ID
import hive.domain.error.AuthorizationException
import hive.domain.error.ConflictException
import hive.domain.model.Project
import hive.domain.model.ProjectId
import hive.domain.model.TaskStatus
import hive.domain.model.TaskStatus.CANCELED
import hive.domain.model.TaskStatus.COMPLETED
import hive.domain.model.TaskStatus.DRAFT
import hive.domain.model.TaskStatus.IN_PROGRESS
import hive.domain.model.Team
import hive.domain.model.TeamName
import hive.domain.model.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * One test per rule id in `docs/authorization.md`, positive and negative, named
 * after the rule so that traceability from document to test is mechanical:
 * searching the codebase for `AS-4` finds both the clause and its proof.
 */
@DisplayName("AuthorizationPolicy")
class AuthorizationPolicyTest {

    /** The five relationships an actor can hold to the fixture task. */
    enum class Role(val userId: UserId) {
        PROJECT_OWNER(OWNER),
        ASSIGNEE_OF_TASK(ASSIGNEE),
        TEAM_LEAD(LEAD),
        PLAIN_MEMBER(MEMBER),
        OUTSIDER(DomainFixtures.OUTSIDER),
    }

    // =====================================================================
    // Section 4 -- task visibility
    // =====================================================================

    @Nested
    @DisplayName("canViewTask (VIS-1..VIS-5)")
    inner class Visibility {

        @ParameterizedTest(name = "{1} can see a {0} task: {2}")
        @MethodSource("hive.domain.policy.AuthorizationPolicyTest#visibilityMatrix")
        fun `the full status x role visibility matrix`(status: TaskStatus, role: Role, expected: Boolean) {
            val ctx = DomainFixtures.context(status = status, actor = role.userId)

            assertEquals(expected, AuthorizationPolicy.canViewTask(ctx))
        }

        @ParameterizedTest(name = "assignee sees their own {0} task")
        @EnumSource(TaskStatus::class)
        fun `VIS-1 a user always sees tasks assigned to them`(status: TaskStatus) {
            val ctx = DomainFixtures.context(status = status, actor = ASSIGNEE)

            assertTrue(AuthorizationPolicy.canViewTask(ctx))
        }

        @ParameterizedTest(name = "project owner sees a {0} task")
        @EnumSource(TaskStatus::class)
        fun `VIS-2 the project owner sees every task in their project in every status`(status: TaskStatus) {
            val ctx = DomainFixtures.context(status = status, actor = OWNER)

            assertTrue(AuthorizationPolicy.canViewTask(ctx))
        }

        @Test
        fun `VIS-2 holds even though the project owner is not a member of the team`() {
            assertFalse(TEAM.hasMember(OWNER), "the fixture owner must be a non-member for this to mean anything")
            assertTrue(AuthorizationPolicy.canViewTask(DomainFixtures.context(DRAFT, OWNER)))
        }

        @Test
        fun `VIS-3 the team lead sees non-Draft tasks`() {
            listOf(TaskStatus.TODO, IN_PROGRESS, COMPLETED, CANCELED).forEach { status ->
                assertTrue(
                    AuthorizationPolicy.canViewTask(DomainFixtures.context(status, LEAD)),
                    "lead should see $status",
                )
            }
        }

        @Test
        fun `VIS-3 the team lead cannot see Draft tasks`() {
            assertFalse(AuthorizationPolicy.canViewTask(DomainFixtures.context(DRAFT, LEAD)))
        }

        @Test
        fun `VIS-4 a team member sees tasks that are neither Draft nor Canceled`() {
            listOf(TaskStatus.TODO, IN_PROGRESS, COMPLETED).forEach { status ->
                assertTrue(
                    AuthorizationPolicy.canViewTask(DomainFixtures.context(status, MEMBER)),
                    "member should see $status",
                )
            }
        }

        @Test
        fun `VIS-4 a team member cannot see Draft or Canceled tasks they do not hold`() {
            assertFalse(AuthorizationPolicy.canViewTask(DomainFixtures.context(DRAFT, MEMBER)))
            assertFalse(AuthorizationPolicy.canViewTask(DomainFixtures.context(CANCELED, MEMBER)))
        }

        @Test
        fun `VIS-5 the assignee of a canceled task still sees it, overriding the VIS-4 status filter`() {
            val theirs = DomainFixtures.context(CANCELED, ASSIGNEE)
            val somebodyElses = DomainFixtures.context(CANCELED, MEMBER)

            assertTrue(AuthorizationPolicy.canViewTask(theirs), "VIS-5: a canceled assignment stays visible")
            assertFalse(AuthorizationPolicy.canViewTask(somebodyElses), "but only to its assignee")
        }

        @ParameterizedTest(name = "an outsider cannot see a {0} task")
        @EnumSource(TaskStatus::class)
        fun `a user matching no rule sees nothing, which the API reports as 404`(status: TaskStatus) {
            assertFalse(AuthorizationPolicy.canViewTask(DomainFixtures.context(status, OUTSIDER)))
        }

        @Test
        fun `an unassigned task is not visible to everyone merely because assignee is null`() {
            val ctx = DomainFixtures.context(status = TaskStatus.TODO, actor = OUTSIDER, assignee = null)

            assertFalse(AuthorizationPolicy.canViewTask(ctx))
        }
    }

    // =====================================================================
    // Section 6 -- comments
    // =====================================================================

    @Nested
    @DisplayName("canCommentOnTask (CM-1..CM-3)")
    inner class Comments {

        @ParameterizedTest(name = "{1} on a {0} task: comment right equals view right")
        @MethodSource("hive.domain.policy.AuthorizationPolicyTest#visibilityMatrix")
        fun `CM-1 a user may comment if and only if they can see the task`(
            status: TaskStatus,
            role: Role,
            expected: Boolean,
        ) {
            val ctx = DomainFixtures.context(status = status, actor = role.userId)

            assertEquals(expected, AuthorizationPolicy.canCommentOnTask(ctx))
            assertEquals(AuthorizationPolicy.canViewTask(ctx), AuthorizationPolicy.canCommentOnTask(ctx))
        }

        @Test
        fun `CM-3 and TE-4 comments may still be added to terminal tasks`() {
            assertTrue(AuthorizationPolicy.canCommentOnTask(DomainFixtures.context(COMPLETED, OWNER)))
            assertTrue(AuthorizationPolicy.canCommentOnTask(DomainFixtures.context(COMPLETED, ASSIGNEE)))
            assertTrue(AuthorizationPolicy.canCommentOnTask(DomainFixtures.context(CANCELED, ASSIGNEE)))
        }

        @Test
        fun `CM-2 an outsider may not read or write comments on a task they cannot see`() {
            assertFalse(AuthorizationPolicy.canCommentOnTask(DomainFixtures.context(TaskStatus.TODO, OUTSIDER)))
        }
    }

    // =====================================================================
    // Section 5 -- task operations
    // =====================================================================

    @Nested
    @DisplayName("checkCreateTask (TK-1)")
    inner class CreateTask {

        @Test
        fun `TK-1 the project owner may create tasks in their project`() {
            assertDoesNotThrow { AuthorizationPolicy.checkCreateTask(PROJECT, OWNER) }
        }

        @Test
        fun `TK-1 the team lead may not create tasks`() {
            assertThrows<AuthorizationException> { AuthorizationPolicy.checkCreateTask(PROJECT, LEAD) }
        }

        @Test
        fun `TK-1 a team member may not create tasks`() {
            assertThrows<AuthorizationException> { AuthorizationPolicy.checkCreateTask(PROJECT, MEMBER) }
        }

        @Test
        fun `TK-1 an outsider may not create tasks`() {
            assertThrows<AuthorizationException> { AuthorizationPolicy.checkCreateTask(PROJECT, OUTSIDER) }
        }
    }

    @Nested
    @DisplayName("checkEditTaskFields (TK-3, TK-4, TE-1)")
    inner class EditTaskFields {

        @Test
        fun `TK-3 the project owner may edit the name and description`() {
            assertDoesNotThrow { AuthorizationPolicy.checkEditTaskFields(DomainFixtures.context(DRAFT, OWNER)) }
            assertDoesNotThrow {
                AuthorizationPolicy.checkEditTaskFields(DomainFixtures.context(IN_PROGRESS, OWNER))
            }
        }

        @Test
        fun `TK-3 the team lead may not edit task fields`() {
            assertThrows<AuthorizationException> {
                AuthorizationPolicy.checkEditTaskFields(DomainFixtures.context(TaskStatus.TODO, LEAD))
            }
        }

        @Test
        fun `TK-3 the assignee may not edit task fields`() {
            assertThrows<AuthorizationException> {
                AuthorizationPolicy.checkEditTaskFields(DomainFixtures.context(IN_PROGRESS, ASSIGNEE))
            }
        }

        @Test
        fun `TE-1 and TK-4 a Completed task cannot be edited even by the project owner`() {
            assertThrows<ConflictException> {
                AuthorizationPolicy.checkEditTaskFields(DomainFixtures.context(COMPLETED, OWNER))
            }
        }

        @Test
        fun `TE-1 and TK-4 a Canceled task cannot be edited even by the project owner`() {
            assertThrows<ConflictException> {
                AuthorizationPolicy.checkEditTaskFields(DomainFixtures.context(CANCELED, OWNER))
            }
        }

        @Test
        fun `the terminal conflict is decided before the role, so a non-owner gets 409 and not 403`() {
            assertThrows<ConflictException> {
                AuthorizationPolicy.checkEditTaskFields(DomainFixtures.context(COMPLETED, OUTSIDER))
            }
        }
    }

    @Nested
    @DisplayName("checkAssign (AS-1..AS-7, TE-3)")
    inner class Assignment {

        @Test
        fun `AS-1 only the team lead may assign`() {
            assertDoesNotThrow {
                AuthorizationPolicy.checkAssign(DomainFixtures.context(TaskStatus.TODO, LEAD), MEMBER)
            }
        }

        @Test
        fun `AS-1 the project owner may not assign, even though they own the work`() {
            assertThrows<AuthorizationException> {
                AuthorizationPolicy.checkAssign(DomainFixtures.context(TaskStatus.TODO, OWNER), MEMBER)
            }
        }

        @Test
        fun `AS-1 a plain team member may not assign`() {
            assertThrows<AuthorizationException> {
                AuthorizationPolicy.checkAssign(DomainFixtures.context(TaskStatus.TODO, MEMBER), MEMBER)
            }
        }

        @Test
        fun `AS-1 an outsider may not assign`() {
            assertThrows<AuthorizationException> {
                AuthorizationPolicy.checkAssign(DomainFixtures.context(TaskStatus.TODO, OUTSIDER), MEMBER)
            }
        }

        @Test
        fun `AS-2 the assignee must be a member of the project's team`() {
            assertThrows<AuthorizationException> {
                AuthorizationPolicy.checkAssign(DomainFixtures.context(TaskStatus.TODO, LEAD), OUTSIDER)
            }
        }

        @Test
        fun `AS-3 a Draft task cannot be assigned`() {
            assertThrows<ConflictException> {
                AuthorizationPolicy.checkAssign(DomainFixtures.context(DRAFT, LEAD), MEMBER)
            }
        }

        @Test
        fun `AS-3 outranks AS-1, so a non-lead assigning a Draft task gets 409 and not 403`() {
            assertThrows<ConflictException> {
                AuthorizationPolicy.checkAssign(DomainFixtures.context(DRAFT, OUTSIDER), MEMBER)
            }
        }

        @Test
        fun `AS-4 project owner may never be assigned a task in their own project`() {
            assertThrows<AuthorizationException> {
                AuthorizationPolicy.checkAssign(DomainFixtures.context(TaskStatus.TODO, LEAD), OWNER)
            }
        }

        @Test
        fun `AS-4 holds even when the project owner is also a member of the team`() {
            val teamIncludingOwner = TEAM.addMember(OWNER)
            val ctx = DomainFixtures.context(TaskStatus.TODO, LEAD, team = teamIncludingOwner)

            assertTrue(teamIncludingOwner.hasMember(OWNER))
            assertThrows<AuthorizationException> { AuthorizationPolicy.checkAssign(ctx, OWNER) }
        }

        @Test
        fun `AS-4 holds even when the project owner is the team lead assigning to themselves`() {
            val ownerLedTeam = Team(TEAM_ID, TeamName("Owner-led"), OWNER, setOf(MEMBER))
            val ctx = DomainFixtures.context(TaskStatus.TODO, OWNER, team = ownerLedTeam)

            assertThrows<AuthorizationException> { AuthorizationPolicy.checkAssign(ctx, OWNER) }
        }

        @Test
        fun `AS-4 does not stop the owner of some other project being assigned here`() {
            val otherProjectOwner = MEMBER // owns nothing in this fixture
            assertDoesNotThrow {
                AuthorizationPolicy.checkAssign(DomainFixtures.context(TaskStatus.TODO, LEAD), otherProjectOwner)
            }
        }

        @Test
        fun `AS-5 a team lead may assign a task to themselves`() {
            assertDoesNotThrow {
                AuthorizationPolicy.checkAssign(DomainFixtures.context(TaskStatus.TODO, LEAD), LEAD)
            }
        }

        @Test
        fun `AS-5 works because INV-1 guarantees the lead passes the AS-2 membership check`() {
            val freshTeam = Team(TEAM_ID, TeamName("Fresh"), LEAD) // no explicit members at all
            val ctx = DomainFixtures.context(TaskStatus.TODO, LEAD, team = freshTeam)

            assertTrue(freshTeam.hasMember(LEAD))
            assertDoesNotThrow { AuthorizationPolicy.checkAssign(ctx, LEAD) }
        }

        @Test
        fun `AS-6 an In Progress task may be reassigned`() {
            assertDoesNotThrow {
                AuthorizationPolicy.checkAssign(DomainFixtures.context(IN_PROGRESS, LEAD), MEMBER)
            }
        }

        @Test
        fun `AS-7 a Todo task may be unassigned`() {
            assertDoesNotThrow {
                AuthorizationPolicy.checkAssign(DomainFixtures.context(TaskStatus.TODO, LEAD), null)
            }
        }

        @Test
        fun `AS-7 an In Progress task may not be unassigned, because it could never complete`() {
            assertThrows<ConflictException> {
                AuthorizationPolicy.checkAssign(DomainFixtures.context(IN_PROGRESS, LEAD), null)
            }
        }

        @Test
        fun `TE-3 a Completed task cannot be reassigned`() {
            assertThrows<ConflictException> {
                AuthorizationPolicy.checkAssign(DomainFixtures.context(COMPLETED, LEAD), MEMBER)
            }
        }

        @Test
        fun `TE-3 a Canceled task cannot be reassigned`() {
            assertThrows<ConflictException> {
                AuthorizationPolicy.checkAssign(DomainFixtures.context(CANCELED, LEAD), MEMBER)
            }
        }

        @Test
        fun `TE-3 outranks AS-1, so a non-lead reassigning a terminal task gets 409 and not 403`() {
            assertThrows<ConflictException> {
                AuthorizationPolicy.checkAssign(DomainFixtures.context(COMPLETED, OUTSIDER), MEMBER)
            }
        }

        @Test
        fun `TE-3 outranks AS-4 as well`() {
            assertThrows<ConflictException> {
                AuthorizationPolicy.checkAssign(DomainFixtures.context(CANCELED, LEAD), OWNER)
            }
        }
    }

    // =====================================================================
    // Section 8 -- project operations
    // =====================================================================

    @Nested
    @DisplayName("project operations (PR-2, PR-3, PR-5..PR-8)")
    inner class Projects {

        @Test
        fun `PR-3 the project owner can view the project`() {
            assertTrue(AuthorizationPolicy.canViewProject(PROJECT, TEAM, OWNER))
        }

        @Test
        fun `PR-3 the team lead and team members can view the project`() {
            assertTrue(AuthorizationPolicy.canViewProject(PROJECT, TEAM, LEAD))
            assertTrue(AuthorizationPolicy.canViewProject(PROJECT, TEAM, MEMBER))
            assertTrue(AuthorizationPolicy.canViewProject(PROJECT, TEAM, ASSIGNEE))
        }

        @Test
        fun `PR-3 an outsider cannot view the project, which the API reports as 404`() {
            assertFalse(AuthorizationPolicy.canViewProject(PROJECT, TEAM, OUTSIDER))
        }

        @Test
        fun `PR-2 a member of the target team may create a project on it`() {
            assertDoesNotThrow { AuthorizationPolicy.checkCreateProject(TEAM, MEMBER) }
            assertDoesNotThrow { AuthorizationPolicy.checkCreateProject(TEAM, LEAD) }
        }

        @Test
        fun `PR-2 a stranger may not attach a project to somebody else's team`() {
            assertThrows<AuthorizationException> { AuthorizationPolicy.checkCreateProject(TEAM, OUTSIDER) }
        }

        @Test
        fun `PR-5 only the project owner may rename the project`() {
            assertDoesNotThrow { AuthorizationPolicy.checkRenameProject(PROJECT, OWNER) }
            assertThrows<AuthorizationException> { AuthorizationPolicy.checkRenameProject(PROJECT, LEAD) }
            assertThrows<AuthorizationException> { AuthorizationPolicy.checkRenameProject(PROJECT, MEMBER) }
            assertThrows<AuthorizationException> { AuthorizationPolicy.checkRenameProject(PROJECT, OUTSIDER) }
        }

        @Test
        fun `PR-6 only the current owner may transfer the project`() {
            assertDoesNotThrow {
                AuthorizationPolicy.checkTransferProjectOwner(PROJECT, OWNER, OUTSIDER, emptyList())
            }
            assertThrows<AuthorizationException> {
                AuthorizationPolicy.checkTransferProjectOwner(PROJECT, LEAD, OUTSIDER, emptyList())
            }
            assertThrows<AuthorizationException> {
                AuthorizationPolicy.checkTransferProjectOwner(PROJECT, OUTSIDER, MEMBER, emptyList())
            }
        }

        @Test
        fun `PR-7 the new owner need not be a member of the project's team`() {
            assertFalse(TEAM.hasMember(OUTSIDER))
            assertDoesNotThrow {
                AuthorizationPolicy.checkTransferProjectOwner(PROJECT, OWNER, OUTSIDER, emptyList())
            }
        }

        @Test
        fun `PR-8 transferring to a user who holds live tasks in this project is a conflict`() {
            val live = listOf(DomainFixtures.task(TaskStatus.TODO, assignee = MEMBER))

            val thrown =
                assertThrows<ConflictException> {
                    AuthorizationPolicy.checkTransferProjectOwner(PROJECT, OWNER, MEMBER, live)
                }

            assertTrue(
                thrown.message!!.contains("Requeen hive 4"),
                "the conflict must name the tasks: ${thrown.message}",
            )
        }

        @Test
        fun `PR-8 ignores tasks that are not live`() {
            val finished = listOf(DomainFixtures.task(COMPLETED, assignee = MEMBER))

            assertDoesNotThrow {
                AuthorizationPolicy.checkTransferProjectOwner(PROJECT, OWNER, MEMBER, finished)
            }
        }

        @Test
        fun `PR-8 ignores live tasks belonging to a different project`() {
            val elsewhere =
                listOf(DomainFixtures.task(IN_PROGRESS, assignee = MEMBER, projectId = ProjectId(999)))

            assertDoesNotThrow {
                AuthorizationPolicy.checkTransferProjectOwner(PROJECT, OWNER, MEMBER, elsewhere)
            }
        }

        @Test
        fun `PR-8 outranks PR-6, so the conflict is reported before the role check`() {
            val live = listOf(DomainFixtures.task(IN_PROGRESS, assignee = MEMBER))

            assertThrows<ConflictException> {
                AuthorizationPolicy.checkTransferProjectOwner(PROJECT, OUTSIDER, MEMBER, live)
            }
        }

        @Test
        fun `PR-8 exists to protect AS-4 - the transferred-to user would become an owner-assignee`() {
            // Demonstrate the invariant PR-8 protects: after the transfer, the new
            // owner holding a live task would be exactly what AS-4 forbids.
            val transferred: Project = PROJECT.transferOwnershipTo(MEMBER)
            val ctx = DomainFixtures.context(TaskStatus.TODO, LEAD, project = transferred)

            assertThrows<AuthorizationException> { AuthorizationPolicy.checkAssign(ctx, MEMBER) }
        }
    }

    // =====================================================================
    // Section 7 -- team operations
    // =====================================================================

    @Nested
    @DisplayName("team operations (TM-2, TM-3, TM-5..TM-7, TM-9)")
    inner class Teams {

        @Test
        fun `TM-2 members and the lead may view the team`() {
            assertTrue(AuthorizationPolicy.canViewTeam(TEAM, LEAD, ownsAProjectOfTeam = false))
            assertTrue(AuthorizationPolicy.canViewTeam(TEAM, MEMBER, ownsAProjectOfTeam = false))
            assertTrue(AuthorizationPolicy.canViewTeam(TEAM, ASSIGNEE, ownsAProjectOfTeam = false))
        }

        @Test
        fun `TM-2 a stranger may not view the team, which the API reports as 404`() {
            assertFalse(AuthorizationPolicy.canViewTeam(TEAM, OUTSIDER, ownsAProjectOfTeam = false))
        }

        @Test
        fun `TM-3 the owner of a project of this team may view it despite not being a member`() {
            assertFalse(TEAM.hasMember(OWNER))
            assertTrue(AuthorizationPolicy.canViewTeam(TEAM, OWNER, ownsAProjectOfTeam = true))
        }

        @Test
        fun `TM-3 owning a project of some other team confers nothing`() {
            assertFalse(AuthorizationPolicy.canViewTeam(TEAM, OWNER, ownsAProjectOfTeam = false))
        }

        @Test
        fun `TM-5 only the team lead may rename the team`() {
            assertDoesNotThrow { AuthorizationPolicy.checkRenameTeam(TEAM, LEAD) }
            assertThrows<AuthorizationException> { AuthorizationPolicy.checkRenameTeam(TEAM, MEMBER) }
            assertThrows<AuthorizationException> { AuthorizationPolicy.checkRenameTeam(TEAM, OWNER) }
            assertThrows<AuthorizationException> { AuthorizationPolicy.checkRenameTeam(TEAM, OUTSIDER) }
        }

        @Test
        fun `TM-6 only the team lead may add members`() {
            assertDoesNotThrow { AuthorizationPolicy.checkAddMember(TEAM, LEAD, OUTSIDER) }
            assertThrows<AuthorizationException> { AuthorizationPolicy.checkAddMember(TEAM, MEMBER, OUTSIDER) }
            assertThrows<AuthorizationException> { AuthorizationPolicy.checkAddMember(TEAM, OWNER, OUTSIDER) }
            assertThrows<AuthorizationException> { AuthorizationPolicy.checkAddMember(TEAM, OUTSIDER, OUTSIDER) }
        }

        @Test
        fun `TM-7 only the team lead may remove members`() {
            assertDoesNotThrow { AuthorizationPolicy.checkRemoveMember(TEAM, LEAD, MEMBER) }
            assertThrows<AuthorizationException> { AuthorizationPolicy.checkRemoveMember(TEAM, MEMBER, ASSIGNEE) }
            assertThrows<AuthorizationException> { AuthorizationPolicy.checkRemoveMember(TEAM, OUTSIDER, MEMBER) }
        }

        @Test
        fun `TM-7 the current lead can never be removed, not even by themselves`() {
            assertThrows<ConflictException> { AuthorizationPolicy.checkRemoveMember(TEAM, LEAD, LEAD) }
        }

        @Test
        fun `TM-7 the lead-removal conflict outranks the role check`() {
            assertThrows<ConflictException> { AuthorizationPolicy.checkRemoveMember(TEAM, OUTSIDER, LEAD) }
        }

        @Test
        fun `TM-9 only the current lead may transfer the team`() {
            assertDoesNotThrow { AuthorizationPolicy.checkTransferLead(TEAM, LEAD, MEMBER) }
            assertThrows<AuthorizationException> { AuthorizationPolicy.checkTransferLead(TEAM, MEMBER, MEMBER) }
            assertThrows<AuthorizationException> { AuthorizationPolicy.checkTransferLead(TEAM, OWNER, MEMBER) }
            assertThrows<AuthorizationException> { AuthorizationPolicy.checkTransferLead(TEAM, OUTSIDER, MEMBER) }
        }

        @Test
        fun `TM-9 and TM-10 the new lead need not already be a member`() {
            assertFalse(TEAM.hasMember(OUTSIDER))
            assertDoesNotThrow { AuthorizationPolicy.checkTransferLead(TEAM, LEAD, OUTSIDER) }
            assertTrue(TEAM.transferLeadTo(OUTSIDER).hasMember(OUTSIDER))
        }
    }

    // =====================================================================
    // Invariants that the policy depends on
    // =====================================================================

    @Nested
    @DisplayName("role invariants (INV-1..INV-4)")
    inner class Invariants {

        @Test
        fun `INV-1 a team lead is always a team member, so TaskContext reports both`() {
            val ctx = DomainFixtures.context(TaskStatus.TODO, LEAD)

            assertTrue(ctx.isTeamLead)
            assertTrue(ctx.isTeamMember)
        }

        @Test
        fun `INV-2 a project owner is not thereby a team member`() {
            val ctx = DomainFixtures.context(TaskStatus.TODO, OWNER)

            assertTrue(ctx.isProjectOwner)
            assertFalse(ctx.isTeamMember)
        }

        @Test
        fun `INV-4 one user can be both a project owner and a team lead`() {
            val ownerLedTeam = TEAM.transferLeadTo(OWNER)
            val ctx = DomainFixtures.context(TaskStatus.TODO, OWNER, team = ownerLedTeam)

            assertTrue(ctx.isProjectOwner)
            assertTrue(ctx.isTeamLead)
            assertTrue(ctx.isTeamMember)
        }

        @Test
        fun `TaskContext derives isAssignee from the task, and an unassigned task matches nobody`() {
            val unassigned = DomainFixtures.context(TaskStatus.TODO, MEMBER, assignee = null)

            assertFalse(unassigned.isAssignee)
        }
    }

    companion object {
        /**
         * The visibility table from `docs/authorization.md` section 4,
         * transcribed by hand rather than computed from the policy.
         */
        private fun expectedVisibility(status: TaskStatus, role: Role): Boolean =
            when (role) {
                Role.ASSIGNEE_OF_TASK -> true // VIS-1 / VIS-5, every status
                Role.PROJECT_OWNER -> true // VIS-2, every status
                Role.TEAM_LEAD -> status != DRAFT // VIS-3
                Role.PLAIN_MEMBER -> status != DRAFT && status != CANCELED // VIS-4
                Role.OUTSIDER -> false
            }

        @JvmStatic
        fun visibilityMatrix(): Stream<Arguments> =
            TaskStatus.entries
                .flatMap { status ->
                    Role.entries.map { role ->
                        Arguments.of(status, role, expectedVisibility(status, role))
                    }
                }.stream()
    }
}
