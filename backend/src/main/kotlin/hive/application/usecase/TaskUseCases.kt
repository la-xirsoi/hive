package hive.application.usecase

import hive.application.view.TaskDetailView
import hive.application.view.TaskSummaryView
import hive.domain.model.Page
import hive.domain.model.PageRequest
import hive.domain.model.TaskId
import hive.domain.model.UserId

/**
 * Inbound port for section 5 of `docs/api-contract.md`.
 *
 * | Endpoint | Method |
 * |----------|--------|
 * | `POST /tasks` | [create] |
 * | `GET /tasks/{id}` | [get] |
 * | `PATCH /tasks/{id}` | [update] |
 * | `PUT /tasks/{id}/status` | [transition] |
 * | `PUT /tasks/{id}/assignee` | [assign] |
 * | `GET /tasks/assigned-to-me` | [listAssignedToMe] |
 * | `GET /tasks/unassigned` | [listUnassigned] |
 * | `DELETE /tasks/{id}` | -- TK-6, unsupported: the adapter answers 405 |
 *
 * Every returned [TaskDetailView] carries the acting user's
 * [hive.application.view.TaskPermissions], computed from the same policy that
 * enforces the rules, so the UI can never be shown a control the server would
 * reject.
 */
interface TaskUseCases {

    /**
     * `POST /tasks` -- TK-1/TK-2: the project owner only; the task is created
     * `Draft`, credited to its creator, with no assignee.
     *
     * @throws hive.domain.error.NotFoundException (404) invisible project.
     * @throws hive.domain.error.AuthorizationException (403) not the owner.
     * @throws hive.domain.error.ValidationException (400) invalid name or description.
     */
    fun create(actor: UserId, command: CreateTaskCommand): TaskDetailView

    /**
     * `GET /tasks/{id}` -- VIS-1..VIS-5.
     *
     * @throws hive.domain.error.NotFoundException (404) missing or invisible.
     */
    fun get(actor: UserId, taskId: TaskId): TaskDetailView

    /**
     * `PATCH /tasks/{id}` -- TK-3/TK-4: the project owner only, and never in a
     * terminal state.
     *
     * @throws hive.domain.error.NotFoundException (404) missing or invisible.
     * @throws hive.domain.error.ConflictException (409) TE-1, terminal task.
     * @throws hive.domain.error.AuthorizationException (403) not the owner.
     * @throws hive.domain.error.ValidationException (400) an empty command, or
     *   an invalid name or description.
     */
    fun update(actor: UserId, taskId: TaskId, command: UpdateTaskCommand): TaskDetailView

    /**
     * `PUT /tasks/{id}/status` -- TK-5: the **only** way a status changes.
     *
     * The 409-before-403 split is the whole point of this operation: a move that
     * is not in the state machine is impossible for anybody, so it is answered
     * before anyone asks who is calling.
     *
     * @throws hive.domain.error.NotFoundException (404) missing or invisible.
     * @throws hive.domain.error.ConflictException (409) the move is not in the table.
     * @throws hive.domain.error.AuthorizationException (403) the move is legal
     *   but this actor may not make it.
     */
    fun transition(actor: UserId, taskId: TaskId, command: TransitionTaskCommand): TaskDetailView

    /**
     * `PUT /tasks/{id}/assignee` -- AS-1..AS-7 and TE-3: the team lead only,
     * never onto the project's owner, never onto a non-member, never while the
     * task is `Draft` or terminal, and unassignment only from `Todo`.
     *
     * @throws hive.domain.error.NotFoundException (404) missing or invisible.
     * @throws hive.domain.error.ConflictException (409) `Draft`, terminal, or an
     *   unassignment outside `Todo`.
     * @throws hive.domain.error.AuthorizationException (403) not the lead, or the
     *   target is the project owner or not a member of the team.
     * @throws hive.domain.error.ValidationException (400) AS-2, no such user.
     */
    fun assign(actor: UserId, taskId: TaskId, command: AssignTaskCommand): TaskDetailView

    /** `GET /tasks/assigned-to-me` -- VIS-1/VIS-5: every task assigned to [actor], in every status. */
    fun listAssignedToMe(actor: UserId, page: PageRequest = PageRequest.DEFAULT): Page<TaskSummaryView>

    /**
     * `GET /tasks/unassigned` -- UQ-1: `Todo` tasks with no assignee across every
     * team [actor] leads. A user who leads nothing gets an empty page, not a 403:
     * the queue is empty for them, and that is not an error.
     */
    fun listUnassigned(actor: UserId, page: PageRequest = PageRequest.DEFAULT): Page<TaskSummaryView>
}
