package hive.adapter.out.persistence

import hive.domain.model.Page
import hive.domain.model.PageRequest
import hive.domain.model.Project
import hive.domain.model.ProjectId
import hive.domain.model.Task
import hive.domain.model.TaskId
import hive.domain.model.TaskStatus
import hive.domain.model.Team
import hive.domain.model.TeamId
import hive.domain.model.UserId
import hive.domain.port.ProjectRepository
import hive.domain.port.TaskRepository
import hive.domain.port.TeamRepository
import hive.domain.port.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The visibility tests -- the ones a leak has to get past.
 *
 * Each assertion names the rule from `docs/authorization.md` section 4 or 5.2
 * that it enforces and compares against an **exact** set of task ids, not a
 * count and not a subset. An over-broad query is a security defect, so
 * `containsExactly` is the only assertion that means anything here: a test that
 * only checked "the lead sees the Todo task" would pass just as happily against
 * a query that returned every task in the database.
 *
 * The fixture graph is described in [HiveGraph].
 */
@DisplayName("role-scoped queries")
class RoleScopedQueryIT : PersistenceIntegrationTest() {

    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var teams: TeamRepository
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var tasks: TaskRepository

    private lateinit var g: HiveGraph

    @BeforeEach
    fun buildGraph() {
        g = HiveGraph(users, teams, projects, tasks)
    }

    // =========================================================================
    // findVisibleInProject -- VIS-1 .. VIS-5
    // =========================================================================

