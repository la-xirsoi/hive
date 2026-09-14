package hive.adapter.out

/**
 * Outbound adapter layer (Hexagonal Architecture "driven" side).
 *
 * Implements the repository ports declared in `hive.domain.port` against
 * Microsoft SQL Server. Everything lives under
 * [hive.adapter.out.persistence]:
 *
 * * `persistence.entity` -- JPA entities, a **separate** set of classes from the
 *   domain entities. No domain class carries a JPA annotation; that rule is
 *   enforced by test, not by convention.
 * * `persistence.mapper` -- the one place the two models meet, in both
 *   directions.
 * * `persistence.jpa` -- Spring Data repositories. The role-scoped visibility
 *   queries are here, as SQL, because `docs/architecture.md` says visibility is
 *   a query concern and not a filter.
 * * `persistence` -- the five adapters implementing the ports, plus the paging
 *   conversion that keeps Spring Data's `Pageable` and `Page` out of the domain.
 *
 * The schema itself is `src/main/resources/db/migration/V1__baseline.sql`.
 *
 * This package may depend on `hive.domain`. It may not depend on
 * `hive.application` or `hive.adapter.in`.
 */
internal const val LAYER: String = "adapter.out"
