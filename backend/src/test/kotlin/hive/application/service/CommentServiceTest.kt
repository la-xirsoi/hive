package hive.application.service

import hive.application.AppFixtures.ASSIGNEE_ID
import hive.application.AppFixtures.AT
import hive.application.AppFixtures.CLOCK
import hive.application.AppFixtures.LEAD
import hive.application.AppFixtures.LEAD_ID
import hive.application.AppFixtures.MEMBER
import hive.application.AppFixtures.MEMBER_ID
import hive.application.AppFixtures.OUTSIDER_ID
import hive.application.AppFixtures.OWNER_ID
import hive.application.AppFixtures.PROJECT
import hive.application.AppFixtures.TASK_ID
import hive.application.AppFixtures.TEAM
import hive.application.AppFixtures.comment
import hive.application.AppFixtures.pageOf
import hive.application.AppFixtures.task
import hive.application.Harness
import hive.application.usecase.AddCommentCommand
import hive.domain.HiveClock
import hive.domain.error.NotFoundException
import hive.domain.error.ValidationException
import hive.domain.model.Comment
import hive.domain.model.CommentId
import hive.domain.model.Page
import hive.domain.model.PageRequest
import hive.domain.model.TaskStatus
import hive.domain.model.TaskStatus.CANCELED
import hive.domain.model.TaskStatus.COMPLETED
import hive.domain.model.TaskStatus.DRAFT
import hive.domain.model.TaskStatus.TODO
import hive.domain.model.UserId
import hive.application.usecase.CommentUseCases
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("CommentService")
class CommentServiceTest {

    private val harness = Harness()
    private val service = CommentService(harness.commentRepository, harness.loader, harness.views, CLOCK)

    private fun world(status: TaskStatus = TODO, assignee: UserId? = ASSIGNEE_ID) =
        harness.withTeams(TEAM).withProjects(PROJECT).withTasks(task(status, assignee))

