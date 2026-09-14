package hive.adapter.`in`.dto

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * The `error` discriminator of the contract's error body.
 *
 * `docs/api-contract.md` section 1.1 closes this set: an error body carries one
 * of these seven strings and nothing else. Statuses the contract does not name
 * (415, for instance) are folded onto the nearest code rather than being
 * allowed to invent an eighth value the frontend cannot parse.
 */
enum class ApiErrorCode {
    BAD_REQUEST,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    METHOD_NOT_ALLOWED,
    CONFLICT,
    INTERNAL_ERROR,
}

/** One field-level validation failure. Mirrors [hive.domain.error.FieldError] on the wire. */
data class FieldErrorDto(
    val field: String,
    val message: String,
)

/**
 * The single error body of the whole API (`docs/api-contract.md` section 1.1).
 *
 * Every non-2xx response uses this shape and no other -- including the ones
 * produced inside the security filter chain, which is why
 * [hive.adapter.`in`.security.ApiAuthenticationEntryPoint] writes it by hand
 * rather than letting the servlet container answer.
 *
 * [fieldErrors] is serialized only when present, which per the contract means
 * only on 400 validation failures.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ErrorResponse(
    val status: Int,
    val error: ApiErrorCode,
    val message: String,
    val path: String,
    val timestamp: String,
    val fieldErrors: List<FieldErrorDto>? = null,
)
