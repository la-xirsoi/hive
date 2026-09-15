package hive.adapter.`in`.bootstrap

import hive.application.usecase.AddCommentCommand
import hive.application.usecase.AssignTaskCommand
import hive.application.usecase.CommentUseCases
import hive.application.usecase.CreateProjectCommand
import hive.application.usecase.CreateTaskCommand
import hive.application.usecase.CreateTeamCommand
import hive.application.usecase.PrincipalClaims
import hive.application.usecase.ProjectUseCases
import hive.application.usecase.TaskUseCases
import hive.application.usecase.TeamUseCases
import hive.application.usecase.TransitionTaskCommand
import hive.application.usecase.UserUseCases
import hive.domain.model.ProjectId
import hive.domain.model.TaskId
import hive.domain.model.TaskStatus
import hive.domain.model.TeamId
import hive.domain.model.UserId
import hive.domain.port.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * Seeds a demonstration graph under the `dev` profile.
 *
 * **Everything here is created by calling the real use cases as the user who
 * would really have done it.** Nothing is written straight to a repository.
 * That costs a few more lines than inserting rows, and buys two things:
 *
 * 1. Every seeded state is provably *reachable*. A `Completed` task exists only
 *    because an owner created and published it, a lead assigned it, and the
 *    assignee started and finished it -- in that order, through the same
 *    authorization policy that guards production. A seeder that inserts a
 *    `Completed` row directly can happily manufacture states the application
 *    itself would reject.
 * 2. It doubles as an end-to-end smoke test against a real database. If the
 *    policy, the transition machine, the mappers or the schema disagree, the
 *    application fails loudly at startup rather than serving a broken graph.
 *
 * The cast's email addresses match the users in the Keycloak realm
 * (`containers/idp/realm-hive.json`) so that signing in through the identity
 * provider lands on a seeded account rather than an empty new one.
 *
 * Seeding is skipped entirely if any user already exists, so restarting a dev
 * container does not duplicate the graph.
 */
@Configuration
@Profile("dev")
class DevDataSeeder {

    private val log = LoggerFactory.getLogger(DevDataSeeder::class.java)

