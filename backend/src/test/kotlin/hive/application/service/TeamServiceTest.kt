package hive.application.service

import hive.application.AppFixtures.ASSIGNEE_ID
import hive.application.AppFixtures.FOREIGN_PROJECT
import hive.application.AppFixtures.GHOST_ID
import hive.application.AppFixtures.LEAD_ID
import hive.application.AppFixtures.MEMBER_ID
import hive.application.AppFixtures.OTHER_TEAM_ID
import hive.application.AppFixtures.OUTSIDER
import hive.application.AppFixtures.OUTSIDER_ID
import hive.application.AppFixtures.OWNER
import hive.application.AppFixtures.OWNER_ID
import hive.application.AppFixtures.PROJECT
import hive.application.AppFixtures.PROJECT_ID
import hive.application.AppFixtures.SIBLING_PROJECT
import hive.application.AppFixtures.SIBLING_PROJECT_ID
import hive.application.AppFixtures.TEAM
import hive.application.AppFixtures.TEAM_ID
import hive.application.AppFixtures.task
import hive.application.Harness
import hive.application.usecase.CreateTeamCommand
import hive.application.usecase.RenameTeamCommand
import hive.domain.error.AuthorizationException
import hive.domain.error.ConflictException
import hive.domain.error.NotFoundException
import hive.domain.error.ValidationException
import hive.domain.model.Task
import hive.domain.model.TaskId
import hive.domain.model.TaskStatus
import hive.domain.model.Team
import hive.domain.model.TeamId
import hive.domain.model.UserId
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TeamService")
class TeamServiceTest {

    private val harness = Harness()
    private val service = TeamService(
        harness.teamRepository,
        harness.projectRepository,
        harness.taskRepository,
        harness.userRepository,
        harness.loader,
        harness.views,
    )

    @Nested
    @DisplayName("create (TM-1, INV-1)")
    inner class Create {

        @Test
        fun `the creator becomes the lead and the first member`() {
            val saved = slot<Team>()
            every { harness.teamRepository.save(capture(saved)) } answers {
                firstArg<Team>().copy(id = TeamId(99))
            }

            val view = service.create(OUTSIDER_ID, CreateTeamCommand("  Scouts  "))

            assertThat(saved.captured.id).isNull()
            assertThat(saved.captured.name.value).isEqualTo("Scouts")
            assertThat(saved.captured.teamLead).isEqualTo(OUTSIDER_ID)
            assertThat(saved.captured.memberIds).containsExactly(OUTSIDER_ID)
            assertThat(view.lead).isEqualTo(OUTSIDER)
            assertThat(view.memberCount).isEqualTo(1)
        }

        @Test
        fun `400 on a blank name`() {
            assertThatThrownBy { service.create(OUTSIDER_ID, CreateTeamCommand(" ")) }
                .isInstanceOf(ValidationException::class.java)
        }

        @Test
        fun `404 when the creator has no user row`() {
            every { harness.teamRepository.save(any()) } answers { firstArg<Team>().copy(id = TeamId(99)) }

            assertThatThrownBy { service.create(GHOST_ID, CreateTeamCommand("Ghosts")) }
                .isInstanceOf(NotFoundException::class.java)
        }
    }

