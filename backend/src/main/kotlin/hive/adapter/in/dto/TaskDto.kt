package hive.adapter.`in`.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/** Tasks -- `docs/api-contract.md` section 5. */

/** `TaskSummary` (contract section 1.2). `assignee` is explicitly `null`, never absent. */
data class TaskSummaryDto(
    val id: Long,
    val name: String,
    val status: String,
    val projectId: Long,
    val projectName: String,
    val assignee: UserSummaryDto?,
)

/**
 * `TaskPermissions` (contract section 1.2): what the acting user may do with
 * this task *right now*, computed by the same policy that enforces the rules.
 *
 * A convenience for the UI and never an enforcement point -- every request is
 * authorized independently.
 */
data class TaskPermissionsDto(
    val canEdit: Boolean,
    val canAssign: Boolean,
    val canComment: Boolean,
    val allowedTransitions: List<String>,
)

/** `TaskDetail` (contract section 1.2). */
data class TaskDetailDto(
    val id: Long,
    val name: String,
    val description: String,
    val status: String,
    val project: ProjectSummaryDto,
    val creator: UserSummaryDto,
    val assignee: UserSummaryDto?,
    val permissions: TaskPermissionsDto,
)

/**
 * `POST /tasks`.
 *
 * Neither `status` nor `assignee` appears: TK-2 fixes both (`Draft`,
 * unassigned) and a client that could suggest otherwise would be lying to
 * itself.
 */
data class CreateTaskRequest(
    @field:NotNull(message = MSG_REQUIRED)
    val projectId: Long? = null,
    @field:NotBlank(message = MSG_NOT_BLANK)
    @field:Size(min = NAME_MIN, max = NAME_MAX, message = MSG_NAME_SIZE)
    val name: String? = null,
    @field:NotNull(message = MSG_REQUIRED)
    @field:Size(max = DESCRIPTION_MAX, message = MSG_DESCRIPTION_SIZE)
    val description: String? = null,
)

/**
 * `PATCH /tasks/{id}`. Both fields are optional; `null` means "leave alone".
 *
 * "At least one must be present" is **not** annotated here. It is a rule about
 * the command as a whole, the use case already owns it
 * ([hive.application.usecase.UpdateTaskCommand.isEmpty] -> 400), and encoding
 * it twice would let the two copies disagree.
 *
 * [name] carries no `@NotBlank` for the same reason it is nullable: on this
 * request `null` means "leave the name alone", and `@NotBlank` rejects null.
 * A *present* but blank name is caught by [hive.domain.model.TaskName], which
 * answers with the same 400 and the same `name` field error.
 */
data class UpdateTaskRequest(
    @field:Size(min = NAME_MIN, max = NAME_MAX, message = MSG_NAME_SIZE)
    val name: String? = null,
    @field:Size(max = DESCRIPTION_MAX, message = MSG_DESCRIPTION_SIZE)
    val description: String? = null,
)

/**
 * `PUT /tasks/{id}/status`.
 *
 * Typed as a `String`, not as the enum: Jackson answers an unknown enum literal
 * with an unreadable-body error that names no field, while
 * [hive.domain.model.TaskStatus.fromWireName] answers it with the contract's
 * 400 and a `status` field error (section 8).
 */
data class UpdateTaskStatusRequest(
    @field:NotBlank(message = MSG_NOT_BLANK)
    val status: String? = null,
)

/** `PUT /tasks/{id}/assignee`. `null` unassigns, which AS-7 permits only from `Todo`. */
data class UpdateTaskAssigneeRequest(
    val userId: Long? = null,
)
