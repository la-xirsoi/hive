package hive.adapter.`in`.security

import hive.adapter.`in`.dto.DevTokenRequest
import hive.adapter.`in`.dto.DevTokenResponse
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant

/**
 * `POST /api/v1/dev/token` -- the dev-profile sign-in of
 * `docs/api-contract.md` section 9.
 *
 * Hands out a signed token for any email address the caller names. That is
 * obviously not authentication; it is a stand-in for an identity provider that
 * cannot be hosted on this machine, and it is why the whole class is
 * `@Profile("dev")`. `SecurityConfigTest` asserts the route is absent
 * without that profile, because "it is only in dev" is a claim worth a test
 * rather than a comment.
 *
 * The response shape is fixed by the contract: the Angular dev sign-in is
 * written against it by a different agent and the appendix is the only thing
 * keeping the two in agreement.
 */
@RestController
@RequestMapping("/api/v1/dev")
@Profile("dev")
class DevTokenController(
    private val encoder: JwtEncoder,
    @param:Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private val issuer: String,
    @param:Value("\${hive.dev-auth.token-ttl-seconds:3600}")
    private val ttlSeconds: Long,
) {

    @PostMapping("/token", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun issue(
        @Valid @RequestBody request: DevTokenRequest,
    ): DevTokenResponse {
        // @Valid has already rejected a null or malformed address, so the
        // non-null assertion here is a statement about the validator, not a
        // hope about the client.
        val email = requireNotNull(request.email)
        val issuedAt = Instant.now()
        val expiresAt = issuedAt.plus(Duration.ofSeconds(ttlSeconds))

        val claims =
            JwtClaimsSet.builder()
                .issuer(issuer)
                // No subject registry exists locally, so the email doubles as
                // the stable subject. US-3 matches on the email claim anyway
                // (`docs/authorization.md` section 12).
                .subject(email)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("email", email)
                .also { builder -> request.name?.takeIf { it.isNotBlank() }?.let { builder.claim("name", it) } }
                .build()

        val header = JwsHeader.with(SignatureAlgorithm.RS256).build()
        val token = encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue

        return DevTokenResponse(accessToken = token, expiresIn = ttlSeconds)
    }
}
