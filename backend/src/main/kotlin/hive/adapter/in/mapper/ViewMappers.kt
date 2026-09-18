package hive.adapter.`in`.mapper

import hive.adapter.`in`.dto.CommentDto
import hive.adapter.`in`.dto.PageResponse
import hive.adapter.`in`.dto.ProjectPermissionsDto
import hive.adapter.`in`.dto.ProjectSummaryDto
import hive.adapter.`in`.dto.TaskDetailDto
import hive.adapter.`in`.dto.TaskPermissionsDto
import hive.adapter.`in`.dto.TaskSummaryDto
import hive.adapter.`in`.dto.TeamDetailDto
import hive.adapter.`in`.dto.TeamPermissionsDto
import hive.adapter.`in`.dto.TeamSummaryDto
import hive.adapter.`in`.dto.UserSummaryDto
import hive.adapter.`in`.dto.toApiTimestamp
import hive.application.view.CommentView
import hive.application.view.ProjectPermissions
import hive.application.view.ProjectView
import hive.application.view.TaskDetailView
import hive.application.view.TaskPermissions
import hive.application.view.TaskSummaryView
import hive.application.view.TeamPermissions
import hive.application.view.TeamView
import hive.domain.model.Page
import hive.domain.model.TaskStatus
import hive.domain.model.User

/**
 * The one place the application layer's read models become wire DTOs.
 *
 * The views ([hive.application.view.Views]) carry domain entities; the contract
 * publishes flat JSON. Keeping the translation here means a controller never
 * reaches into a domain object to build a payload, and a change to the JSON
 * shape touches exactly this file plus the DTOs.
 *
 * Every entity reaching this layer has been persisted, so its id is non-null.
 * [idOf] states that as an assertion rather than an `!!`, so the failure -- if
 * the application layer ever returned an unsaved entity -- is a clearly
 * attributable 500 rather than a bare `NullPointerException`.
 */

private fun <T> idOf(id: T?, what: String): T =
    checkNotNull(id) { "A persisted $what reached the REST adapter without an id." }

/** `UserSummary`. */
fun User.toSummaryDto(): UserSummaryDto =
    UserSummaryDto(
        id = idOf(id, "user").value,
        name = name.value,
        email = email.value,
    )

/** `TeamSummary` -- the lead resolved and the members counted, not listed. */
fun TeamView.toSummaryDto(): TeamSummaryDto =
    TeamSummaryDto(
        id = idOf(team.id, "team").value,
        name = team.name.value,
        teamLead = lead.toSummaryDto(),
        memberCount = memberCount,
    )

/** `TeamDetail` -- the same team with its members listed in full, and the caller's permissions. */
fun TeamView.toDetailDto(): TeamDetailDto =
    TeamDetailDto(
        id = idOf(team.id, "team").value,
        name = team.name.value,
        teamLead = lead.toSummaryDto(),
        members = members.map { it.toSummaryDto() },
        permissions = permissions.toDto(),
    )

/** `TeamPermissions`. */
fun TeamPermissions.toDto(): TeamPermissionsDto =
    TeamPermissionsDto(
        canRename = canRename,
        canAddMember = canAddMember,
        canRemoveMember = canRemoveMember,
        canTransferLead = canTransferLead,
    )

/** `ProjectSummary`. */
fun ProjectView.toDto(): ProjectSummaryDto =
    ProjectSummaryDto(
        id = idOf(project.id, "project").value,
        name = project.name.value,
        team = team.toSummaryDto(),
        projectOwner = owner.toSummaryDto(),
        permissions = permissions.toDto(),
    )

/** `ProjectPermissions`. */
fun ProjectPermissions.toDto(): ProjectPermissionsDto =
    ProjectPermissionsDto(
        canRename = canRename,
        canTransferOwnership = canTransferOwnership,
        canCreateTask = canCreateTask,
    )

/** `TaskSummary`. */
fun TaskSummaryView.toDto(): TaskSummaryDto =
    TaskSummaryDto(
        id = idOf(task.id, "task").value,
        name = task.name.value,
        status = task.status.wireName,
        projectId = task.projectId.value,
        projectName = projectName.value,
        assignee = assignee?.toSummaryDto(),
    )

/**
 * `TaskPermissions`.
 *
 * `allowedTransitions` is published in the contract's declaration order rather
 * than in whatever order the policy's `Set` happens to iterate, so the payload
 * is stable across runs and a client may render it as given.
 */
fun TaskPermissions.toDto(): TaskPermissionsDto =
    TaskPermissionsDto(
        canEdit = canEdit,
        canAssign = canAssign,
        canComment = canComment,
        allowedTransitions = TaskStatus.entries.filter { it in allowedTransitions }.map { it.wireName },
    )

/** `TaskDetail`. */
fun TaskDetailView.toDto(): TaskDetailDto =
    TaskDetailDto(
        id = idOf(task.id, "task").value,
        name = task.name.value,
        description = task.description.value,
        status = task.status.wireName,
        project = project.toDto(),
        creator = creator.toSummaryDto(),
        assignee = assignee?.toSummaryDto(),
        permissions = permissions.toDto(),
    )

/** `Comment`. */
fun CommentView.toDto(): CommentDto =
    CommentDto(
        id = idOf(comment.id, "comment").value,
        taskId = comment.taskId.value,
        author = author.toSummaryDto(),
        timestamp = comment.timestamp.toApiTimestamp(),
        content = comment.content.value,
    )

/** Wrap a domain page in the contract's envelope, mapping each element. */
fun <T, R> Page<T>.toResponse(transform: (T) -> R): PageResponse<R> =
    PageResponse(
        content = content.map(transform),
        page = page,
        size = size,
        totalElements = totalElements,
        totalPages = totalPages,
    )
