package hive.domain.model

/**
 * A project: a named body of work owned by one user and worked by one team.
 *
 * INV-2: owning a project does **not** make the owner a member of the project's
 * team. They may separately be one; that is neither required nor forbidden, and
 * it is what makes the visibility and assignment rules (VIS-2, AS-4)
 * non-trivial.
 *
 * [teamId] has no mutating operation: PR-9 ("move project to another team") is
 * explicitly unsupported because the spec gives no semantics for the fate of
 * in-flight assignments.
 */
data class Project(
    val id: ProjectId?,
    val name: ProjectName,
    val teamId: TeamId,
    val projectOwner: UserId,
) {
    /** Whether [userId] owns this project. */
    fun isOwnedBy(userId: UserId): Boolean = projectOwner == userId

    /** PR-5: rename the project. */
    fun rename(newName: ProjectName): Project = copy(name = newName)

    /**
     * PR-6: hand the project to [newOwner].
     *
     * PR-7 (the new owner may be any existing user) and PR-8 (the new owner
     * must not be the assignee of live tasks in this project) are checked by
     * [hive.domain.policy.AuthorizationPolicy] before this is called; they need
     * information the entity does not hold.
     */
    fun transferOwnershipTo(newOwner: UserId): Project = copy(projectOwner = newOwner)
}
