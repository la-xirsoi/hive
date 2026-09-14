package hive.adapter.out.persistence

import hive.adapter.out.persistence.jpa.TeamJpaRepository
import hive.adapter.out.persistence.mapper.PersistenceMappers
import hive.domain.model.Team
import hive.domain.model.TeamId
import hive.domain.model.UserId
import hive.domain.port.TeamRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * [TeamRepository] against SQL Server.
 *
 * The membership set travels with the team: `team_members` is mapped as an
 * element collection of [hive.adapter.out.persistence.entity.TeamEntity], so one
 * `save` reconciles the rows and there is no second call that could fail
 * separately and leave a team half-updated.
 */
@Repository
@Transactional(readOnly = true)
class TeamPersistenceAdapter(
    private val teams: TeamJpaRepository,
) : TeamRepository {

    override fun findById(id: TeamId): Team? =
        teams.findById(id.value).orElse(null)?.let(PersistenceMappers::toDomain)

    /** TM-4. Includes teams the user leads (INV-1), and the query says so itself. */
    override fun findTeamsForMember(userId: UserId): List<Team> =
        teams.findTeamsForMember(userId.value).map(PersistenceMappers::toDomain)

    /** UQ-1. */
    override fun findTeamsLedBy(userId: UserId): List<Team> =
        teams.findByTeamLeadOrderByIdAsc(userId.value).map(PersistenceMappers::toDomain)

    /**
     * Persists the team and its membership as one unit.
     *
     * INV-1 needs no enforcement here: [Team.memberIds] always contains the
     * lead, because the domain constructor puts it there, so the rows written
     * cannot omit it.
     */
    @Transactional
    override fun save(team: Team): Team =
        PersistenceMappers.toDomain(teams.save(PersistenceMappers.toEntity(team)))
}
