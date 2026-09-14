package hive.adapter.out.persistence.jpa

import hive.adapter.out.persistence.entity.CommentEntity
import hive.adapter.out.persistence.entity.ProjectEntity
import hive.adapter.out.persistence.entity.TaskEntity
import hive.adapter.out.persistence.entity.TeamEntity
import hive.adapter.out.persistence.entity.UserEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * The Spring Data JPA repositories.
 *
 * These are an implementation detail of the adapter: the application layer never
 * sees them, only the domain ports in `hive.domain.port`. That indirection is
 * what lets the queries below be written in whatever form is fastest without any
 * of it reaching the domain.
 *
 * **Every role-scoped query here is a database query.** `docs/architecture.md`
 * states the reason plainly: "Visibility is a query concern, not a filter.
 * Fetching then filtering would be both slow and a place for a leak to hide."
 * There is no `findAll().filter { ... }` anywhere in this package, and the
 * fixture tests in `RoleScopedQueryIT` assert the exact row sets each of these
 * returns.
 *
 * Status is compared against a bound parameter rather than a literal so that the
 * value comes from [hive.domain.model.TaskStatus.wireName] -- the same constant
 * the mapper writes and the `CK_tasks_status` constraint checks -- instead of
 * being re-typed into a query string where a typo would silently widen
 * visibility.
 */

interface UserJpaRepository : JpaRepository<UserEntity, Long> {

    /** US-2: the stored address is already normalized, so an exact match is a case-insensitive match. */
    fun findByEmail(email: String): UserEntity?

    /**
     * US-4: the user directory. [pattern] is a pre-lowercased `%substring%`.
     *
     * `email` is stored lower-cased so it needs no `LOWER(...)`; `name` is stored
     * as the user typed it, so it does.
     */
    @Query(
        """
        SELECT u FROM UserEntity u
        WHERE LOWER(u.name) LIKE :pattern OR u.email LIKE :pattern
        """,
    )
    fun search(@Param("pattern") pattern: String, pageable: Pageable): Page<UserEntity>
}

interface TeamJpaRepository : JpaRepository<TeamEntity, Long> {

    /** UQ-1: every team this user leads. */
    fun findByTeamLeadOrderByIdAsc(teamLead: Long): List<TeamEntity>

    /**
     * TM-4: every team this user belongs to.
     *
     * The lead arm of the `OR` is redundant in a consistent database (INV-1 puts
     * the lead in the member set) but is kept deliberately: it makes the query
     * correct on its own terms rather than correct only because some other class
     * maintains an invariant, and it costs an index seek.
     *
     * `EXISTS` rather than a join, so a team with several matching member rows
     * cannot produce duplicate teams.
     */
    @Query(
        """
        SELECT t FROM TeamEntity t
        WHERE t.teamLead = :userId
           OR EXISTS (
                SELECT 1 FROM TeamEntity m JOIN m.memberIds mid
                WHERE m.id = t.id AND mid = :userId
              )
        ORDER BY t.id
        """,
    )
    fun findTeamsForMember(@Param("userId") userId: Long): List<TeamEntity>
}

interface ProjectJpaRepository : JpaRepository<ProjectEntity, Long> {

    /** Every project of one team. Also answers TM-3. */
    fun findByTeamIdOrderByIdAsc(teamId: Long): List<ProjectEntity>

    /**
     * PR-4: projects the user owns, plus projects of teams they lead or belong
     * to -- the union pushed into one statement instead of three round trips
     * and a set union in Kotlin.
     */
    @Query(
        """
        SELECT p FROM ProjectEntity p
        WHERE p.projectOwner = :userId
           OR EXISTS (
                SELECT 1 FROM TeamEntity tl
                WHERE tl.id = p.teamId AND tl.teamLead = :userId
              )
           OR EXISTS (
                SELECT 1 FROM TeamEntity tm JOIN tm.memberIds mid
                WHERE tm.id = p.teamId AND mid = :userId
              )
        ORDER BY p.id
        """,
    )
    fun findVisibleTo(@Param("userId") userId: Long): List<ProjectEntity>
}

interface TaskJpaRepository : JpaRepository<TaskEntity, Long> {

    /** VIS-1/VIS-5: everything assigned to this user, in every status. */
    fun findByAssignee(assignee: Long, pageable: Pageable): Page<TaskEntity>

