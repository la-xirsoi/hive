package hive.adapter.`in`.controller

import hive.adapter.`in`.ApiWebTestBase
import hive.adapter.`in`.WebFixtures
import hive.application.AppFixtures
import hive.application.view.ProjectPermissions
import hive.domain.error.AuthorizationException
import hive.domain.error.ConflictException
import hive.domain.error.NotFoundException
import hive.domain.model.PageRequest
import hive.domain.model.ProjectId
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

/** `docs/api-contract.md` section 4 -- projects. */
class ProjectControllerTest : ApiWebTestBase() {

    @Test
    fun `POST projects is 201 with the project summary`() {
        every { projectUseCases.create(any(), any()) } returns WebFixtures.PROJECT_VIEW

        mvc
            .post("/api/v1/projects") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Apiary","teamId":10}"""
            }.andExpect {
                status { isCreated() }
                jsonPath("$.id") { value(20) }
                jsonPath("$.team.memberCount") { value(3) }
            }
    }

    @Test
    fun `POST projects rejects a missing teamId`() {
        mvc
            .post("/api/v1/projects") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Apiary"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.fieldErrors[0].field") { value("teamId") }
            }
    }

    @Test
    fun `POST projects is 403 for a caller who can see the team but does not belong to it (PR-2)`() {
        every { projectUseCases.create(any(), any()) } throws
            AuthorizationException("Only a member or the lead of the team may create a project in it.")

        mvc
            .post("/api/v1/projects") {
                with(callerIs(AppFixtures.OWNER))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Apiary","teamId":10}"""
            }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `POST projects is 404 when the team is invisible`() {
        every { projectUseCases.create(any(), any()) } throws NotFoundException("Team", 10)

        mvc
            .post("/api/v1/projects") {
                with(callerIs(AppFixtures.OUTSIDER))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Apiary","teamId":10}"""
            }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `POST projects without a token is 401`() {
        mvc
            .post("/api/v1/projects") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Apiary","teamId":10}"""
            }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `GET projects mine lists the caller's projects`() {
        every { projectUseCases.listMine(any()) } returns listOf(WebFixtures.PROJECT_VIEW)

        mvc
            .get("/api/v1/projects/mine") { with(callerIs()) }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].id") { value(20) }
            }
    }

    @Test
    fun `GET projects by id returns the summary`() {
        every { projectUseCases.get(any(), ProjectId(20)) } returns WebFixtures.PROJECT_VIEW

        mvc
            .get("/api/v1/projects/20") { with(callerIs()) }
            .andExpect {
                status { isOk() }
                jsonPath("$.name") { value("Apiary") }
            }
    }

    @Test
    fun `GET projects by id publishes the caller's permissions`() {
        every { projectUseCases.get(any(), ProjectId(20)) } returns
            WebFixtures.projectView(
                ProjectPermissions(
                    canRename = false,
                    canTransferOwnership = false,
                    canCreateTask = false,
                ),
            )

        mvc
            .get("/api/v1/projects/20") { with(callerIs()) }
            .andExpect {
                status { isOk() }
                jsonPath("$.permissions.canRename") { value(false) }
                jsonPath("$.permissions.canTransferOwnership") { value(false) }
                jsonPath("$.permissions.canCreateTask") { value(false) }
            }
    }

    @Test
    fun `GET projects by id is 404 when invisible`() {
        every { projectUseCases.get(any(), ProjectId(20)) } throws NotFoundException("Project", 20)

        mvc
            .get("/api/v1/projects/20") { with(callerIs(AppFixtures.OUTSIDER)) }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `PATCH projects renames`() {
        every { projectUseCases.rename(any(), ProjectId(20), any()) } returns WebFixtures.PROJECT_VIEW

        mvc
            .patch("/api/v1/projects/20") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Apiary"}"""
            }.andExpect { status { isOk() } }
    }

    @Test
    fun `PATCH projects is 403 for a non-owner`() {
        every { projectUseCases.rename(any(), ProjectId(20), any()) } throws
            AuthorizationException("Only the project owner may rename this project.")

        mvc
            .patch("/api/v1/projects/20") {
                with(callerIs(AppFixtures.MEMBER))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Apiary"}"""
            }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `PUT owner transfers ownership`() {
        every { projectUseCases.transferOwner(any(), ProjectId(20), UserId(4)) } returns WebFixtures.PROJECT_VIEW

        mvc
            .put("/api/v1/projects/20/owner") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"userId":4}"""
            }.andExpect { status { isOk() } }
    }

    @Test
    fun `PUT owner is 409 when the incoming owner holds live tasks here (PR-8)`() {
        every { projectUseCases.transferOwner(any(), ProjectId(20), UserId(3)) } throws
            ConflictException("User 3 is the assignee of live tasks 30 in this project.")

        mvc
            .put("/api/v1/projects/20/owner") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"userId":3}"""
            }.andExpect {
                status { isConflict() }
                jsonPath("$.message") { value("User 3 is the assignee of live tasks 30 in this project.") }
            }
    }

    @Test
    fun `GET project tasks returns the visible page`() {
        every { projectUseCases.listTasks(any(), ProjectId(20), null, PageRequest(0, 50)) } returns
            AppFixtures.pageOf(WebFixtures.summaryView())

        mvc
            .get("/api/v1/projects/20/tasks") { with(callerIs()) }
            .andExpect {
                status { isOk() }
                jsonPath("$.content[0].id") { value(30) }
                jsonPath("$.content[0].projectName") { value("Apiary") }
                jsonPath("$.content[0].assignee.id") { value(3) }
                jsonPath("$.totalElements") { value(1) }
            }
    }

    @Test
    fun `GET project tasks passes the status filter through`() {
        every {
            projectUseCases.listTasks(any(), ProjectId(20), setOf(TaskStatus.TODO, TaskStatus.IN_PROGRESS), any())
        } returns AppFixtures.pageOf(WebFixtures.summaryView())

        mvc
            .get("/api/v1/projects/20/tasks?status=Todo,In Progress") { with(callerIs()) }
            .andExpect { status { isOk() } }

        verify {
            projectUseCases.listTasks(
                AppFixtures.OWNER_ID,
                ProjectId(20),
                setOf(TaskStatus.TODO, TaskStatus.IN_PROGRESS),
                PageRequest(0, 50),
            )
        }
    }

    @Test
    fun `GET project tasks rejects an unknown status literal`() {
        mvc
            .get("/api/v1/projects/20/tasks?status=Almost") { with(callerIs()) }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.fieldErrors[0].field") { value("status") }
            }
    }

    @Test
    fun `GET project tasks is 404 for an invisible project, never 403`() {
        every { projectUseCases.listTasks(any(), ProjectId(20), any(), any()) } throws
            NotFoundException("Project", 20)

        mvc
            .get("/api/v1/projects/20/tasks") { with(callerIs(AppFixtures.OUTSIDER)) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.error") { value("NOT_FOUND") }
            }
    }

    @Test
    fun `DELETE projects is 405 (PR-10)`() {
        mvc
            .delete("/api/v1/projects/20") { with(callerIs()) }
            .andExpect {
                status { isMethodNotAllowed() }
                jsonPath("$.error") { value("METHOD_NOT_ALLOWED") }
            }
    }
}
