package hive.domain.port

import hive.domain.model.EmailAddress
import hive.domain.model.Page
import hive.domain.model.PageRequest
import hive.domain.model.User
import hive.domain.model.UserId

/**
 * Outbound port for user storage.
 *
 * Returns domain types only -- never a JPA entity, never a Spring `Page`. The
 * persistence adapter converts at its own boundary, which is what keeps this
 * package free of framework imports.
 */
interface UserRepository {
    fun findById(id: UserId): User?

    /** US-2: lookup is case-insensitive, matching [EmailAddress] equality. */
    fun findByEmail(email: EmailAddress): User?

    fun findAllById(ids: Set<UserId>): List<User>

    /** US-4: the user directory. [query] is a name/email substring; `null` matches everyone. */
    fun search(query: String?, page: PageRequest): Page<User>

    /** Inserts when `user.id` is null, otherwise updates. */
    fun save(user: User): User
}
