package hive.application.usecase

import hive.domain.model.Page
import hive.domain.model.PageRequest
import hive.domain.model.User
import hive.domain.model.UserId

/**
 * Inbound port for section 2 of `docs/api-contract.md`.
 *
 * | Endpoint | Method |
 * |----------|--------|
 * | `GET /users/me` | [provisionFromPrincipal] |
 * | `PATCH /users/me` | [renameSelf] |
 * | `GET /users?query=` | [search] |
 * | `GET /users/{id}` | [getById] |
 *
 * Every operation that acts on behalf of somebody takes that somebody as an
 * explicit [UserId]. The application layer never reaches into a Spring
 * `SecurityContext`: resolving the principal from the token is the inbound
 * adapter's job (AU-2), and keeping it a parameter is what lets these services
 * be tested without a security framework.
 */
interface UserUseCases {

    /**
     * `GET /users/me` -- US-3: return the Hive user for an authenticated
     * principal, provisioning them on first sight.
     *
     * There is no registration endpoint; identity is owned by the identity
     * provider and this is the only way a [User] row is ever created.
     *
     * @throws hive.domain.error.ValidationException (400) if the claims carry a
     *   malformed email or name.
     * @throws hive.domain.error.ConflictException (409) US-2, if the address was
     *   taken between the lookup and the insert.
     */
    fun provisionFromPrincipal(claims: PrincipalClaims): User

    /**
     * `PATCH /users/me` -- US-5: change the acting user's own display name.
     *
     * @throws hive.domain.error.NotFoundException (404) if [actor] is unknown.
     * @throws hive.domain.error.ValidationException (400) if the name is invalid.
     */
    fun renameSelf(actor: UserId, command: RenameUserCommand): User

    /**
     * `GET /users?query=` -- US-4: the user directory, a case-insensitive
     * name-or-email substring match. A `null` [query] matches everyone.
     *
     * Any authenticated user may search: assignment, ownership transfer and lead
     * transfer all require naming another user.
     */
    fun search(actor: UserId, query: String?, page: PageRequest): Page<User>

    /**
     * `GET /users/{id}` -- US-4: look one user up by id.
     *
     * @throws hive.domain.error.NotFoundException (404) if no such user exists.
     */
    fun getById(actor: UserId, id: UserId): User
}
