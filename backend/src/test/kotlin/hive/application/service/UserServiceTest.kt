package hive.application.service

import hive.application.AppFixtures.ASSIGNEE
import hive.application.AppFixtures.ASSIGNEE_ID
import hive.application.AppFixtures.EVERYONE
import hive.application.AppFixtures.GHOST_ID
import hive.application.AppFixtures.MEMBER
import hive.application.AppFixtures.OWNER
import hive.application.AppFixtures.OWNER_ID
import hive.application.AppFixtures.pageOf
import hive.application.Harness
import hive.application.usecase.PrincipalClaims
import hive.application.usecase.RenameUserCommand
import hive.domain.error.ConflictException
import hive.domain.error.NotFoundException
import hive.domain.error.ValidationException
import hive.domain.model.EmailAddress
import hive.domain.model.PageRequest
import hive.domain.model.User
import hive.domain.model.UserId
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("UserService")
class UserServiceTest {

    private val harness = Harness()
    private val service = UserService(harness.userRepository)

    @Nested
    @DisplayName("provisionFromPrincipal (US-3)")
    inner class Provisioning {

        @Test
        fun `returns the existing user when the address is already known`() {
            every { harness.userRepository.findByEmail(EmailAddress("olive@hive.test")) } returns OWNER

            val result = service.provisionFromPrincipal(
                PrincipalClaims("sub-1", "olive@hive.test", "Someone Else"),
            )

            assertThat(result).isEqualTo(OWNER)
            verify(exactly = 0) { harness.userRepository.save(any()) }
        }

        @Test
        fun `matches case-insensitively, because EmailAddress equality does`() {
            every { harness.userRepository.findByEmail(any()) } returns OWNER

            val result = service.provisionFromPrincipal(PrincipalClaims("sub-1", "OLIVE@HIVE.TEST", null))

            assertThat(result).isEqualTo(OWNER)
        }

        @Test
        fun `creates the user on first sight, taking the name from the claims`() {
            every { harness.userRepository.findByEmail(any()) } returns null
            val saved = slot<User>()
            every { harness.userRepository.save(capture(saved)) } answers {
                firstArg<User>().copy(id = UserId(77))
            }

            val result = service.provisionFromPrincipal(
                PrincipalClaims("sub-9", "nova@hive.test", "  Nova Newcomer  "),
            )

            assertThat(saved.captured.id).isNull()
            assertThat(saved.captured.name.value).isEqualTo("Nova Newcomer")
            assertThat(saved.captured.email.value).isEqualTo("nova@hive.test")
            assertThat(result.id).isEqualTo(UserId(77))
        }

        @Test
        fun `falls back to the address local part when the provider sends no name`() {
            every { harness.userRepository.findByEmail(any()) } returns null
            harness.echoUserSaves()

            val result = service.provisionFromPrincipal(PrincipalClaims("sub-9", "nova@hive.test", null))

            assertThat(result.name.value).isEqualTo("nova")
        }

        @Test
        fun `falls back to the local part when the name claim is blank`() {
            every { harness.userRepository.findByEmail(any()) } returns null
            harness.echoUserSaves()

            val result = service.provisionFromPrincipal(PrincipalClaims("sub-9", "nova@hive.test", "   "))

            assertThat(result.name.value).isEqualTo("nova")
        }

        @Test
        fun `does not refresh the stored name from the claims on later sightings (US-5)`() {
            every { harness.userRepository.findByEmail(any()) } returns ASSIGNEE

            val result = service.provisionFromPrincipal(
                PrincipalClaims("sub-3", "amos@hive.test", "Renamed By The IdP"),
            )

            assertThat(result.name).isEqualTo(ASSIGNEE.name)
        }

        @Test
        fun `409 when the address is claimed between the lookup and the insert (US-2)`() {
            every { harness.userRepository.findByEmail(any()) } returnsMany listOf(null, OWNER)

            assertThatThrownBy {
                service.provisionFromPrincipal(PrincipalClaims("sub-1", "olive@hive.test", "Olive"))
            }
                .isInstanceOf(ConflictException::class.java)
                .hasMessageContaining("already exists")

            verify(exactly = 0) { harness.userRepository.save(any()) }
        }

        @Test
        fun `400 when the claims carry a malformed address`() {
            assertThatThrownBy {
                service.provisionFromPrincipal(PrincipalClaims("sub-1", "not-an-address", "Nobody"))
            }
                .isInstanceOf(ValidationException::class.java)
        }
    }

    @Nested
    @DisplayName("renameSelf (US-5)")
    inner class Renaming {

        @Test
        fun `changes the acting user's own name`() {
            every { harness.userRepository.findById(OWNER_ID) } returns OWNER
            harness.echoUserSaves()

            val result = service.renameSelf(OWNER_ID, RenameUserCommand("Olive Renamed"))

            assertThat(result.name.value).isEqualTo("Olive Renamed")
            assertThat(result.email).isEqualTo(OWNER.email)
        }

        @Test
        fun `404 when the acting user is unknown`() {
            every { harness.userRepository.findById(GHOST_ID) } returns null

            assertThatThrownBy { service.renameSelf(GHOST_ID, RenameUserCommand("Ghost")) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `400 on a blank name`() {
            every { harness.userRepository.findById(OWNER_ID) } returns OWNER

            assertThatThrownBy { service.renameSelf(OWNER_ID, RenameUserCommand("   ")) }
                .isInstanceOf(ValidationException::class.java)
        }
    }

    @Nested
    @DisplayName("the directory (US-4)")
    inner class Directory {

        @Test
        fun `search passes the query straight through to the port`() {
            every { harness.userRepository.search("ami", PageRequest.DEFAULT) } returns pageOf(ASSIGNEE)

            val result = service.search(OWNER_ID, "ami", PageRequest.DEFAULT)

            assertThat(result.content).containsExactly(ASSIGNEE)
        }

        @Test
        fun `a null query means everyone`() {
            every { harness.userRepository.search(null, PageRequest.DEFAULT) } returns
                pageOf(*EVERYONE.toTypedArray())

            val result = service.search(OWNER_ID, null, PageRequest.DEFAULT)

            assertThat(result.content).hasSize(5)
        }

        @Test
        fun `a blank query is treated as no query, not as a search for whitespace`() {
            every { harness.userRepository.search(null, PageRequest.DEFAULT) } returns pageOf(MEMBER)

            service.search(OWNER_ID, "   ", PageRequest.DEFAULT)

            verify { harness.userRepository.search(null, PageRequest.DEFAULT) }
        }

        @Test
        fun `getById returns any user, because assignment requires naming one`() {
            every { harness.userRepository.findById(ASSIGNEE_ID) } returns ASSIGNEE

            assertThat(service.getById(OWNER_ID, ASSIGNEE_ID)).isEqualTo(ASSIGNEE)
        }

        @Test
        fun `getById 404s on an unknown id`() {
            every { harness.userRepository.findById(GHOST_ID) } returns null

            assertThatThrownBy { service.getById(OWNER_ID, GHOST_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }
    }
}
