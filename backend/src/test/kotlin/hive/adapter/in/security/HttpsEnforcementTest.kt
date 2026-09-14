package hive.adapter.`in`.security

import hive.adapter.`in`.MockedJwtDecoder
import hive.adapter.`in`.MockedUseCases
import hive.adapter.`in`.error.ApiExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * AU-3 -- HTTPS is enforced in production and only in production.
 *
 * Both halves are asserted. The prod half is what the requirement says; the
 * non-prod half is what keeps every MockMvc test in this package from becoming
 * an assertion about a 302 to `https://localhost`, and is therefore just as
 * easy to break by accident.
 */
class HttpsEnforcementConfigTest {

    private val runner = ApplicationContextRunner().withUserConfiguration(HttpsEnforcementConfig::class.java)

    @Test
    fun `the channel rule is registered under the prod profile`() {
        runner.withPropertyValues("spring.profiles.active=prod").run { context ->
            assertThat(context).hasSingleBean(HttpSecurityCustomizer::class.java)
        }
    }

    @Test
    fun `it is absent under dev and test`() {
        listOf("dev", "test").forEach { profile ->
            runner.withPropertyValues("spring.profiles.active=$profile").run { context ->
                assertThat(context).doesNotHaveBean(HttpSecurityCustomizer::class.java)
            }
        }
    }
}

/**
 * The same rule observed from outside: under `prod`, a plain-HTTP request is
 * redirected to the same URL over TLS instead of being served.
 *
 * Run as a web slice rather than a full context because `prod` demands a real
 * SQL Server from the environment; the filter chain is the whole subject here,
 * and it is the real one.
 */
@WebMvcTest
@ActiveProfiles("prod")
@Import(
    MockedUseCases::class,
    MockedJwtDecoder::class,
    SecurityConfig::class,
    HttpsEnforcementConfig::class,
    WebMvcConfig::class,
    CurrentUserArgumentResolver::class,
    ApiAuthenticationEntryPoint::class,
    ApiAccessDeniedHandler::class,
    ApiExceptionHandler::class,
)
@TestPropertySource(
    properties = ["spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.invalid"],
)
class HttpsEnforcedInProdTest {

    @Autowired
    private lateinit var mvc: MockMvc

    @Test
    fun `a plain HTTP request is redirected to HTTPS, not served`() {
        mvc
            .get("http://localhost/api/v1/users/me")
            .andExpect {
                status { is3xxRedirection() }
                redirectedUrl("https://localhost/api/v1/users/me")
            }
    }

    @Test
    fun `even the public health probe is redirected`() {
        // The channel rule is `anyRequest`: permitting a path without a token is
        // not the same as permitting it without TLS.
        mvc
            .get("http://localhost/actuator/health")
            .andExpect { status { is3xxRedirection() } }
    }

    @Test
    fun `a request that already arrived over HTTPS is served normally`() {
        mvc
            .get("https://localhost/api/v1/users/me")
            .andExpect { status { isUnauthorized() } }
    }
}
