# Testing the persistence layer

Hive targets **Microsoft SQL Server 2022**. This machine has no container
runtime, so there is no SQL Server to test against. This document records what
the test suite runs instead, exactly what that proves, what it leaves unproven,
and what to do about the difference.

The short version: **the real Flyway migration runs in the tests**, against H2 in
SQL Server compatibility mode, and Hibernate then validates the entity mappings
against the schema that migration produced. What is not covered is SQL Server's
*behaviour* -- its dialect, its collation, its concurrency -- not the DDL's
shape.

---

## 1. What the tests actually run against

```
spring.datasource.url = jdbc:h2:mem:hive;MODE=MSSQLServer;DB_CLOSE_DELAY=-1
spring.flyway.enabled = true          # V1__baseline.sql runs, unmodified
spring.jpa.hibernate.ddl-auto = validate
```

Three things follow from those three lines, and each is load-bearing:

1. **Flyway runs the production migration.** The tests do not use a
   Hibernate-generated schema and do not use a second, H2-flavoured copy of the
   DDL. `src/main/resources/db/migration/V1__baseline.sql` is executed verbatim.
   A syntax error, a missing column, or a constraint that does not parse fails
   the build.

2. **`ddl-auto: validate` checks the entities against that schema.** If a JPA
   entity declares a column the migration does not create -- or of an
   incompatible type -- the context fails to start and every test in
   `hive.adapter.out.persistence` fails at once. This is what keeps the entity
   classes and the migration from drifting apart, which is the single most
   common way a "green" persistence layer fails in production.

3. **The constraints are real and are asserted by name.**
   `SchemaConstraintIT` provokes `UQ_users_email`, `CK_tasks_status`,
   `FK_tasks_project`, `FK_tasks_assignee`, `FK_projects_team` and
   `FK_team_members_user`, and asserts the *named* constraint appears in the
   error. That is why `V1__baseline.sql` names every constraint explicitly: an
   auto-generated name could not be asserted on, and a test that accepted "some
   integrity error" would pass when the wrong constraint fired.

The migration was written for the intersection of the two dialects on purpose.
Every construct in it -- `BIGINT IDENTITY(1,1)`, `NVARCHAR(n)`, `DATETIME2(0)`,
named `PRIMARY KEY` / `UNIQUE` / `FOREIGN KEY` / `CHECK` constraints, and
`CREATE INDEX` -- parses and behaves identically in SQL Server 2022 and in H2's
`MODE=MSSQLServer`. Nothing in the DDL was weakened to achieve that: no
construct was dropped, no constraint was left unnamed, and no `datetime2`
precision was widened. The constructs that would *not* have survived the trip
were simply never needed:

| Avoided | Why |
|---------|-----|
| `GO` batch separators | Flyway's SQL Server parser understands them; H2's does not. Not needed -- there is no batch-scoped statement in the schema. |
| `N'...'` literals | The `CHECK` constraint compares ASCII status names, for which a plain literal is identical. |
| `NVARCHAR(MAX)` | Every text column has a real maximum from the domain value objects (200, 254, 4000), so an unbounded type would have been less strict, not more portable. |
| Filtered indexes (`CREATE INDEX ... WHERE`) | None of the queries needs one; a composite index answers each. |
| `sys.*` catalog lookups, `IF NOT EXISTS` guards | A baseline migration runs exactly once against an empty database. |

---

## 2. The test topology

| Test | Kind | Subject |
|------|------|---------|
| `PersistenceMappersTest` | pure unit, no Spring | entity <-> domain round-tripping, in both directions, for all five aggregates and all five task statuses |
| `DomainHasNoJpaAnnotationsTest` | source scan | that no domain class carries a JPA annotation, qualified or imported |
| `SchemaConstraintIT` | Spring + H2 | uniqueness, referential integrity, the status check, `DATETIME2(0)` minute precision, paging, batch save |
| `RoleScopedQueryIT` | Spring + H2 | the visibility rules, as exact row sets, for every role |

The integration tests boot a **narrow context** (`PersistenceTestContext`): a
datasource, Hibernate, Flyway, a transaction manager, the five JPA repositories
and the five adapters -- and nothing else. `hive.application` and
`hive.adapter.in` are not component-scanned. A failure in this package therefore
means the persistence adapter is wrong, not that some unrelated bean failed to
start.

Each test runs in a transaction that rolls back, so the shared in-memory
database needs no cleanup between tests.

### Why `RoleScopedQueryIT` is shaped the way it is

`docs/architecture.md` says visibility is a query concern, not a filter. That
makes the queries in `TaskJpaRepository` and `ProjectJpaRepository` security
code, and security code needs tests that fail when a query returns **too much**,
not merely when it returns too little. So:

