package hive.adapter.`in`.security

import hive.adapter.`in`.ApiWebTestBase
import hive.application.AppFixtures
import hive.application.usecase.PrincipalClaims
import hive.application.usecase.UserUseCases
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.ServletWebRequest
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * The filter chain's own behaviour: who may pass without a token, what an
 * unauthenticated request receives, and how the acting user is resolved.
 */
class SecurityConfigTest : ApiWebTestBase() {

    @Test
    fun `an unauthenticated API request is 401 with the contract's body, not an empty response`() {
        mvc
            .get("/api/v1/teams/mine")
            .andExpect {
                status { isUnauthorized() }
                content { contentType("application/json;charset=UTF-8") }
                jsonPath("$.error") { value("UNAUTHORIZED") }
                jsonPath("$.message") { value("Authentication is required to access this resource.") }
                jsonPath("$.path") { value("/api/v1/teams/mine") }
            }
    }

    @Test
    fun `a mutating request needs no CSRF token`() {
        // Bearer tokens and no cookies mean no CSRF vector; if the token were
        // still required, this POST would be a 403 instead of reaching the
        // handler and failing validation.
        mvc
            .post("/api/v1/teams") {
                with(callerIs())
                contentType = org.springframework.http.MediaType.APPLICATION_JSON
                content = """{}"""
            }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `the public paths are not challenged for a token`() {
        // These are not mapped in a web-layer slice, so the assertion is that
        // they are answered as "no such handler" rather than "no token".
        listOf("/actuator/health", "/v3/api-docs", "/swagger-ui/index.html").forEach { path ->
            mvc.get(path).andExpect { status { isNotFound() } }
        }
    }

    @Test
    fun `the dev token route does not exist outside the dev profile`() {
        // `docs/api-contract.md` section 9: absent under any profile but dev,
        // and absent means 404 -- not 401, which would suggest it is there.
        mvc
            .post("/api/v1/dev/token") {
                contentType = org.springframework.http.MediaType.APPLICATION_JSON
                content = """{"email":"olive@hive.test"}"""
            }.andExpect {
                status { isNotFound() }
                jsonPath("$.error") { value("NOT_FOUND") }
            }
    }

    @Test
    fun `the acting user comes from the token and is provisioned once per request`() {
        every { userUseCases.getById(any(), any()) } returns AppFixtures.MEMBER

        mvc.get("/api/v1/users/me") { with(callerIs(AppFixtures.MEMBER)) }.andExpect { status { isOk() } }

        verify(exactly = 1) { userUseCases.provisionFromPrincipal(any()) }
    }
}

/**
 * [CurrentUserArgumentResolver] on its own, for the paths a MockMvc request
 * cannot reach: no authentication at all, a principal that is not a JWT, and a
 * token with no email claim.
 */
class CurrentUserArgumentResolverTest {

    private val users: UserUseCases = mockk()
    private val resolver = CurrentUserArgumentResolver(users)

    private fun webRequest() = ServletWebRequest(MockHttpServletRequest())

    private fun tokenFor(email: String?, name: String? = "Olive Owner"): Jwt {
        val claims = buildMap<String, Any> {
            put("sub", "idp|1")
            email?.let { put("email", it) }
            name?.let { put("name", it) }
        }
        return Jwt(
            "token-value",
            Instant.now(),
            Instant.now().plusSeconds(60),
            mapOf("alg" to "RS256"),
            claims,
        )
    }

