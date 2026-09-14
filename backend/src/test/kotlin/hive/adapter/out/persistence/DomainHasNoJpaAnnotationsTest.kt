package hive.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The rule that makes this whole package worth its weight: **the domain model
 * and the persistence model are two different sets of classes.**
 *
 * `hive.domain.NoFrameworkImportsTest` already forbids `jakarta.*` imports in
 * the domain. This test is the same rule stated from the adapter's side and
 * closes the gap that an import check leaves open -- a fully-qualified
 * `@jakarta.persistence.Entity` needs no import at all.
 *
 * Why it matters beyond tidiness: an ORM annotation on a domain class drags
 * mutable fields, a no-arg constructor and lazy proxies into entities that are
 * deliberately immutable, and it makes the rules in `docs/authorization.md`
 * impossible to test without a database.
 */
@DisplayName("domain / persistence separation")
class DomainHasNoJpaAnnotationsTest {

    private val domainSources: List<File> =
        File("src/main/kotlin/hive/domain")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun `the domain source tree was found`() {
        assertThat(domainSources).hasSizeGreaterThanOrEqualTo(15)
    }

    @Test
    fun `no domain source mentions jakarta persistence, qualified or imported`() {
        val offenders = domainSources.filter { it.readText().contains("jakarta.persistence") }

        assertThat(offenders)
            .describedAs("JPA must not reach hive.domain; map to a separate entity in hive.adapter.out instead")
            .isEmpty()
    }

    @Test
    fun `no domain source carries a JPA or Hibernate annotation`() {
        val annotations = listOf(
            "@Entity", "@Table", "@Column", "@Id", "@GeneratedValue", "@Embeddable", "@Embedded",
            "@OneToMany", "@ManyToOne", "@ManyToMany", "@OneToOne", "@ElementCollection",
            "@JoinColumn", "@JoinTable", "@CollectionTable", "@Enumerated", "@Converter",
            "@MappedSuperclass", "@Transient", "@Version", "@NamedQuery",
        )

        val offenders =
            domainSources.flatMap { file ->
                codeLinesOf(file)
                    .filter { line -> annotations.any { line.contains(it) } }
                    .map { "${file.path}: $it" }
            }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the persistence entities are a separate set of classes from the domain entities`() {
        val entityNames =
            File("src/main/kotlin/hive/adapter/out/persistence/entity")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file -> Regex("""^open class (\w+)""", RegexOption.MULTILINE).findAll(file.readText()) }
                .map { it.groupValues[1] }
                .toList()

        // Five aggregates, five entities, none of them sharing a name with the
        // domain class it maps -- the `Entity` suffix is what makes a mistaken
        // import at a call site visible on sight.
        assertThat(entityNames).containsExactlyInAnyOrder(
            "UserEntity",
            "TeamEntity",
            "ProjectEntity",
            "TaskEntity",
            "CommentEntity",
        )
    }

    /** Source lines with KDoc and line comments dropped, so prose about a rule is not mistaken for a breach of it. */
    private fun codeLinesOf(file: File): List<String> =
        file.readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
}
