package hive.application.service

import hive.application.AppFixtures.ASSIGNEE
import hive.application.AppFixtures.ASSIGNEE_ID
import hive.application.AppFixtures.FOREIGN_PROJECT
import hive.application.AppFixtures.GHOST_ID
import hive.application.AppFixtures.LEAD_ID
import hive.application.AppFixtures.MEMBER_ID
import hive.application.AppFixtures.OTHER_TEAM_ID
import hive.application.AppFixtures.OUTSIDER_ID
import hive.application.AppFixtures.OWNER_ID
import hive.application.AppFixtures.PROJECT
import hive.application.AppFixtures.PROJECT_ID
import hive.application.AppFixtures.SIBLING_PROJECT
import hive.application.AppFixtures.TEAM
import hive.application.AppFixtures.TEAM_ID
import hive.application.AppFixtures.pageOf
import hive.application.AppFixtures.task
import hive.application.Harness
import hive.application.usecase.CreateProjectCommand
import hive.application.usecase.RenameProjectCommand
import hive.domain.error.AuthorizationException
import hive.domain.error.ConflictException
import hive.domain.error.NotFoundException
import hive.domain.error.ValidationException
import hive.domain.model.Page
import hive.domain.model.PageRequest
import hive.domain.model.Project
import hive.domain.model.ProjectId
import hive.domain.model.TaskId
import hive.domain.model.TaskStatus
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ProjectService")
class ProjectServiceTest {

    private val harness = Harness()
    private val service = ProjectService(
        harness.projectRepository,
        harness.taskRepository,
        harness.userRepository,
        harness.loader,
        harness.views,
    )

