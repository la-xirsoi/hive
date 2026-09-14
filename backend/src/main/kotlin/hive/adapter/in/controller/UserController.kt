package hive.adapter.`in`.controller

import hive.adapter.`in`.dto.PageResponse
import hive.adapter.`in`.dto.UpdateMeRequest
import hive.adapter.`in`.dto.UserSummaryDto
import hive.adapter.`in`.dto.pageRequestOf
import hive.adapter.`in`.mapper.toResponse
import hive.adapter.`in`.mapper.toSummaryDto
import hive.adapter.`in`.security.ActingUser
import hive.adapter.`in`.security.CurrentUser
import hive.application.usecase.RenameUserCommand
import hive.application.usecase.UserUseCases
import hive.domain.model.PageRequest
import hive.domain.model.UserId
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Users -- `docs/api-contract.md` section 2.
 *
 * | Endpoint | Use case |
 * |----------|----------|
 * | `GET /users/me` | [UserUseCases.getById] on the resolved actor |
 * | `PATCH /users/me` | [UserUseCases.renameSelf] |
 * | `GET /users?query=` | [UserUseCases.search] |
 * | `GET /users/{id}` | [UserUseCases.getById] |
 *
 * `GET /users/me` looks like it should call `provisionFromPrincipal`, and it
 * does -- indirectly. Resolving `@CurrentUser` *is* the provisioning step
 * (US-3), so by the time this method runs the row exists and the only thing
 * left is to read it back. Doing it this way means every endpoint provisions on
 * first sight, not just this one, so the frontend's first request can be any
 * request.
 */
@RestController
@RequestMapping("/api/v1/users", produces = [MediaType.APPLICATION_JSON_VALUE])
class UserController(
    private val users: UserUseCases,
) {

    /** `GET /users/me` -- the acting user, provisioned on first sight (US-3). */
    @GetMapping("/me")
    fun me(
        @CurrentUser actor: ActingUser,
    ): UserSummaryDto = users.getById(actor.id, actor.id).toSummaryDto()

    /** `PATCH /users/me` -- US-5: the display name only; sending `email` is a 400. */
    @PatchMapping("/me", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun updateMe(
        @CurrentUser actor: ActingUser,
        @Valid @RequestBody request: UpdateMeRequest,
    ): UserSummaryDto = users.renameSelf(actor.id, RenameUserCommand(requireNotNull(request.name))).toSummaryDto()

    /** `GET /users?query=` -- US-4: the directory, readable by any authenticated caller. */
    @GetMapping
    fun search(
        @CurrentUser actor: ActingUser,
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "${PageRequest.DEFAULT_PAGE}") page: Int,
        @RequestParam(defaultValue = "${PageRequest.DEFAULT_SIZE}") size: Int,
    ): PageResponse<UserSummaryDto> =
        users.search(actor.id, query, pageRequestOf(page, size)).toResponse { it.toSummaryDto() }

    /** `GET /users/{id}` -- US-4. 404 if there is no such user. */
    @GetMapping("/{id}")
    fun byId(
        @CurrentUser actor: ActingUser,
        @PathVariable id: Long,
    ): UserSummaryDto = users.getById(actor.id, UserId(id)).toSummaryDto()
}
