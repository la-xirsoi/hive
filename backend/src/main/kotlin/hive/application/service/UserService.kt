package hive.application.service

import hive.application.usecase.PrincipalClaims
import hive.application.usecase.RenameUserCommand
import hive.application.usecase.UserUseCases
import hive.domain.error.ConflictException
import hive.domain.error.NotFoundException
import hive.domain.model.EmailAddress
import hive.domain.model.Page
import hive.domain.model.PageRequest
import hive.domain.model.PersonName
import hive.domain.model.User
import hive.domain.model.UserId
import hive.domain.port.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * US-1..US-5.
 *
 * The thinnest of the five services, because users carry no contextual role:
 * every rule about who may look a user up (US-4) resolves to "any authenticated
 * caller", and the one rule about mutation (US-5) resolves to "yourself, name
 * only". [actor] is still an explicit parameter on every read so that tightening
 * the directory later is a change to this file and not to every caller.
 */
@Service
class UserService(
    private val userRepository: UserRepository,
) : UserUseCases {

    /**
     * US-3: find the Hive user behind an authenticated principal, creating the
     * row on first sight.
     *
     * The match is on **email**, not on the token's subject, because
     * [hive.domain.model.User] has no subject field: identity is owned by the
     * identity provider and Hive stores only what its own contract publishes.
     * That is sound precisely because US-2 makes the address unique.
     *
     * The stored name is **not** refreshed from the claims on later sightings.
     * US-5 lets a user rename themselves inside Hive, and overwriting that from
     * the provider on every request would silently undo it.
     */
    @Transactional
    override fun provisionFromPrincipal(claims: PrincipalClaims): User {
        val email = EmailAddress(claims.email)
        userRepository.findByEmail(email)?.let { return it }

        val user = User(id = null, name = displayNameFor(claims, email), email = email)
        // US-2. `GET /users/me` is the frontend's *first* request and several
        // tabs can fire it at once, so the window between the lookup above and
        // this insert is real rather than theoretical. The database's unique
        // index is the ultimate backstop; this turns the race into the 409 the
        // contract names instead of a 500.
        if (userRepository.findByEmail(email) != null) {
            throw ConflictException("A user with this email address already exists.")
        }
        return userRepository.save(user)
    }

    @Transactional
    override fun renameSelf(actor: UserId, command: RenameUserCommand): User {
        val user = require(actor)
        return userRepository.save(user.rename(PersonName(command.name)))
    }

    @Transactional(readOnly = true)
    override fun search(actor: UserId, query: String?, page: PageRequest): Page<User> =
        userRepository.search(query?.takeIf { it.isNotBlank() }, page)

    @Transactional(readOnly = true)
    override fun getById(actor: UserId, id: UserId): User = require(id)

    private fun require(id: UserId): User =
        userRepository.findById(id) ?: throw NotFoundException("User", id)

    /**
     * The `name` claim is optional -- plenty of providers omit it -- but
     * [PersonName] is not nullable and a blank display name is useless in an
     * assignment picker. The local part of the address is the least surprising
     * stand-in, and US-5 lets the user correct it immediately.
     */
    private fun displayNameFor(claims: PrincipalClaims, email: EmailAddress): PersonName {
        val claimed = claims.name?.trim().orEmpty()
        val fallback = email.value.substringBefore('@').take(PersonName.MAX_LENGTH)
        return PersonName(claimed.ifEmpty { fallback })
    }
}
