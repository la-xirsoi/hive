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
| MS SQL Server 2022 | **met** — the migration has now run against SQL Server 2022; see 4.1 and section 7 |
| Containerized, Podman | **met** — the stack builds and runs under Podman; see 4.2 and section 7 |
| OAuth-compatible login, JWT, HTTPS | **met** — a Keycloak token from a real PKCE login is accepted; see 4.3 and section 7 |

---

## 4. What could NOT be verified

This machine had **no container runtime** when this report was written:
neither Podman nor Docker was installed. Everything in this section followed
from that single fact. None of it was a design gap; all of it was unexecuted
code.

> **Superseded in part on 2026-09-15.** Podman was installed and the stack was
> run. Sections 4.1 through 4.4 below are kept as written — they are the honest
> record of what was and was not known then — and **section 7 records what the
> first real execution proved and what it cost**. Read them together: the
> predictions in 4.2 about what an unrun stack might hide were, in substance,
> correct.

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

> **2026-09-15:** it needed exactly that round of fixes — five of them, listed
> in section 7.2 — and now comes up. The warning stands as written for anything
> else in this repository that has never been executed.

---

## 7. Addendum — 2026-09-15: the stack has been run

Podman 5 on Windows 11. `podman compose up -d` from `containers/`, after
`generate-certs.sh` and trusting the CA.

### 7.1 What is now proven

| Claim | Evidence |
|-------|----------|
| Both images build | `podman compose build` succeeds; the Gradle and npm dependency layers cache across source-only edits as intended |
| The stack comes up | `db`, `db-init`, `idp`, `backend`, `frontend` all reach their terminal state; four healthchecks report healthy |
| nginx accepts the configuration | the frontend container serves the SPA, and both proxied upstreams answer |
| The migration runs on real SQL Server | Flyway reports `Successfully validated 1 migration` against `Microsoft SQL Server 16.0`, and `ddl-auto: validate` passes on the schema it produced — retiring most of 4.1 |
| HTTPS end to end | browser to nginx, nginx to backend and nginx to Keycloak are all TLS, each verified against the development CA rather than skipped |
| Plaintext redirects to the right place | `http://localhost:8080/projects/7?tab=tasks` answers `301` to `https://localhost:8444/projects/7?tab=tasks`, path and query intact; the frontend logs `hive: plaintext redirects to https://localhost:8444` at startup. `/healthz` still answers `200` over plaintext, by design, so probes need no certificate |
| A real Keycloak token is accepted | the full authorization-code + PKCE flow was driven through the gateway as user `ada`; the resulting token carries `iss: https://localhost:8444/idp/realms/hive` and `GET /api/v1/projects/mine` with it returns `200 []`, where the same request without it returns `401` — retiring 4.3 |

### 7.2 What it cost — six bugs no parser could have found

1. **The IdP healthcheck could never pass.** Setting `KC_HTTPS_*` makes
   Keycloak's management interface serve HTTPS as well, and the image ships no
   TLS-capable client, so a plaintext probe of `/health/ready` read an empty
   reply forever. Keycloak itself was fine and served the login screen the whole
   time; compose reported the service as failed, and the backend — gated on
   `condition: service_healthy` — never started at all. **A healthcheck that
   cannot pass is indistinguishable, from the outside, from a service that
   cannot start.**
2. **No application database existed.** The mssql image creates none, so Flyway
   failed with `Cannot open database "hive" requested by the login`. Fixed with
   an idempotent `db-init` service.
3. **The SPA had no route to the API.** `environment.ts` asks for `/api/v1` on
   its own origin; nginx had no proxy for it, so every API call fell through the
   SPA fallback and returned `index.html` with a `200`. The worst possible
   failure shape: success status, wrong content type, no error anywhere.
4. **The issuer could not have matched.** The compose issuer was
   `https://idp:8443/realms/hive`, a name no browser can resolve, while the
   bundle still held the placeholder `id.hive.example.com`. Exactly the
   issuer/audience mismatch 4.3 predicted would not be caught. Fixed
   structurally rather than by editing three values into agreement: the gateway
   now serves the IdP under `/idp` on the app's own origin, the SPA derives the
   issuer from `window.location.origin`, and the backend fetches signing keys
   from an internal URL while validating the public `iss` string. There is no
   longer a hostname in the bundle to get wrong.

