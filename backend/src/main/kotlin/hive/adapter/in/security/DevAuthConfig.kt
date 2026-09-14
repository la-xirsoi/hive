package hive.adapter.`in`.security

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.UUID

/**
 * The dev profile's local token issuer (`docs/api-contract.md` section 9).
 *
 * The build machine has no container runtime and therefore no identity provider
 * to authenticate against (`docs/toolchain.md`, "Known environment gaps"). This
 * mints tokens locally so the stack can be demonstrated end to end.
 *
 * The important property is that it changes **only where the signature comes
 * from**. It publishes a [JwtDecoder] built from the same key pair, which
 * Spring Boot's resource-server auto-configuration then backs away from
 * (`@ConditionalOnMissingBean`), so the token still travels the ordinary
 * `Authorization: Bearer` path, through the ordinary filter chain, into the
 * ordinary [CurrentUserArgumentResolver]. Nothing downstream of the decoder can
 * tell a dev token from a real one -- which is precisely why the dev path is
 * evidence about the prod path.
 *
 * The key pair is generated at startup and never leaves memory: it is not
 * written to disk, not committed, and gone when the process ends.
 *
 * Everything in this file is `@Profile("dev")`. Under any other profile there
 * is no issuer, no signing key and -- as `DevTokenRouteAbsentTest` proves -- no
 * route.
 */
@Configuration
@Profile("dev")
class DevAuthConfig(
    @param:Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private val issuer: String,
) {

    /** An RSA key pair minted at startup, used to sign and to verify dev tokens. */
    @Bean
    fun devSigningKey(): RSAKey {
        val keyPair =
            KeyPairGenerator.getInstance("RSA")
                .apply { initialize(KEY_SIZE) }
                .generateKeyPair()
        return RSAKey.Builder(keyPair.public as RSAPublicKey)
            .privateKey(keyPair.private as RSAPrivateKey)
            .keyID(UUID.randomUUID().toString())
            .build()
    }

    /** Signs the tokens [DevTokenController] hands out. */
    @Bean
    fun devJwtEncoder(devSigningKey: RSAKey): JwtEncoder =
        NimbusJwtEncoder(ImmutableJWKSet<SecurityContext>(JWKSet(devSigningKey)))

    /**
     * Validates them again on the way back in -- signature, expiry **and**
     * issuer, exactly as the production decoder would.
     */
    @Bean
    fun jwtDecoder(devSigningKey: RSAKey): JwtDecoder =
        NimbusJwtDecoder.withPublicKey(devSigningKey.toRSAPublicKey())
            .build()
            .apply { setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer)) }

    private companion object {
        const val KEY_SIZE = 2048
    }
}
