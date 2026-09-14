package hive.domain

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The hard rule of the hexagonal architecture, enforced mechanically rather than
 * by review: **nothing under `hive.domain` may import a framework.**
 *
 * The domain compiles against the Kotlin standard library and `java.time` alone.
 * A grep is easy to forget; this test is not.
 */
@DisplayName("hive.domain imports")
class NoFrameworkImportsTest {

    private val domainSources: List<File> =
        File("src/main/kotlin/hive/domain")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun `the domain source tree was found`() {
        assertTrue(
            domainSources.size >= 15,
            "expected the domain sources to be scanned, found ${domainSources.size}",
        )
    }

    @Test
    fun `no domain source imports a framework`() {
        val forbidden =
            listOf(
                "org.springframework.",
                "jakarta.",
                "javax.",
                "org.hibernate.",
                "com.fasterxml.",
                "org.flywaydb.",
                "io.mockk.",
                "org.junit.",
                "com.microsoft.",
            )

        val violations =
            domainSources.flatMap { file ->
                importsOf(file)
                    .filter { import -> forbidden.any { import.startsWith(it) } }
                    .map { "${file.path}: import $it" }
            }

        assertTrue(violations.isEmpty(), "framework imports found in hive.domain:\n${violations.joinToString("\n")}")
    }

    @Test
    fun `the domain imports only itself, java time and the Kotlin standard library`() {
        val allowedPrefixes = listOf("hive.domain.", "java.time.", "kotlin.")

        val violations =
            domainSources.flatMap { file ->
                importsOf(file)
                    .filter { import -> allowedPrefixes.none { import.startsWith(it) } }
                    .map { "${file.path}: import $it" }
            }

        assertTrue(
            violations.isEmpty(),
            "unexpected imports in hive.domain -- widen this allowlist only with good reason:\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `no domain source calls Instant now directly - time comes from HiveClock`() {
        val offenders =
            domainSources
                .filter { it.name != "HiveClock.kt" }
                .filter { file -> codeLinesOf(file).any { it.contains("Instant.now()") } }
                .map { it.path }

        assertTrue(offenders.isEmpty(), "Instant.now() outside HiveClock: $offenders")
    }

    /** Source lines with KDoc and line comments dropped, so prose about a rule is not mistaken for a breach of it. */
    private fun codeLinesOf(file: File): List<String> =
        file.readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }

    private fun importsOf(file: File): List<String> =
        file.readLines()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .map { it.removePrefix("import ").substringBefore(" as ").trim() }
}