    @Nested
    @DisplayName("findVisibleInProject")
    inner class VisibleInProject {

        @Test
        fun `VIS-2 -- the project owner sees every task, Draft and Canceled included`() {
            assertVisible(g.apiary, g.owner, g.allApiaryTasks)
        }

        @Test
        fun `VIS-3 -- the team lead sees everything except Draft`() {
            assertVisible(
                g.apiary,
                g.lead,
                listOf(g.todoOpen, g.todoAssigned, g.inProgress, g.completed, g.canceledOpen, g.canceledAssigned),
            )
        }

        @Test
        fun `VIS-4 and VIS-5 -- a member sees neither Draft nor Canceled, but keeps their own canceled assignment`() {
            assertVisible(
                g.apiary,
                g.member,
                listOf(g.todoOpen, g.todoAssigned, g.inProgress, g.completed, g.canceledAssigned),
            )
        }

        @Test
        fun `VIS-4 -- a member who is an assignee of no canceled task does not see canceledAssigned`() {
            assertVisible(
                g.apiary,
                g.assignee,
                listOf(g.todoOpen, g.todoAssigned, g.inProgress, g.completed),
            )
        }

        @Test
        fun `VIS-4 -- a member holding nothing sees the same as any other plain member`() {
            assertVisible(
                g.apiary,
                g.cross,
                listOf(g.todoOpen, g.todoAssigned, g.inProgress, g.completed),
            )
        }

        @Test
        fun `an outsider sees nothing at all`() {
            assertVisible(g.apiary, g.outsider, emptyList())
            assertVisible(g.nectar, g.outsider, emptyList())
            assertVisible(g.pollen, g.outsider, emptyList())
        }

        @Test
        fun `leading one team grants no sight of another team's project`() {
            assertVisible(g.apiary, g.betaLead, emptyList())
            assertVisible(g.pollen, g.lead, emptyList())
        }

        @Test
        fun `INV-2 -- owning one project of a team grants no sight of another project of that team`() {
            // `owner` owns apiary and is NOT an Alpha member, so nectar -- the
            // other Alpha project -- is entirely invisible to them.
            assertVisible(g.nectar, g.owner, emptyList())
        }

        @Test
        fun `overlapping roles union rather than shadow -- the lead who owns a project sees its Draft too`() {
            assertVisible(g.nectar, g.lead, listOf(g.nectarDraft, g.nectarTodo))
        }

        @Test
        fun `VIS-4 -- a plain member of the same team sees that project's non-Draft tasks only`() {
            assertVisible(g.nectar, g.member, listOf(g.nectarTodo))
        }

        @Test
        fun `membership of the second team is scoped to the second team's project`() {
            assertVisible(g.pollen, g.cross, listOf(g.pollenTodo, g.pollenInProgress))
        }

        @Test
        fun `the result is paged in the database, with totals over the whole visible set`() {
            val firstPage = tasks.findVisibleInProject(g.apiary, g.owner, page = PageRequest(0, 3))

            assertThat(firstPage.content).hasSize(3)
            assertThat(firstPage.totalElements).isEqualTo(7)
            assertThat(firstPage.totalPages).isEqualTo(3)
            assertThat(firstPage.page).isEqualTo(0)
            assertThat(firstPage.size).isEqualTo(3)

            val lastPage = tasks.findVisibleInProject(g.apiary, g.owner, page = PageRequest(2, 3))

            assertThat(lastPage.taskIds()).containsExactly(g.canceledAssigned)
            assertThat(lastPage.totalElements).isEqualTo(7)
        }

        @Test
        fun `the status filter runs in SQL, so the total counts the filtered set`() {
            // The owner sees 7 tasks in Apiary; exactly two of them are Todo.
            val all = tasks.findVisibleInProject(g.apiary, g.owner, page = PageRequest.DEFAULT)
            assertThat(all.totalElements).isEqualTo(7)

            val todoOnly = tasks.findVisibleInProject(
                g.apiary,
                g.owner,
                setOf(TaskStatus.TODO),
                PageRequest.DEFAULT,
            )

            assertThat(todoOnly.taskIds()).containsExactly(g.todoOpen, g.todoAssigned)
            assertThat(todoOnly.totalElements)
                .describedAs("a filtered page must not report the unfiltered total")
                .isEqualTo(2)
        }

        @Test
        fun `a filtered page is a full page, not a short one`() {
            // Filtering the result of a query instead of the query itself would
            // return at most the Todo rows that happened to fall on page 0 of
            // the unfiltered set -- here, a page of 2 must actually hold 2.
            val page = tasks.findVisibleInProject(
                g.apiary,
                g.owner,
                setOf(TaskStatus.TODO, TaskStatus.CANCELED),
                PageRequest(0, 2),
            )

            assertThat(page.content).hasSize(2)
            assertThat(page.totalElements).isEqualTo(4)
            assertThat(page.totalPages).isEqualTo(2)
        }

        @Test
        fun `the status filter intersects with visibility, and never widens it`() {
            // The filter is ANDed with the visibility predicate, so asking for a
            // status you are not entitled to see does not reveal it.
            //
            // The member is barred from Draft and from Canceled by VIS-4, but is
            // the assignee of canceledAssigned, which VIS-5 keeps visible to
            // them regardless of status. Asking for Draft and Canceled therefore
            // yields exactly that one row: their own canceled assignment, and
            // neither the Draft task nor anyone else's canceled task.
            val page = tasks.findVisibleInProject(
                g.apiary,
                g.member,
                setOf(TaskStatus.DRAFT, TaskStatus.CANCELED),
                PageRequest.DEFAULT,
            )

            assertThat(page.taskIds())
                .describedAs("filtering cannot grant visibility the viewer lacks")
                .containsExactly(g.canceledAssigned)
            assertThat(page.totalElements).isEqualTo(1)
        }

        @Test
        fun `asking for a status you cannot see returns nothing`() {
            // The unambiguous case: a viewer with no assignment among them.
            val page = tasks.findVisibleInProject(
                g.apiary,
                g.lead,
                setOf(TaskStatus.DRAFT),
                PageRequest.DEFAULT,
            )

            assertThat(page.content)
                .describedAs("VIS-3 bars the lead from Draft; naming it changes nothing")
                .isEmpty()
            assertThat(page.totalElements).isEqualTo(0)
        }

        @Test
        fun `a null or empty status set means no filtering`() {
            val unfiltered = tasks.findVisibleInProject(g.apiary, g.owner, null, PageRequest.DEFAULT)
            val emptyFilter = tasks.findVisibleInProject(g.apiary, g.owner, emptySet(), PageRequest.DEFAULT)

            assertThat(unfiltered.totalElements).isEqualTo(7)
            assertThat(emptyFilter.totalElements).isEqualTo(7)
        }

        @Test
        fun `paging a restricted view counts only what that viewer may see`() {
            val page = tasks.findVisibleInProject(g.apiary, g.assignee, page = PageRequest(0, 2))

            assertThat(page.totalElements).isEqualTo(4)
            assertThat(page.taskIds()).containsExactly(g.todoOpen, g.todoAssigned)
        }

        private fun assertVisible(project: ProjectId, viewer: UserId, expected: List<TaskId>) {
            assertThat(tasks.findVisibleInProject(project, viewer, page = PageRequest.DEFAULT).taskIds())
                .containsExactlyElementsOf(expected)
        }
    }

