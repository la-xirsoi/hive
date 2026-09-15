# Verification Report

What was checked, how, and — more importantly — what could **not** be checked
on the machine Hive was built on.

Run from a clean tree on 2026-09-14.

---

## 1. Automated verification: results

| Gate | Result |
|------|--------|
| `./gradlew clean build --rerun-tasks` | **PASS** |
| Backend tests | **891 passed**, 0 failures, 0 errors |
| Backend line coverage (Kover) | **99.68%** (1264/1268) — gate requires 70% |
| `koverVerify` (fails below 70%) | **PASS**, and proven to fail when the bound is raised |
| `npm run test:ci` (headless Chrome) | **471 passed**, 0 failures |
| Frontend line coverage | **97.74%** (1779/1820) — gate requires 70% |
| Karma coverage gate | **PASS**, and proven to fail when the bound is raised |
| `npm run build` (production bundle) | **PASS** |
| `npm run lint` (Prettier) | **PASS** |

Total: **1362 automated tests**, all passing.

### Coverage by layer

| Package | Line coverage |
|---------|---------------|
| `hive.domain.model` / `policy` / `port` / `error` | **100%** |
| `hive.application.*` (service, support, usecase, view) | **100%** |
| `hive.adapter.in.controller` / `mapper` / `bootstrap` | **100%** |
| `hive.adapter.in.security` | 99.2% |
| `hive.adapter.in.error` | 98.6% |
| `hive.adapter.out.persistence` | 98.8% |

---

## 2. Architecture compliance

The hexagonal dependency rule is checked mechanically, not by inspection. All
clean:

| Check | Result |
|-------|--------|
| No framework imports anywhere in `hive.domain` (Spring, Jakarta, Hibernate, Jackson) | **clean** |
| `hive.domain` imports no other Hive package | **clean** |
| `hive.application` imports no adapter | **clean** |
| `hive.adapter.out` imports neither `adapter.in` nor `application` | **clean** |
| No JPA annotation on any domain class | **clean** |

The first is additionally enforced at build time by `NoFrameworkImportsTest`,
which also asserts that nothing but `HiveClock` calls `Instant.now()`, and the
last by `DomainHasNoJpaAnnotationsTest`. Convention is not relied upon.

## 3. Technology constraints

| Constraint (`spec.md`) | Status |
|------------------------|--------|
| Only Kotlin >= 2.4 and TypeScript >= 6.0 | **met** — Kotlin 2.4.20, TypeScript 6.0.2. No `.java`, `.groovy` or hand-written `.js` in production source |
| Spring Boot >= 4.0.5 | **met** — 4.0.8 |
| Angular >= 22.0.2 | **met** — 22.1.x |
| Hexagonal package layout | **met** — see section 2 |
| JUnit 5 + MockK | **met** |
| Jasmine + Karma | **met** — Angular 22 defaults to Vitest; Karma was selected explicitly |
| >= 70% line coverage | **met and enforced** — 99.68% / 97.74% |
| MS SQL Server 2022 | **partially** — see 4.1 |
| Containerized, Podman | **not verified** — see 4.2 |
| OAuth-compatible login, JWT, HTTPS | **partially** — see 4.3 |

---

## 4. What could NOT be verified

This machine has **no container runtime**: neither Podman nor Docker is
installed. Everything below follows from that single fact. None of it is a
design gap; all of it is unexecuted code.

### 4.1 The migration has never been run by SQL Server

**What was done instead.** The Flyway migration `V1__baseline.sql` runs in the
test suite against H2 in `MODE=MSSQLServer`, and `ddl-auto: validate` then
checks every JPA mapping against the schema that migration produced. Each named
constraint is provoked and asserted by name. The DDL was written in the
intersection of the two dialects deliberately, and no construct was weakened to
achieve it.

**What remains unproven.** Hibernate emits `H2Dialect` SQL in tests and would
emit `SQLServerDialect` in production — paging syntax differs. Flyway parses with
the H2 parser rather than `flyway-sqlserver`. Collation, `DATETIME2` rounding,
concurrency behaviour, index plans, vendor error text, and the `mssql-jdbc`
driver itself are all untouched.

**How to retire it.** Bring up the compose stack and run the suite against the
`dev` profile. `docs/testing.md` section 4 gives the procedure.

### 4.2 No container image has been built and the stack has never started

