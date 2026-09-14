package hive.adapter.`in`.controller

import hive.adapter.`in`.ApiWebTestBase
import hive.application.AppFixtures
import hive.domain.error.NotFoundException
import hive.domain.error.ValidationException
import hive.domain.model.PageRequest
import hive.domain.model.UserId
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch

/**
 * `docs/api-contract.md` section 2 -- every row of the users table, success and
 * every error status it lists.
 */
class UserControllerTest : ApiWebTestBase() {

    @Test
    fun `GET users me returns the acting user`() {
        every { userUseCases.getById(any(), any()) } returns AppFixtures.OWNER

        mvc
            .get("/api/v1/users/me") { with(callerIs()) }
            .andExpect {
                status { isOk() }
                content { contentType(MediaType.APPLICATION_JSON) }
                jsonPath("$.id") { value(AppFixtures.OWNER_ID.value) }
                jsonPath("$.name") { value("Olive Owner") }
                jsonPath("$.email") { value("olive@hive.test") }
            }
    }

    @Test
    fun `GET users me provisions the caller from the token claims (US-3)`() {
        val claims = slot<hive.application.usecase.PrincipalClaims>()
        every { userUseCases.provisionFromPrincipal(capture(claims)) } returns AppFixtures.MEMBER
        every { userUseCases.getById(any(), any()) } returns AppFixtures.MEMBER

        mvc.get("/api/v1/users/me") { with(callerIs(AppFixtures.MEMBER)) }.andExpect { status { isOk() } }

        assertThat(claims.captured.email).isEqualTo("mira@hive.test")
        assertThat(claims.captured.name).isEqualTo("Mira Member")
        // AU-2: the acting user is the token's, and the use case is called with
        // the id that provisioning returned -- never with anything from the request.
        verify { userUseCases.getById(UserId(4), UserId(4)) }
    }

    @Test
    fun `GET users me without a token is 401`() {
        mvc
            .get("/api/v1/users/me")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.error") { value("UNAUTHORIZED") }
                jsonPath("$.status") { value(401) }
                jsonPath("$.path") { value("/api/v1/users/me") }
                jsonPath("$.fieldErrors") { doesNotExist() }
            }
    }

    @Test
    fun `PATCH users me renames the caller`() {
        every { userUseCases.renameSelf(any(), any()) } returns
            AppFixtures.user(AppFixtures.OWNER_ID, "Olive Renamed", "olive@hive.test")

        mvc
            .patch("/api/v1/users/me") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Olive Renamed"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.name") { value("Olive Renamed") }
            }
    }

    @Test
    fun `PATCH users me rejects an email change with a field error (US-5)`() {
        mvc
            .patch("/api/v1/users/me") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Olive","email":"new@hive.test"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.error") { value("BAD_REQUEST") }
                jsonPath("$.fieldErrors[0].field") { value("email") }
            }
    }

    @Test
    fun `PATCH users me rejects a blank name`() {
        mvc
            .patch("/api/v1/users/me") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"  "}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.fieldErrors[0].field") { value("name") }
            }
    }

    @Test
    fun `PATCH users me rejects malformed JSON without leaking the parser`() {
        mvc
            .patch("/api/v1/users/me") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.error") { value("BAD_REQUEST") }
                jsonPath("$.message") { value("The request body is missing or is not valid JSON.") }
                jsonPath("$.fieldErrors") { doesNotExist() }
            }
    }

    @Test
    fun `GET users searches the directory with the contract's page envelope`() {
        every { userUseCases.search(any(), "mi", PageRequest(0, 50)) } returns
            AppFixtures.pageOf(AppFixtures.MEMBER)

        mvc
            .get("/api/v1/users?query=mi") { with(callerIs()) }
            .andExpect {
                status { isOk() }
                jsonPath("$.content[0].email") { value("mira@hive.test") }
                jsonPath("$.page") { value(0) }
                jsonPath("$.size") { value(50) }
                jsonPath("$.totalElements") { value(1) }
                jsonPath("$.totalPages") { value(1) }
            }
    }

    @Test
    fun `GET users honours explicit paging parameters`() {
        every { userUseCases.search(any(), null, PageRequest(2, 5)) } returns
            AppFixtures.pageOf(AppFixtures.MEMBER, request = PageRequest(2, 5))

        mvc
            .get("/api/v1/users?page=2&size=5") { with(callerIs()) }
            .andExpect {
                status { isOk() }
                jsonPath("$.page") { value(2) }
                jsonPath("$.size") { value(5) }
            }
    }

    @Test
    fun `GET users rejects an out-of-range page size`() {
        mvc
            .get("/api/v1/users?size=0") { with(callerIs()) }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.fieldErrors[0].field") { value("size") }
            }
    }

    @Test
    fun `GET users rejects a non-numeric page`() {
        mvc
            .get("/api/v1/users?page=first") { with(callerIs()) }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.fieldErrors[0].field") { value("page") }
            }
    }

    @Test
    fun `GET users by id returns the user`() {
        every { userUseCases.getById(any(), UserId(3)) } returns AppFixtures.ASSIGNEE

        mvc
            .get("/api/v1/users/3") { with(callerIs()) }
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(3) }
            }
    }

    @Test
    fun `GET users by id is 404 for an unknown user`() {
        every { userUseCases.getById(any(), UserId(999)) } throws NotFoundException("User", 999)

        mvc
            .get("/api/v1/users/999") { with(callerIs()) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.error") { value("NOT_FOUND") }
                jsonPath("$.message") { value("User 999 was not found.") }
            }
    }

    @Test
    fun `a domain validation failure becomes a 400 with its field errors`() {
        every { userUseCases.renameSelf(any(), any()) } throws
            ValidationException("name", "must be between 1 and 200 characters.")

        mvc
            .patch("/api/v1/users/me") {
                with(callerIs())
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"ok"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.fieldErrors[0].field") { value("name") }
                jsonPath("$.fieldErrors[0].message") { value("must be between 1 and 200 characters.") }
            }
    }
}
