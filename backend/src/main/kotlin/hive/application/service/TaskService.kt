package hive.application.service

import hive.application.support.ContextLoader
import hive.application.support.ViewAssembler
import hive.application.usecase.AssignTaskCommand
import hive.application.usecase.CreateTaskCommand
import hive.application.usecase.TaskUseCases
import hive.application.usecase.TransitionTaskCommand
import hive.application.usecase.UpdateTaskCommand
import hive.application.view.TaskDetailView
import hive.application.view.TaskSummaryView
import hive.domain.error.AuthorizationException
import hive.domain.error.ConflictException
import hive.domain.error.ValidationException
import hive.domain.model.Page
import hive.domain.model.PageRequest
import hive.domain.model.Task
import hive.domain.model.TaskContext
import hive.domain.model.TaskDescription
import hive.domain.model.TaskId
import hive.domain.model.TaskName
import hive.domain.model.UserId
import hive.domain.policy.AuthorizationPolicy
import hive.domain.policy.TaskTransitions
import hive.domain.policy.TransitionResult
import hive.domain.port.TaskRepository
import hive.domain.port.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * TK-1..TK-6, AS-1..AS-7, TE-1..TE-3, UQ-1 and the state machine.
 *
 * The most rule-dense area of the app and therefore the one with the least rule
 * logic in it: every decision is delegated to [AuthorizationPolicy] or to
 * [TaskTransitions]. What this service owns is the *sequence* -- which
 * aggregates to load, and the 404-before-409-before-403-before-400 ordering that
 * the contract makes non-negotiable.
 */
@Service
class TaskService(
    private val taskRepository: TaskRepository,
    private val userRepository: UserRepository,
    private val loader: ContextLoader,
    private val views: ViewAssembler,
) : TaskUseCases {

    /** TK-1/TK-2: the project owner only; [Task.create] fixes `Draft`, the creator and the empty assignee. */
    @Transactional
    override fun create(actor: UserId, command: CreateTaskCommand): TaskDetailView {
        val (project, team) = loader.requireVisibleProject(command.projectId, actor)
        AuthorizationPolicy.checkCreateTask(project, actor)

        val task = Task.create(
            name = TaskName(command.name),
            description = TaskDescription(command.description),
            projectId = command.projectId,
            creator = actor,
        )
        val saved = taskRepository.save(task)
        return views.taskDetail(TaskContext(saved, project, team, actor))
    }

    @Transactional(readOnly = true)
    override fun get(actor: UserId, taskId: TaskId): TaskDetailView =
        views.taskDetail(loader.requireVisibleTask(taskId, actor))

    /**
     * TK-3/TK-4.
     *
     * The empty-command 400 is checked *after* the policy, not before. A
     * terminal task answers 409 and a non-owner answers 403 whatever the body
     * said, and the ordering rule puts payload complaints last.
     */
    @Transactional
    override fun update(actor: UserId, taskId: TaskId, command: UpdateTaskCommand): TaskDetailView {
        val context = loader.requireVisibleTask(taskId, actor)
        AuthorizationPolicy.checkEditTaskFields(context)

        if (command.isEmpty) {
            throw ValidationException("name", "at least one of name or description must be supplied.")
        }
        var task = context.task
        command.name?.let { task = task.rename(TaskName(it)) }
        command.description?.let { task = task.changeDescription(TaskDescription(it)) }

        return views.taskDetail(context.copy(task = taskRepository.save(task)))
    }

    /**
     * TK-5: the only path by which a status changes.
     *
     * [TaskTransitions.attempt] returns the three outcomes as distinct types so
     * that 409 and 403 cannot be collapsed by accident; this method's only job
     * is to translate them, and it deliberately does not look at the task's
     * status itself.
     */
    @Transactional
    override fun transition(actor: UserId, taskId: TaskId, command: TransitionTaskCommand): TaskDetailView {
        val context = loader.requireVisibleTask(taskId, actor)

        return when (val result = TaskTransitions.attempt(context, command.target)) {
            is TransitionResult.Allowed ->
                views.taskDetail(context.copy(task = taskRepository.save(result.task)))
            is TransitionResult.Illegal -> throw ConflictException(result.reason)
            is TransitionResult.Forbidden -> throw AuthorizationException(result.reason)
        }
    }

    /**
     * AS-1..AS-7 and TE-3.
     *
     * AS-2 has two halves with two different statuses: a target who exists but
     * does not belong to the team is a **403**, and a target who does not exist
     * at all is a **400**. The policy is given ids, not a directory, so it
     * answers 403 for both -- and the 400 half is refined here, after the fact.
     *
     * Refining afterwards rather than looking the user up first is what keeps
     * the ordering intact: the 409s for a `Draft` or terminal task, and the 403
     * for a caller who is not the lead, still come first. Only a refusal that
     * *could* have been "no such user" triggers the extra lookup, and AS-4's
     * "that is the project owner" is untouched because the owner exists.
     */
    @Transactional
    override fun assign(actor: UserId, taskId: TaskId, command: AssignTaskCommand): TaskDetailView {
        val context = loader.requireVisibleTask(taskId, actor)
        val newAssignee = command.assignee

        try {
            AuthorizationPolicy.checkAssign(context, newAssignee)
        } catch (refused: AuthorizationException) {
            if (newAssignee != null && userRepository.findById(newAssignee) == null) {
                throw ValidationException("userId", "must be an existing user.")
            }
            throw refused
        }

        val saved = taskRepository.save(context.task.assignTo(newAssignee))
        return views.taskDetail(context.copy(task = saved))
    }

    /** VIS-1/VIS-5: every status, including tasks canceled after they were assigned. */
    @Transactional(readOnly = true)
    override fun listAssignedToMe(actor: UserId, page: PageRequest): Page<TaskSummaryView> =
        views.taskSummaries(taskRepository.findAssignedTo(actor, page))

    /** UQ-1: empty rather than forbidden for a user who leads no team -- the queue is simply empty for them. */
    @Transactional(readOnly = true)
    override fun listUnassigned(actor: UserId, page: PageRequest): Page<TaskSummaryView> =
        views.taskSummaries(taskRepository.findUnassignedForLead(actor, page))
}
