package hive.adapter.`in`.controller

import hive.adapter.`in`.ApiWebTestBase
import hive.adapter.`in`.WebFixtures
import hive.application.AppFixtures
import hive.application.usecase.AddCommentCommand
import hive.domain.error.NotFoundException
import hive.domain.model.PageRequest
import hive.domain.model.TaskId
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

/** `docs/api-contract.md` section 6 -- comments. */
class CommentControllerTest : ApiWebTestBase() {

    @Test
    fun `GET comments returns a page, oldest first, with the contract's timestamp format`() {
        every { commentUseCases.list(any(), TaskId(30), PageRequest(0, 50)) } returns
            AppFixtures.pageOf(WebFixtures.COMMENT_VIEW)

        mvc
            .get("/api/v1/tasks/30/comments") { with(callerIs()) }
            .andExpect {
                status { isOk() }
                jsonPath("$.content[0].id") { value(1) }
                jsonPath("$.content[0].taskId") { value(30) }
                jsonPath("$.content[0].author.id") { value(4) }
                jsonPath("$.content[0].timestamp") { value("2026-09-13T18:30:00Z") }
                jsonPath("$.content[0].content") { value("Noted.") }
            }
    }

    @Test
    fun `GET comments is 404 when the task is invisible (CM-2)`() {
        every { commentUseCases.list(any(), TaskId(30), any()) } throws NotFoundException("Task", 30)

        mvc
            .get("/api/v1/tasks/30/comments") { with(callerIs(AppFixtures.OUTSIDER)) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.error") { value("NOT_FOUND") }
            }
    }

    @Test
    fun `GET comments without a token is 401`() {
        mvc.get("/api/v1/tasks/30/comments").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `POST comment is 201 with the server-assigned author and timestamp`() {
        every { commentUseCases.add(any(), TaskId(30), any()) } returns WebFixtures.COMMENT_VIEW

        mvc
            .post("/api/v1/tasks/30/comments") {
                with(callerIs(AppFixtures.MEMBER))
                contentType = MediaType.APPLICATION_JSON
                content = """{"content":"Noted."}"""
            }.andExpect {
                status { isCreated() }
                jsonPath("$.author.id") { value(4) }
                jsonPath("$.timestamp") { value("2026-09-13T18:30:00Z") }
            }

        verify { commentUseCases.add(AppFixtures.OWNER_ID, TaskId(30), AddCommentCommand("Noted.")) }
    }

    @Test
    fun `POST comment rejects blank content`() {
        mvc
            .post("/api/v1/tasks/30/comments") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"content":"   "}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.fieldErrors[0].field") { value("content") }
            }
    }

    @Test
    fun `POST comment rejects content over 4000 characters`() {
        mvc
            .post("/api/v1/tasks/30/comments") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"content":"${"x".repeat(4001)}"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.fieldErrors[0].field") { value("content") }
            }
    }

    @Test
    fun `POST comment is 404 when the task is invisible (CM-1)`() {
        every { commentUseCases.add(any(), TaskId(30), any()) } throws NotFoundException("Task", 30)

        mvc
            .post("/api/v1/tasks/30/comments") {
                with(callerIs(AppFixtures.OUTSIDER))
                contentType = MediaType.APPLICATION_JSON
                content = """{"content":"Hello?"}"""
            }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `there is no way to edit a comment (CM-6)`() {
        mvc
            .put("/api/v1/tasks/30/comments") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"content":"Edited."}"""
            }.andExpect { status { isMethodNotAllowed() } }
    }
}
