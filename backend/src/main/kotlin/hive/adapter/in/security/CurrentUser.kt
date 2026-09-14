package hive.adapter.`in`.security

import hive.application.usecase.PrincipalClaims
import hive.application.usecase.UserUseCases
import hive.domain.model.UserId
import org.springframework.core.MethodParameter
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * The acting user, resolved from the bearer token.
 *
 * A plain class wrapping the [UserId] rather than the [UserId] itself: an
 * inline value class is erased to the `Long` it wraps by the time Spring
 * inspects a handler method's parameters, so a `UserId` parameter is
 * indistinguishable from an unannotated `long` and Spring's own
 * `@RequestParam` fallback claims it first. Wrapping restores a type the
 * argument resolver can recognise, and costs the controllers one `.id`.
 */
data class ActingUser(val id: UserId)

/**
 * Marks the controller parameter that receives the acting user.
 *
 * AU-2: the acting user is always the token's subject and is never accepted
 * from the request. Controllers therefore declare
 *
 *     fun get(@CurrentUser actor: ActingUser, ...)
 *
 * and never touch a `SecurityContext`; [CurrentUserArgumentResolver] does that
 * once, in one place, and the application layer below it sees only a [UserId].
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentUser

/**
 * Turns the authenticated JWT into the Hive [UserId] the use cases expect.
 *
 * This is the *only* bridge between Spring Security and the rest of the
 * application, and it is what US-3 means in practice: the first request a new
 * person makes provisions their [hive.domain.model.User] row from the token's
 * claims, matched by email (`docs/authorization.md` section 12 explains why the
 * subject claim is not stored).
 *
 * The resolved id is cached in a request attribute, so a handler that needs it
 * twice -- or a `@ControllerAdvice` that runs after it -- does not provision
 * twice within one request.
 */
@Component
class CurrentUserArgumentResolver(
    private val users: UserUseCases,
) : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentUser::class.java) &&
            ActingUser::class.java == parameter.parameterType

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): ActingUser {
        val cached = webRequest.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST)
        if (cached is ActingUser) {
            return cached
        }
        val resolved = resolve()
        webRequest.setAttribute(ATTRIBUTE, resolved, RequestAttributes.SCOPE_REQUEST)
        return resolved
    }

    private fun resolve(): ActingUser {
        val authentication =
            SecurityContextHolder.getContext().authentication
                ?: throw InvalidBearerTokenException(NO_TOKEN)
        val jwt =
            authentication.principal as? Jwt
                ?: throw InvalidBearerTokenException(NO_TOKEN)

        // A token with no usable email cannot be matched to a Hive user at all
        // (US-3 matches by email), so it is an unusable credential -- 401 -- and
        // not a 400 about a field the caller never sent.
        val email =
            jwt.getClaimAsString(EMAIL_CLAIM)?.takeIf { it.isNotBlank() }
                ?: throw InvalidBearerTokenException(NO_EMAIL)

        val claims =
            PrincipalClaims(
                subject = jwt.subject ?: email,
                email = email,
                name = jwt.getClaimAsString(NAME_CLAIM)?.takeIf { it.isNotBlank() },
            )
        val user = users.provisionFromPrincipal(claims)
        return ActingUser(
            checkNotNull(user.id) { "A provisioned user reached the REST adapter without an id." },
        )
    }

    private companion object {
        const val ATTRIBUTE = "hive.currentUserId"
        const val EMAIL_CLAIM = "email"
        const val NAME_CLAIM = "name"
        const val NO_TOKEN = "The request carries no authenticated bearer token."
        const val NO_EMAIL = "The bearer token carries no email claim."
    }
}
