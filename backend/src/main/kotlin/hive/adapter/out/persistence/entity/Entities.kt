package hive.adapter.out.persistence.entity

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * The JPA entities.
 *
 * These are a **separate set of classes from the domain entities** and exist for
 * one reason: to be the shape the database understands. No class under
 * `hive.domain` carries a JPA annotation, and nothing here carries a domain
 * rule. The translation between the two lives in
 * [hive.adapter.out.persistence.mapper.PersistenceMappers] and nowhere else.
 *
 * Three deliberate differences from the domain model:
 *
 * 1. **Foreign keys are plain `Long` columns, not `@ManyToOne` associations.**
 *    The domain aggregates reference each other by id ([hive.domain.model.Task]
 *    holds a [hive.domain.model.ProjectId], not a `Project`), so an association
 *    graph here would buy nothing but lazy-loading proxies that escape the
 *    adapter. Joins the queries genuinely need are written explicitly in JPQL,
 *    where they are visible.
 *
 * 2. **Typed ids and value objects are unwrapped.** `UserId`, `EmailAddress`,
 *    `TaskName` and friends validate on construction; re-validating every row on
 *    the way out of the database is the mapper's job, not the ORM's.
 *
 * 3. **`status` is a `String` holding the wire spelling**, not an
 *    `@Enumerated` enum. `@Enumerated(STRING)` would persist the Kotlin constant
 *    name (`IN_PROGRESS`), which is not what `spec.md` or the `CK_tasks_status`
 *    check constraint says. The mapper converts through
 *    [hive.domain.model.TaskStatus.wireName], so the constant may be renamed
 *    without rewriting the database.
 *
 * Properties are `var` with defaults: Hibernate instantiates entities through
 * the no-arg constructor supplied by the `kotlin("plugin.jpa")` compiler plugin
 * and populates them by reflection.
 */

/** Row of `users`. [email] always holds the lower-cased form -- see `V1__baseline.sql`. */
@Entity
@Table(name = "users")
open class UserEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    open var id: Long? = null,
    @Column(name = "name", nullable = false, length = 200)
    open var name: String = "",
    @Column(name = "email", nullable = false, length = 254)
    open var email: String = "",
)

/**
 * Row of `teams`, plus its membership set.
 *
 * The members are an [ElementCollection] of raw user ids rather than a
 * `@ManyToMany` to [UserEntity]: the domain's [hive.domain.model.Team] holds a
 * `Set<UserId>`, and a collection of ids maps to that one-to-one with no
 * intermediate objects to detach. It also means `save` persists the team and its
 * membership as one unit, which is what [hive.domain.port.TeamRepository.save]
 * promises -- Hibernate reconciles the `team_members` rows against the set.
 *
 * Fetched eagerly because membership is small, always needed (every visibility
 * decision consults it), and because the mapper converts to an immutable domain
 * object outside any session.
 */
@Entity
@Table(name = "teams")
open class TeamEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    open var id: Long? = null,
    @Column(name = "name", nullable = false, length = 200)
    open var name: String = "",
    @Column(name = "team_lead", nullable = false)
    open var teamLead: Long = 0L,
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "team_members",
        joinColumns = [JoinColumn(name = "team_id")],
    )
    @Column(name = "user_id", nullable = false)
    open var memberIds: MutableSet<Long> = mutableSetOf(),
)

/** Row of `projects`. [projectOwner] is independent of team membership (INV-2). */
@Entity
@Table(name = "projects")
open class ProjectEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    open var id: Long? = null,
    @Column(name = "name", nullable = false, length = 200)
    open var name: String = "",
    @Column(name = "team_id", nullable = false)
    open var teamId: Long = 0L,
    @Column(name = "project_owner", nullable = false)
    open var projectOwner: Long = 0L,
)

/** Row of `tasks`. [status] holds the wire spelling; [assignee] is the schema's only nullable FK. */
@Entity
@Table(name = "tasks")
open class TaskEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    open var id: Long? = null,
    @Column(name = "name", nullable = false, length = 200)
    open var name: String = "",
    @Column(name = "description", nullable = false, length = 4000)
    open var description: String = "",
    @Column(name = "project_id", nullable = false)
    open var projectId: Long = 0L,
    @Column(name = "creator", nullable = false)
    open var creator: Long = 0L,
    @Column(name = "assignee")
    open var assignee: Long? = null,
    @Column(name = "status", nullable = false, length = 20)
    open var status: String = "",
)

/**
 * Row of `comments`.
 *
 * [createdAt] is a [LocalDateTime] rather than an `Instant` on purpose. The
 * domain's timestamp is an `Instant` already truncated to the minute in UTC
 * (CM-5); the mapper converts at [java.time.ZoneOffset.UTC] explicitly, so the
 * value written to `DATETIME2(0)` is UTC wall-clock by construction and does not
 * depend on the JVM's default zone, the session time zone, or which JDBC type
 * Hibernate would otherwise choose for an `Instant`.
 */
@Entity
@Table(name = "comments")
open class CommentEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    open var id: Long? = null,
    @Column(name = "task_id", nullable = false)
    open var taskId: Long = 0L,
    @Column(name = "author", nullable = false)
    open var author: Long = 0L,
    @Column(name = "created_at", nullable = false)
    open var createdAt: LocalDateTime = LocalDateTime.MIN,
    @Column(name = "content", nullable = false, length = 4000)
    open var content: String = "",
)
