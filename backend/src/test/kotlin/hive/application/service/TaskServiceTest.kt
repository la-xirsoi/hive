package hive.application.service

import hive.application.AppFixtures.ASSIGNEE
import hive.application.AppFixtures.ASSIGNEE_ID
import hive.application.AppFixtures.GHOST_ID
import hive.application.AppFixtures.LEAD_ID
import hive.application.AppFixtures.MEMBER
import hive.application.AppFixtures.MEMBER_ID
import hive.application.AppFixtures.OUTSIDER_ID
import hive.application.AppFixtures.OWNER
import hive.application.AppFixtures.OWNER_ID
import hive.application.AppFixtures.PROJECT
import hive.application.AppFixtures.PROJECT_ID
import hive.application.AppFixtures.TASK_ID
import hive.application.AppFixtures.TEAM
import hive.application.AppFixtures.pageOf
import hive.application.AppFixtures.task
import hive.application.Harness
import hive.application.usecase.AssignTaskCommand
import hive.application.usecase.CreateTaskCommand
import hive.application.usecase.TransitionTaskCommand
import hive.application.usecase.UpdateTaskCommand
import hive.domain.error.AuthorizationException
import hive.domain.error.ConflictException
import hive.domain.error.NotFoundException
import hive.domain.error.ValidationException
import hive.domain.model.PageRequest
import hive.domain.model.Task
import hive.domain.model.TaskId
import hive.domain.model.TaskStatus
import hive.domain.model.TaskStatus.CANCELED
import hive.domain.model.TaskStatus.COMPLETED
import hive.domain.model.TaskStatus.DRAFT
import hive.domain.model.TaskStatus.IN_PROGRESS
import hive.domain.model.TaskStatus.TODO
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TaskService")
class TaskServiceTest {

    private val harness = Harness()
    private val service = TaskService(
        harness.taskRepository,
        harness.userRepository,
        harness.loader,
        harness.views,
    )

    private fun world(vararg tasks: Task) = harness
        .withTeams(TEAM)
        .withProjects(PROJECT)
        .withTasks(*tasks)
        .echoTaskSaves()

