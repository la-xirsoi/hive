package hive.application.usecase

import hive.application.view.ProjectView
import hive.application.view.TaskSummaryView
import hive.domain.model.Page
import hive.domain.model.PageRequest
import hive.domain.model.ProjectId
import hive.domain.model.TaskStatus
import hive.domain.model.UserId

/**
 * Inbound port for section 4 of `docs/api-contract.md`.
 *
 * | Endpoint | Method |
 * |----------|--------|
 * | `POST /projects` | [create] |
 * | `GET /projects/mine` | [listMine] |
 * | `GET /projects/{id}` | [get] |
 * | `PATCH /projects/{id}` | [rename] |
 * | `PUT /projects/{id}/owner` | [transferOwner] |
 * | `GET /projects/{id}/tasks` | [listTasks] |
 * | `DELETE /projects/{id}` | -- PR-10, unsupported: the adapter answers 405 |
 *
 * PR-9 (move a project to another team) has no method here on purpose: the
 * domain [hive.domain.model.Project] offers no such operation, because the spec
 * gives no semantics for the fate of in-flight assignments.
 */
interface ProjectUseCases {

    /**
     * `POST /projects` -- PR-1/PR-2: the creator owns the new project and must
     * be a member or the lead of the target team.
     *
     * @throws hive.domain.error.NotFoundException (404) if the team is invisible
     *   to [actor] -- a stranger must not learn that it exists.
     * @throws hive.domain.error.AuthorizationException (403) if [actor] can see
     *   the team but does not belong to it.
     * @throws hive.domain.error.ValidationException (400) invalid name.
     */
    fun create(actor: UserId, command: CreateProjectCommand): ProjectView

    /** `GET /projects/mine` -- PR-4: projects [actor] owns, plus those of teams they lead or belong to. */
    fun listMine(actor: UserId): List<ProjectView>

    /**
     * `GET /projects/{id}` -- PR-3: the owner, and the members and lead of the
     * project's team.
     *
     * @throws hive.domain.error.NotFoundException (404) missing or invisible.
     */
    fun get(actor: UserId, projectId: ProjectId): ProjectView

    /**
     * `PATCH /projects/{id}` -- PR-5: the owner only.
     *
     * @throws hive.domain.error.NotFoundException (404) missing or invisible.
     * @throws hive.domain.error.AuthorizationException (403) not the owner.
     * @throws hive.domain.error.ValidationException (400) invalid name.
     */
    fun rename(actor: UserId, projectId: ProjectId, command: RenameProjectCommand): ProjectView

    /**
     * `PUT /projects/{id}/owner` -- PR-6/PR-7/PR-8: the current owner hands the
     * project to any existing user, unless that user is the assignee of live
     * tasks in this project -- accepting would instantly violate AS-4.
     *
     * @throws hive.domain.error.NotFoundException (404) missing or invisible.
     * @throws hive.domain.error.ConflictException (409) PR-8, naming the
     *   blocking tasks.
     * @throws hive.domain.error.AuthorizationException (403) not the owner.
     * @throws hive.domain.error.ValidationException (400) no such user.
     */
    fun transferOwner(actor: UserId, projectId: ProjectId, newOwner: UserId): ProjectView

    /**
     * `GET /projects/{id}/tasks` -- the tasks of this project that [actor] may
     * see under VIS-1..VIS-5, never the project's full list.
     *
     * @param statuses an optional narrowing filter. It only ever *removes* rows;
     *   it can never widen visibility.
     * @throws hive.domain.error.NotFoundException (404) missing or invisible project.
     */
    fun listTasks(
        actor: UserId,
        projectId: ProjectId,
        statuses: Set<TaskStatus>? = null,
        page: PageRequest = PageRequest.DEFAULT,
    ): Page<TaskSummaryView>
}
