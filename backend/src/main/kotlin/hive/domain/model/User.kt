package hive.domain.model

/**
 * A person who uses Hive.
 *
 * Users are provisioned from the authenticated OAuth principal on first sight
 * (US-3); there is no registration operation in the domain. Email uniqueness
 * (US-2) is a repository-level constraint -- the entity cannot see the other
 * users -- but it is case-insensitive here because [EmailAddress] says so.
 */
data class User(
    val id: UserId?,
    val name: PersonName,
    val email: EmailAddress,
) {
    /**
     * US-5: a user may change their own name. Email is identity-provider owned
     * and has no rename operation by design.
     */
    fun rename(newName: PersonName): User = copy(name = newName)
}
