package hive.adapter.`in`.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.header.HeaderWriterFilter
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.UriComponentsBuilder

/**
 * One hook for a profile-specific addition to the filter chain.
 *
 * It exists so that [SecurityConfig] stays a single, profile-independent
 * description of the API's rules while HTTPS enforcement -- which must *not* be
 * on in tests, or MockMvc's plain-HTTP requests would all be redirected -- can
 * be switched on by adding a bean under one profile.
 */
fun interface HttpSecurityCustomizer {
    fun customize(http: HttpSecurity)
}

/**
 * The API's security rules (`docs/api-contract.md` section 7,
 * `docs/authorization.md` sections 1 and 11).
 *
 * - **Bearer tokens only.** The API is an OAuth2 resource server; the JWT is
 *   validated against the issuer named by
 *   `spring.security.oauth2.resourceserver.jwt.issuer-uri`.
 * - **Stateless, no CSRF token.** There is no session and no cookie, so there
 *   is no cross-site request forgery vector for a CSRF token to close; leaving
 *   it on would only break every non-GET call.
 * - **Public**: the health probe the container's healthcheck polls, the
 *   generated OpenAPI document and the API explorer. The dev token endpoint is
 *   listed too -- it exists only under the `dev` profile, and under any other
 *   the route is simply absent and answers 404, which is what section 9
 *   requires and what `SecurityConfigTest` proves.
 * - **Everything else requires authentication**, which is the default the last
 *   rule states explicitly rather than leaving implied.
 *
 * Authorisation beyond "is this caller authenticated" is deliberately absent
 * here. Who may do what is a domain decision made by
 * [hive.domain.policy.AuthorizationPolicy] with the whole aggregate in hand;
 * expressing half of it as URL patterns would put two authorities in
 * disagreement.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun apiSecurityFilterChain(
        http: HttpSecurity,
        entryPoint: ApiAuthenticationEntryPoint,
        accessDeniedHandler: ApiAccessDeniedHandler,
        customizers: ObjectProvider<HttpSecurityCustomizer>,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors(Customizer.withDefaults())
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { registry ->
                registry
                    .requestMatchers(*PUBLIC_PATHS).permitAll()
                    .requestMatchers(HttpMethod.POST, DEV_TOKEN_PATH).permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 -> oauth2.jwt(Customizer.withDefaults()) }
            .exceptionHandling { handling ->
                handling
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
            }

        customizers.orderedStream().forEach { it.customize(http) }
        return http.build()
    }

    companion object {
        /** `docs/api-contract.md` section 7 -- the endpoints that carry no token. */
        val PUBLIC_PATHS: Array<String> =
            arrayOf(
                "/actuator/health",
                "/actuator/health/**",
                "/v3/api-docs",
                "/v3/api-docs/**",
                "/swagger-ui.html",
                "/swagger-ui/**",
            )

        /** `docs/api-contract.md` section 9 -- present only under the `dev` profile. */
        const val DEV_TOKEN_PATH: String = "/api/v1/dev/token"
    }
}

/**
 * AU-3: HTTPS only -- in production.
 *
 * A plain-HTTP request is answered with a redirect to the same URL over TLS
 * rather than being served, and every HTTPS response carries HSTS so the
 * browser does not try plain HTTP a second time.
 *
 * It is a filter rather than Spring Security's old `requiresChannel` rule
 * because Spring Security 7 removed channel security
 * (`org.springframework.security.web.access.channel` is gone from
 * spring-security-web); the DSL method still compiles against the config
 * module and fails at runtime with a `NoClassDefFoundError`, which is a worse
 * outcome than writing the eight lines by hand.
 *
 * Scoped to `prod` on purpose: MockMvc issues plain HTTP, so enabling this
 * under the test profile would turn every web-layer test into an assertion
 * about a 302.
 *
 * Behind the reverse proxy the container stack terminates TLS at, the original
 * scheme arrives in `X-Forwarded-Proto`; `server.forward-headers-strategy` in
 * the `prod` profile is what makes `isSecure` report the client's scheme rather
 * than the proxy's hop.
 */
@Configuration
@Profile("prod")
class HttpsEnforcementConfig {

    @Bean
    fun requireHttps(): HttpSecurityCustomizer =
        HttpSecurityCustomizer { http ->
            http.addFilterBefore(HttpsRedirectFilter(), HeaderWriterFilter::class.java)
            http.headers { headers ->
                headers.httpStrictTransportSecurity { hsts ->
                    hsts.includeSubDomains(true).maxAgeInSeconds(HSTS_MAX_AGE_SECONDS)
                }
            }
        }

    private companion object {
        /** One year, the value HSTS preload lists require. */
        const val HSTS_MAX_AGE_SECONDS = 31_536_000L
    }
}

/**
 * Redirects any request that did not arrive over TLS to the same URL on
 * `https`, query string included.
 *
 * Installed only by [HttpsEnforcementConfig], so only under `prod`.
 */
class HttpsRedirectFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.isSecure) {
            filterChain.doFilter(request, response)
            return
        }
        val target =
            UriComponentsBuilder
                .fromUriString(request.requestURL.toString())
                .scheme("https")
                // The TLS port is the default one at the edge; carrying the
                // plain-HTTP port across would redirect to a port nothing
                // listens on.
                .port(-1)
                .query(request.queryString)
                .build()
                .toUriString()
        response.sendRedirect(target)
    }
}
