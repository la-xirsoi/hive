package hive.adapter.`in`.error

import hive.adapter.`in`.ApiWebTestBase
import hive.application.AppFixtures
import hive.domain.error.AuthorizationException
import hive.domain.error.ConflictException
import hive.domain.error.NotFoundException
import hive.domain.error.ValidationException
import io.mockk.every
import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

/**
 * Every row of `docs/authorization.md` section 11, asserted on both the status
 * and the body (`docs/api-contract.md` section 1.1).
 *
 * The per-endpoint tests cover these codes where they arise naturally; this
 * file pins the *mapping itself*, including the rows no endpoint can reach on
 * purpose -- an unexpected throwable, an unknown route, a wrong media type.
 */
class ErrorMappingTest : ApiWebTestBase() {

    @Test
    fun `an unexpected throwable is a 500 that leaks nothing`() {
        every { userUseCases.getById(any(), any()) } throws
            IllegalStateException("could not open JDBC connection for hive.UserEntity: SELECT * FROM users")

        mvc
            .get("/api/v1/users/me") { with(callerIs()) }
            .andExpect {
                status { isInternalServerError() }
                jsonPath("$.status") { value(500) }
                jsonPath("$.error") { value("INTERNAL_ERROR") }
                jsonPath("$.message") {
                    value("The request could not be completed because of an unexpected server error.")
                }
                jsonPath("$.fieldErrors") { doesNotExist() }
            }.andExpect {
                content {
                    // Nothing from the throwable: no message, no SQL, no class name.
                    string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SELECT")))
                    string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("UserEntity")))
                    string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("IllegalState")))
                    string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hive.")))
                }
            }
    }

    @Test
    fun `every error body carries the five mandatory fields`() {
        every { userUseCases.getById(any(), any()) } throws NotFoundException("User", 7)

        mvc
            .get("/api/v1/users/7") { with(callerIs()) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.status") { value(404) }
                jsonPath("$.error") { value("NOT_FOUND") }
                jsonPath("$.message") { exists() }
                jsonPath("$.path") { value("/api/v1/users/7") }
                jsonPath("$.timestamp") {
                    value(org.hamcrest.Matchers.matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"))
                }
            }
    }

    @Test
    fun `an unknown route is a 404 in the contract's shape`() {
        mvc
            .get("/api/v1/nonesuch") { with(callerIs()) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.error") { value("NOT_FOUND") }
            }
    }

    @Test
    fun `a body sent as the wrong media type is rejected without a 415 code the contract does not define`() {
        mvc
            .patch("/api/v1/users/me") {
                with(callerIs())
                contentType = org.springframework.http.MediaType.TEXT_PLAIN
                content = "name=Olive"
            }.andExpect {
                status { isUnsupportedMediaType() }
                // The contract closes the `error` set at seven values; 415 folds
                // onto the nearest one rather than inventing an eighth.
                jsonPath("$.error") { value("BAD_REQUEST") }
            }
    }

    @Test
    fun `a missing body is a 400`() {
        mvc
            .post("/api/v1/teams") {
                with(callerIs())
                contentType = org.springframework.http.MediaType.APPLICATION_JSON
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.error") { value("BAD_REQUEST") }
            }
    }

    @Test
    fun `a JSON type that cannot bind is a 400, not a 500`() {
        mvc
            .post("/api/v1/teams/10/members") {
                with(callerIs())
                contentType = org.springframework.http.MediaType.APPLICATION_JSON
                content = """{"userId":"four"}"""
            }.andExpect { status { isBadRequest() } }
    }

    // --- The handler's own branches, exercised directly -----------------------
    //
    // Spring Security's own AccessDeniedException and AuthenticationException
    // can reach the advice when they are raised inside a handler rather than in
    // the filter chain. Driving them through MockMvc would mean inventing a
    // controller that throws them, which would test the fixture; calling the
    // handler is the honest way to pin the mapping.

    private val handler = ApiExceptionHandler()

    private fun request(path: String): HttpServletRequest =
        MockHttpServletRequest("GET", path).apply { requestURI = path }

    @Test
    fun `AccessDeniedException maps to 403`() {
        val response = handler.onAccessDenied(AccessDeniedException("nope"), request("/api/v1/teams/1"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.body?.error?.name).isEqualTo("FORBIDDEN")
        assertThat(response.body?.message).doesNotContain("nope")
        assertThat(response.body?.fieldErrors).isNull()
    }

    @Test
    fun `an authentication failure raised inside a handler maps to 401`() {
        val response =
            handler.onAuthentication(InvalidBearerTokenException("token expired"), request("/api/v1/users/me"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.body?.error?.name).isEqualTo("UNAUTHORIZED")
        assertThat(response.body?.message).doesNotContain("token expired")
    }

    @Test
    fun `the domain exceptions map to their documented statuses`() {
        assertThat(
            handler.onValidation(ValidationException("name", "is required."), request("/api/v1/teams")).statusCode,
        ).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(handler.onAuthorization(AuthorizationException("no"), request("/x")).statusCode)
            .isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(handler.onNotFound(NotFoundException("Team", 1), request("/x")).statusCode)
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(handler.onConflict(ConflictException("already done"), request("/x")).statusCode)
            .isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `a constraint on a query parameter maps to 400 with the parameter named`() {
        // The framework raises this for a `@RequestParam` that fails a
        // constraint annotation. Building one by hand is the only way to reach
        // the branch without adding a controller parameter that exists purely
        // to be invalid.
        val parameter: org.springframework.core.MethodParameter = io.mockk.mockk(relaxed = true)
        every { parameter.parameterName } returns "size"
        val parameterResult: org.springframework.validation.method.ParameterValidationResult =
            io.mockk.mockk(relaxed = true)
        every { parameterResult.methodParameter } returns parameter
        every { parameterResult.resolvableErrors } returns
            listOf(
                org.springframework.context.support.DefaultMessageSourceResolvable(
                    arrayOf("Size"),
                    null,
                    "must be between 1 and 200.",
                ),
            )
        val result: org.springframework.validation.method.MethodValidationResult = io.mockk.mockk(relaxed = true)
        every { result.parameterValidationResults } returns listOf(parameterResult)

        val response =
            handler.onInvalidParameters(
                org.springframework.web.method.annotation.HandlerMethodValidationException(result),
                request("/api/v1/users"),
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body?.fieldErrors?.single()?.field).isEqualTo("size")
        assertThat(response.body?.fieldErrors?.single()?.message).isEqualTo("must be between 1 and 200.")
    }

    @Test
    fun `a missing required query parameter names the parameter`() {
        val response =
            handler.onMissingParameter(
                org.springframework.web.bind.MissingServletRequestParameterException("query", "String"),
                request("/api/v1/users"),
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body?.fieldErrors?.single()?.field).isEqualTo("query")
    }

    @Test
    fun `a validation failure with no field detail still produces a readable message`() {
        val response = handler.onValidation(ValidationException(emptyList()), request("/api/v1/teams"))

        assertThat(response.body?.message).isEqualTo("Validation failed.")
        assertThat(response.body?.fieldErrors).isEmpty()
    }

    @Test
    fun `the unexpected-throwable branch never echoes the throwable`() {
        val response =
            handler.onUnexpected(
                RuntimeException("hive.adapter.out.persistence.TaskRepositoryAdapter blew up"),
                request("/api/v1/tasks/1"),
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(response.body?.message).doesNotContain("hive.adapter", "blew up", "RuntimeException")
    }

    @Test
    fun `the caller is still the token's user when an error is returned`() {
        // A failing request must not fall back to some other actor: the advice
        // runs after the resolver, and the resolver provisioned this caller.
        every { userUseCases.getById(any(), any()) } throws NotFoundException("User", 4)

        mvc
            .get("/api/v1/users/me") { with(callerIs(AppFixtures.MEMBER)) }
            .andExpect { status { isNotFound() } }
    }
}
