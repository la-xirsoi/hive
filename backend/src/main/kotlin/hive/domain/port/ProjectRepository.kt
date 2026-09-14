package hive.domain.port

import hive.domain.model.Project
import hive.domain.model.ProjectId
import hive.domain.model.TeamId
import hive.domain.model.UserId

/**
 * Outbound port for project storage.
 */
interface ProjectRepository {
    fun findById(id: ProjectId): Project?

    /** Every project belonging to a team. Also answers TM-3's "owns a project of this team". */
    fun findByTeam(teamId: TeamId): List<Project>

    /**
     * PR-4: projects [userId] owns, plus projects of teams they lead or belong
     * to.
     *
     * This is a port rather than an in-memory filter so the adapter can push the
     * union into a single SQL statement instead of loading every project in the
     * system to discard most of them.
     */
    fun findVisibleTo(userId: UserId): List<Project>

    fun save(project: Project): Project
}