    @Nested
    @DisplayName("add (CM-1, CM-3, CM-4, CM-5)")
    inner class Add {

        @Test
        fun `a team member may comment, with the author and timestamp assigned server-side`() {
            world()
            val saved = slot<Comment>()
            every { harness.commentRepository.save(capture(saved)) } answers {
                firstArg<Comment>().copy(id = CommentId(5))
            }

            val view = service.add(MEMBER_ID, TASK_ID, AddCommentCommand("  Queen spotted.  "))

            assertThat(saved.captured.id).isNull()
            assertThat(saved.captured.author).isEqualTo(MEMBER_ID)
            assertThat(saved.captured.taskId).isEqualTo(TASK_ID)
            assertThat(saved.captured.content.value).isEqualTo("Queen spotted.")
            assertThat(saved.captured.timestamp).isEqualTo(AT)
            assertThat(view.author).isEqualTo(MEMBER)
            assertThat(view.comment.id).isEqualTo(CommentId(5))
        }

        @Test
        fun `CM-5 the timestamp is truncated to the minute`() {
            world()
            val service = CommentService(
                harness.commentRepository,
                harness.loader,
                harness.views,
                HiveClock.fixedAt(Instant.parse("2026-09-13T18:30:59.987Z")),
            )
            every { harness.commentRepository.save(any()) } answers { firstArg() }

            val view = service.add(MEMBER_ID, TASK_ID, AddCommentCommand("Noted."))

            assertThat(view.comment.timestamp).isEqualTo(Instant.parse("2026-09-13T18:30:00Z"))
        }

        @Test
        fun `CM-3 and TE-4 a Completed task still accepts comments`() {
            world(COMPLETED)
            every { harness.commentRepository.save(any()) } answers { firstArg() }

            val view = service.add(OWNER_ID, TASK_ID, AddCommentCommand("Good work."))

            assertThat(view.comment.content.value).isEqualTo("Good work.")
        }

        @Test
        fun `CM-3 a Canceled task still accepts comments`() {
            world(CANCELED)
            every { harness.commentRepository.save(any()) } answers { firstArg() }

            assertThat(service.add(OWNER_ID, TASK_ID, AddCommentCommand("Dropped, see thread.")).comment)
                .isNotNull()
        }

        @Test
        fun `the project owner may comment on their own Draft task`() {
            world(DRAFT, assignee = null)
            every { harness.commentRepository.save(any()) } answers { firstArg() }

            assertThat(service.add(OWNER_ID, TASK_ID, AddCommentCommand("Not ready yet.")).author.id)
                .isEqualTo(OWNER_ID)
        }

        @Test
        fun `404 when the actor cannot see the task -- never 403, which would confirm it exists`() {
            world()

            assertThatThrownBy { service.add(OUTSIDER_ID, TASK_ID, AddCommentCommand("Hello?")) }
                .isInstanceOf(NotFoundException::class.java)

            verify(exactly = 0) { harness.commentRepository.save(any()) }
        }

        @Test
        fun `404 on a Draft task for the lead, who cannot see it (VIS-3)`() {
            world(DRAFT, assignee = null)

            assertThatThrownBy { service.add(LEAD_ID, TASK_ID, AddCommentCommand("Hello?")) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `404 when the task does not exist`() {
            harness.withTeams(TEAM).withProjects(PROJECT).withTasks()

            assertThatThrownBy { service.add(OWNER_ID, TASK_ID, AddCommentCommand("Hello?")) }
                .isInstanceOf(NotFoundException::class.java)
        }

        @Test
        fun `400 on blank content`() {
            world()

            assertThatThrownBy { service.add(MEMBER_ID, TASK_ID, AddCommentCommand("   ")) }
                .isInstanceOf(ValidationException::class.java)
        }

        @Test
        fun `400 on content past the 4000-character cap`() {
            world()

            assertThatThrownBy { service.add(MEMBER_ID, TASK_ID, AddCommentCommand("x".repeat(4001))) }
                .isInstanceOf(ValidationException::class.java)
        }
    }

    @Nested
    @DisplayName("list (CM-2)")
    inner class List {

        @Test
        fun `returns the page the repository ordered, with every author resolved`() {
            world()
            every { harness.commentRepository.findByTask(TASK_ID, PageRequest.DEFAULT) } returns
                pageOf(comment(1, MEMBER_ID, "First."), comment(2, LEAD_ID, "Second."))

            val page = service.list(MEMBER_ID, TASK_ID)

            assertThat(page.content.map { it.comment.content.value }).containsExactly("First.", "Second.")
            assertThat(page.content.map { it.author }).containsExactly(MEMBER, LEAD)
        }

        @Test
        fun `an empty thread needs no directory lookup`() {
            world()
            every { harness.commentRepository.findByTask(TASK_ID, PageRequest.DEFAULT) } returns
                pageOf<Comment>()

            assertThat(service.list(MEMBER_ID, TASK_ID).content).isEmpty()
            verify(exactly = 0) { harness.userRepository.findAllById(any()) }
        }

        @Test
        fun `honours a non-default page request`() {
            world()
            val request = PageRequest(page = 1, size = 10)
            every { harness.commentRepository.findByTask(TASK_ID, request) } returns
                Page.of(listOf(comment(3)), request, 17L)

            val page = service.list(MEMBER_ID, TASK_ID, request)

            assertThat(page.page).isEqualTo(1)
            assertThat(page.size).isEqualTo(10)
            assertThat(page.totalPages).isEqualTo(2)
        }

        @Test
        fun `404 for somebody who cannot see the task`() {
            world()

            assertThatThrownBy { service.list(OUTSIDER_ID, TASK_ID) }
                .isInstanceOf(NotFoundException::class.java)

            verify(exactly = 0) { harness.commentRepository.findByTask(any(), any()) }
        }

        @Test
        fun `VIS-5 the assignee of a canceled task can still read its thread`() {
            world(CANCELED)
            every { harness.commentRepository.findByTask(TASK_ID, PageRequest.DEFAULT) } returns
                pageOf(comment(1))

            assertThat(service.list(ASSIGNEE_ID, TASK_ID).content).hasSize(1)
        }

        @Test
        fun `a comment by an author with no user row is a broken reference, reported as 404`() {
            world()
            every { harness.commentRepository.findByTask(TASK_ID, PageRequest.DEFAULT) } returns
                pageOf(comment(1, UserId(4242)))

            assertThatThrownBy { service.list(MEMBER_ID, TASK_ID) }
                .isInstanceOf(NotFoundException::class.java)
        }
    }

    @Nested
    @DisplayName("immutability (CM-6)")
    inner class Immutability {

        @Test
        fun `the port offers no way to change or remove a comment`() {
            // Value-class parameters mangle the JVM names, so compare the stems.
            val methods = CommentUseCases::class.java.methods
                .map { it.name.substringBefore('-') }
                .distinct()

            assertThat(methods).contains("list", "add")
            assertThat(methods).doesNotContain("update", "edit", "delete", "remove")
        }
    }
}
