package hive.domain.model

import hive.domain.error.ConflictException

/**
 * A team: a lead plus its members.
 *
 * **INV-1 -- the lead is always a member -- holds by construction.** The
 * constructor adds the lead to [memberIds] rather than rejecting input that
 * omits them, so no code path anywhere in the system can produce a team whose
 * lead is not a member: not [copy], not [transferLeadTo], not deserialisation
 * from the database. [removeMember] refuses to remove the lead (TM-7).
 *
 * This is a hand-written class rather than a `data class` precisely so that the
 * normalisation in the initializer cannot be bypassed; it keeps the data-class
 * surface ([copy], structural equality, a readable [toString]).
 */
class Team(
    val id: TeamId?,
    val name: TeamName,
    val teamLead: UserId,
    memberIds: Set<UserId> = emptySet(),
) {
    /** Every member of the team, always including [teamLead] (INV-1). */
    val memberIds: Set<UserId> = memberIds + teamLead

    /** Whether [userId] is a member of this team (the lead always is). */
    fun hasMember(userId: UserId): Boolean = userId in memberIds

    /** Whether [userId] leads this team. */
    fun isLedBy(userId: UserId): Boolean = teamLead == userId

    /** TM-5: rename the team. Who may call this is [hive.domain.policy.AuthorizationPolicy]'s business. */
    fun rename(newName: TeamName): Team = copy(name = newName)

    /** TM-6: add a member. Idempotent -- adding an existing member is a no-op. */
    fun addMember(userId: UserId): Team = copy(memberIds = memberIds + userId)

    /**
     * TM-7: remove a member.
     *
     * @throws ConflictException if [userId] is the current lead -- that would
     *   violate INV-1. The lead must be transferred away first.
     */
    fun removeMember(userId: UserId): Team {
        if (userId == teamLead) {
            throw ConflictException(
                "The team lead cannot be removed from the team. Transfer the lead role first.",
            )
        }
        return copy(memberIds = memberIds - userId)
    }

    /**
     * TM-9/TM-10: hand the team to [newLead].
     *
     * The new lead becomes a member if they were not already one, and the
     * outgoing lead **remains** a member.
     */
    fun transferLeadTo(newLead: UserId): Team = copy(teamLead = newLead, memberIds = memberIds + newLead)

    fun copy(
        id: TeamId? = this.id,
        name: TeamName = this.name,
        teamLead: UserId = this.teamLead,
        memberIds: Set<UserId> = this.memberIds,
    ): Team = Team(id, name, teamLead, memberIds)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Team) return false
        return id == other.id &&
            name == other.name &&
            teamLead == other.teamLead &&
            memberIds == other.memberIds
    }

    override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + name.hashCode()
        result = 31 * result + teamLead.hashCode()
        result = 31 * result + memberIds.hashCode()
        return result
    }

    override fun toString(): String =
        "Team(id=$id, name=$name, teamLead=$teamLead, memberIds=$memberIds)"
}