    @Nested
    @DisplayName("create (PR-1, PR-2)")
    inner class Create {

        @Test
        fun `a team member creates a project and owns it`() {
            harness.withTeams(TEAM).withProjects(PROJECT)
            val saved = slot<Project>()
            every { harness.projectRepository.save(capture(saved)) } answers {
                firstArg<Project>().copy(id = ProjectId(99))
            }

            val view = service.create(MEMBER_ID, CreateProjectCommand("  Nectar  ", TEAM_ID))

            assertThat(saved.captured.id).isNull()
            assertThat(saved.captured.name.value).isEqualTo("Nectar")
            assertThat(saved.captured.projectOwner).isEqualTo(MEMBER_ID)
            assertThat(saved.captured.teamId).isEqualTo(TEAM_ID)
            assertThat(view.owner.id).isEqualTo(MEMBER_ID)
            assertThat(view.team.team).isEqualTo(TEAM)
        }

        @Test
        fun `403 when the creator can see the team but does not belong to it (PR-2)`() {
            harness.withTeams(TEAM).withProjects(PROJECT)

            assertThatThrownBy { service.create(OWNER_ID, CreateProjectCommand("Nectar", TEAM_ID)) }
                .isInstanceOf(AuthorizationException::class.java)
        }

        @Test
        fun `404 when the team is invisible, so a stranger never learns it exists`() {
            harness.withTeams(TEAM).withProjects(PROJECT)

            assertThatThrownBy { service.create(OUTSIDER_ID, CreateProjectCommand("Nectar", TEAM_ID)) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `404 when the team does not exist`() {
            harness.withTeams().withProjects()

            assertThatThrownBy { service.create(MEMBER_ID, CreateProjectCommand("Nectar", OTHER_TEAM_ID)) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `400 on a blank name, after the role check`() {
            harness.withTeams(TEAM).withProjects(PROJECT)

            assertThatThrownBy { service.create(MEMBER_ID, CreateProjectCommand("  ", TEAM_ID)) }
                .isInstanceOf(ValidationException::class.java)
        }
    }

    @Nested
    @DisplayName("read (PR-3, PR-4)")
    inner class Read {

        @Test
        fun `the owner sees the project even though they do not belong to its team (INV-2)`() {
            harness.withTeams(TEAM).withProjects(PROJECT)

            val view = service.get(OWNER_ID, PROJECT_ID)

            assertThat(view.project).isEqualTo(PROJECT)
            assertThat(view.owner.id).isEqualTo(OWNER_ID)
            assertThat(view.team.memberCount).isEqualTo(3)
        }

        @Test
        fun `a team member sees the project`() {
            harness.withTeams(TEAM).withProjects(PROJECT)

            assertThat(service.get(MEMBER_ID, PROJECT_ID).project).isEqualTo(PROJECT)
        }

        @Test
        fun `404 for a stranger`() {
            harness.withTeams(TEAM).withProjects(PROJECT)

            assertThatThrownBy { service.get(OUTSIDER_ID, PROJECT_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `404 when the project does not exist`() {
            harness.withTeams(TEAM).withProjects()

            assertThatThrownBy { service.get(OWNER_ID, PROJECT_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `a project pointing at a missing team is a broken reference, reported as 404`() {
            harness.withTeams().withProjects(PROJECT)

            assertThatThrownBy { service.get(OWNER_ID, PROJECT_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `listMine trusts the port for the PR-4 union and loads each team once`() {
            every { harness.projectRepository.findVisibleTo(LEAD_ID) } returns
                listOf(PROJECT, SIBLING_PROJECT)
            harness.withTeams(TEAM)

            val views = service.listMine(LEAD_ID)

            assertThat(views.map { it.project.id }).containsExactly(PROJECT_ID, SIBLING_PROJECT.id)
            verify(exactly = 1) { harness.teamRepository.findById(TEAM_ID) }
            verify(exactly = 1) { harness.userRepository.findAllById(any()) }
        }

        @Test
        fun `listMine is empty for somebody who sees nothing`() {
            every { harness.projectRepository.findVisibleTo(OUTSIDER_ID) } returns emptyList()

            assertThat(service.listMine(OUTSIDER_ID)).isEmpty()
        }

        @Test
        fun `listMine reports a dangling team reference as 404`() {
            every { harness.projectRepository.findVisibleTo(OWNER_ID) } returns listOf(PROJECT)
            harness.withTeams()

            assertThatThrownBy { service.listMine(OWNER_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }
    }

    @Nested
    @DisplayName("rename (PR-5)")
    inner class Rename {

        @Test
        fun `the owner may rename`() {
            harness.withTeams(TEAM).withProjects(PROJECT).echoProjectSaves()

            val view = service.rename(OWNER_ID, PROJECT_ID, RenameProjectCommand("Apiary II"))

            assertThat(view.project.name.value).isEqualTo("Apiary II")
        }

        @Test
        fun `403 for the team lead, who can see it but does not own it`() {
            harness.withTeams(TEAM).withProjects(PROJECT)

            assertThatThrownBy { service.rename(LEAD_ID, PROJECT_ID, RenameProjectCommand("Mine")) }
                .isInstanceOf(AuthorizationException::class.java)
        }

        @Test
        fun `404 for a stranger`() {
            harness.withTeams(TEAM).withProjects(PROJECT)

            assertThatThrownBy { service.rename(OUTSIDER_ID, PROJECT_ID, RenameProjectCommand("Mine")) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `400 on a blank name`() {
            harness.withTeams(TEAM).withProjects(PROJECT)

            assertThatThrownBy { service.rename(OWNER_ID, PROJECT_ID, RenameProjectCommand(" ")) }
                .isInstanceOf(ValidationException::class.java)
        }
    }

    @Nested
    @DisplayName("transferOwner (PR-6, PR-7, PR-8)")
    inner class TransferOwner {

        @Test
        fun `the owner hands the project to any existing user, membership not required`() {
            harness.withTeams(TEAM).withProjects(PROJECT).echoProjectSaves()
            every { harness.taskRepository.findLiveTasksAssignedTo(ASSIGNEE_ID, PROJECT_ID) } returns
                emptyList()
            every { harness.userRepository.findById(ASSIGNEE_ID) } returns ASSIGNEE

            val view = service.transferOwner(OWNER_ID, PROJECT_ID, ASSIGNEE_ID)

            assertThat(view.project.projectOwner).isEqualTo(ASSIGNEE_ID)
        }

        @Test
        fun `PR-8 409 naming the blocking tasks when the incoming owner holds live work here`() {
            harness.withTeams(TEAM).withProjects(PROJECT)
            every { harness.taskRepository.findLiveTasksAssignedTo(ASSIGNEE_ID, PROJECT_ID) } returns
                listOf(
                    task(TaskStatus.TODO, ASSIGNEE_ID, TaskId(31), name = "Requeen hive 4"),
                    task(TaskStatus.IN_PROGRESS, ASSIGNEE_ID, TaskId(32), name = "Split the brood box"),
                )

            assertThatThrownBy { service.transferOwner(OWNER_ID, PROJECT_ID, ASSIGNEE_ID) }
                .isInstanceOf(ConflictException::class.java)
                .hasMessageContaining("Requeen hive 4")
                .hasMessageContaining("Split the brood box")

            verify(exactly = 0) { harness.projectRepository.save(any()) }
        }

        @Test
        fun `PR-8 is answered before PR-6, so a non-owner still gets the conflict`() {
            harness.withTeams(TEAM).withProjects(PROJECT)
            every { harness.taskRepository.findLiveTasksAssignedTo(ASSIGNEE_ID, PROJECT_ID) } returns
                listOf(task(TaskStatus.TODO, ASSIGNEE_ID, TaskId(31)))

            assertThatThrownBy { service.transferOwner(LEAD_ID, PROJECT_ID, ASSIGNEE_ID) }
                .isInstanceOf(ConflictException::class.java)
        }

        @Test
        fun `403 for somebody who can see the project but does not own it`() {
            harness.withTeams(TEAM).withProjects(PROJECT)
            every { harness.taskRepository.findLiveTasksAssignedTo(MEMBER_ID, PROJECT_ID) } returns
                emptyList()

            assertThatThrownBy { service.transferOwner(LEAD_ID, PROJECT_ID, MEMBER_ID) }
                .isInstanceOf(AuthorizationException::class.java)
        }

        @Test
        fun `404 for a stranger`() {
            harness.withTeams(TEAM).withProjects(PROJECT)
            every { harness.taskRepository.findLiveTasksAssignedTo(any(), any()) } returns emptyList()

            assertThatThrownBy { service.transferOwner(OUTSIDER_ID, PROJECT_ID, MEMBER_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `400 when the incoming owner does not exist (PR-7)`() {
            harness.withTeams(TEAM).withProjects(PROJECT)
            every { harness.taskRepository.findLiveTasksAssignedTo(GHOST_ID, PROJECT_ID) } returns emptyList()
            every { harness.userRepository.findById(GHOST_ID) } returns null

            assertThatThrownBy { service.transferOwner(OWNER_ID, PROJECT_ID, GHOST_ID) }
                .isInstanceOf(ValidationException::class.java)

            verify(exactly = 0) { harness.projectRepository.save(any()) }
        }
    }

    @Nested
    @DisplayName("listTasks")
    inner class ListTasks {

        private val todo = task(TaskStatus.TODO, ASSIGNEE_ID, TaskId(31))
        private val inProgress = task(TaskStatus.IN_PROGRESS, ASSIGNEE_ID, TaskId(32))
        private val canceled = task(TaskStatus.CANCELED, null, TaskId(33))

        @Test
        fun `returns the port's visible slice, resolved into summaries`() {
            harness.withTeams(TEAM).withProjects(PROJECT)
            every { harness.taskRepository.findVisibleInProject(PROJECT_ID, MEMBER_ID, page = PageRequest.DEFAULT) } returns
                pageOf(todo, inProgress)

            val page = service.listTasks(MEMBER_ID, PROJECT_ID)

            assertThat(page.content.map { it.task.id }).containsExactly(TaskId(31), TaskId(32))
            assertThat(page.content[0].projectName).isEqualTo(PROJECT.name)
            assertThat(page.content[0].assignee).isEqualTo(ASSIGNEE)
            assertThat(page.totalElements).isEqualTo(2)
        }

        @Test
        fun `an unassigned row carries a null assignee rather than a hole`() {
            harness.withTeams(TEAM).withProjects(PROJECT)
            every { harness.taskRepository.findVisibleInProject(PROJECT_ID, OWNER_ID, page = PageRequest.DEFAULT) } returns
                pageOf(canceled)

            val page = service.listTasks(OWNER_ID, PROJECT_ID)

            assertThat(page.content.single().assignee).isNull()
        }

        @Test
        fun `the status filter is handed to the port, not applied to its result`() {
            harness.withTeams(TEAM).withProjects(PROJECT)
            val wanted = setOf(TaskStatus.TODO)
            every {
                harness.taskRepository.findVisibleInProject(
                    PROJECT_ID,
                    MEMBER_ID,
                    wanted,
                    PageRequest.DEFAULT,
                )
            } returns pageOf(todo)

            val page = service.listTasks(MEMBER_ID, PROJECT_ID, statuses = wanted)

            assertThat(page.content.map { it.task.id }).containsExactly(TaskId(31))
            // The point of the test: the narrowing reached the query. Filtering
            // the returned page instead would leave totalElements describing the
            // unfiltered set and could yield a page shorter than `size`.
            verify(exactly = 1) {
                harness.taskRepository.findVisibleInProject(
                    PROJECT_ID,
                    MEMBER_ID,
                    wanted,
                    PageRequest.DEFAULT,
                )
            }
        }

        @Test
        fun `a filtered page reports the filtered total, not the unfiltered one`() {
            harness.withTeams(TEAM).withProjects(PROJECT)
            val wanted = setOf(TaskStatus.TODO)
            every {
                harness.taskRepository.findVisibleInProject(
                    PROJECT_ID,
                    MEMBER_ID,
                    wanted,
                    PageRequest.DEFAULT,
                )
            } returns Page.of(listOf(todo), PageRequest.DEFAULT, 1L)

            val page = service.listTasks(MEMBER_ID, PROJECT_ID, statuses = wanted)

            assertThat(page.totalElements)
                .describedAs("the count query carries the same predicate as the page query")
                .isEqualTo(1)
        }

        @Test
        fun `an empty status set is passed through as no filter`() {
            harness.withTeams(TEAM).withProjects(PROJECT)
            every {
                harness.taskRepository.findVisibleInProject(
                    PROJECT_ID,
                    MEMBER_ID,
                    emptySet(),
                    PageRequest.DEFAULT,
                )
            } returns pageOf(todo, inProgress)

            val page = service.listTasks(MEMBER_ID, PROJECT_ID, statuses = emptySet())

            assertThat(page.content).hasSize(2)
        }

        @Test
        fun `404 for a stranger, without ever querying the tasks`() {
            harness.withTeams(TEAM).withProjects(PROJECT)

            assertThatThrownBy { service.listTasks(OUTSIDER_ID, PROJECT_ID) }
                .isInstanceOf(NotFoundException::class.java)

            verify(exactly = 0) {
                harness.taskRepository.findVisibleInProject(any(), any(), any(), any())
            }
        }

        @Test
        fun `honours a non-default page request`() {
            val request = PageRequest(page = 2, size = 5)
            harness.withTeams(TEAM).withProjects(PROJECT)
            every { harness.taskRepository.findVisibleInProject(PROJECT_ID, MEMBER_ID, page = request) } returns
                Page.of(listOf(todo), request, 11L)

            val page = service.listTasks(MEMBER_ID, PROJECT_ID, page = request)

            assertThat(page.page).isEqualTo(2)
            assertThat(page.size).isEqualTo(5)
            assertThat(page.totalElements).isEqualTo(11)
        }
    }

    @Nested
    @DisplayName("the unsupported operations")
    inner class Unsupported {

        @Test
        fun `there is no way to move a project between teams, by construction`() {
            // PR-9: the port exposes no such method, so this is asserted at the
            // type level. The foreign-team fixture exists only to make that
            // explicit here: a project's team is fixed at creation.
            assertThat(FOREIGN_PROJECT.teamId).isEqualTo(OTHER_TEAM_ID)
            assertThat(ProjectService::class.java.methods.map { it.name })
                .doesNotContain("moveToTeam", "delete")
        }
    }
}
