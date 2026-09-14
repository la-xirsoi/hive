package hive.application.service

import hive.application.support.ContextLoader
import hive.application.support.ViewAssembler
import hive.application.usecase.AddCommentCommand
import hive.application.usecase.CommentUseCases
import hive.application.view.CommentView
import hive.domain.HiveClock
import hive.domain.model.Comment
import hive.domain.model.CommentContent
import hive.domain.model.Page
import hive.domain.model.PageRequest
import hive.domain.model.TaskId
import hive.domain.model.UserId
import hive.domain.port.CommentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * CM-1..CM-6.
 *
 * The simplest service in the layer, and its simplicity is the design: a comment
 * carries no role of its own. The single question -- may this actor see the task?
 * -- is answered by [ContextLoader.requireCommentableTask] through the policy,
 * and everything else follows.
 *
 * CM-3/TE-4 is visible here as an *absence*: no terminal-state check. Comments on
 * `Completed` and `Canceled` tasks are deliberately allowed, because the spec's
 * edit ban enumerates title, description and status, and a comment mutates none
 * of them. Post-mortem commentary on finished work is a normal activity.
 *
 * CM-6 is likewise an absence: there is no update and no delete.
 */
@Service
class CommentService(
    private val commentRepository: CommentRepository,
    private val loader: ContextLoader,
    private val views: ViewAssembler,
    /**
     * CM-5: time comes from the injected clock, never `Instant.now()`, so the
     * minute truncation the spec mandates cannot be forgotten and tests stay
     * deterministic. Defaulted so this service needs no configuration bean to
     * stand up, while a context that does define a [HiveClock] still wins.
     */
    private val clock: HiveClock = HiveClock.SYSTEM,
) : CommentUseCases {

    /** CM-2: readable by exactly the users who can see the task. Ordering is the repository's contract. */
    @Transactional(readOnly = true)
    override fun list(actor: UserId, taskId: TaskId, page: PageRequest): Page<CommentView> {
        loader.requireCommentableTask(taskId, actor)
        return views.commentViews(commentRepository.findByTask(taskId, page))
    }

    /** CM-1/CM-4/CM-5: [Comment.create] assigns the author and the timestamp; neither is client-supplied. */
    @Transactional
    override fun add(actor: UserId, taskId: TaskId, command: AddCommentCommand): CommentView {
        loader.requireCommentableTask(taskId, actor)

        val comment = Comment.create(
            taskId = taskId,
            author = actor,
            content = CommentContent(command.content),
            clock = clock,
        )
        return views.commentView(commentRepository.save(comment))
    }
}
