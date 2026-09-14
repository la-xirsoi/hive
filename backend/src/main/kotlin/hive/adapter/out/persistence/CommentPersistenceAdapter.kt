package hive.adapter.out.persistence

import hive.adapter.out.persistence.jpa.CommentJpaRepository
import hive.adapter.out.persistence.mapper.PersistenceMappers
import hive.domain.model.Comment
import hive.domain.model.Page
import hive.domain.model.PageRequest
import hive.domain.model.TaskId
import hive.domain.port.CommentRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * [CommentRepository] against SQL Server.
 *
 * CM-6 -- comments are immutable -- is honoured by omission here exactly as it
 * is in the port: there is no update and no delete method, so no caller can
 * reach one. `save` is only ever an insert in practice, because
 * [Comment.create] is the only way the application layer makes a comment and it
 * always produces a null id.
 */
@Repository
@Transactional(readOnly = true)
class CommentPersistenceAdapter(
    private val comments: CommentJpaRepository,
) : CommentRepository {

    /**
     * CM-2: one task's comments, oldest first.
     *
     * Visibility of the *task* is decided before this is called -- the port says
     * so -- which is why there is no viewer parameter. Comments carry no
     * visibility of their own beyond their task's (CM-1/CM-2).
     */
    override fun findByTask(taskId: TaskId, page: PageRequest): Page<Comment> =
        Paging.toDomainPage(
            comments.findByTaskId(taskId.value, Paging.toPageable(page)),
            page,
            PersistenceMappers::toDomain,
        )

    /** CM-5: the timestamp arrives already truncated to the minute and is stored in `DATETIME2(0)`. */
    @Transactional
    override fun save(comment: Comment): Comment =
        PersistenceMappers.toDomain(comments.save(PersistenceMappers.toEntity(comment)))
}
