package hive.adapter.`in`.controller

import hive.adapter.`in`.ApiWebTestBase
import hive.adapter.`in`.WebFixtures
import hive.application.AppFixtures
import hive.application.usecase.AssignTaskCommand
import hive.application.usecase.TransitionTaskCommand
import hive.application.usecase.UpdateTaskCommand
import hive.application.view.TaskPermissions
import hive.domain.error.AuthorizationException
import hive.domain.error.ConflictException
import hive.domain.error.NotFoundException
import hive.domain.error.ValidationException
import hive.domain.model.PageRequest
import hive.domain.model.TaskId
import hive.domain.model.TaskStatus
import hive.domain.model.UserId
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

/** `docs/api-contract.md` section 5 -- tasks, including the 404/409/403 ordering. */
class TaskControllerTest : ApiWebTestBase() {

    @Test
    fun `POST tasks is 201 with the detail and its permissions`() {
        every { taskUseCases.create(any(), any()) } returns
            WebFixtures.detailView(AppFixtures.task(TaskStatus.DRAFT, assignee = null))

        mvc
            .post("/api/v1/tasks") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"projectId":20,"name":"Requeen hive 4","description":"The colony is queenless."}"""
            }.andExpect {
                status { isCreated() }
                jsonPath("$.id") { value(30) }
                jsonPath("$.status") { value("Draft") }
                jsonPath("$.assignee") { value(null) }
                jsonPath("$.creator.id") { value(1) }
                jsonPath("$.project.id") { value(20) }
                jsonPath("$.permissions.canEdit") { value(true) }
                jsonPath("$.permissions.canAssign") { value(false) }
                // Published in the contract's declaration order, not the set's.
                jsonPath("$.permissions.allowedTransitions[0]") { value("In Progress") }
                jsonPath("$.permissions.allowedTransitions[1]") { value("Canceled") }
            }
    }

    @Test
    fun `POST tasks rejects a missing description`() {
        mvc
            .post("/api/v1/tasks") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"projectId":20,"name":"Requeen"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.fieldErrors[0].field") { value("description") }
            }
    }

    @Test
    fun `POST tasks is 403 for a caller who is not the project owner (TK-1)`() {
        every { taskUseCases.create(any(), any()) } throws
            AuthorizationException("Only the project owner may create tasks in this project.")

        mvc
            .post("/api/v1/tasks") {
                with(callerIs(AppFixtures.MEMBER))
                contentType = MediaType.APPLICATION_JSON
                content = """{"projectId":20,"name":"Requeen","description":"x"}"""
            }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `POST tasks is 404 when the project is invisible`() {
        every { taskUseCases.create(any(), any()) } throws NotFoundException("Project", 20)

        mvc
            .post("/api/v1/tasks") {
                with(callerIs(AppFixtures.OUTSIDER))
                contentType = MediaType.APPLICATION_JSON
                content = """{"projectId":20,"name":"Requeen","description":"x"}"""
            }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `POST tasks without a token is 401`() {
        mvc
            .post("/api/v1/tasks") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"projectId":20,"name":"Requeen","description":"x"}"""
            }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `GET task by id returns the detail`() {
        every { taskUseCases.get(any(), TaskId(30)) } returns WebFixtures.detailView()

        mvc
            .get("/api/v1/tasks/30") { with(callerIs()) }
            .andExpect {
                status { isOk() }
                jsonPath("$.name") { value("Requeen hive 4") }
                jsonPath("$.assignee.id") { value(3) }
            }
    }

    @Test
    fun `GET task by id is 404 for an invisible task`() {
        every { taskUseCases.get(any(), TaskId(30)) } throws NotFoundException("Task", 30)

        mvc
            .get("/api/v1/tasks/30") { with(callerIs(AppFixtures.OUTSIDER)) }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `PATCH task updates name and description`() {
        every { taskUseCases.update(any(), TaskId(30), any()) } returns WebFixtures.detailView()

        mvc
            .patch("/api/v1/tasks/30") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Requeen hive 4"}"""
            }.andExpect { status { isOk() } }

        verify { taskUseCases.update(AppFixtures.OWNER_ID, TaskId(30), UpdateTaskCommand(name = "Requeen hive 4")) }
    }

    @Test
    fun `PATCH task with no fields is the use case's 400`() {
        every { taskUseCases.update(any(), any(), any()) } throws
            ValidationException("request", "at least one field must be present.")

        mvc
            .patch("/api/v1/tasks/30") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.fieldErrors[0].field") { value("request") }
            }
    }

    @Test
    fun `PATCH task is 409 in a terminal state (TE-1)`() {
        every { taskUseCases.update(any(), any(), any()) } throws
            ConflictException("Task 30 is Completed and can no longer be edited.")

        mvc
            .patch("/api/v1/tasks/30") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Late edit"}"""
            }.andExpect {
                status { isConflict() }
                jsonPath("$.message") { value("Task 30 is Completed and can no longer be edited.") }
            }
    }

    @Test
    fun `PATCH task is 403 for a non-owner`() {
        every { taskUseCases.update(any(), any(), any()) } throws
            AuthorizationException("Only the project owner may edit this task.")

        mvc
            .patch("/api/v1/tasks/30") {
                with(callerIs(AppFixtures.MEMBER))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Nope"}"""
            }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `PUT status transitions the task`() {
        every { taskUseCases.transition(any(), TaskId(30), any()) } returns
            WebFixtures.detailView(AppFixtures.task(TaskStatus.IN_PROGRESS))

        mvc
            .put("/api/v1/tasks/30/status") {
                with(callerIs(AppFixtures.ASSIGNEE))
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"In Progress"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.status") { value("In Progress") }
            }

        verify {
            taskUseCases.transition(
                AppFixtures.OWNER_ID,
                TaskId(30),
                TransitionTaskCommand(TaskStatus.IN_PROGRESS),
            )
        }
    }

    @Test
    fun `PUT status rejects an unknown literal with a status field error`() {
        mvc
            .put("/api/v1/tasks/30/status") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"Nearly done"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.fieldErrors[0].field") { value("status") }
            }
    }

    @Test
    fun `PUT status is 409 for a move outside the state machine, whoever asks`() {
        every { taskUseCases.transition(any(), any(), any()) } throws
            ConflictException("A Completed task cannot move to In Progress.")

        mvc
            .put("/api/v1/tasks/30/status") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"In Progress"}"""
            }.andExpect {
                status { isConflict() }
                jsonPath("$.error") { value("CONFLICT") }
            }
    }

    @Test
    fun `PUT status is 403 when the move is legal but this actor may not make it`() {
        // The distinction the contract cares about: 409 is about the task, 403
        // is about the caller, and the task is visible in both cases.
        every { taskUseCases.transition(any(), any(), any()) } throws
            AuthorizationException("Only the assignee may start work on this task.")

        mvc
            .put("/api/v1/tasks/30/status") {
                with(callerIs(AppFixtures.MEMBER))
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"In Progress"}"""
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.error") { value("FORBIDDEN") }
            }
    }

    @Test
    fun `PUT status is 404 before either, for an invisible task`() {
        every { taskUseCases.transition(any(), any(), any()) } throws NotFoundException("Task", 30)

        mvc
            .put("/api/v1/tasks/30/status") {
                with(callerIs(AppFixtures.OUTSIDER))
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"In Progress"}"""
            }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `PUT assignee assigns a member`() {
        every { taskUseCases.assign(any(), TaskId(30), any()) } returns WebFixtures.detailView()

        mvc
            .put("/api/v1/tasks/30/assignee") {
                with(callerIs(AppFixtures.LEAD))
                contentType = MediaType.APPLICATION_JSON
                content = """{"userId":3}"""
            }.andExpect { status { isOk() } }

        verify { taskUseCases.assign(AppFixtures.OWNER_ID, TaskId(30), AssignTaskCommand(UserId(3))) }
    }

    @Test
    fun `PUT assignee with a null userId unassigns (AS-7)`() {
        every { taskUseCases.assign(any(), TaskId(30), any()) } returns
            WebFixtures.detailView(AppFixtures.task(TaskStatus.TODO, assignee = null))

        mvc
            .put("/api/v1/tasks/30/assignee") {
                with(callerIs(AppFixtures.LEAD))
                contentType = MediaType.APPLICATION_JSON
                content = """{"userId":null}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.assignee") { value(null) }
            }

        verify { taskUseCases.assign(AppFixtures.OWNER_ID, TaskId(30), AssignTaskCommand(null)) }
    }

    @Test
    fun `PUT assignee is 409 while the task is Draft (AS-3)`() {
        every { taskUseCases.assign(any(), any(), any()) } throws
            ConflictException("A Draft task cannot be assigned.")

        mvc
            .put("/api/v1/tasks/30/assignee") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"userId":3}"""
            }.andExpect { status { isConflict() } }
    }

    @Test
    fun `PUT assignee is 403 when the target is not a member of the team (AS-2)`() {
        every { taskUseCases.assign(any(), any(), any()) } throws
            AuthorizationException("User 5 is not a member of this project's team.")

        mvc
            .put("/api/v1/tasks/30/assignee") {
                with(callerIs(AppFixtures.LEAD))
                contentType = MediaType.APPLICATION_JSON
                content = """{"userId":5}"""
            }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `PUT assignee is 400 when the named user does not exist`() {
        every { taskUseCases.assign(any(), any(), any()) } throws
            ValidationException("userId", "no such user.")

        mvc
            .put("/api/v1/tasks/30/assignee") {
                with(callerIs(AppFixtures.LEAD))
                contentType = MediaType.APPLICATION_JSON
                content = """{"userId":999}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.fieldErrors[0].field") { value("userId") }
            }
    }

    @Test
    fun `GET assigned-to-me returns a page`() {
        every { taskUseCases.listAssignedToMe(any(), PageRequest(0, 50)) } returns
            AppFixtures.pageOf(WebFixtures.summaryView())

        mvc
            .get("/api/v1/tasks/assigned-to-me") { with(callerIs(AppFixtures.ASSIGNEE)) }
            .andExpect {
                status { isOk() }
                jsonPath("$.content[0].id") { value(30) }
            }
    }

    @Test
    fun `GET unassigned is an empty page for a caller who leads nothing (UQ-1)`() {
        every { taskUseCases.listUnassigned(any(), any()) } returns
            hive.domain.model.Page
                .empty(PageRequest.DEFAULT)

        mvc
            .get("/api/v1/tasks/unassigned") { with(callerIs(AppFixtures.MEMBER)) }
            .andExpect {
                status { isOk() }
                jsonPath("$.content.length()") { value(0) }
                jsonPath("$.totalElements") { value(0) }
                jsonPath("$.totalPages") { value(0) }
            }
    }

    @Test
    fun `GET unassigned without a token is 401`() {
        mvc.get("/api/v1/tasks/unassigned").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `DELETE tasks is 405 (TK-6)`() {
        mvc
            .delete("/api/v1/tasks/30") { with(callerIs()) }
            .andExpect {
                status { isMethodNotAllowed() }
                jsonPath("$.error") { value("METHOD_NOT_ALLOWED") }
            }
    }

    @Test
    fun `a task with no allowed transitions publishes an empty list, not a missing field`() {
        every { taskUseCases.get(any(), TaskId(30)) } returns
            WebFixtures.detailView(
                AppFixtures.task(TaskStatus.COMPLETED),
                TaskPermissions(
                    canEdit = false,
                    canAssign = false,
                    canComment = true,
                    allowedTransitions = emptySet(),
                ),
            )

        mvc
            .get("/api/v1/tasks/30") { with(callerIs()) }
            .andExpect {
                status { isOk() }
                jsonPath("$.permissions.allowedTransitions.length()") { value(0) }
                jsonPath("$.permissions.canComment") { value(true) }
            }
    }
}