    // =========================================================================
    // findAssignedTo -- VIS-1 / VIS-5
    // =========================================================================

    @Nested
    @DisplayName("findAssignedTo")
    inner class AssignedTo {

        @Test
        fun `VIS-1 -- every task assigned to the user, across projects and teams`() {
            assertThat(tasks.findAssignedTo(g.assignee, PageRequest.DEFAULT).taskIds())
                .containsExactly(g.todoAssigned, g.inProgress, g.pollenInProgress)
        }

        @Test
        fun `VIS-5 -- terminal assignments are included, Completed and Canceled alike`() {
            assertThat(tasks.findAssignedTo(g.member, PageRequest.DEFAULT).taskIds())
                .containsExactly(g.completed, g.canceledAssigned)
        }

        @Test
        fun `the lead is not an assignee merely by leading`() {
            assertThat(tasks.findAssignedTo(g.lead, PageRequest.DEFAULT).content).isEmpty()
        }

        @Test
        fun `the owner is not an assignee merely by owning`() {
            assertThat(tasks.findAssignedTo(g.owner, PageRequest.DEFAULT).content).isEmpty()
        }

        @Test
        fun `an outsider is assigned nothing`() {
            assertThat(tasks.findAssignedTo(g.outsider, PageRequest.DEFAULT).content).isEmpty()
        }
    }

    // =========================================================================
    // findUnassignedForLead -- UQ-1
    // =========================================================================

    @Nested
    @DisplayName("findUnassignedForLead")
    inner class UnassignedQueue {

        @Test
        fun `UQ-1 -- unassigned Todo tasks across every project of every team the user leads`() {
            assertThat(tasks.findUnassignedForLead(g.lead, PageRequest.DEFAULT).taskIds())
                .containsExactly(g.todoOpen, g.nectarTodo)
        }

        @Test
        fun `UQ-1 -- Draft is excluded, because leads cannot see Draft at all`() {
            val queue = tasks.findUnassignedForLead(g.lead, PageRequest.DEFAULT).taskIds()

            assertThat(queue).doesNotContain(g.draft, g.nectarDraft)
        }

        @Test
        fun `UQ-1 -- terminal tasks are excluded even when unassigned`() {
            val queue = tasks.findUnassignedForLead(g.lead, PageRequest.DEFAULT).taskIds()

            assertThat(queue).doesNotContain(g.canceledOpen)
        }

        @Test
        fun `UQ-1 -- assigned Todo tasks are not in the queue`() {
            val queue = tasks.findUnassignedForLead(g.lead, PageRequest.DEFAULT).taskIds()

            assertThat(queue).doesNotContain(g.todoAssigned)
        }

        @Test
        fun `the queue is scoped to the teams the user actually leads`() {
            assertThat(tasks.findUnassignedForLead(g.betaLead, PageRequest.DEFAULT).taskIds())
                .containsExactly(g.pollenTodo)
        }

        @Test
        fun `a member, an owner and an outsider all have an empty queue`() {
            assertThat(tasks.findUnassignedForLead(g.member, PageRequest.DEFAULT).content).isEmpty()
            assertThat(tasks.findUnassignedForLead(g.cross, PageRequest.DEFAULT).content).isEmpty()
            assertThat(tasks.findUnassignedForLead(g.owner, PageRequest.DEFAULT).content).isEmpty()
            assertThat(tasks.findUnassignedForLead(g.outsider, PageRequest.DEFAULT).content).isEmpty()
        }
    }

    // =========================================================================
    // findLiveTasksAssignedTo -- TM-8 / PR-8
    // =========================================================================

    @Nested
    @DisplayName("findLiveTasksAssignedTo")
    inner class LiveAssignments {

        @Test
        fun `TM-8 -- every live assignment, across projects, when no project is given`() {
            assertThat(tasks.findLiveTasksAssignedTo(g.assignee, null).taskIds())
                .containsExactly(g.todoAssigned, g.inProgress, g.pollenInProgress)
        }

        @Test
        fun `PR-8 -- narrowed to one project`() {
            assertThat(tasks.findLiveTasksAssignedTo(g.assignee, g.apiary).taskIds())
                .containsExactly(g.todoAssigned, g.inProgress)

            assertThat(tasks.findLiveTasksAssignedTo(g.assignee, g.pollen).taskIds())
                .containsExactly(g.pollenInProgress)
        }

        @Test
        fun `terminal assignments are not live`() {
            assertThat(tasks.findLiveTasksAssignedTo(g.member, null)).isEmpty()
        }

        @Test
        fun `unassigned live tasks belong to nobody`() {
            assertThat(tasks.findLiveTasksAssignedTo(g.lead, null)).isEmpty()
            assertThat(tasks.findLiveTasksAssignedTo(g.outsider, null)).isEmpty()
        }
    }

