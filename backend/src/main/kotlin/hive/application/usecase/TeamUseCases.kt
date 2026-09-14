package hive.application.usecase

import hive.application.view.ProjectView
import hive.application.view.TeamView
import hive.domain.model.TeamId
import hive.domain.model.UserId

/**
 * Inbound port for section 3 of `docs/api-contract.md`.
 *
 * | Endpoint | Method |
 * |----------|--------|
 * | `POST /teams` | [create] |
 * | `GET /teams/mine` | [listMine] |
 * | `GET /teams/{id}` | [get] |
 * | `PATCH /teams/{id}` | [rename] |
 * | `POST /teams/{id}/members` | [addMember] |
 * | `DELETE /teams/{id}/members/{userId}` | [removeMember] |
 * | `PUT /teams/{id}/lead` | [transferLead] |
 * | `GET /teams/{id}/projects` | [listProjects] |
 * | `DELETE /teams/{id}` | -- TM-11, unsupported: the adapter answers 405 |
 *
 * Every mutating operation follows the one ordering the whole system uses:
 * load, check visibility (404), ask the policy (409 then 403), mutate, save.
 * Not one of them decides a rule for itself.
 */
interface TeamUseCases {

    /**
     * `POST /teams` -- TM-1: any authenticated user may create a team. The
     * creator becomes its lead and, by INV-1, its first member.
     *
     * @throws hive.domain.error.ValidationException (400) if the name is invalid.
     * @throws hive.domain.error.NotFoundException (404) if [actor] is unknown.
     */
    fun create(actor: UserId, command: CreateTeamCommand): TeamView

    /** `GET /teams/mine` -- TM-4: every team [actor] leads or belongs to. */
    fun listMine(actor: UserId): List<TeamView>

    /**
     * `GET /teams/{id}` -- TM-2/TM-3: members, the lead, and the owner of any
     * project of this team may see it.
     *
     * @throws hive.domain.error.NotFoundException (404) if the team does not
     *   exist **or** is invisible to [actor].
     */
    fun get(actor: UserId, teamId: TeamId): TeamView

    /**
     * `PATCH /teams/{id}` -- TM-5: the lead only.
     *
     * @throws hive.domain.error.NotFoundException (404) invisible team.
     * @throws hive.domain.error.AuthorizationException (403) not the lead.
     * @throws hive.domain.error.ValidationException (400) invalid name.
     */
    fun rename(actor: UserId, teamId: TeamId, command: RenameTeamCommand): TeamView

    /**
     * `POST /teams/{id}/members` -- TM-6: the lead only. Adding an existing
     * member is an idempotent no-op, not a conflict.
     *
     * @throws hive.domain.error.NotFoundException (404) invisible team.
     * @throws hive.domain.error.AuthorizationException (403) not the lead.
     * @throws hive.domain.error.ValidationException (400) no such user.
     */
    fun addMember(actor: UserId, teamId: TeamId, member: UserId): TeamView

    /**
     * `DELETE /teams/{id}/members/{userId}` -- TM-7 and TM-8: the lead only, the
     * current lead can never be removed, and the departing member's live
     * (`Todo` / `In Progress`) tasks in this team's projects are unassigned so
     * they return to the unassigned queue rather than staying with a stranger.
     *
     * @throws hive.domain.error.NotFoundException (404) invisible team.
     * @throws hive.domain.error.ConflictException (409) [member] is the lead.
     * @throws hive.domain.error.AuthorizationException (403) not the lead.
     */
    fun removeMember(actor: UserId, teamId: TeamId, member: UserId): TeamView

    /**
     * `PUT /teams/{id}/lead` -- TM-9/TM-10: the current lead only. The new lead
     * joins as a member if they were not one; the outgoing lead stays a member.
     *
     * @throws hive.domain.error.NotFoundException (404) invisible team.
     * @throws hive.domain.error.AuthorizationException (403) not the lead.
     * @throws hive.domain.error.ValidationException (400) no such user.
     */
    fun transferLead(actor: UserId, teamId: TeamId, newLead: UserId): TeamView

    /**
     * `GET /teams/{id}/projects` -- the team's projects, narrowed to the ones
     * [actor] may see under PR-3.
     *
     * The narrowing matters for TM-3 viewers: somebody who can see the team only
     * because they own a project of it must not learn about the others.
     *
     * @throws hive.domain.error.NotFoundException (404) invisible team.
     */
    fun listProjects(actor: UserId, teamId: TeamId): List<ProjectView>
}
