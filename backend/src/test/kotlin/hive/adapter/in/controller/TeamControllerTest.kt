package hive.adapter.`in`.controller

import hive.adapter.`in`.ApiWebTestBase
import hive.adapter.`in`.WebFixtures
import hive.application.AppFixtures
import hive.domain.error.AuthorizationException
import hive.domain.error.ConflictException
import hive.domain.error.NotFoundException
import hive.domain.error.ValidationException
import hive.domain.model.TeamId
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

/** `docs/api-contract.md` section 3 -- teams, every row and every listed error. */
class TeamControllerTest : ApiWebTestBase() {

    @Test
    fun `POST teams is 201 with the team detail`() {
        every { teamUseCases.create(any(), any()) } returns WebFixtures.TEAM_VIEW

        mvc
            .post("/api/v1/teams") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Hive Core"}"""
            }.andExpect {
                status { isCreated() }
                jsonPath("$.id") { value(10) }
                jsonPath("$.name") { value("Hive Core") }
                jsonPath("$.teamLead.id") { value(2) }
                jsonPath("$.members.length()") { value(3) }
            }
    }

    @Test
    fun `POST teams rejects a missing name`() {
        mvc
            .post("/api/v1/teams") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.fieldErrors[0].field") { value("name") }
            }
    }

    @Test
    fun `POST teams without a token is 401`() {
        mvc
            .post("/api/v1/teams") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Hive Core"}"""
            }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `GET teams mine returns summaries with a member count, not a member list`() {
        every { teamUseCases.listMine(any()) } returns listOf(WebFixtures.TEAM_VIEW)

        mvc
            .get("/api/v1/teams/mine") { with(callerIs()) }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].memberCount") { value(3) }
                jsonPath("$[0].members") { doesNotExist() }
            }
    }

    @Test
    fun `GET teams by id returns the detail`() {
        every { teamUseCases.get(any(), TeamId(10)) } returns WebFixtures.TEAM_VIEW

        mvc
            .get("/api/v1/teams/10") { with(callerIs()) }
            .andExpect {
                status { isOk() }
                jsonPath("$.members[0].name") { value("Lena Lead") }
            }
    }

    @Test
    fun `GET teams by id is 404 when the team is invisible to the caller`() {
        every { teamUseCases.get(any(), TeamId(10)) } throws NotFoundException("Team", 10)

        mvc
            .get("/api/v1/teams/10") { with(callerIs(AppFixtures.OUTSIDER)) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.error") { value("NOT_FOUND") }
            }
    }

    @Test
    fun `PATCH teams renames`() {
        every { teamUseCases.rename(any(), TeamId(10), any()) } returns WebFixtures.TEAM_VIEW

        mvc
            .patch("/api/v1/teams/10") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Hive Core"}"""
            }.andExpect { status { isOk() } }
    }

    @Test
    fun `PATCH teams is 403 for a member who is not the lead`() {
        every { teamUseCases.rename(any(), TeamId(10), any()) } throws
            AuthorizationException("Only the team lead may rename this team.")

        mvc
            .patch("/api/v1/teams/10") {
                with(callerIs(AppFixtures.MEMBER))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Renamed"}"""
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.error") { value("FORBIDDEN") }
                jsonPath("$.message") { value("Only the team lead may rename this team.") }
            }
    }

    @Test
    fun `POST members adds a member`() {
        every { teamUseCases.addMember(any(), TeamId(10), UserId(4)) } returns WebFixtures.TEAM_VIEW

        mvc
            .post("/api/v1/teams/10/members") {
                with(callerIs(AppFixtures.LEAD))
                contentType = MediaType.APPLICATION_JSON
                content = """{"userId":4}"""
            }.andExpect { status { isOk() } }

        verify { teamUseCases.addMember(AppFixtures.OWNER_ID, TeamId(10), UserId(4)) }
    }

    @Test
    fun `POST members rejects a missing userId`() {
        mvc
            .post("/api/v1/teams/10/members") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.fieldErrors[0].field") { value("userId") }
            }
    }

    @Test
    fun `POST members is 400 when the named user does not exist`() {
        every { teamUseCases.addMember(any(), any(), any()) } throws
            ValidationException("userId", "no such user.")

        mvc
            .post("/api/v1/teams/10/members") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"userId":999}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.fieldErrors[0].field") { value("userId") }
            }
    }

    @Test
    fun `DELETE member removes them`() {
        every { teamUseCases.removeMember(any(), TeamId(10), UserId(4)) } returns WebFixtures.TEAM_VIEW

        mvc
            .delete("/api/v1/teams/10/members/4") { with(callerIs(AppFixtures.LEAD)) }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `DELETE member is 409 for the current lead (TM-7)`() {
        every { teamUseCases.removeMember(any(), TeamId(10), UserId(2)) } throws
            ConflictException("The team lead cannot be removed from the team.")

        mvc
            .delete("/api/v1/teams/10/members/2") { with(callerIs(AppFixtures.LEAD)) }
            .andExpect {
                status { isConflict() }
                jsonPath("$.error") { value("CONFLICT") }
                jsonPath("$.message") { value("The team lead cannot be removed from the team.") }
                jsonPath("$.fieldErrors") { doesNotExist() }
            }
    }

    @Test
    fun `DELETE member is 403 for a non-lead, and 409 only once past that`() {
        // The 403-vs-409 distinction: a conflict does not depend on who is
        // asking, but a caller with no right to the operation still gets 403.
        every { teamUseCases.removeMember(any(), TeamId(10), UserId(4)) } throws
            AuthorizationException("Only the team lead may remove members.")

        mvc
            .delete("/api/v1/teams/10/members/4") { with(callerIs(AppFixtures.MEMBER)) }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `PUT lead transfers leadership`() {
        every { teamUseCases.transferLead(any(), TeamId(10), UserId(3)) } returns WebFixtures.TEAM_VIEW

        mvc
            .put("/api/v1/teams/10/lead") {
                with(callerIs(AppFixtures.LEAD))
                contentType = MediaType.APPLICATION_JSON
                content = """{"userId":3}"""
            }.andExpect { status { isOk() } }
    }

    @Test
    fun `GET team projects returns only the visible ones`() {
        every { teamUseCases.listProjects(any(), TeamId(10)) } returns listOf(WebFixtures.PROJECT_VIEW)

        mvc
            .get("/api/v1/teams/10/projects") { with(callerIs()) }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].name") { value("Apiary") }
                jsonPath("$[0].team.id") { value(10) }
                jsonPath("$[0].projectOwner.id") { value(1) }
            }
    }

    @Test
    fun `DELETE teams is 405 (TM-11)`() {
        mvc
            .delete("/api/v1/teams/10") { with(callerIs()) }
            .andExpect {
                status { isMethodNotAllowed() }
                jsonPath("$.error") { value("METHOD_NOT_ALLOWED") }
                jsonPath("$.status") { value(405) }
            }
    }
}
