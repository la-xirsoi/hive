package hive.adapter.out.persistence

import hive.adapter.out.persistence.jpa.UserJpaRepository
import hive.adapter.out.persistence.mapper.PersistenceMappers
import hive.domain.model.EmailAddress
import hive.domain.model.Page
import hive.domain.model.PageRequest
import hive.domain.model.User
import hive.domain.model.UserId
import hive.domain.port.UserRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * [UserRepository] against SQL Server.
 *
 * The adapter owns three translations and no rules: typed ids to `Long`, JPA
 * entities to domain objects, and Spring Data paging to the domain's own.
 */
@Repository
@Transactional(readOnly = true)
class UserPersistenceAdapter(
    private val users: UserJpaRepository,
) : UserRepository {

    override fun findById(id: UserId): User? =
        users.findById(id.value).orElse(null)?.let(PersistenceMappers::toDomain)

    /**
     * US-2: matching on the normalized form is what makes lookup
     * case-insensitive, exactly as [EmailAddress] equality is. No `LOWER(...)`
     * in SQL, so the unique index is usable.
     */
    override fun findByEmail(email: EmailAddress): User? =
        users.findByEmail(email.normalized)?.let(PersistenceMappers::toDomain)

    override fun findAllById(ids: Set<UserId>): List<User> {
        if (ids.isEmpty()) {
            // `IN ()` is a syntax error in SQL Server and an always-false
            // predicate elsewhere; short-circuiting avoids depending on which.
            return emptyList()
        }
        return users.findAllById(ids.map { it.value }).map(PersistenceMappers::toDomain)
    }

    /**
     * US-4: the directory. A null or blank [query] matches everyone; otherwise
     * the term is matched as a case-insensitive substring of name or email.
     *
     * The two branches exist rather than one query with a nullable parameter
     * because `WHERE :p IS NULL OR ...` defeats the index for the common case
     * and makes the parameter's type ambiguous to the query compiler.
     */
    override fun search(query: String?, page: PageRequest): Page<User> {
        val term = query?.trim()?.lowercase()
        val pageable = Paging.toPageable(page)
        val result =
            if (term.isNullOrEmpty()) {
                users.findAll(pageable)
            } else {
                users.search("%$term%", pageable)
            }
        return Paging.toDomainPage(result, page, PersistenceMappers::toDomain)
    }

    /**
     * Inserts when `user.id` is null, updates otherwise.
     *
     * US-2 duplicate emails are rejected by `UQ_users_email`, which surfaces as
     * a [org.springframework.dao.DataIntegrityViolationException]; translating
     * that to the 409 the spec requires is the application layer's decision to
     * make, not this adapter's -- it has no way to tell a duplicate email from
     * any other constraint breach without parsing vendor error text.
     */
    @Transactional
    override fun save(user: User): User =
        PersistenceMappers.toDomain(users.save(PersistenceMappers.toEntity(user)))
}
