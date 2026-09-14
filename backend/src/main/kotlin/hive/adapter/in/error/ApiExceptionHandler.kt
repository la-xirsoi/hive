package hive.adapter.`in`.error

import hive.adapter.`in`.dto.ErrorResponse
import hive.adapter.`in`.dto.FieldErrorDto
import hive.domain.error.AuthorizationException
import hive.domain.error.ConflictException
import hive.domain.error.NotFoundException
import hive.domain.error.ValidationException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.validation.FieldError
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * The whole API's error mapping, in one place
 * (`docs/api-contract.md` section 1.1, `docs/authorization.md` section 11).
 *
 * | Situation | Status |
 * |-----------|--------|
 * | [ValidationException], bean-validation failure, malformed or missing body, bad query/path type | 400 |
 * | [AuthenticationException] -- no token, invalid token, expired token | 401 |
 * | [AuthorizationException], Spring's [AccessDeniedException] | 403 |
 * | [NotFoundException], no such route | 404 |
 * | Method not supported on an existing resource -- `DELETE` on a task, team or project | 405 |
 * | [ConflictException] | 409 |
 * | anything else | 500, generic |
 *
 * The 500 branch is the reason this class exists as much as any other: an
 * unexpected throwable's own message may name a class, a table or a column, so
 * it is logged with its stack trace server-side and answered with a fixed
 * sentence. Nothing from the throwable reaches the client.
 *
 * The ordering rule of section 11 (401 -> 404 -> 409 -> 403 -> 400) is *not*
 * implemented here. It is implemented where the decisions are made -- in the
 * filter chain and the application services -- and by the time an exception
 * reaches this class the ordering question has already been settled: only one
 * exception is ever thrown.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    // --- 400 ----------------------------------------------------------------

    /** The domain's own validation failure, field detail included. */
    @ExceptionHandler(ValidationException::class)
    fun onValidation(
        ex: ValidationException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> =
        respond(
            HttpStatus.BAD_REQUEST,
            ex.message ?: VALIDATION_FALLBACK,
            request,
            ex.fieldErrors.map { FieldErrorDto(it.field, it.message) },
        )

    /** `@Valid` on a request body failed. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun onInvalidBody(
        ex: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val fields =
            ex.bindingResult.allErrors.map { error ->
                val field = (error as? FieldError)?.field ?: error.objectName
                FieldErrorDto(field, error.defaultMessage ?: "is invalid.")
            }
        return respond(HttpStatus.BAD_REQUEST, renderFields(fields), request, fields)
    }

    /** Constraint annotations on method parameters (`@RequestParam`, `@PathVariable`). */
    @ExceptionHandler(HandlerMethodValidationException::class)
    fun onInvalidParameters(
        ex: HandlerMethodValidationException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val fields =
            ex.parameterValidationResults.flatMap { result ->
                result.resolvableErrors.map { error ->
                    FieldErrorDto(
                        result.methodParameter.parameterName ?: "request",
                        error.defaultMessage ?: "is invalid.",
                    )
                }
            }
        return respond(HttpStatus.BAD_REQUEST, renderFields(fields), request, fields.ifEmpty { null })
    }

    /**
     * The body could not be parsed at all -- malformed JSON, a missing body, or
     * a JSON type that cannot become the target property.
     *
     * Jackson's own message quotes the offending class and source position, so
     * it is replaced wholesale rather than forwarded.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun onUnreadableBody(
        ex: HttpMessageNotReadableException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.debug("Unreadable request body on {}", request.apiPath(), ex)
        return respond(HttpStatus.BAD_REQUEST, "The request body is missing or is not valid JSON.", request)
    }

    /** `/users/abc` where a numeric id belongs, or `?page=x`. */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun onTypeMismatch(
        ex: MethodArgumentTypeMismatchException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val field = ex.name
        val fields = listOf(FieldErrorDto(field, "must be a whole number."))
        return respond(HttpStatus.BAD_REQUEST, renderFields(fields), request, fields)
    }

    /** A required query parameter was omitted. */
    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun onMissingParameter(
        ex: MissingServletRequestParameterException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val fields = listOf(FieldErrorDto(ex.parameterName, "is required."))
        return respond(HttpStatus.BAD_REQUEST, renderFields(fields), request, fields)
    }

    /** A body sent as something other than JSON. Folded onto 400, per the contract's closed code set. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun onUnsupportedMediaType(
        ex: HttpMediaTypeNotSupportedException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> =
        respond(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "The request body must be sent as application/json.",
            request,
        )

    // --- 401 ----------------------------------------------------------------

    /**
     * An authentication failure that surfaced *after* the filter chain -- most
     * often a request whose token carries no usable identity, raised while the
     * acting user is being resolved.
     *
     * The filter chain's own failures never reach here; they are answered by
     * [hive.adapter.`in`.security.ApiAuthenticationEntryPoint] with the same body.
     */
    @ExceptionHandler(AuthenticationException::class)
    fun onAuthentication(
        ex: AuthenticationException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.UNAUTHORIZED, "Authentication is required to access this resource.", request)

    // --- 403 ----------------------------------------------------------------

    /** The actor may see the resource but lacks the role this operation needs. */
    @ExceptionHandler(AuthorizationException::class)
    fun onAuthorization(
        ex: AuthorizationException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.FORBIDDEN, ex.message ?: FORBIDDEN_FALLBACK, request)

    /** The same answer for a denial raised by Spring Security itself. */
    @ExceptionHandler(AccessDeniedException::class)
    fun onAccessDenied(
        ex: AccessDeniedException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> = respond(HttpStatus.FORBIDDEN, FORBIDDEN_FALLBACK, request)

    // --- 404 ----------------------------------------------------------------

    /** Missing, or invisible to this actor -- the contract does not distinguish the two. */
    @ExceptionHandler(NotFoundException::class)
    fun onNotFound(
        ex: NotFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.NOT_FOUND, ex.message ?: NOT_FOUND_FALLBACK, request)

    /**
     * No route at all -- including `POST /api/v1/dev/token` under any profile
     * but `dev`, which section 9 of the contract requires to be a 404.
     */
    @ExceptionHandler(NoHandlerFoundException::class, NoResourceFoundException::class)
    fun onNoRoute(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.NOT_FOUND, "No endpoint is mapped to this path.", request)

    // --- 405 ----------------------------------------------------------------

    /**
     * The path exists but not with this method: `DELETE /tasks/{id}` (TK-6),
     * `DELETE /teams/{id}` (TM-11) and `DELETE /projects/{id}` (PR-10) are the
     * deliberate cases, and each is covered by a test.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun onMethodNotSupported(
        ex: HttpRequestMethodNotSupportedException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> =
        respond(
            HttpStatus.METHOD_NOT_ALLOWED,
            "${ex.method} is not supported on this resource.",
            request,
        )

    // --- 409 ----------------------------------------------------------------

    /** Impossible in the resource's current state -- and so answered before the actor's role. */
    @ExceptionHandler(ConflictException::class)
    fun onConflict(
        ex: ConflictException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.CONFLICT, ex.message ?: CONFLICT_FALLBACK, request)

    // --- 500 ----------------------------------------------------------------

    /**
     * Everything unforeseen.
     *
     * The throwable is logged with its stack trace and then discarded: the body
     * carries a fixed sentence, never the exception's message, class name or
     * any part of its cause chain (spec.md -- a 500 "must not leak
     * implementation details").
     */
    @ExceptionHandler(Throwable::class)
    fun onUnexpected(
        ex: Throwable,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.error("Unhandled exception while serving {} {}", request.method, request.apiPath(), ex)
        return respond(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "The request could not be completed because of an unexpected server error.",
            request,
        )
    }

    private fun respond(
        status: HttpStatus,
        message: String,
        request: HttpServletRequest,
        fieldErrors: List<FieldErrorDto>? = null,
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(errorBody(status, message, request.apiPath(), fieldErrors))

    private fun renderFields(fields: List<FieldErrorDto>): String =
        if (fields.isEmpty()) {
            VALIDATION_FALLBACK
        } else {
            fields.joinToString(separator = "; ") { "${it.field}: ${it.message}" }
        }

    private companion object {
        const val VALIDATION_FALLBACK = "Validation failed."
        const val FORBIDDEN_FALLBACK = "You do not have permission to perform this operation."
        const val NOT_FOUND_FALLBACK = "The requested resource was not found."
        const val CONFLICT_FALLBACK = "The operation is not possible in the resource's current state."
    }
}