`containers/backend/Containerfile`, `containers/frontend/Containerfile`,
`containers/frontend/nginx.conf` and `containers/compose.yaml` are authored and
reviewed but **never executed**. The compose YAML and the Keycloak realm JSON
were parsed and validated; that is all a parser can tell you.

What that leaves unknown: whether the images build, whether the Gradle and npm
layers cache as intended, whether the healthchecks pass, whether the service
dependency ordering is right, and whether nginx accepts the configuration.

**Verified by exception:** `containers/scripts/generate-certs.sh` *was* run. It
produces a CA and three leaf certificates; all three verify against the CA with
correct SANs, and the PKCS#12 keystore loads. Doing so surfaced and fixed two
real Windows portability bugs (MSYS path conversion mangling openssl's `/CN=`
subject, and native openssl being unable to read a process-substitution fd).

### 4.3 Authentication has not been tested against a real identity provider

**What was done instead.** The backend is a standard OAuth2 resource server. A
`@Profile("dev")` endpoint mints a locally signed JWT that the **same**
`JwtDecoder` then accepts — asserted by test — so the token's path through the
application is identical in dev and prod and only the issuer differs. The
endpoint's absence outside `dev` is asserted twice: a 404 in the web slice, and
no such bean in the full context.

**What remains unproven.** No token from Keycloak has been validated. The
authorization-code-with-PKCE flow has been unit tested in the Angular app but
never driven end to end against a live issuer. Issuer/audience mismatches are
the most common real failure here and would not have been caught.

### 4.4 HTTPS enforcement is proven in part

The prod-profile redirect filter and HSTS are covered by a prod-profile slice
test. What has **not** been exercised is the full chain: nginx terminating TLS,
redirecting plaintext, and the backend rejecting plaintext behind it.

### 4.5 No browser-level end-to-end test

The Angular suite is component-level with mocked HTTP. No test drives a real
browser against a running backend. The closest thing is `DevDataSeederIT`, which
exercises policy, transition machine, mappers, JPA and the migrated schema
together in one Spring context — but through Kotlin, not a browser.

### 4.6 OpenAPI documentation is not implemented

`spec.md` does not require it; the project plan did. springdoc has no release
compatible with Spring Boot 4 / Spring Framework 7 — Maven Central's newest is
Boot 3.x-only. Rather than downgrade the framework to satisfy a documentation
tool, the issue is closed as a recorded decision, with `hive-4jc` filed to revisit when springdoc ships Boot 4 support. Hand-authoring an `openapi.yaml` was also rejected: a hand-maintained document drifts from the code silently, which is worse than having none. `/v3/api-docs/**` and
`/swagger-ui/**` are already permitted in the security config, so adding the
starter later requires no security change. `docs/api-contract.md` remains the
authoritative API description meanwhile.

---

## 5. Known limitations in delivered behaviour

These are properties of the code as written, not environment gaps.

1. **Server-computed permissions exist only for tasks.** `TaskDetail` carries a
   `permissions` block, so every task control is driven by the server. Teams and
   projects have no equivalent, so the UI decides whether to show rename, member
   management, lead transfer, ownership transfer and task-create by comparing the
   current user's id with the `teamLead.id` / `projectOwner.id` the server
   returned. That is a comparison against server data rather than re-derived
   policy, and every operation is still enforced server-side — the worst case is
   a control that appears and then 403s. A `TeamPermissions` / `ProjectPermissions`
   block would close it properly. Tracked as `hive-3p8`.

2. **The OAuth subject claim is not stored.** Users are matched by email, which
   is sound only because email is unique. If a user's address changes at the
   identity provider they become a new Hive user. Recorded in
   `docs/authorization.md` section 12.

3. **No delete anywhere.** `spec.md` defines no deletion semantics, so task,
   team and project deletion return 405 by design, with `Canceled` as the
   disposal path for tasks.

---

## 6. Judgement

The domain rules — the part of this system that is actually the product — are
implemented at 100% line coverage with a test named after every rule in
`docs/authorization.md`, and the transition machine is checked against a
hand-transcribed table over the full cross product of states and actor roles.
Those I would defend.

The infrastructure is the opposite: carefully written, entirely unrun. The
Containerfiles, the compose stack and the identity provider integration are
plausible and reviewed, and I would expect them to need a round of fixes the
first time anyone has a container runtime to point at them. **Do not treat the
green build as evidence that the stack comes up.**
