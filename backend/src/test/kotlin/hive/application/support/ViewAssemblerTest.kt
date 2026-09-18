package hive.application.support

import hive.application.AppFixtures.ASSIGNEE
import hive.application.AppFixtures.LEAD
import hive.application.AppFixtures.LEAD_ID
import hive.application.AppFixtures.MEMBER
import hive.application.AppFixtures.OWNER_ID
import hive.application.AppFixtures.PROJECT
import hive.application.AppFixtures.TEAM
import hive.application.AppFixtures.context
import hive.application.Harness
import hive.domain.error.NotFoundException
import hive.domain.model.EmailAddress
import hive.domain.model.PersonName
import hive.domain.model.TaskStatus.CANCELED
import hive.domain.model.TaskStatus.TODO
import hive.domain.model.User
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The assembler is exercised end to end by every service test; this file covers
 * the two things those cannot reach -- a directory that answers with a row the
 * rest of the system could not have produced, and the tiebreak in the member
 * ordering.
 */
@DisplayName("ViewAssembler")
class ViewAssemblerTest {

    private val harness = Harness()

    @Test
    fun `a directory row with no id is treated as a missing user, not as a hole in the payload`() {
        every { harness.userRepository.findAllById(any()) } returns
            listOf(LEAD, ASSIGNEE, MEMBER.copy(id = null))

        assertThatThrownBy { harness.views.teamView(TEAM, LEAD_ID) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessageContaining("User")
    }

    @Test
    fun `members with the same name are ordered by their unique address`() {
        val twins = listOf(
            User(LEAD_ID, PersonName("Sam Twin"), EmailAddress("zoe@hive.test")),
            ASSIGNEE.copy(name = PersonName("sam twin"), email = EmailAddress("abe@hive.test")),
            MEMBER.copy(name = PersonName("Sam Twin")),
        )
        every { harness.userRepository.findAllById(any()) } returns twins

        val view = harness.views.teamView(TEAM, LEAD_ID)

        assertThat(view.members.map { it.email.normalized })
            .containsExactly("abe@hive.test", "mira@hive.test", "zoe@hive.test")
    }

    @Test
    fun `permissions come from the policy, including a Todo task nobody can start yet (TR-2)`() {
        val permissions = harness.views.permissions(context(TODO, actor = OWNER_ID, assignee = null))

        assertThat(permissions.canEdit).isTrue()
        assertThat(permissions.canAssign).isFalse()
        assertThat(permissions.canComment).isTrue()
        assertThat(permissions.allowedTransitions).containsExactly(CANCELED)
    }

    @Test
    fun `projectView resolves the owner even though they are not a member of the team (INV-2)`() {
        val view = harness.views.projectView(PROJECT, TEAM, OWNER_ID)

        assertThat(view.owner.id).isEqualTo(OWNER_ID)
        assertThat(view.team.members.map { it.id }).doesNotContain(OWNER_ID)
    }
}