    @Bean
    fun seedDemonstrationData(
        users: UserUseCases,
        teams: TeamUseCases,
        projects: ProjectUseCases,
        tasks: TaskUseCases,
        comments: CommentUseCases,
        userRepository: UserRepository,
    ) = ApplicationRunner {
        if (userRepository.search(null, hive.domain.model.PageRequest.DEFAULT).totalElements > 0) {
            log.info("Dev data already present; skipping seed.")
            return@ApplicationRunner
        }

        log.info("Seeding dev demonstration data...")

        // --- The cast ---------------------------------------------------------
        // Provisioned exactly as the security layer would on first sign-in.
        val ada = provision(users, "ada@hive.example", "Ada Lovelace")
        val grace = provision(users, "grace@hive.example", "Grace Hopper")
        val alan = provision(users, "alan@hive.example", "Alan Turing")
        val katherine = provision(users, "katherine@hive.example", "Katherine Johnson")

        // --- Teams ------------------------------------------------------------
        // Grace leads Platform; Ada leads Research. Membership overlaps
        // deliberately: Alan is on both, so "my teams" is more than one row and
        // the visibility rules have something to actually discriminate.
        val platform = teams.create(grace, CreateTeamCommand("Platform")).requireId()
        teams.addMember(grace, platform, alan)
        teams.addMember(grace, platform, katherine)

        val research = teams.create(ada, CreateTeamCommand("Research")).requireId()
        teams.addMember(ada, research, alan)

        // --- Projects ---------------------------------------------------------
        // Ada joins Platform because PR-2 requires a project's creator to be a
        // member or lead of the team it is bound to. She then owns Apiary while
        // being an ordinary member of the team that works it -- which is exactly
        // the case AS-4 exists for: she can never be assigned her own project's
        // tasks, so the assignee picker must exclude her even though she is a
        // perfectly valid member of that team.
        teams.addMember(grace, platform, ada)
        val apiary = projects.create(ada, CreateProjectCommand("Apiary", platform)).requireId()
        val nectar = projects.create(grace, CreateProjectCommand("Nectar", platform)).requireId()
        val pollen = projects.create(ada, CreateProjectCommand("Pollen Analysis", research)).requireId()

        // --- Tasks, each reaching its status the way a user would -------------

        // Draft: created, not yet published. Only Ada can see this one.
        val draft = tasks.create(
            ada,
            CreateTaskCommand(apiary, "Design the hive dashboard", "Wireframes for the landing view."),
        ).requireId()

        // Todo, unassigned: the state that populates Grace's attention queue.
        val unassigned = publish(tasks, ada, apiary, "Add task filtering", "Filter the project task list by status.")

        // A second one, so the queue is visibly a queue rather than a single row.
        publish(tasks, ada, apiary, "Write the onboarding guide", "Short guide for a new team member.")

        // Todo, assigned: waiting for its assignee to start.
        val assigned = publish(tasks, ada, apiary, "Export tasks to CSV", "Let a lead export a project's tasks.")
        tasks.assign(grace, assigned, AssignTaskCommand(alan))

        // In Progress: assigned, then started by the assignee.
        val inProgress = publish(tasks, ada, apiary, "Fix timestamp rendering", "Comment times must show in local time.")
        tasks.assign(grace, inProgress, AssignTaskCommand(katherine))
        tasks.transition(katherine, inProgress, TransitionTaskCommand(TaskStatus.IN_PROGRESS))

        // Completed: the full chain, finished by the assignee.
        val completed = publish(tasks, grace, nectar, "Provision the staging database", "Stand up SQL Server for staging.")
        tasks.assign(grace, completed, AssignTaskCommand(alan))
        tasks.transition(alan, completed, TransitionTaskCommand(TaskStatus.IN_PROGRESS))
        tasks.transition(alan, completed, TransitionTaskCommand(TaskStatus.COMPLETED))

        // Canceled: published, then canceled by the project owner.
        val canceled = publish(tasks, grace, nectar, "Migrate to the legacy API", "Superseded before it started.")
        tasks.transition(grace, canceled, TransitionTaskCommand(TaskStatus.CANCELED))

        // A task in the Research project, so Alan's cross-team membership shows.
        val research1 = publish(tasks, ada, pollen, "Collect field samples", "Three sites, two weeks.")
        tasks.assign(ada, research1, AssignTaskCommand(alan))

        // --- Comments ---------------------------------------------------------
        comments.add(katherine, inProgress, AddCommentCommand("Reproduced -- the server sends UTC, we render it raw."))
        comments.add(ada, inProgress, AddCommentCommand("Thanks. Local time only, please, with the UTC instant kept in the markup."))
        comments.add(alan, completed, AddCommentCommand("Staging is up. Connection details are in the runbook."))

        log.info(
            "Seeded 4 users, 2 teams, 3 projects, 8 tasks covering all five " +
                "statuses (2 of them unassigned, for the lead's queue) and 3 comments.",
        )
        log.info("Sign in as any of: ada@, grace@, alan@, katherine@hive.example")
        log.debug("Draft task {} is visible only to its project owner; unassigned example {}", draft.value, unassigned.value)
    }

    private fun provision(users: UserUseCases, email: String, name: String): UserId =
        users.provisionFromPrincipal(
            PrincipalClaims(subject = "dev|$email", email = email, name = name),
        ).id ?: error("provisioned user $email has no id")

    /** Create a task as its project owner and publish it to `Todo`, as R01 requires. */
    private fun publish(
        tasks: TaskUseCases,
        owner: UserId,
        project: ProjectId,
        name: String,
        description: String,
    ): TaskId {
        val created = tasks.create(owner, CreateTaskCommand(project, name, description)).requireId()
        tasks.transition(owner, created, TransitionTaskCommand(TaskStatus.TODO))
        return created
    }
}

// The views wrap domain entities rather than exposing ids directly, and a
// freshly saved entity always has one -- these unwrap that with a message that
// names the thing, rather than a bare !! at nine call sites.
private fun hive.application.view.TeamView.requireId(): TeamId =
    team.id ?: error("team '${team.name}' was created without an id")

private fun hive.application.view.ProjectView.requireId(): ProjectId =
    project.id ?: error("project '${project.name}' was created without an id")

private fun hive.application.view.TaskDetailView.requireId(): TaskId =
    task.id ?: error("task '${task.name}' was created without an id")