    // =========================================================================
    // ProjectRepository.findVisibleTo -- PR-4
    // =========================================================================

    @Nested
    @DisplayName("findVisibleTo (projects)")
    inner class VisibleProjects {

        @Test
        fun `PR-4 and INV-2 -- an owner who is in no team still sees the project they own, and only that one`() {
            assertThat(projects.findVisibleTo(g.owner).projectIds()).containsExactly(g.apiary)
        }

        @Test
        fun `PR-4 -- a lead sees every project of the team they lead`() {
            assertThat(projects.findVisibleTo(g.lead).projectIds()).containsExactly(g.apiary, g.nectar)
        }

        @Test
        fun `PR-4 -- a plain member sees every project of their team`() {
            assertThat(projects.findVisibleTo(g.member).projectIds()).containsExactly(g.apiary, g.nectar)
        }

        @Test
        fun `PR-4 -- membership of two teams unions, without duplicates`() {
            assertThat(projects.findVisibleTo(g.cross).projectIds())
                .containsExactly(g.apiary, g.nectar, g.pollen)
        }

        @Test
        fun `PR-4 -- leading one team and owning its project yields that project once`() {
            assertThat(projects.findVisibleTo(g.betaLead).projectIds()).containsExactly(g.pollen)
        }

        @Test
        fun `an outsider sees no projects`() {
            assertThat(projects.findVisibleTo(g.outsider)).isEmpty()
        }

        @Test
        fun `findByTeam returns exactly that team's projects`() {
            assertThat(projects.findByTeam(g.alpha).projectIds()).containsExactly(g.apiary, g.nectar)
            assertThat(projects.findByTeam(g.beta).projectIds()).containsExactly(g.pollen)
        }
    }

    // =========================================================================
    // TeamRepository -- TM-4 / UQ-1
    // =========================================================================

    @Nested
    @DisplayName("team membership queries")
    inner class TeamQueries {

        @Test
        fun `TM-4 -- overlapping membership returns both teams`() {
            assertThat(teams.findTeamsForMember(g.cross).teamIds()).containsExactly(g.alpha, g.beta)
            assertThat(teams.findTeamsForMember(g.assignee).teamIds()).containsExactly(g.alpha, g.beta)
        }

        @Test
        fun `INV-1 -- a lead is returned as a member of the team they lead`() {
            assertThat(teams.findTeamsForMember(g.lead).teamIds()).containsExactly(g.alpha)
        }

        @Test
        fun `INV-2 -- owning a project confers no membership of its team`() {
            assertThat(teams.findTeamsForMember(g.owner)).isEmpty()
        }

        @Test
        fun `an outsider belongs to no team`() {
            assertThat(teams.findTeamsForMember(g.outsider)).isEmpty()
        }

        @Test
        fun `findTeamsLedBy returns only teams the user leads`() {
            assertThat(teams.findTeamsLedBy(g.lead).teamIds()).containsExactly(g.alpha)
            assertThat(teams.findTeamsLedBy(g.betaLead).teamIds()).containsExactly(g.beta)
            assertThat(teams.findTeamsLedBy(g.member)).isEmpty()
            assertThat(teams.findTeamsLedBy(g.cross)).isEmpty()
        }

        @Test
        fun `the membership set round-trips through the join table`() {
            val alpha = teams.findById(g.alpha)!!

            assertThat(alpha.memberIds)
                .containsExactlyInAnyOrder(g.lead, g.member, g.assignee, g.cross)
            assertThat(alpha.teamLead).isEqualTo(g.lead)
        }
    }

    // --- small readability helpers -------------------------------------------

    private fun Page<Task>.taskIds(): List<TaskId> = content.map { it.id!! }

    private fun List<Task>.taskIds(): List<TaskId> = map { it.id!! }

    private fun List<Project>.projectIds(): List<ProjectId> = map { it.id!! }

    private fun List<Team>.teamIds(): List<TeamId> = map { it.id!! }
}