5. **The plaintext listener redirected to a port nothing listens on, and a
   browser could not have reached it anyway.** Reported as
   `ERR_SSL_PROTOCOL_ERROR` on `http://localhost:8080/`, which is two
   independent failures wearing one error message.

   *Server side:* the redirect was `return 301 https://$host$request_uri`, and
   `$host` is the Host header with the port **stripped** — so the answer was
   `Location: https://localhost/`, port 443, where nothing in this stack
   listens. The published port is a fact of the compose file rather than of the
   container, so it is now passed in as `HIVE_PUBLIC_ORIGIN` and written into
   the configuration at startup beside the resolver, and the redirect preserves
   path and query.

   *Browser side, and the reason the error said TLS:* **HSTS is scoped to a
   host and has no concept of a port.** Once any response from
   `https://localhost:8444` has carried the header — in practice the identity
   provider's, for the reason in item 6 — `http://localhost:` is pinned on
   *every* port, so the browser upgraded the request to HTTPS and spoke TLS to a
   listener that answers plaintext. Nothing was sent to port 8080 in cleartext
   and no redirect was ever requested — **no server-side change can reach that
   request.** The escape hatch is `chrome://net-internals/#hsts` → *Delete
   domain security policies* → `localhost`; the standing advice is to use
   `https://localhost:8444` and leave 8080 to probes and `curl`.

   The blast radius is wider than this project: that header pins `localhost`
   itself, so it breaks every unrelated plain-HTTP dev server on the machine,
   on any port, for two years. `hive-ild` asks whether publishing 8080 at all
   is worth the trap. The runbook now carries the warning and the escape hatch.

   **This is the bug this report exists to predict and could not have.** Both
   halves are invisible to a parser: one is a redirect target that is
   syntactically perfect and semantically wrong, the other is browser state
   accumulated across visits, held outside the stack entirely.

6. **The SPA's own responses carried no security headers at all.** Found while
   confirming where the HSTS pin in item 5 came from: `GET
   https://localhost:8444/` returned no `Strict-Transport-Security`, no
   `Content-Security-Policy`, no `X-Frame-Options`, `X-Content-Type-Options`,
   `Referrer-Policy` and no `Permissions-Policy`. Neither did the fingerprinted
   assets. Every one of those directives was present in the configuration, in
   the server block, spelled correctly.

   *The cause is a property of nginx, not a missing line:* **`add_header` in a
   `location` replaces the inherited set rather than extending it.** A location
   that declares one header of its own starts from empty and inherits nothing.
   Two did — the asset regex and `location = /index.html`, each for a
   `Cache-Control` — and so answered with that one header and nothing else.
   The SPA fallback routes every deep link through `= /index.html`, so this
   covered the document and every client-side route in the application. `/healthz` had
   the same defect through an `add_header Content-Type`, which was also
   duplicating a header nginx already sets.

   The consequences ran in both directions. The CSP that item 4 describes as
   the structural fix for the single-origin problem — the one that makes
   `connect-src 'self'` mean something — was never sent on the document it
   exists to constrain. And the HSTS pin that item 5 spent two paragraphs
   explaining was **not the application's**: it came from `/idp`, the one
   location that declares its own headers deliberately and therefore had to
   repeat HSTS to keep it. The pin that broke every plain-HTTP dev server on
   the machine was set by Keycloak's responses.

   *Fixed structurally,* the way item 4 was, rather than by pasting six
   directives into two more places: the set now lives in
   `containers/frontend/security-headers.conf` and is `include`d by the server
   block and again by each location that declares a header of its own, so
   adding one is no longer a way to silently lose the rest. `/healthz` sets its
   type with `default_type` and declares nothing. The asset location spells out
   `max-age=31536000` instead of leaving it to `expires 1y`, which would emit a
   second `Cache-Control` beside the `immutable` one. `/idp` keeps its
   deliberate override and now sends HSTS *once* — Keycloak's own, shorter,
   header is dropped with `proxy_hide_header`, because a browser reads only the
   first one in a response and was therefore honouring a year where the origin
   intends two.

   Re-verified against the running stack: `/`, `/index.html`, a deep link, a
   fingerprinted asset, `/favicon.ico` and `/healthz` all return the full six.
   Unlike items 1–5 this one was found on 2026-09-15 and fixed on 2026-09-18;
   the run recorded above was made against the stack as it stood before the
   fix, which is why the finding reads in the past tense and the others do not.

   **This one is invisible to a reviewer reading the file, not just to a
   parser.** Nothing is missing and nothing is misspelled; the headers are
   declared once, in the obvious place, and the defect is in what a *different*
   directive fifty lines below does to them. It is only observable in a
   response. Tracked and closed as `hive-scn`.

### 7.3 What remains unproven

- **4.5 stands.** There is still no browser-driven end-to-end test. The PKCE
  flow above was driven with `curl`, which proves the protocol and the token,
  not the application's screens.
- **SQL Server dialect differences.** The migration runs and the mappings
  validate, but the test suite still executes against H2; paging syntax,
  collation and `DATETIME2` rounding remain exercised only in compatibility
  mode. `docs/testing.md` section 4 gives the procedure for running the suite
  against the `dev` profile, which is now possible and has not been done.
- **Nothing here says anything about a deployed environment.** The certificates
  are from a CA that exists on one machine, `start-dev` is not a production
  Keycloak mode, and the database holds a single SA credential.
