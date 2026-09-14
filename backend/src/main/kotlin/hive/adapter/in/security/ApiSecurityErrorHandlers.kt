package hive.adapter.`in`.security

import hive.adapter.`in`.error.apiPath
import hive.adapter.`in`.error.errorBody
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * The filter chain's two error exits, writing the contract's error body.
 *
 * Failures inside the security filter chain happen *before* any handler, so
 * `@RestControllerAdvice` never sees them; left alone, Spring Security would
 * answer with an empty body and the frontend would have nothing to parse. These
 * two write the same shape [hive.adapter.`in`.error.ApiExceptionHandler] writes,
 * from the same builder.
 */

/** No token, an invalid token, or an expired one -- 401 (`docs/authorization.md` section 11). */
@Component
class ApiAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        // The exception's own message can describe the token's internals, so
        // the client is told only that authentication is required.
        write(
            objectMapper,
            response,
            HttpStatus.UNAUTHORIZED,
            "Authentication is required to access this resource.",
            request.apiPath(),
        )
    }
}

/** An authenticated caller the chain itself refuses -- 403. */
@Component
class ApiAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
) : AccessDeniedHandler {

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        write(
            objectMapper,
            response,
            HttpStatus.FORBIDDEN,
            "You do not have permission to perform this operation.",
            request.apiPath(),
        )
    }
}

private fun write(
    objectMapper: ObjectMapper,
    response: HttpServletResponse,
    status: HttpStatus,
    message: String,
    path: String,
) {
    response.status = status.value()
    response.contentType = MediaType.APPLICATION_JSON_VALUE
    response.characterEncoding = Charsets.UTF_8.name()
    objectMapper.writeValue(response.outputStream, errorBody(status, message, path))
}