    @Nested
    @DisplayName("read (TM-2, TM-3, TM-4)")
    inner class Read {

        @Test
        fun `a member sees the team without the project table being touched`() {
            harness.withStandardWorld()

            val view = service.get(MEMBER_ID, TEAM_ID)

            assertThat(view.team).isEqualTo(TEAM)
            assertThat(view.lead.id).isEqualTo(LEAD_ID)
            assertThat(view.memberCount).isEqualTo(3)
            verify(exactly = 0) { harness.projectRepository.findByTeam(any()) }
        }

        @Test
        fun `members come back in a stable display order`() {
            harness.withStandardWorld()

            val view = service.get(MEMBER_ID, TEAM_ID)

            assertThat(view.members.map { it.name.value })
                .containsExactly("Amos Assignee", "Lena Lead", "Mira Member")
        }

        @Test
        fun `TM-3 the owner of a project of the team may see it, read-only`() {
            harness.withStandardWorld()

            val view = service.get(OWNER_ID, TEAM_ID)

            assertThat(view.team).isEqualTo(TEAM)
            verify { harness.projectRepository.findByTeam(TEAM_ID) }
        }

        @Test
        fun `404 for a stranger who owns nothing of the team`() {
            harness.withStandardWorld()

            assertThatThrownBy { service.get(OUTSIDER_ID, TEAM_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `404 for a team that does not exist`() {
            harness.withTeams()

            assertThatThrownBy { service.get(LEAD_ID, OTHER_TEAM_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `listMine resolves every team's membership in one directory lookup`() {
            val other = Team(OTHER_TEAM_ID, TEAM.name, OUTSIDER_ID, setOf(OUTSIDER_ID, LEAD_ID))
            every { harness.teamRepository.findTeamsForMember(LEAD_ID) } returns listOf(TEAM, other)

            val views = service.listMine(LEAD_ID)

            assertThat(views).hasSize(2)
            assertThat(views[1].lead).isEqualTo(OUTSIDER)
            verify(exactly = 1) { harness.userRepository.findAllById(any()) }
        }

        @Test
        fun `listMine is empty for somebody who belongs to nothing`() {
            every { harness.teamRepository.findTeamsForMember(OUTSIDER_ID) } returns emptyList()

            assertThat(service.listMine(OUTSIDER_ID)).isEmpty()
        }
    }

    @Nested
    @DisplayName("rename (TM-5)")
    inner class Rename {

        @Test
        fun `the lead may rename`() {
            harness.withStandardWorld().echoTeamSaves()

            val view = service.rename(LEAD_ID, TEAM_ID, RenameTeamCommand("Hive Core Renamed"))

            assertThat(view.team.name.value).isEqualTo("Hive Core Renamed")
        }

        @Test
        fun `403 for a plain member`() {
            harness.withStandardWorld()

            assertThatThrownBy { service.rename(MEMBER_ID, TEAM_ID, RenameTeamCommand("Mine Now")) }
                .isInstanceOf(AuthorizationException::class.java)
        }

        @Test
        fun `404 for a stranger, who must not learn the team exists`() {
            harness.withStandardWorld()

            assertThatThrownBy { service.rename(OUTSIDER_ID, TEAM_ID, RenameTeamCommand("Mine Now")) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `400 on a blank name, after the role check has passed`() {
            harness.withStandardWorld()

            assertThatThrownBy { service.rename(LEAD_ID, TEAM_ID, RenameTeamCommand("")) }
                .isInstanceOf(ValidationException::class.java)
        }
    }

    @Nested
    @DisplayName("addMember (TM-6)")
    inner class AddMember {

        @Test
        fun `the lead may add a member`() {
            harness.withStandardWorld().echoTeamSaves()
            every { harness.userRepository.findById(OUTSIDER_ID) } returns OUTSIDER

            val view = service.addMember(LEAD_ID, TEAM_ID, OUTSIDER_ID)

            assertThat(view.team.memberIds).contains(OUTSIDER_ID)
        }

        @Test
        fun `adding an existing member is an idempotent no-op`() {
            harness.withStandardWorld().echoTeamSaves()
            every { harness.userRepository.findById(MEMBER_ID) } returns
                hive.application.AppFixtures.MEMBER

            val view = service.addMember(LEAD_ID, TEAM_ID, MEMBER_ID)

            assertThat(view.team.memberIds).isEqualTo(TEAM.memberIds)
        }

        @Test
        fun `403 for a plain member`() {
            harness.withStandardWorld()

            assertThatThrownBy { service.addMember(MEMBER_ID, TEAM_ID, OUTSIDER_ID) }
                .isInstanceOf(AuthorizationException::class.java)
        }

        @Test
        fun `404 for a stranger`() {
            harness.withStandardWorld()

            assertThatThrownBy { service.addMember(OUTSIDER_ID, TEAM_ID, OUTSIDER_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `400 when the named user does not exist, and only after the 403 is cleared`() {
            harness.withStandardWorld()
            every { harness.userRepository.findById(GHOST_ID) } returns null

            assertThatThrownBy { service.addMember(LEAD_ID, TEAM_ID, GHOST_ID) }
                .isInstanceOf(ValidationException::class.java)

            verify(exactly = 0) { harness.teamRepository.save(any()) }
        }
    }

    @Nested
    @DisplayName("removeMember (TM-7, TM-8)")
    inner class RemoveMember {

        private val liveHere = task(TaskStatus.TODO, ASSIGNEE_ID, TaskId(31))
        private val liveSibling =
            task(TaskStatus.IN_PROGRESS, ASSIGNEE_ID, TaskId(32), SIBLING_PROJECT_ID)
        private val liveElsewhere =
            task(TaskStatus.TODO, ASSIGNEE_ID, TaskId(33), FOREIGN_PROJECT.id!!)

        private fun world() = harness
            .withTeams(TEAM)
            .withProjects(PROJECT, SIBLING_PROJECT, FOREIGN_PROJECT)
            .echoTeamSaves()

        @Test
        fun `the lead may remove a plain member`() {
            world()
            every { harness.taskRepository.findLiveTasksAssignedTo(MEMBER_ID, null) } returns emptyList()

            val view = service.removeMember(LEAD_ID, TEAM_ID, MEMBER_ID)

            assertThat(view.team.memberIds).doesNotContain(MEMBER_ID)
            verify(exactly = 0) { harness.taskRepository.saveAll(any()) }
        }

        @Test
        fun `TM-8 the departing member's live tasks in this team's projects are unassigned`() {
            world()
            every { harness.taskRepository.findLiveTasksAssignedTo(ASSIGNEE_ID, null) } returns
                listOf(liveHere, liveSibling, liveElsewhere)
            val saved = slot<List<Task>>()
            every { harness.taskRepository.saveAll(capture(saved)) } answers { firstArg() }

            service.removeMember(LEAD_ID, TEAM_ID, ASSIGNEE_ID)

            assertThat(saved.captured.map { it.id }).containsExactly(TaskId(31), TaskId(32))
            assertThat(saved.captured).allSatisfy { assertThat(it.assignee).isNull() }
        }

        @Test
        fun `TM-8 leaves tasks in other teams' projects alone`() {
            world()
            every { harness.taskRepository.findLiveTasksAssignedTo(ASSIGNEE_ID, null) } returns
                listOf(liveElsewhere)

            service.removeMember(LEAD_ID, TEAM_ID, ASSIGNEE_ID)

            verify(exactly = 0) { harness.taskRepository.saveAll(any()) }
        }

        @Test
        fun `TM-8 skips the task query entirely when the team owns no projects`() {
            harness.withTeams(TEAM).withProjects().echoTeamSaves()

            service.removeMember(LEAD_ID, TEAM_ID, MEMBER_ID)

            verify(exactly = 0) { harness.taskRepository.findLiveTasksAssignedTo(any(), any()) }
        }

        @Test
        fun `409 when the target is the current lead (TM-7, INV-1)`() {
            world()

            assertThatThrownBy { service.removeMember(LEAD_ID, TEAM_ID, LEAD_ID) }
                .isInstanceOf(ConflictException::class.java)
                .hasMessageContaining("Transfer the lead role first")

            verify(exactly = 0) { harness.taskRepository.saveAll(any()) }
        }

        @Test
        fun `403 for a plain member`() {
            world()

            assertThatThrownBy { service.removeMember(MEMBER_ID, TEAM_ID, ASSIGNEE_ID) }
                .isInstanceOf(AuthorizationException::class.java)
        }

        @Test
        fun `404 for a stranger`() {
            world()

            assertThatThrownBy { service.removeMember(OUTSIDER_ID, TEAM_ID, ASSIGNEE_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `409 is answered before 403, because a conflict does not depend on who asks`() {
            world()

            assertThatThrownBy { service.removeMember(MEMBER_ID, TEAM_ID, LEAD_ID) }
                .isInstanceOf(ConflictException::class.java)
        }
    }

    @Nested
    @DisplayName("transferLead (TM-9, TM-10)")
    inner class TransferLead {

        @Test
        fun `the new lead joins as a member and the outgoing lead stays one`() {
            harness.withStandardWorld().echoTeamSaves()
            every { harness.userRepository.findById(OUTSIDER_ID) } returns OUTSIDER

            val view = service.transferLead(LEAD_ID, TEAM_ID, OUTSIDER_ID)

            assertThat(view.team.teamLead).isEqualTo(OUTSIDER_ID)
            assertThat(view.team.memberIds).contains(OUTSIDER_ID, LEAD_ID)
        }

        @Test
        fun `403 for a plain member`() {
            harness.withStandardWorld()

            assertThatThrownBy { service.transferLead(MEMBER_ID, TEAM_ID, MEMBER_ID) }
                .isInstanceOf(AuthorizationException::class.java)
        }

        @Test
        fun `404 for a stranger`() {
            harness.withStandardWorld()

            assertThatThrownBy { service.transferLead(OUTSIDER_ID, TEAM_ID, OUTSIDER_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `400 when the incoming lead does not exist`() {
            harness.withStandardWorld()
            every { harness.userRepository.findById(GHOST_ID) } returns null

            assertThatThrownBy { service.transferLead(LEAD_ID, TEAM_ID, GHOST_ID) }
                .isInstanceOf(ValidationException::class.java)

            verify(exactly = 0) { harness.teamRepository.save(any()) }
        }
    }

    @Nested
    @DisplayName("listProjects (PR-3)")
    inner class ListProjects {

        @Test
        fun `a member sees every project of the team, including ones they do not own`() {
            harness.withTeams(TEAM).withProjects(PROJECT, SIBLING_PROJECT)

            val views = service.listProjects(MEMBER_ID, TEAM_ID)

            assertThat(views.map { it.project.id }).containsExactly(PROJECT_ID, SIBLING_PROJECT_ID)
            assertThat(views[0].owner.id).isEqualTo(OWNER_ID)
            assertThat(views[0].team.team).isEqualTo(TEAM)
        }

        @Test
        fun `a TM-3 viewer sees only the project that let them see the team at all`() {
            harness.withTeams(TEAM).withProjects(PROJECT, SIBLING_PROJECT)

            val views = service.listProjects(OWNER_ID, TEAM_ID)

            assertThat(views.map { it.project.id }).containsExactly(PROJECT_ID)
        }

        @Test
        fun `404 for a stranger`() {
            harness.withTeams(TEAM).withProjects(PROJECT)

            assertThatThrownBy { service.listProjects(OUTSIDER_ID, TEAM_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `an empty team yields an empty list without any further lookups`() {
            harness.withTeams(TEAM).withProjects()

            assertThat(service.listProjects(MEMBER_ID, TEAM_ID)).isEmpty()
        }
    }

    @Nested
    @DisplayName("membership bookkeeping")
    inner class Bookkeeping {

        @Test
        fun `a team whose lead has no user row is a broken reference, reported as 404`() {
            val orphan = Team(TeamId(12), TEAM.name, UserId(4242))
            harness.withTeams(orphan).withProjects()

            assertThatThrownBy { service.get(UserId(4242), TeamId(12)) }
                .isInstanceOf(NotFoundException::class.java)
        }
    }
}
