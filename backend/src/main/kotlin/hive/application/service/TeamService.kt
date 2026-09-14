package hive.application.service

import hive.application.support.ContextLoader
import hive.application.support.ViewAssembler
import hive.application.usecase.CreateTeamCommand
import hive.application.usecase.RenameTeamCommand
import hive.application.usecase.TeamUseCases
import hive.application.view.ProjectView
import hive.application.view.TeamView
import hive.domain.error.ValidationException
import hive.domain.model.Project
import hive.domain.model.Team
import hive.domain.model.TeamId
import hive.domain.model.TeamName
import hive.domain.model.UserId
import hive.domain.policy.AuthorizationPolicy
import hive.domain.port.ProjectRepository
import hive.domain.port.TaskRepository
import hive.domain.port.TeamRepository
import hive.domain.port.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * TM-1..TM-11.
 *
 * Every mutating method is the same five steps in the same order, which is the
 * point: load, gate visibility (404), ask the policy (409 then 403), mutate the
 * aggregate, save. The service never decides who may do what -- if a line here
 * looked like `if (team.teamLead == actor)` it would belong in
 * [AuthorizationPolicy] instead.
 */
@Service
class TeamService(
    private val teamRepository: TeamRepository,
    private val projectRepository: ProjectRepository,
    private val taskRepository: TaskRepository,
    private val userRepository: UserRepository,
    private val loader: ContextLoader,
    private val views: ViewAssembler,
) : TeamUseCases {

    /** TM-1/INV-1: the creator is the lead, and [Team] puts the lead in the member set itself. */
    @Transactional
    override fun create(actor: UserId, command: CreateTeamCommand): TeamView {
        val team = Team(id = null, name = TeamName(command.name), teamLead = actor)
        return views.teamView(teamRepository.save(team))
    }

    /** TM-4: `findTeamsForMember` already includes teams [actor] leads, by INV-1. */
    @Transactional(readOnly = true)
    override fun listMine(actor: UserId): List<TeamView> =
        views.teamViews(teamRepository.findTeamsForMember(actor))

    @Transactional(readOnly = true)
    override fun get(actor: UserId, teamId: TeamId): TeamView =
        views.teamView(loader.requireVisibleTeam(teamId, actor))

    /** TM-5. */
    @Transactional
    override fun rename(actor: UserId, teamId: TeamId, command: RenameTeamCommand): TeamView {
        val team = loader.requireVisibleTeam(teamId, actor)
        AuthorizationPolicy.checkRenameTeam(team, actor)
        return views.teamView(teamRepository.save(team.rename(TeamName(command.name))))
    }

    /**
     * TM-6. The 400 for an unknown user comes *after* the policy's 403, keeping
     * the documented order: a non-lead learns that they are not the lead, not
     * which user ids exist.
     */
    @Transactional
    override fun addMember(actor: UserId, teamId: TeamId, member: UserId): TeamView {
        val team = loader.requireVisibleTeam(teamId, actor)
        AuthorizationPolicy.checkAddMember(team, actor, member)
        requireExistingUser(member)
        return views.teamView(teamRepository.save(team.addMember(member)))
    }

    /**
     * TM-7 and TM-8.
     *
     * The policy answers TM-7 (409 for the lead, then 403 for a non-lead).
     * TM-8 is the consequence this service applies once that passes: work still
     * assigned to somebody who is no longer a member would violate AS-2 and
     * would be invisible to the lead's unassigned queue, so it is handed back to
     * that queue in the same transaction as the removal.
     *
     * Removing somebody who is not a member is an idempotent no-op, mirroring
     * [Team.addMember]; the contract lists no 400 for this endpoint.
     */
    @Transactional
    override fun removeMember(actor: UserId, teamId: TeamId, member: UserId): TeamView {
        val team = loader.requireVisibleTeam(teamId, actor)
        AuthorizationPolicy.checkRemoveMember(team, actor, member)

        unassignLiveTasksOf(member, teamId)
        return views.teamView(teamRepository.save(team.removeMember(member)))
    }

    /** TM-9/TM-10: [Team.transferLeadTo] enrols the new lead and keeps the old one a member. */
    @Transactional
    override fun transferLead(actor: UserId, teamId: TeamId, newLead: UserId): TeamView {
        val team = loader.requireVisibleTeam(teamId, actor)
        AuthorizationPolicy.checkTransferLead(team, actor, newLead)
        requireExistingUser(newLead)
        return views.teamView(teamRepository.save(team.transferLeadTo(newLead)))
    }

    /**
     * PR-3 applied to one team's projects.
     *
     * Filtering here rather than in a port is deliberate and is not the
     * "fetch the world and sieve it" pattern: `findByTeam` is already scoped to
     * one team, and the remaining question -- which of *these* the caller may
     * see -- is answered by the policy, not re-derived. It matters only for TM-3
     * viewers, who can see the team solely because they own a project of it and
     * must not learn about the rest.
     */
    @Transactional(readOnly = true)
    override fun listProjects(actor: UserId, teamId: TeamId): List<ProjectView> {
        val team = loader.requireVisibleTeam(teamId, actor)
        val visible = projectRepository.findByTeam(teamId)
            .filter { AuthorizationPolicy.canViewProject(it, team, actor) }
        return views.projectViews(visible)
    }

    /**
     * TM-8: hand every live task the departing member holds in this team's
     * projects back to the unassigned queue.
     *
     * One query for the team's projects and one for the member's live tasks.
     * The port cannot express "live tasks of this user *in this team*" -- it
     * scopes to a single project or to none -- so the team's project ids are
     * applied to a result set that is already narrowed to one user's live work,
     * rather than to a table scan.
     */
    private fun unassignLiveTasksOf(member: UserId, teamId: TeamId) {
        val projectIds = projectRepository.findByTeam(teamId).mapNotNullTo(mutableSetOf(), Project::id)
        if (projectIds.isEmpty()) {
            return
        }
        val unassigned = taskRepository.findLiveTasksAssignedTo(member, projectId = null)
            .filter { it.projectId in projectIds }
            .map { it.assignTo(null) }
        if (unassigned.isNotEmpty()) {
            taskRepository.saveAll(unassigned)
        }
    }

    /** AS-2's sibling for team membership: a user id that names nobody is a 400, not a 403. */
    private fun requireExistingUser(userId: UserId) {
        if (userRepository.findById(userId) == null) {
            throw ValidationException("userId", "must be an existing user.")
        }
    }
}