    @Nested
    @DisplayName("create (TK-1, TK-2)")
    inner class Create {

        @Test
        fun `the project owner creates a Draft task with no assignee`() {
            harness.withTeams(TEAM).withProjects(PROJECT)
            val saved = slot<Task>()
            every { harness.taskRepository.save(capture(saved)) } answers {
                firstArg<Task>().copy(id = TaskId(77))
            }

            val view = service.create(
                OWNER_ID,
                CreateTaskCommand(PROJECT_ID, "  Split the brood box  ", "Two frames of eggs."),
            )

            assertThat(saved.captured.id).isNull()
            assertThat(saved.captured.name.value).isEqualTo("Split the brood box")
            assertThat(saved.captured.status).isEqualTo(DRAFT)
            assertThat(saved.captured.assignee).isNull()
            assertThat(saved.captured.creator).isEqualTo(OWNER_ID)
            assertThat(view.task.id).isEqualTo(TaskId(77))
            assertThat(view.creator).isEqualTo(OWNER)
            assertThat(view.project.project).isEqualTo(PROJECT)
        }

        @Test
        fun `an empty description is allowed`() {
            harness.withTeams(TEAM).withProjects(PROJECT).echoTaskSaves()

            val view = service.create(OWNER_ID, CreateTaskCommand(PROJECT_ID, "Feed the nucs", ""))

            assertThat(view.task.description.isEmpty).isTrue()
        }

        @Test
        fun `403 for the team lead, who may see the project but does not own it`() {
            harness.withTeams(TEAM).withProjects(PROJECT)

            assertThatThrownBy {
                service.create(LEAD_ID, CreateTaskCommand(PROJECT_ID, "Sneaky", ""))
            }.isInstanceOf(AuthorizationException::class.java)
        }

        @Test
        fun `404 when the project is invisible to the caller`() {
            harness.withTeams(TEAM).withProjects(PROJECT)

            assertThatThrownBy {
                service.create(OUTSIDER_ID, CreateTaskCommand(PROJECT_ID, "Sneaky", ""))
            }.isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `400 on a blank name, after the ownership check`() {
            harness.withTeams(TEAM).withProjects(PROJECT)

            assertThatThrownBy {
                service.create(OWNER_ID, CreateTaskCommand(PROJECT_ID, "   ", ""))
            }.isInstanceOf(ValidationException::class.java)
        }
    }

    @Nested
    @DisplayName("get and visibility (VIS-1..VIS-5)")
    inner class Visibility {

        @Test
        fun `VIS-2 the project owner sees a Draft task`() {
            world(task(DRAFT, assignee = null))

            assertThat(service.get(OWNER_ID, TASK_ID).task.status).isEqualTo(DRAFT)
        }

        @Test
        fun `VIS-3 the lead cannot see a Draft task`() {
            world(task(DRAFT, assignee = null))

            assertThatThrownBy { service.get(LEAD_ID, TASK_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `VIS-3 the lead sees a Canceled task`() {
            world(task(CANCELED, assignee = null))

            assertThat(service.get(LEAD_ID, TASK_ID).task.status).isEqualTo(CANCELED)
        }

        @Test
        fun `VIS-4 a plain member cannot see a Canceled task they do not hold`() {
            world(task(CANCELED, assignee = null))

            assertThatThrownBy { service.get(MEMBER_ID, TASK_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `VIS-5 the assignee still sees a task that was canceled under them`() {
            world(task(CANCELED, assignee = ASSIGNEE_ID))

            assertThat(service.get(ASSIGNEE_ID, TASK_ID).assignee).isEqualTo(ASSIGNEE)
        }

        @Test
        fun `404 for a stranger`() {
            world(task(TODO))

            assertThatThrownBy { service.get(OUTSIDER_ID, TASK_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `404 when the task does not exist`() {
            world()

            assertThatThrownBy { service.get(OWNER_ID, TASK_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `a task pointing at a missing project is a broken reference, reported as 404`() {
            harness.withTeams(TEAM).withProjects().withTasks(task(TODO))

            assertThatThrownBy { service.get(OWNER_ID, TASK_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `a project pointing at a missing team is a broken reference, reported as 404`() {
            harness.withTeams().withProjects(PROJECT).withTasks(task(TODO))

            assertThatThrownBy { service.get(OWNER_ID, TASK_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }
    }

    @Nested
    @DisplayName("permissions on the detail view")
    inner class Permissions {

        @Test
        fun `the owner of a Draft task may edit it and publish or cancel it`() {
            world(task(DRAFT, assignee = null))

            val permissions = service.get(OWNER_ID, TASK_ID).permissions

            assertThat(permissions.canEdit).isTrue()
            assertThat(permissions.canAssign).isFalse()
            assertThat(permissions.canComment).isTrue()
            assertThat(permissions.allowedTransitions).containsExactlyInAnyOrder(TODO, CANCELED)
        }

        @Test
        fun `the lead of a Todo task may assign it but not edit or move it`() {
            world(task(TODO, assignee = null))

            val permissions = service.get(LEAD_ID, TASK_ID).permissions

            assertThat(permissions.canEdit).isFalse()
            assertThat(permissions.canAssign).isTrue()
            assertThat(permissions.allowedTransitions).isEmpty()
        }

        @Test
        fun `the assignee of a Todo task may start it and nothing else`() {
            world(task(TODO, assignee = ASSIGNEE_ID))

            val permissions = service.get(ASSIGNEE_ID, TASK_ID).permissions

            assertThat(permissions.canEdit).isFalse()
            assertThat(permissions.canAssign).isFalse()
            assertThat(permissions.allowedTransitions).containsExactly(IN_PROGRESS)
        }

        @Test
        fun `the lead may reassign an In Progress task but not unassign it (AS-7)`() {
            world(task(IN_PROGRESS, assignee = ASSIGNEE_ID))

            assertThat(service.get(LEAD_ID, TASK_ID).permissions.canAssign).isTrue()
        }

        @Test
        fun `a terminal task offers nothing but commenting (TE-1, TE-2, TE-3, TE-4)`() {
            world(task(COMPLETED, assignee = ASSIGNEE_ID))

            val permissions = service.get(OWNER_ID, TASK_ID).permissions

            assertThat(permissions.canEdit).isFalse()
            assertThat(permissions.canAssign).isFalse()
            assertThat(permissions.canComment).isTrue()
            assertThat(permissions.allowedTransitions).isEmpty()
        }

        @Test
        fun `canAssign is false when the team has nobody eligible to receive the task`() {
            // A team of one whose only member is the project's owner: AS-4 rules
            // out the only candidate, and AS-7 rules out unassigning an
            // In Progress task, so there is no assignment move left to make.
            val soloTeam = TEAM.copy(teamLead = OWNER_ID, memberIds = setOf(OWNER_ID))
            harness.withTeams(soloTeam).withProjects(PROJECT)
                .withTasks(task(IN_PROGRESS, assignee = ASSIGNEE_ID))

            assertThat(service.get(OWNER_ID, TASK_ID).permissions.canAssign).isFalse()
        }
    }

    @Nested
    @DisplayName("update (TK-3, TK-4, TE-1)")
    inner class Update {

        @Test
        fun `the owner changes the name alone`() {
            world(task(TODO))

            val view = service.update(OWNER_ID, TASK_ID, UpdateTaskCommand(name = "Requeen hive 7"))

            assertThat(view.task.name.value).isEqualTo("Requeen hive 7")
            assertThat(view.task.description).isEqualTo(task(TODO).description)
        }

        @Test
        fun `the owner changes the description alone`() {
            world(task(TODO))

            val view = service.update(OWNER_ID, TASK_ID, UpdateTaskCommand(description = "Queen seen."))

            assertThat(view.task.description.value).isEqualTo("Queen seen.")
            assertThat(view.task.name).isEqualTo(task(TODO).name)
        }

        @Test
        fun `the owner changes both at once`() {
            world(task(DRAFT, assignee = null))

            val view = service.update(OWNER_ID, TASK_ID, UpdateTaskCommand("Requeen hive 7", "Queen seen."))

            assertThat(view.task.name.value).isEqualTo("Requeen hive 7")
            assertThat(view.task.description.value).isEqualTo("Queen seen.")
        }

        @Test
        fun `409 on a Completed task (TE-1)`() {
            world(task(COMPLETED))

            assertThatThrownBy { service.update(OWNER_ID, TASK_ID, UpdateTaskCommand(name = "Too late")) }
                .isInstanceOf(ConflictException::class.java)
        }

        @Test
        fun `409 on a Canceled task (TE-1)`() {
            world(task(CANCELED))

            assertThatThrownBy { service.update(OWNER_ID, TASK_ID, UpdateTaskCommand(name = "Too late")) }
                .isInstanceOf(ConflictException::class.java)
        }

        @Test
        fun `409 comes before 403, so a non-owner is told the task is finished`() {
            world(task(COMPLETED, assignee = ASSIGNEE_ID))

            assertThatThrownBy { service.update(ASSIGNEE_ID, TASK_ID, UpdateTaskCommand(name = "Nope")) }
                .isInstanceOf(ConflictException::class.java)
        }

        @Test
        fun `403 for the assignee, who may move the task but not rewrite it`() {
            world(task(TODO, assignee = ASSIGNEE_ID))

            assertThatThrownBy { service.update(ASSIGNEE_ID, TASK_ID, UpdateTaskCommand(name = "Nope")) }
                .isInstanceOf(AuthorizationException::class.java)
        }

        @Test
        fun `404 for a stranger`() {
            world(task(TODO))

            assertThatThrownBy { service.update(OUTSIDER_ID, TASK_ID, UpdateTaskCommand(name = "Nope")) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `400 when the command asks for nothing, and only after the role check`() {
            world(task(TODO))

            assertThatThrownBy { service.update(OWNER_ID, TASK_ID, UpdateTaskCommand()) }
                .isInstanceOf(ValidationException::class.java)

            verify(exactly = 0) { harness.taskRepository.save(any()) }
        }

        @Test
        fun `400 on a blank name`() {
            world(task(TODO))

            assertThatThrownBy { service.update(OWNER_ID, TASK_ID, UpdateTaskCommand(name = "  ")) }
                .isInstanceOf(ValidationException::class.java)
        }
    }

    @Nested
    @DisplayName("transition (TK-5, TR-1..TR-3, TE-2)")
    inner class Transition {

        @Test
        fun `Draft to Todo by the project owner`() {
            world(task(DRAFT, assignee = null))

            val view = service.transition(OWNER_ID, TASK_ID, TransitionTaskCommand(TODO))

            assertThat(view.task.status).isEqualTo(TODO)
        }

        @Test
        fun `Draft to Canceled by the project owner`() {
            world(task(DRAFT, assignee = null))

            assertThat(service.transition(OWNER_ID, TASK_ID, TransitionTaskCommand(CANCELED)).task.status)
                .isEqualTo(CANCELED)
        }

        @Test
        fun `Todo to In Progress by the assignee (TR-1)`() {
            world(task(TODO, assignee = ASSIGNEE_ID))

            assertThat(service.transition(ASSIGNEE_ID, TASK_ID, TransitionTaskCommand(IN_PROGRESS)).task.status)
                .isEqualTo(IN_PROGRESS)
        }

        @Test
        fun `Todo to Canceled by the project owner`() {
            world(task(TODO, assignee = ASSIGNEE_ID))

            assertThat(service.transition(OWNER_ID, TASK_ID, TransitionTaskCommand(CANCELED)).task.status)
                .isEqualTo(CANCELED)
        }

        @Test
        fun `In Progress to Completed by the assignee`() {
            world(task(IN_PROGRESS, assignee = ASSIGNEE_ID))

            assertThat(service.transition(ASSIGNEE_ID, TASK_ID, TransitionTaskCommand(COMPLETED)).task.status)
                .isEqualTo(COMPLETED)
        }

        @Test
        fun `In Progress to Canceled by the project owner`() {
            world(task(IN_PROGRESS, assignee = ASSIGNEE_ID))

            assertThat(service.transition(OWNER_ID, TASK_ID, TransitionTaskCommand(CANCELED)).task.status)
                .isEqualTo(CANCELED)
        }

        @Test
        fun `403 when the move is legal but the actor is not the required one`() {
            world(task(TODO, assignee = ASSIGNEE_ID))

            assertThatThrownBy { service.transition(MEMBER_ID, TASK_ID, TransitionTaskCommand(IN_PROGRESS)) }
                .isInstanceOf(AuthorizationException::class.java)
        }

        @Test
        fun `403 when the owner tries to start work that is not theirs (TR-1)`() {
            world(task(TODO, assignee = ASSIGNEE_ID))

            assertThatThrownBy { service.transition(OWNER_ID, TASK_ID, TransitionTaskCommand(IN_PROGRESS)) }
                .isInstanceOf(AuthorizationException::class.java)
        }

        @Test
        fun `409 for a move that is not in the table at all`() {
            world(task(TODO, assignee = ASSIGNEE_ID))

            assertThatThrownBy { service.transition(ASSIGNEE_ID, TASK_ID, TransitionTaskCommand(COMPLETED)) }
                .isInstanceOf(ConflictException::class.java)
        }

        @Test
        fun `409 out of a terminal state (TE-2)`() {
            world(task(COMPLETED, assignee = ASSIGNEE_ID))

            assertThatThrownBy { service.transition(OWNER_ID, TASK_ID, TransitionTaskCommand(CANCELED)) }
                .isInstanceOf(ConflictException::class.java)
        }

        @Test
        fun `409 for a move to the status the task already holds (TR-3)`() {
            world(task(TODO, assignee = ASSIGNEE_ID))

            assertThatThrownBy { service.transition(OWNER_ID, TASK_ID, TransitionTaskCommand(TODO)) }
                .isInstanceOf(ConflictException::class.java)
        }

        @Test
        fun `409 when an unassigned Todo task is started, because nobody could start it (TR-2)`() {
            world(task(TODO, assignee = null))

            assertThatThrownBy { service.transition(LEAD_ID, TASK_ID, TransitionTaskCommand(IN_PROGRESS)) }
                .isInstanceOf(ConflictException::class.java)
        }

        @Test
        fun `409 is answered before 403`() {
            world(task(COMPLETED, assignee = ASSIGNEE_ID))

            assertThatThrownBy { service.transition(MEMBER_ID, TASK_ID, TransitionTaskCommand(CANCELED)) }
                .isInstanceOf(ConflictException::class.java)
        }

        @Test
        fun `404 for a stranger`() {
            world(task(TODO))

            assertThatThrownBy { service.transition(OUTSIDER_ID, TASK_ID, TransitionTaskCommand(CANCELED)) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `nothing is written when the move is refused`() {
            world(task(TODO, assignee = ASSIGNEE_ID))

            runCatching { service.transition(ASSIGNEE_ID, TASK_ID, TransitionTaskCommand(COMPLETED)) }

            verify(exactly = 0) { harness.taskRepository.save(any()) }
        }

        @Test
        fun `the returned permissions reflect the new status, not the old one`() {
            world(task(IN_PROGRESS, assignee = ASSIGNEE_ID))

            val view = service.transition(ASSIGNEE_ID, TASK_ID, TransitionTaskCommand(COMPLETED))

            assertThat(view.permissions.allowedTransitions).isEmpty()
            assertThat(view.permissions.canEdit).isFalse()
        }
    }

    @Nested
    @DisplayName("assign (AS-1..AS-7, TE-3)")
    inner class Assign {

        @Test
        fun `the lead assigns a Todo task to a member`() {
            world(task(TODO, assignee = null))

            val view = service.assign(LEAD_ID, TASK_ID, AssignTaskCommand(MEMBER_ID))

            assertThat(view.task.assignee).isEqualTo(MEMBER_ID)
            assertThat(view.assignee).isEqualTo(MEMBER)
        }

        @Test
        fun `AS-5 the lead may assign to themselves`() {
            world(task(TODO, assignee = null))

            assertThat(service.assign(LEAD_ID, TASK_ID, AssignTaskCommand(LEAD_ID)).task.assignee)
                .isEqualTo(LEAD_ID)
        }

        @Test
        fun `AS-6 reassigning an In Progress task leaves its status alone`() {
            world(task(IN_PROGRESS, assignee = ASSIGNEE_ID))

            val view = service.assign(LEAD_ID, TASK_ID, AssignTaskCommand(MEMBER_ID))

            assertThat(view.task.assignee).isEqualTo(MEMBER_ID)
            assertThat(view.task.status).isEqualTo(IN_PROGRESS)
        }

        @Test
        fun `AS-7 unassigning is allowed from Todo`() {
            world(task(TODO, assignee = ASSIGNEE_ID))

            val view = service.assign(LEAD_ID, TASK_ID, AssignTaskCommand(null))

            assertThat(view.task.assignee).isNull()
            assertThat(view.assignee).isNull()
        }

        @Test
        fun `AS-7 409 when unassigning an In Progress task`() {
            world(task(IN_PROGRESS, assignee = ASSIGNEE_ID))

            assertThatThrownBy { service.assign(LEAD_ID, TASK_ID, AssignTaskCommand(null)) }
                .isInstanceOf(ConflictException::class.java)
        }

        @Test
        fun `AS-3 409 on a Draft task`() {
            world(task(DRAFT, assignee = null))

            // The actor has to be the project owner: AS-3's conflict is only
            // reachable by somebody who can see a Draft task at all, and VIS-3
            // keeps the lead out -- for a lead this endpoint answers 404 first.
            assertThatThrownBy { service.assign(OWNER_ID, TASK_ID, AssignTaskCommand(MEMBER_ID)) }
                .isInstanceOf(ConflictException::class.java)
        }

        @Test
        fun `404 comes before AS-3's 409 for a lead, who cannot see a Draft task`() {
            world(task(DRAFT, assignee = null))

            assertThatThrownBy { service.assign(LEAD_ID, TASK_ID, AssignTaskCommand(MEMBER_ID)) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `TE-3 409 on a terminal task`() {
            world(task(COMPLETED, assignee = ASSIGNEE_ID))

            assertThatThrownBy { service.assign(LEAD_ID, TASK_ID, AssignTaskCommand(MEMBER_ID)) }
                .isInstanceOf(ConflictException::class.java)
        }

        @Test
        fun `AS-1 403 for the project owner, who is not the lead`() {
            world(task(TODO, assignee = null))

            assertThatThrownBy { service.assign(OWNER_ID, TASK_ID, AssignTaskCommand(MEMBER_ID)) }
                .isInstanceOf(AuthorizationException::class.java)
        }

        @Test
        fun `AS-1 403 for a plain member`() {
            world(task(TODO, assignee = null))

            assertThatThrownBy { service.assign(MEMBER_ID, TASK_ID, AssignTaskCommand(MEMBER_ID)) }
                .isInstanceOf(AuthorizationException::class.java)
        }

        @Test
        fun `AS-4 403 when the target is the project's own owner`() {
            world(task(TODO, assignee = null))
            every { harness.userRepository.findById(OWNER_ID) } returns OWNER

            assertThatThrownBy { service.assign(LEAD_ID, TASK_ID, AssignTaskCommand(OWNER_ID)) }
                .isInstanceOf(AuthorizationException::class.java)
        }

        @Test
        fun `AS-2 403 when the target exists but does not belong to the team`() {
            world(task(TODO, assignee = null))
            every { harness.userRepository.findById(OUTSIDER_ID) } returns
                hive.application.AppFixtures.OUTSIDER

            assertThatThrownBy { service.assign(LEAD_ID, TASK_ID, AssignTaskCommand(OUTSIDER_ID)) }
                .isInstanceOf(AuthorizationException::class.java)
        }

        @Test
        fun `AS-2 400 when the target does not exist at all`() {
            world(task(TODO, assignee = null))
            every { harness.userRepository.findById(GHOST_ID) } returns null

            assertThatThrownBy { service.assign(LEAD_ID, TASK_ID, AssignTaskCommand(GHOST_ID)) }
                .isInstanceOf(ValidationException::class.java)

            verify(exactly = 0) { harness.taskRepository.save(any()) }
        }

        @Test
        fun `a 409 is never downgraded to a 400 by the existence refinement`() {
            world(task(DRAFT, assignee = null))
            every { harness.userRepository.findById(GHOST_ID) } returns null

            assertThatThrownBy { service.assign(OWNER_ID, TASK_ID, AssignTaskCommand(GHOST_ID)) }
                .isInstanceOf(ConflictException::class.java)

            verify(exactly = 0) { harness.userRepository.findById(GHOST_ID) }
        }

        @Test
        fun `the 400 refinement applies to whichever clause refused, the directory being public anyway (US-4)`() {
            world(task(TODO, assignee = null))

            assertThatThrownBy { service.assign(MEMBER_ID, TASK_ID, AssignTaskCommand(GHOST_ID)) }
                .isInstanceOf(ValidationException::class.java)
        }

        @Test
        fun `unassigning never triggers the existence lookup`() {
            world(task(TODO, assignee = ASSIGNEE_ID))

            assertThatThrownBy { service.assign(MEMBER_ID, TASK_ID, AssignTaskCommand(null)) }
                .isInstanceOf(AuthorizationException::class.java)
        }

        @Test
        fun `404 for a stranger`() {
            world(task(TODO, assignee = null))

            assertThatThrownBy { service.assign(OUTSIDER_ID, TASK_ID, AssignTaskCommand(MEMBER_ID)) }
                .isInstanceOf(NotFoundException::class.java)
        }
    }

    @Nested
    @DisplayName("the listing queries")
    inner class Listings {

        @Test
        fun `assigned-to-me hands the port the acting user and resolves the rows`() {
            harness.withProjects(PROJECT)
            every { harness.taskRepository.findAssignedTo(ASSIGNEE_ID, PageRequest.DEFAULT) } returns
                pageOf(task(TODO, ASSIGNEE_ID, TaskId(31)), task(CANCELED, ASSIGNEE_ID, TaskId(32)))

            val page = service.listAssignedToMe(ASSIGNEE_ID)

            assertThat(page.content.map { it.task.id }).containsExactly(TaskId(31), TaskId(32))
            assertThat(page.content).allSatisfy { assertThat(it.assignee).isEqualTo(ASSIGNEE) }
            assertThat(page.content[0].projectName).isEqualTo(PROJECT.name)
            verify(exactly = 1) { harness.projectRepository.findById(PROJECT_ID) }
        }

        @Test
        fun `assigned-to-me is empty for somebody holding nothing`() {
            every { harness.taskRepository.findAssignedTo(OUTSIDER_ID, PageRequest.DEFAULT) } returns pageOf<Task>()

            assertThat(service.listAssignedToMe(OUTSIDER_ID).content).isEmpty()
        }

        @Test
        fun `UQ-1 the unassigned queue comes straight from the lead-scoped port`() {
            harness.withProjects(PROJECT)
            every { harness.taskRepository.findUnassignedForLead(LEAD_ID, PageRequest.DEFAULT) } returns
                pageOf(task(TODO, null, TaskId(31)))

            val page = service.listUnassigned(LEAD_ID)

            assertThat(page.content.single().assignee).isNull()
        }

        @Test
        fun `UQ-1 a user who leads nothing gets an empty page, not a 403`() {
            every { harness.taskRepository.findUnassignedForLead(MEMBER_ID, PageRequest.DEFAULT) } returns pageOf<Task>()

            assertThat(service.listUnassigned(MEMBER_ID).isEmpty).isTrue()
        }

        @Test
        fun `a summary row whose project has vanished is a broken reference, reported as 404`() {
            harness.withProjects()
            every { harness.taskRepository.findAssignedTo(ASSIGNEE_ID, PageRequest.DEFAULT) } returns
                pageOf(task(TaskStatus.TODO, ASSIGNEE_ID, TaskId(31)))

            assertThatThrownBy { service.listAssignedToMe(ASSIGNEE_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }
    }
}