    /** TM-8/PR-8 across every project. */
    fun findByAssigneeAndStatusInOrderByIdAsc(
        assignee: Long,
        statuses: Collection<String>,
    ): List<TaskEntity>

    /** TM-8/PR-8 narrowed to one project. */
    fun findByAssigneeAndProjectIdAndStatusInOrderByIdAsc(
        assignee: Long,
        projectId: Long,
        statuses: Collection<String>,
    ): List<TaskEntity>

    /**
     * VIS-1 through VIS-5 for one project, as a single predicate.
     *
     * The four `OR` arms are the four visibility rules, in the same order and
     * with the same conditions as
     * [hive.domain.policy.AuthorizationPolicy.canViewTask]:
     *
     * * `t.assignee = :viewerId` -- VIS-1/VIS-5, and it comes first for the same
     *   reason it does in the policy: it overrides every status filter, so an
     *   assignee still sees a task that was later Canceled.
     * * project owner -- VIS-2, *no* status filter: Draft and Canceled included.
     * * team lead -- VIS-3, everything except Draft.
     * * team member -- VIS-4, everything except Draft and Canceled.
     *
     * A user matching none of the four gets an empty page, which the application
     * layer reports as 404 rather than 403 (section 4: returning 403 would
     * confirm the task exists).
     */
    @Query(
        value = TASK_VISIBILITY_SELECT + TASK_VISIBILITY_WHERE,
        countQuery = TASK_VISIBILITY_COUNT + TASK_VISIBILITY_WHERE,
    )
    fun findVisibleInProject(
        @Param("projectId") projectId: Long,
        @Param("viewerId") viewerId: Long,
        @Param("draft") draft: String,
        @Param("canceled") canceled: String,
        pageable: Pageable,
    ): Page<TaskEntity>

    /**
     * UQ-1: the unassigned queue for a lead, across every team they lead.
     *
     * Exactly `Todo` and exactly null-assignee: Draft is excluded because leads
     * cannot see Draft at all (VIS-3), and terminal tasks are excluded because
     * there is nothing left to assign.
     */
    @Query(
        value = UNASSIGNED_SELECT + UNASSIGNED_WHERE,
        countQuery = UNASSIGNED_COUNT + UNASSIGNED_WHERE,
    )
    fun findUnassignedForLead(
        @Param("leadId") leadId: Long,
        @Param("todo") todo: String,
        pageable: Pageable,
    ): Page<TaskEntity>

    companion object {
        const val TASK_VISIBILITY_SELECT = "SELECT t FROM TaskEntity t "
        const val TASK_VISIBILITY_COUNT = "SELECT COUNT(t) FROM TaskEntity t "

        const val TASK_VISIBILITY_WHERE =
            """
            WHERE t.projectId = :projectId
              AND (
                    t.assignee = :viewerId
                 OR EXISTS (
                      SELECT 1 FROM ProjectEntity po
                      WHERE po.id = t.projectId AND po.projectOwner = :viewerId
                    )
                 OR (
                      t.status <> :draft
                      AND EXISTS (
                            SELECT 1 FROM ProjectEntity pl
                            JOIN TeamEntity tl ON tl.id = pl.teamId
                            WHERE pl.id = t.projectId AND tl.teamLead = :viewerId
                          )
                    )
                 OR (
                      t.status <> :draft AND t.status <> :canceled
                      AND EXISTS (
                            SELECT 1 FROM ProjectEntity pm
                            JOIN TeamEntity tm ON tm.id = pm.teamId
                            JOIN tm.memberIds mid
                            WHERE pm.id = t.projectId AND mid = :viewerId
                          )
                    )
              )
            """

        const val UNASSIGNED_SELECT = "SELECT t FROM TaskEntity t "
        const val UNASSIGNED_COUNT = "SELECT COUNT(t) FROM TaskEntity t "

        const val UNASSIGNED_WHERE =
            """
            JOIN ProjectEntity p ON p.id = t.projectId
            JOIN TeamEntity tm ON tm.id = p.teamId
            WHERE t.assignee IS NULL
              AND t.status = :todo
              AND tm.teamLead = :leadId
            """
    }
}

interface CommentJpaRepository : JpaRepository<CommentEntity, Long> {

    /** CM-2: one task's comments. Ordering is supplied by the pageable (id ascending = oldest first). */
    fun findByTaskId(taskId: Long, pageable: Pageable): Page<CommentEntity>
}
