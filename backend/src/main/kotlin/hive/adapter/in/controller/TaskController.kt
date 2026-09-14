package hive.adapter.`in`.controller

import hive.adapter.`in`.dto.CreateTaskRequest
import hive.adapter.`in`.dto.PageResponse
import hive.adapter.`in`.dto.TaskDetailDto
import hive.adapter.`in`.dto.TaskSummaryDto
import hive.adapter.`in`.dto.UpdateTaskAssigneeRequest
import hive.adapter.`in`.dto.UpdateTaskRequest
import hive.adapter.`in`.dto.UpdateTaskStatusRequest
import hive.adapter.`in`.dto.pageRequestOf
import hive.adapter.`in`.mapper.toDto
import hive.adapter.`in`.mapper.toResponse
import hive.adapter.`in`.security.ActingUser
import hive.adapter.`in`.security.CurrentUser
import hive.application.usecase.AssignTaskCommand
import hive.application.usecase.CreateTaskCommand
import hive.application.usecase.TaskUseCases
import hive.application.usecase.TransitionTaskCommand
import hive.application.usecase.UpdateTaskCommand
import hive.domain.model.PageRequest
import hive.domain.model.ProjectId
import hive.domain.model.TaskId
import hive.domain.model.TaskStatus
import hive.domain.model.UserId
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Tasks -- `docs/api-contract.md` section 5.
 *
 * | Endpoint | Use case |
 * |----------|----------|
 * | `POST /tasks` | [TaskUseCases.create] |
 * | `GET /tasks/{id}` | [TaskUseCases.get] |
 * | `PATCH /tasks/{id}` | [TaskUseCases.update] |
 * | `PUT /tasks/{id}/status` | [TaskUseCases.transition] |
 * | `PUT /tasks/{id}/assignee` | [TaskUseCases.assign] |
 * | `GET /tasks/assigned-to-me` | [TaskUseCases.listAssignedToMe] |
 * | `GET /tasks/unassigned` | [TaskUseCases.listUnassigned] |
 * | `DELETE /tasks/{id}` | -- TK-6: 405, no use case exists |
 *
 * Status has its own endpoint rather than being a field of `PATCH /tasks/{id}`
 * because TK-5 makes the transition the operation: it runs the state machine,
 * it has its own authorization, and it answers 409 where an edit would answer
 * nothing. Folding it into the patch would hide a state machine inside an
 * optional field.
 */
@RestController
@RequestMapping("/api/v1/tasks", produces = [MediaType.APPLICATION_JSON_VALUE])
class TaskController(
    private val tasks: TaskUseCases,
) {

    /** `POST /tasks` -- TK-1/TK-2: the project owner only; always `Draft` and unassigned. */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @CurrentUser actor: ActingUser,
        @Valid @RequestBody request: CreateTaskRequest,
    ): TaskDetailDto =
        tasks
            .create(
                actor.id,
                CreateTaskCommand(
                    projectId = ProjectId(requireNotNull(request.projectId)),
                    name = requireNotNull(request.name),
                    description = requireNotNull(request.description),
                ),
            ).toDto()

    /** `GET /tasks/assigned-to-me` -- VIS-1/VIS-5: every status, including terminal ones. */
    @GetMapping("/assigned-to-me")
    fun assignedToMe(
        @CurrentUser actor: ActingUser,
        @RequestParam(defaultValue = "${PageRequest.DEFAULT_PAGE}") page: Int,
        @RequestParam(defaultValue = "${PageRequest.DEFAULT_SIZE}") size: Int,
    ): PageResponse<TaskSummaryDto> =
        tasks.listAssignedToMe(actor.id, pageRequestOf(page, size)).toResponse { it.toDto() }

    /** `GET /tasks/unassigned` -- UQ-1. A user who leads nothing gets an empty page, not a 403. */
    @GetMapping("/unassigned")
    fun unassigned(
        @CurrentUser actor: ActingUser,
        @RequestParam(defaultValue = "${PageRequest.DEFAULT_PAGE}") page: Int,
        @RequestParam(defaultValue = "${PageRequest.DEFAULT_SIZE}") size: Int,
    ): PageResponse<TaskSummaryDto> =
        tasks.listUnassigned(actor.id, pageRequestOf(page, size)).toResponse { it.toDto() }

    /** `GET /tasks/{id}` -- VIS-1..VIS-5. 404 when invisible. */
    @GetMapping("/{id}")
    fun get(
        @CurrentUser actor: ActingUser,
        @PathVariable id: Long,
    ): TaskDetailDto = tasks.get(actor.id, TaskId(id)).toDto()

    /**
     * `PATCH /tasks/{id}` -- TK-3/TE-1: the project owner only, never terminal.
     *
     * "At least one field" is the use case's rule, not this method's: an empty
     * [UpdateTaskCommand] is a 400 raised one layer down, so the two copies of
     * the rule that would otherwise exist here are one copy there.
     */
    @PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun update(
        @CurrentUser actor: ActingUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateTaskRequest,
    ): TaskDetailDto =
        tasks
            .update(actor.id, TaskId(id), UpdateTaskCommand(name = request.name, description = request.description))
            .toDto()

    /**
     * `PUT /tasks/{id}/status` -- TK-5, the only way a status changes.
     *
     * The wire spelling is parsed by [TaskStatus.fromWireName], so an unknown
     * literal is a 400 naming `status` (contract section 8) rather than a
     * Jackson deserialisation failure naming nothing.
     */
    @PutMapping("/{id}/status", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun transition(
        @CurrentUser actor: ActingUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateTaskStatusRequest,
    ): TaskDetailDto =
        tasks
            .transition(
                actor.id,
                TaskId(id),
                TransitionTaskCommand(TaskStatus.fromWireName(requireNotNull(request.status))),
            ).toDto()

    /**
     * `PUT /tasks/{id}/assignee` -- AS-1..AS-7 and TE-3.
     *
     * A body of `{"userId": null}` is an unassignment, which AS-7 permits only
     * from `Todo`; that is a different request from omitting the field, and
     * both arrive here as `null` because the contract gives the two the same
     * meaning.
     */
    @PutMapping("/{id}/assignee", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun assign(
        @CurrentUser actor: ActingUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateTaskAssigneeRequest,
    ): TaskDetailDto =
        tasks.assign(actor.id, TaskId(id), AssignTaskCommand(request.userId?.let { UserId(it) })).toDto()
}
