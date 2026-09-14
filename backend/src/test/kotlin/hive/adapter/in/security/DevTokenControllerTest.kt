package hive.adapter.`in`.security

import hive.adapter.`in`.MockedUseCases
import hive.adapter.`in`.error.ApiExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper

/**
 * `docs/api-contract.md` section 9 -- the dev-profile token endpoint.
 *
 * The test that matters most is the last one: a token minted here is accepted
 * by the very [JwtDecoder] the resource server uses. That is what makes the dev
 * sign-in evidence about the production path rather than a parallel
 * implementation of it.
 */
@WebMvcTest
@ActiveProfiles("dev")
@Import(
    MockedUseCases::class,
    DevAuthConfig::class,
    DevTokenController::class,
    SecurityConfig::class,
    WebMvcConfig::class,
    CurrentUserArgumentResolver::class,
    ApiAuthenticationEntryPoint::class,
    ApiAccessDeniedHandler::class,
    ApiExceptionHandler::class,
)
@TestPropertySource(
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://dev.hive.local",
        "hive.dev-auth.token-ttl-seconds=900",
    ],
)
class DevTokenControllerTest {

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var userUseCases: hive.application.usecase.UserUseCases

    @org.junit.jupiter.api.BeforeEach
    fun stubProvisioning() {
        // Resolving @CurrentUser on the ordinary endpoint below provisions the
        // caller; the dev endpoint itself never touches a use case.
        io.mockk.every { userUseCases.provisionFromPrincipal(any()) } returns hive.application.AppFixtures.OWNER
    }

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `POST dev token returns the shape the Angular dev sign-in expects`() {
        mvc
            .post("/api/v1/dev/token") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"olive@hive.test","name":"Olive Owner"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.accessToken") { exists() }
                jsonPath("$.expiresIn") { value(900) }
            }
    }

    @Test
    fun `the endpoint needs no token of its own`() {
        // It is the way a caller gets their first token; requiring one would
        // make it useless.
        mvc
            .post("/api/v1/dev/token") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"olive@hive.test"}"""
            }.andExpect { status { isOk() } }
    }

    @Test
    fun `it rejects a malformed email with a field error`() {
        mvc
            .post("/api/v1/dev/token") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"not-an-address"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.fieldErrors[0].field") { value("email") }
            }
    }

    @Test
    fun `it rejects a missing email`() {
        mvc
            .post("/api/v1/dev/token") {
                contentType = MediaType.APPLICATION_JSON
                content = """{}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.fieldErrors[0].field") { value("email") }
            }
    }

    @Test
    fun `the minted token is accepted by the resource server's own decoder`() {
        val response =
            mvc
                .post("/api/v1/dev/token") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"email":"olive@hive.test","name":"Olive Owner"}"""
                }.andReturn()
                .response
                .contentAsString

        val token = mapper.readTree(response)["accessToken"].asString()
        val jwt = jwtDecoder.decode(token)

        assertThat(jwt.issuer.toString()).isEqualTo("https://dev.hive.local")
        assertThat(jwt.subject).isEqualTo("olive@hive.test")
        assertThat(jwt.getClaimAsString("email")).isEqualTo("olive@hive.test")
        assertThat(jwt.getClaimAsString("name")).isEqualTo("Olive Owner")
        assertThat(jwt.expiresAt).isAfter(jwt.issuedAt)
    }

    @Test
    fun `a token minted without a name carries no name claim to overwrite an existing user's`() {
        val response =
            mvc
                .post("/api/v1/dev/token") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"email":"olive@hive.test"}"""
                }.andReturn()
                .response
                .contentAsString

        val jwt = jwtDecoder.decode(mapper.readTree(response)["accessToken"].asString())

        assertThat(jwt.getClaimAsString("name")).isNull()
    }

    @Test
    fun `a dev token authenticates an ordinary API request`() {
        // The point of section 9: the token travels the ordinary bearer path.
        val token =
            mapper
                .readTree(
                    mvc
                        .post("/api/v1/dev/token") {
                            contentType = MediaType.APPLICATION_JSON
                            content = """{"email":"olive@hive.test"}"""
                        }.andReturn()
                        .response
                        .contentAsString,
                )["accessToken"]
                .asString()

        mvc
            .post("/api/v1/teams") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = """{}"""
                // A 400 (not a 401) proves the token was accepted and the
                // request reached validation.
            }.andExpect { status { isBadRequest() } }
    }
}