    private fun authenticate(jwt: Jwt) {
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @org.junit.jupiter.api.AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `resolves the Hive user id from the token's email claim`() {
        authenticate(tokenFor("olive@hive.test"))
        val claims = io.mockk.slot<PrincipalClaims>()
        every { users.provisionFromPrincipal(capture(claims)) } returns AppFixtures.OWNER

        val resolved = resolver.resolveArgument(mockk(relaxed = true), null, webRequest(), null)

        assertThat(resolved.id).isEqualTo(AppFixtures.OWNER_ID)
        assertThat(claims.captured.subject).isEqualTo("idp|1")
        assertThat(claims.captured.name).isEqualTo("Olive Owner")
    }

    @Test
    fun `caches the resolved user for the rest of the request`() {
        authenticate(tokenFor("olive@hive.test"))
        every { users.provisionFromPrincipal(any()) } returns AppFixtures.OWNER
        val request = webRequest()

        resolver.resolveArgument(mockk(relaxed = true), null, request, null)
        resolver.resolveArgument(mockk(relaxed = true), null, request, null)

        verify(exactly = 1) { users.provisionFromPrincipal(any()) }
        assertThat(request.getAttribute("hive.currentUserId", RequestAttributes.SCOPE_REQUEST)).isNotNull()
    }

    @Test
    fun `an unauthenticated context is a 401-shaped failure, never a null actor`() {
        assertThatThrownBy { resolver.resolveArgument(mockk(relaxed = true), null, webRequest(), null) }
            .isInstanceOf(InvalidBearerTokenException::class.java)
    }

    @Test
    fun `a token with no email claim cannot be matched to a Hive user`() {
        authenticate(tokenFor(email = null))

        assertThatThrownBy { resolver.resolveArgument(mockk(relaxed = true), null, webRequest(), null) }
            .isInstanceOf(InvalidBearerTokenException::class.java)
    }

    @Test
    fun `a blank name claim is treated as absent rather than provisioned as a blank name`() {
        authenticate(tokenFor("olive@hive.test", name = "   "))
        val claims = io.mockk.slot<PrincipalClaims>()
        every { users.provisionFromPrincipal(capture(claims)) } returns AppFixtures.OWNER

        resolver.resolveArgument(mockk(relaxed = true), null, webRequest(), null)

        assertThat(claims.captured.name).isNull()
    }

    @Test
    fun `an authentication that is not a bearer token is refused`() {
        // Nothing in the chain should produce one, but the resolver must not
        // hand the application layer an actor it could not verify.
        SecurityContextHolder.getContext().authentication =
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "olive",
                "secret",
                emptyList(),
            )

        assertThatThrownBy { resolver.resolveArgument(mockk(relaxed = true), null, webRequest(), null) }
            .isInstanceOf(InvalidBearerTokenException::class.java)
    }

    @Test
    fun `it claims only its own annotated parameter`() {
        val unannotated: org.springframework.core.MethodParameter = mockk(relaxed = true)
        every { unannotated.hasParameterAnnotation(CurrentUser::class.java) } returns false

        assertThat(resolver.supportsParameter(unannotated)).isFalse()
    }
}

/** The two filter-chain exits write the same body the advice writes. */
class ApiSecurityErrorHandlersTest {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `the entry point writes a 401 in the contract's shape`() {
        val request = MockHttpServletRequest("GET", "/api/v1/teams/mine").apply { requestURI = "/api/v1/teams/mine" }
        val response = MockHttpServletResponse()

        ApiAuthenticationEntryPoint(mapper).commence(request, response, InvalidBearerTokenException("expired at 12:00"))

        assertThat(response.status).isEqualTo(HttpStatus.UNAUTHORIZED.value())
        assertThat(response.contentType).isEqualTo("application/json;charset=UTF-8")
        val body = mapper.readTree(response.contentAsString)
        assertThat(body["error"].asString()).isEqualTo("UNAUTHORIZED")
        assertThat(body["path"].asString()).isEqualTo("/api/v1/teams/mine")
        assertThat(body["fieldErrors"]).isNull()
        // The token's own diagnostics stay server-side.
        assertThat(response.contentAsString).doesNotContain("expired at 12:00")
    }

    @Test
    fun `the access-denied handler writes a 403 in the contract's shape`() {
        val request = MockHttpServletRequest("GET", "/api/v1/teams/1").apply { requestURI = "/api/v1/teams/1" }
        val response: HttpServletResponse = MockHttpServletResponse()

        ApiAccessDeniedHandler(mapper).handle(request, response, AccessDeniedException("denied"))

        assertThat(response.status).isEqualTo(HttpStatus.FORBIDDEN.value())
        assertThat((response as MockHttpServletResponse).contentAsString).contains("\"error\":\"FORBIDDEN\"")
    }

    @Test
    fun `the chain's entry point is ours, not Spring's default`() {
        // A guard against a future refactor quietly dropping the custom entry
        // point and leaving clients with an empty 401 body again.
        assertThat(ApiAuthenticationEntryPoint(mapper)).isNotInstanceOf(HttpStatusEntryPoint::class.java)
    }
}