* Every assertion uses `containsExactly` against an enumerated set of ids. A
  test that asserted "the lead can see the Todo task" would pass equally well
  against a query that returned the entire table.
* The fixture graph (`HiveGraph`) contains two teams with overlapping members, a
  project owner who is deliberately *not* in the project's team (INV-2), a
  project whose owner is also the team lead, tasks in all five statuses, a
  canceled task that still has an assignee (VIS-5), and an outsider who must see
  nothing anywhere.
* Negative cases are asserted explicitly: leading one team grants no sight of
  another team's project; owning one project of a team grants no sight of the
  team's other project.

---

## 3. What this does **not** prove

Read this section before treating a green build as evidence about production.

**The dialect is different.** Hibernate generates SQL for `H2Dialect` in tests
and for `SQLServerDialect` in production. The JPQL is the same; the SQL is not.
Paging in particular becomes `LIMIT ... OFFSET` on H2 and `OFFSET ... ROWS FETCH
NEXT ... ROWS ONLY` on SQL Server. The *shape* of every query -- its joins, its
`EXISTS` subqueries, its correlation -- is exercised; the emitted SQL text is
not.

**Flyway parses with a different parser.** In production Flyway uses its SQL
Server parser (`flyway-sqlserver`, on the classpath for exactly that reason); in
tests it uses the H2 one. The migration avoids every construct where the two
differ (see the table above), but "avoids" is a claim verified by review, not by
execution.

**Collation is not exercised.** SQL Server's default collation is
case-insensitive; H2's is case-sensitive. The schema does not depend on either,
because `EmailAddress.normalized` is lower-cased before it is stored and
`UQ_users_email` is a plain unique constraint over the normalized value. That
design choice is what makes the difference irrelevant -- but the *SQL Server*
side of it is unverified. If a future change compares un-normalized text in SQL,
the tests would not catch a collation-dependent result.

**`DATETIME2(0)` rounding is not exercised at the boundary.** SQL Server rounds a
value to the declared scale on insert; H2 behaves the same way for these values,
but Hive never relies on it: `Comment.create` truncates to the minute before the
value ever reaches JDBC, so the seconds field is always zero. A change that
started writing sub-minute values would behave identically under test and might
not under SQL Server.

**Nothing concurrent is tested.** No isolation level, no lock escalation, no
deadlock, no `READ COMMITTED SNAPSHOT`, no behaviour of `IDENTITY` under
concurrent inserts. The tests are single-threaded and transactional.

**Nothing about performance.** The indexes in `V1__baseline.sql` are declared and
created, but H2's optimizer is not SQL Server's. No test asserts that a query
uses an index, and the fixture data is far too small for a plan to be meaningful.

**Vendor error text is not exercised.** `SchemaConstraintIT` asserts that the
*named constraint* appears in the exception message, which both databases
provide, but the SQLState codes and message formats differ. No production code
parses them -- translating `UQ_users_email` into the 409 that US-2 requires is
the application layer's job, done by catching
`DataIntegrityViolationException` -- and it should stay that way.

**The `mssql-jdbc` driver is never loaded.** It is `runtimeOnly` and the tests
use `org.h2.Driver`. Connection-string handling, `encrypt=true` negotiation and
TLS trust are entirely untested here.

---

## 4. Residual risk, and how to retire it

The residual risk is concentrated in one place: **the migration has never been
executed by SQL Server.** Everything else in the list above is either designed
around (collation, rounding) or out of scope for a persistence unit test
(concurrency, performance).

That risk is retired by one action, not by more H2 tests:

1. Bring up the SQL Server 2022 container from the compose stack
   (issue `hive-0fq.3`).
2. Run the application against it with `--spring.profiles.active=dev` and
   `HIVE_DB_*` set. Flyway migrates on startup; `ddl-auto: validate` then checks
   the mappings against the real schema. A clean startup is the proof.
3. Re-run `SchemaConstraintIT` and `RoleScopedQueryIT` with the datasource
   pointed at that container. They are ordinary Spring tests with no H2-specific
   code, so only the four `spring.datasource.*` properties change.

Until step 2 has been done at least once on a real SQL Server, treat the schema
as *reviewed and portably tested* rather than *proven*.

### What would make this obsolete

A container runtime on the build machine. With one available, the right setup is
Testcontainers against `mcr.microsoft.com/mssql/server:2022-latest`, with the
H2 configuration kept only as the fast path for local iteration. The test code
would not change -- the substitution is confined to four properties in the
`test` profile of `application.yml`, which is the reason it was done that way.
