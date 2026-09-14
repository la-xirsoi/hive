package hive.adapter.`in`.error

import hive.adapter.`in`.dto.ApiErrorCode
import hive.adapter.`in`.dto.ErrorResponse
import hive.adapter.`in`.dto.FieldErrorDto
import hive.adapter.`in`.dto.toApiTimestamp
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import java.time.Instant

/**
 * Construction of the single error body (`docs/api-contract.md` section 1.1).
 *
 * Shared by [ApiExceptionHandler] and by the security filter chain's entry
 * point and access-denied handler, which run *before* any controller and so
 * cannot go through `@ExceptionHandler`. One builder means those three paths
 * cannot drift into publishing two different error shapes.
 */

/** The `error` code the contract pairs with each status it names. */
internal fun codeFor(status: HttpStatus): ApiErrorCode =
    when (status) {
        HttpStatus.UNAUTHORIZED -> ApiErrorCode.UNAUTHORIZED
        HttpStatus.FORBIDDEN -> ApiErrorCode.FORBIDDEN
        HttpStatus.NOT_FOUND -> ApiErrorCode.NOT_FOUND
        HttpStatus.METHOD_NOT_ALLOWED -> ApiErrorCode.METHOD_NOT_ALLOWED
        HttpStatus.CONFLICT -> ApiErrorCode.CONFLICT
        HttpStatus.INTERNAL_SERVER_ERROR -> ApiErrorCode.INTERNAL_ERROR
        // Statuses the contract does not name (415, 406, ...) fold onto the
        // nearest code it does, rather than inventing an eighth value the
        // frontend has no case for.
        else -> ApiErrorCode.BAD_REQUEST
    }

/**
 * Build the contract's body.
 *
 * @param fieldErrors present only on 400 validation failures; `null` everywhere
 *   else, where the DTO's `NON_NULL` inclusion drops the key entirely.
 */
internal fun errorBody(
    status: HttpStatus,
    message: String,
    path: String,
    fieldErrors: List<FieldErrorDto>? = null,
): ErrorResponse =
    ErrorResponse(
        status = status.value(),
        error = codeFor(status),
        message = message,
        path = path,
        timestamp = Instant.now().toApiTimestamp(),
        fieldErrors = fieldErrors,
    )

/** The request path, exactly as the client wrote it, for the body's `path` field. */
internal fun HttpServletRequest.apiPath(): String = requestURI ?: ""
