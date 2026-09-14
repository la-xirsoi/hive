package hive.adapter.`in`.security

import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Registers [CurrentUserArgumentResolver] so that `@CurrentUser actor: UserId`
 * resolves on every handler.
 *
 * This is the whole of Hive's MVC customisation: everything else about the web
 * layer is Spring Boot's default.
 */
@Configuration
class WebMvcConfig(
    private val currentUser: CurrentUserArgumentResolver,
) : WebMvcConfigurer {

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(currentUser)
    }
}
