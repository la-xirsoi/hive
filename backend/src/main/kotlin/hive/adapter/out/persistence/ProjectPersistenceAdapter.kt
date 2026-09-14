package hive.adapter.out.persistence

import hive.adapter.out.persistence.jpa.ProjectJpaRepository
import hive.adapter.out.persistence.mapper.PersistenceMappers
import hive.domain.model.Project
import hive.domain.model.ProjectId
import hive.domain.model.TeamId
import hive.domain.model.UserId
import hive.domain.port.ProjectRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * [ProjectRepository] against SQL Server.
 */
@Repository
@Transactional(readOnly = true)
class ProjectPersistenceAdapter(
    private val projects: ProjectJpaRepository,
) : ProjectRepository {

    override fun findById(id: ProjectId): Project? =
        projects.findById(id.value).orElse(null)?.let(PersistenceMappers::toDomain)

    override fun findByTeam(teamId: TeamId): List<Project> =
        projects.findByTeamIdOrderByIdAsc(teamId.value).map(PersistenceMappers::toDomain)

    /**
     * PR-4: the three-way union -- owned, led, joined -- resolved in SQL.
     *
     * INV-2 is why ownership is a separate arm of that union rather than a
     * consequence of membership: a project owner need not be in the project's
     * team at all, and if this query only walked `team_members` an owner would
     * lose sight of their own project.
     */
    override fun findVisibleTo(userId: UserId): List<Project> =
        projects.findVisibleTo(userId.value).map(PersistenceMappers::toDomain)

    @Transactional
    override fun save(project: Project): Project =
        PersistenceMappers.toDomain(projects.save(PersistenceMappers.toEntity(project)))
}
