package hive.domain.model

import hive.domain.error.ConflictException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Team, and the one invariant the whole role model rests on.
 *
 * INV-1 -- "the team lead is a team member" -- is not merely checked here, it is
 * shown to be **unviolatable**: every route into a Team instance is exercised
 * and none of them can produce a lead who is not a member.
 */
@DisplayName("Team")
class TeamTest {

    private val lead = UserId(1)
    private val alice = UserId(2)
    private val bob = UserId(3)
    private val stranger = UserId(4)

    private fun team(
        teamLead: UserId = lead,
        members: Set<UserId> = setOf(alice, bob),
    ) = Team(TeamId(100), TeamName("Hive Core"), teamLead, members)

    @Nested
    @DisplayName("INV-1: the lead is always a member, by construction")
    inner class Inv1 {

        @Test
        fun `INV-1 the constructor adds the lead when the member set omits them`() {
            val subject = Team(TeamId(100), TeamName("Hive Core"), lead, setOf(alice))

            assertTrue(subject.hasMember(lead))
            assertEquals(setOf(lead, alice), subject.memberIds)
        }

        @Test
        fun `INV-1 a team created with no members at all still has its lead`() {
            val subject = Team(null, TeamName("Fresh"), lead)

            assertEquals(setOf(lead), subject.memberIds)
        }

        @Test
        fun `INV-1 survives copy, even a copy that tries to drop the lead`() {
            val subject = team().copy(memberIds = setOf(alice))

            assertTrue(subject.hasMember(lead), "copy must not be a way around INV-1")
        }

        @Test
        fun `INV-1 survives a copy that changes the lead`() {
            val subject = team().copy(teamLead = stranger)

            assertTrue(subject.hasMember(stranger))
        }

        @Test
        fun `INV-1 holds after transferLeadTo a user who was not a member`() {
            val subject = team().transferLeadTo(stranger)

            assertEquals(stranger, subject.teamLead)
            assertTrue(subject.hasMember(stranger), "the incoming lead must become a member (TM-10)")
        }

        @Test
        fun `INV-1 holds after attempting removeMember on the lead`() {
            val subject = team()

            assertThrows<ConflictException> { subject.removeMember(lead) }
            assertTrue(subject.hasMember(lead), "the team must be unchanged after the refusal")
        }

        @Test
        fun `INV-1 holds after removing every other member`() {
            val subject = team().removeMember(alice).removeMember(bob)

            assertEquals(setOf(lead), subject.memberIds)
        }

        @Test
        fun `INV-1 holds after a full chain of operations`() {
            val subject =
                team()
                    .addMember(stranger)
                    .transferLeadTo(alice)
                    .removeMember(lead)
                    .rename(TeamName("Renamed"))
                    .removeMember(stranger)

            assertEquals(alice, subject.teamLead)
            assertTrue(subject.hasMember(alice))
        }
    }

    @Nested
    @DisplayName("membership")
    inner class Membership {

        @Test
        fun `TM-6 addMember adds a user`() {
            val subject = team().addMember(stranger)

            assertTrue(subject.hasMember(stranger))
            assertEquals(setOf(lead, alice, bob, stranger), subject.memberIds)
        }

        @Test
        fun `TM-6 addMember is idempotent`() {
            assertEquals(team().memberIds, team().addMember(alice).memberIds)
        }

        @Test
        fun `TM-7 removeMember removes a plain member`() {
            val subject = team().removeMember(alice)

            assertFalse(subject.hasMember(alice))
            assertTrue(subject.hasMember(bob))
        }

        @Test
        fun `TM-7 removing the lead is a conflict with an explanatory message`() {
            val thrown = assertThrows<ConflictException> { team().removeMember(lead) }

            assertTrue(thrown.message!!.contains("lead", ignoreCase = true))
            assertTrue(thrown.message!!.contains("Transfer", ignoreCase = true))
        }

        @Test
        fun `removing a non-member is a harmless no-op`() {
            assertEquals(team().memberIds, team().removeMember(stranger).memberIds)
        }

        @Test
        fun `operations return new instances and never mutate the original`() {
            val original = team()
            val originalMembers = original.memberIds

            original.addMember(stranger)
            original.removeMember(alice)
            original.transferLeadTo(bob)

            assertEquals(originalMembers, original.memberIds)
            assertEquals(lead, original.teamLead)
        }
    }

    @Nested
    @DisplayName("TM-9 / TM-10: lead transfer")
    inner class LeadTransfer {

        @Test
        fun `TM-10 the outgoing lead remains a member`() {
            val subject = team().transferLeadTo(alice)

            assertEquals(alice, subject.teamLead)
            assertTrue(subject.hasMember(lead), "the outgoing lead stays on the team")
        }

        @Test
        fun `TM-10 transferring to an existing member keeps the membership set intact`() {
            val subject = team().transferLeadTo(alice)

            assertEquals(setOf(lead, alice, bob), subject.memberIds)
        }

        @Test
        fun `INV-3 a team has exactly one lead, so a transfer replaces rather than adds`() {
            val subject = team().transferLeadTo(alice)

            assertFalse(subject.isLedBy(lead))
            assertTrue(subject.isLedBy(alice))
        }

        @Test
        fun `after a transfer the former lead can be removed`() {
            val subject = team().transferLeadTo(alice).removeMember(lead)

            assertFalse(subject.hasMember(lead))
        }
    }

    @Nested
    @DisplayName("identity and equality")
    inner class Identity {

        @Test
        fun `TM-5 rename returns a renamed team`() {
            assertEquals(TeamName("New"), team().rename(TeamName("New")).name)
        }

        @Test
        fun `teams compare structurally, including their membership`() {
            assertEquals(team(), team())
            assertEquals(team().hashCode(), team().hashCode())
            assertNotEquals(team(), team().addMember(stranger))
            assertNotEquals(team(), team().rename(TeamName("Other")))
            assertFalse(team().equals(null))
            assertFalse(team().equals("not a team"))
        }

        @Test
        fun `two teams differing only in an implied lead membership are equal`() {
            // One lists the lead explicitly, the other relies on INV-1 to add them.
            val explicit = Team(TeamId(100), TeamName("Hive Core"), lead, setOf(lead, alice, bob))
            val implied = Team(TeamId(100), TeamName("Hive Core"), lead, setOf(alice, bob))

            assertEquals(explicit, implied)
            assertEquals(explicit.hashCode(), implied.hashCode())
        }

        @Test
        fun `an unsaved team carries a null id`() {
            assertEquals(null, Team(null, TeamName("Fresh"), lead).id)
        }

        @Test
        fun `toString names the team without leaking anything odd`() {
            assertTrue(team().toString().contains("Hive Core"))
        }
    }
}
