package hive.adapter.`in`.controller

import hive.adapter.`in`.dto.ProjectSummaryDto
import hive.adapter.`in`.dto.TeamDetailDto
import hive.adapter.`in`.dto.TeamNameRequest
import hive.adapter.`in`.dto.TeamSummaryDto
import hive.adapter.`in`.dto.UserIdRequest
import hive.adapter.`in`.mapper.toDetailDto
import hive.adapter.`in`.mapper.toDto
import hive.adapter.`in`.mapper.toSummaryDto
import hive.adapter.`in`.security.ActingUser
import hive.adapter.`in`.security.CurrentUser
import hive.application.usecase.CreateTeamCommand
import hive.application.usecase.RenameTeamCommand
import hive.application.usecase.TeamUseCases
import hive.domain.model.TeamId
import hive.domain.model.UserId
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Teams -- `docs/api-contract.md` section 3.
 *
 * | Endpoint | Use case |
 * |----------|----------|
 * | `POST /teams` | [TeamUseCases.create] |
 * | `GET /teams/mine` | [TeamUseCases.listMine] |
 * | `GET /teams/{id}` | [TeamUseCases.get] |
 * | `PATCH /teams/{id}` | [TeamUseCases.rename] |
 * | `POST /teams/{id}/members` | [TeamUseCases.addMember] |
 * | `DELETE /teams/{id}/members/{userId}` | [TeamUseCases.removeMember] |
 * | `PUT /teams/{id}/lead` | [TeamUseCases.transferLead] |
 * | `GET /teams/{id}/projects` | [TeamUseCases.listProjects] |
 * | `DELETE /teams/{id}` | -- TM-11: 405, no use case exists |
 *
 * `GET /teams/mine` is mapped before `GET /teams/{id}` for readability only;
 * Spring prefers the literal path over the variable one regardless of order.
 *
 * Note which of these return `TeamDetail` and which return `TeamSummary`: the
 * contract lists members in full on every single-team response and only counts
 * them in the list response, which is why the mapper offers both.
 */
@RestController
@RequestMapping("/api/v1/teams", produces = [MediaType.APPLICATION_JSON_VALUE])
class TeamController(
    private val teams: TeamUseCases,
) {

    /** `POST /teams` -- TM-1: the creator becomes lead and, by INV-1, a member. */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @CurrentUser actor: ActingUser,
        @Valid @RequestBody request: TeamNameRequest,
    ): TeamDetailDto = teams.create(actor.id, CreateTeamCommand(requireNotNull(request.name))).toDetailDto()

    /** `GET /teams/mine` -- TM-4: every team the actor leads or belongs to. */
    @GetMapping("/mine")
    fun mine(
        @CurrentUser actor: ActingUser,
    ): List<TeamSummaryDto> = teams.listMine(actor.id).map { it.toSummaryDto() }

    /** `GET /teams/{id}` -- TM-2/TM-3. 404 when invisible. */
    @GetMapping("/{id}")
    fun get(
        @CurrentUser actor: ActingUser,
        @PathVariable id: Long,
    ): TeamDetailDto = teams.get(actor.id, TeamId(id)).toDetailDto()

    /** `PATCH /teams/{id}` -- TM-5: the lead only. */
    @PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun rename(
        @CurrentUser actor: ActingUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: TeamNameRequest,
    ): TeamDetailDto =
        teams.rename(actor.id, TeamId(id), RenameTeamCommand(requireNotNull(request.name))).toDetailDto()

    /**
     * `POST /teams/{id}/members` -- TM-6: the lead only.
     *
     * 200, not 201: the response is the updated team, not a newly addressable
     * membership resource, and adding an existing member is an idempotent no-op.
     */
    @PostMapping("/{id}/members", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun addMember(
        @CurrentUser actor: ActingUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: UserIdRequest,
    ): TeamDetailDto = teams.addMember(actor.id, TeamId(id), UserId(requireNotNull(request.userId))).toDetailDto()

    /** `DELETE /teams/{id}/members/{userId}` -- TM-7 (409 for the lead) and TM-8. */
    @DeleteMapping("/{id}/members/{userId}")
    fun removeMember(
        @CurrentUser actor: ActingUser,
        @PathVariable id: Long,
        @PathVariable userId: Long,
    ): TeamDetailDto = teams.removeMember(actor.id, TeamId(id), UserId(userId)).toDetailDto()

    /** `PUT /teams/{id}/lead` -- TM-9/TM-10: the new lead auto-joins as a member. */
    @PutMapping("/{id}/lead", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun transferLead(
        @CurrentUser actor: ActingUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: UserIdRequest,
    ): TeamDetailDto = teams.transferLead(actor.id, TeamId(id), UserId(requireNotNull(request.userId))).toDetailDto()

    /** `GET /teams/{id}/projects` -- narrowed to what the caller may see (PR-3). */
    @GetMapping("/{id}/projects")
    fun projects(
        @CurrentUser actor: ActingUser,
        @PathVariable id: Long,
    ): List<ProjectSummaryDto> = teams.listProjects(actor.id, TeamId(id)).map { it.toDto() }
}
