package hive.adapter.`in`.controller

import hive.adapter.`in`.dto.CreateProjectRequest
import hive.adapter.`in`.dto.PageResponse
import hive.adapter.`in`.dto.ProjectNameRequest
import hive.adapter.`in`.dto.ProjectSummaryDto
import hive.adapter.`in`.dto.TaskSummaryDto
import hive.adapter.`in`.dto.UserIdRequest
import hive.adapter.`in`.dto.pageRequestOf
import hive.adapter.`in`.mapper.toDto
import hive.adapter.`in`.mapper.toResponse
import hive.adapter.`in`.security.ActingUser
import hive.adapter.`in`.security.CurrentUser
import hive.application.usecase.CreateProjectCommand
import hive.application.usecase.ProjectUseCases
import hive.application.usecase.RenameProjectCommand
import hive.domain.model.PageRequest
import hive.domain.model.ProjectId
import hive.domain.model.TaskStatus
import hive.domain.model.TeamId
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
 * Projects -- `docs/api-contract.md` section 4.
 *
 * | Endpoint | Use case |
 * |----------|----------|
 * | `POST /projects` | [ProjectUseCases.create] |
 * | `GET /projects/mine` | [ProjectUseCases.listMine] |
 * | `GET /projects/{id}` | [ProjectUseCases.get] |
 * | `PATCH /projects/{id}` | [ProjectUseCases.rename] |
 * | `PUT /projects/{id}/owner` | [ProjectUseCases.transferOwner] |
 * | `GET /projects/{id}/tasks` | [ProjectUseCases.listTasks] |
 * | `DELETE /projects/{id}` | -- PR-10: 405, no use case exists |
 */
@RestController
@RequestMapping("/api/v1/projects", produces = [MediaType.APPLICATION_JSON_VALUE])
class ProjectController(
    private val projects: ProjectUseCases,
) {

    /** `POST /projects` -- PR-1/PR-2: the creator owns it and must belong to the team. */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @CurrentUser actor: ActingUser,
        @Valid @RequestBody request: CreateProjectRequest,
    ): ProjectSummaryDto =
        projects
            .create(
                actor.id,
                CreateProjectCommand(
                    name = requireNotNull(request.name),
                    teamId = TeamId(requireNotNull(request.teamId)),
                ),
            ).toDto()

    /** `GET /projects/mine` -- PR-4. */
    @GetMapping("/mine")
    fun mine(
        @CurrentUser actor: ActingUser,
    ): List<ProjectSummaryDto> = projects.listMine(actor.id).map { it.toDto() }

    /** `GET /projects/{id}` -- PR-3. 404 when invisible. */
    @GetMapping("/{id}")
    fun get(
        @CurrentUser actor: ActingUser,
        @PathVariable id: Long,
    ): ProjectSummaryDto = projects.get(actor.id, ProjectId(id)).toDto()

    /** `PATCH /projects/{id}` -- PR-5: the owner only. */
    @PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun rename(
        @CurrentUser actor: ActingUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: ProjectNameRequest,
    ): ProjectSummaryDto =
        projects.rename(actor.id, ProjectId(id), RenameProjectCommand(requireNotNull(request.name))).toDto()

    /** `PUT /projects/{id}/owner` -- PR-6/PR-7/PR-8: 409 if the incoming owner holds live tasks here. */
    @PutMapping("/{id}/owner", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun transferOwner(
        @CurrentUser actor: ActingUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: UserIdRequest,
    ): ProjectSummaryDto =
        projects.transferOwner(actor.id, ProjectId(id), UserId(requireNotNull(request.userId))).toDto()

    /**
     * `GET /projects/{id}/tasks` -- the caller's **visible** subset (VIS-1..VIS-5),
     * optionally narrowed by `?status=Todo,In Progress`.
     *
     * 403 is not a possible answer here: an invisible project is a 404, and a
     * visible one filters the list rather than refusing the request. The status
     * filter only ever removes rows; it can never widen visibility.
     *
     * The filter is parsed here rather than bound as an enum so that an unknown
     * literal is the contract's 400 with a `status` field error
     * ([TaskStatus.fromWireName]) instead of an unreadable-parameter error.
     */
    @GetMapping("/{id}/tasks")
    fun tasks(
        @CurrentUser actor: ActingUser,
        @PathVariable id: Long,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "${PageRequest.DEFAULT_PAGE}") page: Int,
        @RequestParam(defaultValue = "${PageRequest.DEFAULT_SIZE}") size: Int,
    ): PageResponse<TaskSummaryDto> =
        projects
            .listTasks(actor.id, ProjectId(id), parseStatuses(status), pageRequestOf(page, size))
            .toResponse { it.toDto() }

    private fun parseStatuses(raw: String?): Set<TaskStatus>? =
        raw
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map { TaskStatus.fromWireName(it) }
            ?.toSet()
            ?.ifEmpty { null }
}
