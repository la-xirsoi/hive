package hive.adapter.`in`

import hive.adapter.`in`.error.ApiExceptionHandler
import hive.adapter.`in`.security.ApiAccessDeniedHandler
import hive.adapter.`in`.security.ApiAuthenticationEntryPoint
import hive.adapter.`in`.security.CurrentUserArgumentResolver
import hive.adapter.`in`.security.SecurityConfig
import hive.adapter.`in`.security.WebMvcConfig
import hive.application.AppFixtures
import hive.application.usecase.CommentUseCases
import hive.application.usecase.ProjectUseCases
import hive.application.usecase.TaskUseCases
import hive.application.usecase.TeamUseCases
import hive.application.usecase.UserUseCases
import hive.application.view.CommentView
import hive.application.view.ProjectView
import hive.application.view.TaskDetailView
import hive.application.view.TaskPermissions
import hive.application.view.TaskSummaryView
import hive.application.view.TeamView
import hive.domain.model.Task
import hive.domain.model.TaskStatus
import hive.domain.model.User
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor

/**
 * The web-layer test harness.
 *
 * Every controller test runs against the **real** filter chain, the real
 * argument resolver and the real exception handler, with only the five inbound
 * ports mocked. That is deliberate: the things this layer is responsible for --
 * the status code, the JSON shape, the 401, the resolved actor -- are all
 * produced by the pieces between the socket and the port, so stubbing any of
 * them out would test the mock instead of the adapter.
 *
 * What is *not* here: an authorization decision. Those belong to the domain
 * policy and are covered by its own tests; here a 403 means "the port threw
 * [hive.domain.error.AuthorizationException] and the adapter answered 403".
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@WebMvcTest
@ActiveProfiles("test")
@Import(
    MockedUseCases::class,
    MockedJwtDecoder::class,
    SecurityConfig::class,
    WebMvcConfig::class,
    CurrentUserArgumentResolver::class,
    ApiAuthenticationEntryPoint::class,
    ApiAccessDeniedHandler::class,
    ApiExceptionHandler::class,
)
annotation class ApiWebTest

/** The five inbound ports, mocked. */
@TestConfiguration
class MockedUseCases {

    @Bean
    fun userUseCases(): UserUseCases = mockk()

    @Bean
    fun teamUseCases(): TeamUseCases = mockk()

    @Bean
    fun projectUseCases(): ProjectUseCases = mockk()

    @Bean
    fun taskUseCases(): TaskUseCases = mockk()

    @Bean
    fun commentUseCases(): CommentUseCases = mockk()
}

/**
 * A stand-in for the resource server's [JwtDecoder].
 *
 * It is never *used*: `SecurityMockMvcRequestPostProcessors.jwt()` installs an
 * already-authenticated token, and the unauthenticated tests send none at all.
 * It exists because `oauth2ResourceServer { jwt }` needs a decoder bean to
 * start, and mocking it keeps the tests off the network. Kept apart from
 * [MockedUseCases] so that the dev-profile test can bring the real, locally
 * keyed decoder instead.
 */
@TestConfiguration
class MockedJwtDecoder {

    @Bean
    fun jwtDecoder(): JwtDecoder = mockk()
}

/**
 * Base class for the controller tests: the mocks, the `MockMvc`, and a caller.
 *
 * The mocks are context-scoped singletons -- Spring caches one context for the
 * whole suite -- so they are cleared before each test rather than rebuilt.
 */
@ApiWebTest
abstract class ApiWebTestBase {

    @Autowired
    protected lateinit var mvc: MockMvc

    @Autowired
    protected lateinit var userUseCases: UserUseCases

    @Autowired
    protected lateinit var teamUseCases: TeamUseCases

    @Autowired
    protected lateinit var projectUseCases: ProjectUseCases

    @Autowired
    protected lateinit var taskUseCases: TaskUseCases

    @Autowired
    protected lateinit var commentUseCases: CommentUseCases

    @BeforeEach
    fun resetMocks() {
        clearMocks(userUseCases, teamUseCases, projectUseCases, taskUseCases, commentUseCases)
        // Resolving @CurrentUser provisions the caller (US-3); nearly every test
        // needs it, and the ones about provisioning itself override it.
        every { userUseCases.provisionFromPrincipal(any()) } returns AppFixtures.OWNER
    }

    /** A caller holding a valid token for [user]. */
    protected fun callerIs(user: User = AppFixtures.OWNER): RequestPostProcessor =
        jwt().jwt { builder ->
            builder
                .subject("idp|${user.id}")
                .claim("email", user.email.value)
                .claim("name", user.name.value)
        }
}

/** Views built from the shared cast of characters, so the JSON assertions read the same everywhere. */
object WebFixtures {

    val TEAM_VIEW =
        TeamView(
            team = AppFixtures.TEAM,
            lead = AppFixtures.LEAD,
            members = listOf(AppFixtures.LEAD, AppFixtures.ASSIGNEE, AppFixtures.MEMBER),
        )

    val PROJECT_VIEW =
        ProjectView(project = AppFixtures.PROJECT, owner = AppFixtures.OWNER, team = TEAM_VIEW)

    fun summaryView(task: Task = AppFixtures.task(TaskStatus.TODO)): TaskSummaryView =
        TaskSummaryView(
            task = task,
            projectName = AppFixtures.PROJECT.name,
            assignee = task.assignee?.let { id -> AppFixtures.EVERYONE.first { it.id == id } },
        )

    fun detailView(
        task: Task = AppFixtures.task(TaskStatus.TODO),
        permissions: TaskPermissions = PERMISSIONS,
    ): TaskDetailView =
        TaskDetailView(
            task = task,
            project = PROJECT_VIEW,
            creator = AppFixtures.OWNER,
            assignee = task.assignee?.let { id -> AppFixtures.EVERYONE.first { it.id == id } },
            permissions = permissions,
        )

    val PERMISSIONS =
        TaskPermissions(
            canEdit = true,
            canAssign = false,
            canComment = true,
            // Out of declaration order on purpose: the mapper is expected to
            // publish the contract's order regardless of the set's iteration.
            allowedTransitions = setOf(TaskStatus.CANCELED, TaskStatus.IN_PROGRESS),
        )

    val COMMENT_VIEW = CommentView(comment = AppFixtures.comment(1), author = AppFixtures.MEMBER)
}
