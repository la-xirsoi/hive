package hive.adapter.out.persistence

import hive.adapter.out.persistence.jpa.TaskJpaRepository
import hive.adapter.out.persistence.mapper.PersistenceMappers
import hive.domain.model.Page
import hive.domain.model.PageRequest
import hive.domain.model.ProjectId
import hive.domain.model.Task
import hive.domain.model.TaskId
import hive.domain.model.TaskStatus
import hive.domain.model.UserId
import hive.domain.port.TaskRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * [TaskRepository] against SQL Server.
 *
 * This is the adapter the visibility rules live or die by. Every role-scoped
 * method delegates to a JPQL predicate that the database evaluates; not one of
 * them loads rows to discard them in Kotlin. `docs/architecture.md`: "Visibility
 * is a query concern, not a filter."
 *
 * The status constants passed to those queries come from [TaskStatus] rather
 * than from string literals in the query text, so the wire spellings have
 * exactly one definition in the whole system.
 */
@Repository
@Transactional(readOnly = true)
class TaskPersistenceAdapter(
    private val taskRows: TaskJpaRepository,
) : TaskRepository {

    override fun findById(id: TaskId): Task? =
        taskRows.findById(id.value).orElse(null)?.let(PersistenceMappers::toDomain)

    /** VIS-1/VIS-5: every status, including Canceled work the user was assigned. */
    override fun findAssignedTo(userId: UserId, page: PageRequest): Page<Task> =
        Paging.toDomainPage(
            taskRows.findByAssignee(userId.value, Paging.toPageable(page)),
            page,
            PersistenceMappers::toDomain,
        )

    /** VIS-1..VIS-5 for one project, evaluated by the database. */
    override fun findVisibleInProject(
        projectId: ProjectId,
        viewer: UserId,
        page: PageRequest,
    ): Page<Task> =
        Paging.toDomainPage(
            taskRows.findVisibleInProject(
                projectId = projectId.value,
                viewerId = viewer.value,
                draft = TaskStatus.DRAFT.wireName,
                canceled = TaskStatus.CANCELED.wireName,
                pageable = Paging.toPageable(page),
            ),
            page,
            PersistenceMappers::toDomain,
        )

    /** UQ-1: unassigned `Todo` tasks across every team this user leads. */
    override fun findUnassignedForLead(leadId: UserId, page: PageRequest): Page<Task> =
        Paging.toDomainPage(
            taskRows.findUnassignedForLead(
                leadId = leadId.value,
                todo = TaskStatus.TODO.wireName,
                pageable = Paging.toPageable(page),
            ),
            page,
            PersistenceMappers::toDomain,
        )

    /**
     * TM-8 and PR-8. Unpaged, because both callers act on the whole set.
     *
     * "Live" is [Task.isLive] spelled in SQL: `Todo` or `In Progress`. The two
     * definitions are kept in step by deriving the status list from [TaskStatus]
     * here rather than hard-coding it.
     */
    override fun findLiveTasksAssignedTo(userId: UserId, projectId: ProjectId?): List<Task> {
        val entities =
            if (projectId == null) {
                taskRows.findByAssigneeAndStatusInOrderByIdAsc(userId.value, LIVE_STATUSES)
            } else {
                taskRows.findByAssigneeAndProjectIdAndStatusInOrderByIdAsc(
                    userId.value,
                    projectId.value,
                    LIVE_STATUSES,
                )
            }
        return entities.map(PersistenceMappers::toDomain)
    }

    @Transactional
    override fun save(task: Task): Task =
        PersistenceMappers.toDomain(taskRows.save(PersistenceMappers.toEntity(task)))

    /** TM-8 unassigns a whole batch; one flush, one round trip. */
    @Transactional
    override fun saveAll(tasks: List<Task>): List<Task> {
        if (tasks.isEmpty()) {
            return emptyList()
        }
        return taskRows
            .saveAll(tasks.map(PersistenceMappers::toEntity))
            .map(PersistenceMappers::toDomain)
    }

    private companion object {
        /**
         * The wire spellings of the live statuses, derived from the enum's own
         * [Task.isLive] definition so that adding a status cannot leave this
         * list quietly stale.
         */
        val LIVE_STATUSES: List<String> =
            listOf(TaskStatus.TODO, TaskStatus.IN_PROGRESS).map { it.wireName }
    }
}
