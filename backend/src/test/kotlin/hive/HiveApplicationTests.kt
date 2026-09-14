package hive

import hive.adapter.`in`.controller.CommentController
import hive.adapter.`in`.controller.ProjectController
import hive.adapter.`in`.controller.TaskController
import hive.adapter.`in`.controller.TeamController
import hive.adapter.`in`.controller.UserController
import hive.adapter.`in`.security.HttpSecurityCustomizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.ActiveProfiles

/**
 * Context-load smoke test. Runs against the `test` profile, which points at an
 * embedded H2 database in SQL Server compatibility mode, so no live SQL Server
 * is required.
 *
 * The web-layer tests run with a sliced context and mocked ports, so this is the
 * one test that proves the real thing starts: every controller wired to a real
 * service, the security filter chain built, and a JWT decoder produced from the
 * configured issuer.
 */
@SpringBootTest
@ActiveProfiles("test")
class HiveApplicationTests {

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `application context loads`() {
        assertThat(context).isNotNull
    }

    @Test
    fun `component scanning reaches the backtick-escaped adapter in package`() {
        assertThat(context.getBean(UserController::class.java)).isNotNull
        assertThat(context.getBean(TeamController::class.java)).isNotNull
        assertThat(context.getBean(ProjectController::class.java)).isNotNull
        assertThat(context.getBean(TaskController::class.java)).isNotNull
        assertThat(context.getBean(CommentController::class.java)).isNotNull
    }

    @Test
    fun `security is wired as a resource server`() {
        assertThat(context.getBeanNamesForType(SecurityFilterChain::class.java)).isNotEmpty()
        assertThat(context.getBean(JwtDecoder::class.java)).isNotNull
    }

    @Test
    fun `the dev token endpoint is not registered outside the dev profile`() {
        assertThat(context.getBeanNamesForType(Class.forName("hive.adapter.in.security.DevTokenController")))
            .isEmpty()
    }

    @Test
    fun `HTTPS is not enforced outside the prod profile`() {
        // AU-3 is prod-only by design: MockMvc speaks plain HTTP, and a channel
        // redirect here would make every web-layer test an assertion about a 302.
        assertThat(context.getBeanNamesForType(HttpSecurityCustomizer::class.java)).isEmpty()
    }
}
