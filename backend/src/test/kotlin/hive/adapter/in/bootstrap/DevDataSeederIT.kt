package hive.adapter.`in`.bootstrap

import hive.domain.model.EmailAddress
import hive.domain.model.PageRequest
import hive.domain.model.TaskStatus
import hive.domain.port.ProjectRepository
import hive.domain.port.TaskRepository
import hive.domain.port.TeamRepository
import hive.domain.port.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

/**
 * Runs the dev seeder against a real database and checks the graph it produces.
 *
 * This is the closest thing the project has to an end-to-end test. The seeder
 * drives the **real use cases** -- policy, transition machine, mappers, JPA and
 * the Flyway-migrated schema all participate -- so a disagreement anywhere in
 * that chain fails here rather than at a developer's first sign-in.
 *
 * It is also the only test that exercises the full Spring context with every
 * layer wired together; the other suites are deliberately narrow slices.
 *
 * The `dev` profile is activated for the seeder bean, and `test` after it so the
 * H2 datasource wins. Profile order matters: later profiles take precedence.
 */
@SpringBootTest
@ActiveProfiles("dev", "test")
@TestPropertySource(
    properties = [
        // Its OWN in-memory database, not the one every other suite shares.
        //
        // The `test` profile's URL carries DB_CLOSE_DELAY=-1, so that database
        // outlives any single context and is reused across test classes. This
        // class is the one suite that deliberately commits a populated graph and
        // cannot roll it back -- the seeder manages its own transactions through
        // the use cases. Left on the shared database it silently broke
        // SchemaConstraintIT, which reasonably assumes an empty users table.
        "spring.datasource.url=jdbc:h2:mem:hive_seed;MODE=MSSQLServer;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    ],
)
class DevDataSeederIT {

    @Autowired private lateinit var runners: List<ApplicationRunner>

    @Autowired private lateinit var users: UserRepository

    @Autowired private lateinit var teams: TeamRepository

    @Autowired private lateinit var projects: ProjectRepository

    @Autowired private lateinit var tasks: TaskRepository

    private fun seed() {
        // @SpringBootTest does not run ApplicationRunners, so invoke it here.
        runners.forEach { it.run(object : ApplicationArguments {
            override fun getSourceArgs() = emptyArray<String>()
            override fun getOptionNames() = emptySet<String>()
            override fun containsOption(name: String) = false
            override fun getOptionValues(name: String) = emptyList<String>()
            override fun getNonOptionArgs() = emptyList<String>()
        }) }
    }

    @Test
    @DisplayName("seeds a graph covering every task status, through the real use cases")
    fun `seeds the demonstration graph`() {
        seed()

        val ada = users.findByEmail(EmailAddress("ada@hive.example"))
        val grace = users.findByEmail(EmailAddress("grace@hive.example"))
        val alan = users.findByEmail(EmailAddress("alan@hive.example"))
        val katherine = users.findByEmail(EmailAddress("katherine@hive.example"))
        assertThat(listOf(ada, grace, alan, katherine)).doesNotContainNull()

        // Every status is represented. This is the assertion that matters: each
        // of these was reached by a legal transition performed by a user
        // entitled to make it, not by inserting the row.
        val platformProjects = teams.findTeamsLedBy(grace!!.id!!)
            .flatMap { projects.findByTeam(it.id!!) }
        val allTasks = platformProjects.flatMap {
            tasks.findVisibleInProject(it.id!!, ada!!.id!!, PageRequest(0, 200)).content
        }
        // Ada owns Apiary, so she sees its tasks in every status (VIS-2).
        assertThat(allTasks.map { it.status })
            .contains(TaskStatus.DRAFT, TaskStatus.TODO, TaskStatus.IN_PROGRESS)

        // The completed and canceled ones live in Grace's project.
        val graceView = platformProjects.flatMap {
            tasks.findVisibleInProject(it.id!!, grace.id!!, PageRequest(0, 200)).content
        }
        assertThat(graceView.map { it.status })
            .contains(TaskStatus.COMPLETED, TaskStatus.CANCELED)

        // The lead's attention queue is populated -- the point of UQ-1.
        val queue = tasks.findUnassignedForLead(grace.id!!, PageRequest(0, 200))
        assertThat(queue.content).isNotEmpty
        assertThat(queue.content).allSatisfy {
            assertThat(it.status).isEqualTo(TaskStatus.TODO)
            assertThat(it.assignee).isNull()
        }

        // AS-4 held throughout: Ada owns Apiary and is a member of Platform, yet
        // nothing in a project she owns is assigned to her.
        val adaOwned = projects.findVisibleTo(ada!!.id!!).filter { it.projectOwner == ada.id }
        val assignedToAdaInHerOwnProjects = adaOwned.flatMap {
            tasks.findVisibleInProject(it.id!!, ada.id!!, PageRequest(0, 200)).content
        }.filter { it.assignee == ada.id }
        assertThat(assignedToAdaInHerOwnProjects)
            .describedAs("AS-4: a project owner is never the assignee in their own project")
            .isEmpty()
    }

    @Test
    @DisplayName("is idempotent: seeding twice does not duplicate the graph")
    fun `does not duplicate on a second run`() {
        seed()
        val after1 = users.search(null, PageRequest(0, 200)).totalElements
        seed()
        val after2 = users.search(null, PageRequest(0, 200)).totalElements

        assertThat(after2)
            .describedAs("a restarted dev container must not re-seed")
            .isEqualTo(after1)
    }
}
