package hive.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("User and Project")
class UserAndProjectTest {

    private val ada = UserId(1)
    private val grace = UserId(2)

    @Nested
    @DisplayName("User")
    inner class Users {

        private fun user() = User(UserId(1), PersonName("Ada Lovelace"), EmailAddress("ada@example.com"))

        @Test
        fun `US-5 a user may change their own name`() {
            val renamed = user().rename(PersonName("Ada King"))

            assertEquals(PersonName("Ada King"), renamed.name)
            assertEquals(PersonName("Ada Lovelace"), user().name, "the original is untouched")
        }

        @Test
        fun `US-5 there is no operation to change a user's email`() {
            // Email is identity-provider owned; the entity offers no rename for it.
            assertEquals(EmailAddress("ada@example.com"), user().email)
        }

        @Test
        fun `US-2 two users with the same email in different casing compare as the same address`() {
            val lower = User(UserId(1), PersonName("Ada"), EmailAddress("ada@example.com"))
            val upper = User(UserId(1), PersonName("Ada"), EmailAddress("ADA@EXAMPLE.COM"))

            assertEquals(lower, upper, "uniqueness must be case-insensitive")
        }

        @Test
        fun `an unprovisioned user carries a null id rather than a sentinel`() {
            assertNull(User(null, PersonName("New"), EmailAddress("new@example.com")).id)
        }
    }

    @Nested
    @DisplayName("Project")
    inner class Projects {

        private fun project() = Project(ProjectId(1), ProjectName("Apiary"), TeamId(10), ada)

        @Test
        fun `isOwnedBy identifies the owner`() {
            assertTrue(project().isOwnedBy(ada))
            assertFalse(project().isOwnedBy(grace))
        }

        @Test
        fun `PR-5 rename returns a renamed project`() {
            assertEquals(ProjectName("Renamed"), project().rename(ProjectName("Renamed")).name)
            assertEquals(ProjectName("Apiary"), project().name, "the original is untouched")
        }

        @Test
        fun `PR-6 ownership transfer returns a project owned by the new owner`() {
            val transferred = project().transferOwnershipTo(grace)

            assertEquals(grace, transferred.projectOwner)
            assertTrue(transferred.isOwnedBy(grace))
            assertFalse(transferred.isOwnedBy(ada))
        }

        @Test
        fun `INV-3 a transfer replaces the single owner rather than adding one`() {
            assertEquals(grace, project().transferOwnershipTo(grace).projectOwner)
        }

        @Test
        fun `PR-9 the project's team is fixed at creation - there is no move operation`() {
            val transferred = project().transferOwnershipTo(grace)
            val renamed = project().rename(ProjectName("Renamed"))

            assertEquals(TeamId(10), transferred.teamId)
            assertEquals(TeamId(10), renamed.teamId)
        }

        @Test
        fun `projects compare structurally`() {
            assertEquals(project(), project())
            assertEquals(project().hashCode(), project().hashCode())
            assertNotEquals(project(), project().transferOwnershipTo(grace))
        }

        @Test
        fun `an unsaved project carries a null id`() {
            assertNull(Project(null, ProjectName("New"), TeamId(10), ada).id)
        }
    }
}
