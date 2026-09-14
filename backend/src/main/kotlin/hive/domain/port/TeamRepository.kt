package hive.domain.port

import hive.domain.model.Team
import hive.domain.model.TeamId
import hive.domain.model.UserId

/**
 * Outbound port for team storage, including the membership set.
 */
interface TeamRepository {
    fun findById(id: TeamId): Team?

    /** TM-4: every team [userId] belongs to. Includes teams they lead, by INV-1. */
    fun findTeamsForMember(userId: UserId): List<Team>

    /** UQ-1: every team [userId] leads. */
    fun findTeamsLedBy(userId: UserId): List<Team>

    /** Persists the team and its membership set as one unit. */
    fun save(team: Team): Team
}
