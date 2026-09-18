# Verification Report

What was checked, how, and — more importantly — what could **not** be checked
on the machine Hive was built on.

Run from a clean tree on 2026-09-14.

---

## 1. Automated verification: results

| Gate                                  | Result                                                |
| ------------------------------------- | ----------------------------------------------------- |
| `./gradlew clean build --rerun-tasks` | **PASS**                                              |
| Backend tests                         | **891 passed**, 0 failures, 0 errors                  |
| Backend line coverage (Kover)         | **99.68%** (1264/1268) — gate requires 70%            |
| `koverVerify` (fails below 70%)       | **PASS**, and proven to fail when the bound is raised |
| `npm run test:ci` (headless Chrome)   | **471 passed**, 0 failures                            |
| Frontend line coverage                | **97.74%** (1779/1820) — gate requires 70%            |
| Karma coverage gate                   | **PASS**, and proven to fail when the bound is raised |
| `npm run build` (production bundle)   | **PASS**                                              |
| `npm run lint` (Prettier)             | **PASS**                                              |

Total: **1362 automated tests**, all passing.

### Coverage by layer

| Package                                                | Line coverage |
| ------------------------------------------------------ | ------------- |
| `hive.domain.model` / `policy` / `port` / `error`      | **100%**      |
| `hive.application.*` (service, support, usecase, view) | **100%**      |
| `hive.adapter.in.controller` / `mapper` / `bootstrap`  | **100%**      |
| `hive.adapter.in.security`                             | 99.2%         |
| `hive.adapter.in.error`                                | 98.6%         |
| `hive.adapter.out.persistence`                         | 98.8%         |

---

## 2. Architecture compliance

The hexagonal dependency rule is checked mechanically, not by inspection. All
clean:

| Check                                                                                | Result    |
| ------------------------------------------------------------------------------------ | --------- |
| No framework imports anywhere in `hive.domain` (Spring, Jakarta, Hibernate, Jackson) | **clean** |
| `hive.domain` imports no other Hive package                                          | **clean** |
| `hive.application` imports no adapter                                                | **clean** |
| `hive.adapter.out` imports neither `adapter.in` nor `application`                    | **clean** |
| No JPA annotation on any domain class                                                | **clean** |

The first is additionally enforced at build time by `NoFrameworkImportsTest`,
which also asserts that nothing but `HiveClock` calls `Instant.now()`, and the
last by `DomainHasNoJpaAnnotationsTest`. Convention is not relied upon.

## 3. Technology constraints

| Constraint (`spec.md`)                   | Status                                                                                                      |
| ---------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| Only Kotlin >= 2.4 and TypeScript >= 6.0 | **met** — Kotlin 2.4.20, TypeScript 6.0.2. No `.java`, `.groovy` or hand-written `.js` in production source |
| Spring Boot >= 4.0.5                     | **met** — 4.0.8                                                                                             |
| Angular >= 22.0.2                        | **met** — 22.1.x                                                                                            |
| Hexagonal package layout                 | **met** — see section 2                                                                                     |
| JUnit 5 + MockK                          | **met**                                                                                                     |
| Jasmine + Karma                          | **met** — Angular 22 defaults to Vitest; Karma was selected explicitly                                      |
| >= 70% line coverage                     | **met and enforced** — 99.68% / 97.74%                                                                      |
| MS SQL Server 2022                       | **met** — the migration has now run against SQL Server 2022; see 4.1 and section 7                          |
| Containerized, Podman                    | **met** — the stack builds and runs under Podman; see 4.2 and section 7                                     |
| OAuth-compatible login, JWT, HTTPS       | **met** — a Keycloak token from a real PKCE login is accepted; see 4.3 and section 7                        |

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

**Verified by exception:** `containers/scripts/generate-certs.sh` _was_ run. It
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

> **Closed on 2026-09-18.** `frontend/e2e/` now drives the built SPA through a
> real authorization-code + PKCE sign-in against the compose Keycloak. Section 8
> records what it proves and what it still does not. Tracked as `hive-nfv`.

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

| Claim                                  | Evidence                                                                                                                                                                                                                                                                                                                                                                                                 |
| -------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Both images build                      | `podman compose build` succeeds; the Gradle and npm dependency layers cache across source-only edits as intended                                                                                                                                                                                                                                                                                         |
| The stack comes up                     | `db`, `db-init`, `idp`, `backend`, `frontend` all reach their terminal state; four healthchecks report healthy                                                                                                                                                                                                                                                                                           |
| nginx accepts the configuration        | the frontend container serves the SPA, and both proxied upstreams answer                                                                                                                                                                                                                                                                                                                                 |
| The migration runs on real SQL Server  | Flyway reports `Successfully validated 1 migration` against `Microsoft SQL Server 16.0`, and `ddl-auto: validate` passes on the schema it produced — retiring most of 4.1                                                                                                                                                                                                                                |
| HTTPS end to end                       | browser to nginx, nginx to backend and nginx to Keycloak are all TLS, each verified against the development CA rather than skipped                                                                                                                                                                                                                                                                       |
| Plaintext redirects to the right place | asked from inside the container, `http://127.0.0.1:8080/projects/7?tab=tasks` answers `301` to `https://localhost:8444/projects/7?tab=tasks`, path and query intact; the frontend logs `hive: plaintext redirects to https://localhost:8444` at startup. `/healthz` still answers `200` over plaintext, by design, so probes need no certificate. The port is not published to the host — see 7.2 item 5 |
| A real Keycloak token is accepted      | the full authorization-code + PKCE flow was driven through the gateway as user `ada`; the resulting token carries `iss: https://localhost:8444/idp/realms/hive` and `GET /api/v1/projects/mine` with it returns `200 []`, where the same request without it returns `401` — retiring 4.3                                                                                                                 |

### 7.2 What it cost — seven bugs no parser could have found

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

    _Server side:_ the redirect was `return 301 https://$host$request_uri`, and
    `$host` is the Host header with the port **stripped** — so the answer was
    `Location: https://localhost/`, port 443, where nothing in this stack
    listens. The published port is a fact of the compose file rather than of the
    container, so it is now passed in as `HIVE_PUBLIC_ORIGIN` and written into
    the configuration at startup beside the resolver, and the redirect preserves
    path and query.

    _Browser side, and the reason the error said TLS:_ **HSTS is scoped to a
    host and has no concept of a port.** Once any response from
    `https://localhost:8444` has carried the header — in practice the identity
    provider's, for the reason in item 6 — `http://localhost:` is pinned on
    _every_ port, so the browser upgraded the request to HTTPS and spoke TLS to a
    listener that answers plaintext. Nothing was sent to port 8080 in cleartext
    and no redirect was ever requested — **no server-side change can reach that
    request.** The escape hatch is `chrome://net-internals/#hsts` → _Delete
    domain security policies_ → `localhost`; the standing advice is to use
    `https://localhost:8444`.

    The blast radius is wider than this project: that header pins `localhost`
    itself, so it breaks every unrelated plain-HTTP dev server on the machine,
    on any port, for two years. `hive-ild` asked whether publishing 8080 at all
    was worth the trap, and the answer was no: **the host mapping is gone.** The
    listener stays inside the container, where `/healthz` and the redirect serve
    probes that should not have to trust the development certificate, but
    nothing on the host offers a plaintext address that no browser can use. The
    header itself keeps its two-year `max-age` — it is the value a real
    deployment wants, and the trap was the published port, not the policy. The
    runbook carries the warning and the escape hatch.

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

    _The cause is a property of nginx, not a missing line:_ **`add_header` in a
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

    _Fixed structurally,_ the way item 4 was, rather than by pasting six
    directives into two more places: the set now lives in
    `containers/frontend/security-headers.conf` and is `include`d by the server
    block and again by each location that declares a header of its own, so
    adding one is no longer a way to silently lose the rest. `/healthz` sets its
    type with `default_type` and declares nothing. The asset location spells out
    `max-age=31536000` instead of leaving it to `expires 1y`, which would emit a
    second `Cache-Control` beside the `immutable` one. `/idp` keeps its
    deliberate override and now sends HSTS _once_ — Keycloak's own, shorter,
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
    declared once, in the obvious place, and the defect is in what a _different_
    directive fifty lines below does to them. It is only observable in a
    response. Tracked and closed as `hive-scn`.

7. **Sign-in was broken outright, and every earlier verification had missed
   it.** Clicking _Sign in with your organization account_ on the running stack
   produced `Sign-in failed — Invalid scopes: openid profile email
offline_access`. Not a degraded login: the authorization endpoint answered
   `302` back to the callback with `error=invalid_scope` **before drawing a
   login form**, so no credential was ever collected.

    _The cause is an unasked-for scope meeting a client that cannot grant it._
    The SPA requested `openid profile email offline_access`; the realm's
    `hive-web` client declared `defaultClientScopes` and **no**
    `optionalClientScopes`, so `offline_access` was not assignable to it. An
    issuer rejects the entire authorization request over one unknown scope
    rather than dropping it and continuing — the failure is total and arrives
    before authentication.

    Underneath it sat a wrong belief, written into a comment and _asserted by a
    test_: that `offline_access` is what produces the refresh token the silent
    refresh needs. It is not. The authorization-code flow issues a refresh token
    regardless — measured here at `refresh_expires_in: 1800` with
    `offline_access` absent — and `offline_access` only asks that the token
    outlive the SSO session. That is worthless to this application, whose token
    set lives in `sessionStorage` and dies with the tab, and it is a
    longer-lived credential in a public browser client for no gain. So the scope
    was dropped rather than granted, and `app-config.spec.ts` now asserts its
    **absence**, with the reason.

    _The realm file was wrong in two further ways, both invisible until
    queried._ It listed `openid` among `defaultClientScopes`, but Keycloak has
    no client scope by that name — `openid` is an OIDC request value — so the
    entry was silently discarded at import. And by naming the default list
    explicitly it lost `basic`, which is what puts `sub` in a token: the access
    tokens this realm minted **had no subject claim at all**. Nothing failed,
    because `CurrentUser` matches on `email` and falls back to it for the
    subject, so a non-conformant token was being tolerated by a fallback written
    for a different reason. The client now carries `basic` and `acr` among its
    defaults and the standard optional set, which is what Keycloak would have
    created had the list never been overridden.

    Re-verified by recreating the identity provider from the corrected file —
    `Import finished successfully`, and the live client's scope lists now read
    back as intended. A full authorization-code + PKCE run then yields a token
    carrying `sub` and `aud: hive-api`, a refresh token, and a `200` from
    `GET /api/v1/users/me`. Recreating the IdP also proved something the design
    claims: the realm's users are minted fresh with new subject UUIDs, and `ada`
    still resolves to the same Hive user, because `UserService` matches on email
    and the domain stores no subject.

    **This is the first bug found by using the application rather than testing
    it,** and it is the one 4.5 predicted in the abstract. The PKCE run recorded
    in 7.1 passed while sign-in was completely broken, because that run built
    its own authorization URL with its own scope string and therefore never
    exercised the value the bundle actually ships. A verification that
    reconstructs the request instead of making the application issue it proves
    the protocol and nothing about the product. Tracked and closed as
    `hive-m50`.

### 7.3 What remains unproven

- **4.5 stood, and item 7 is what it cost.** The PKCE flow above was driven
  with `curl`, which proves the protocol and the token, not the application's
  screens — and item 7 is a sign-in that was broken for everyone while that
  flow passed, found by a person clicking the button. Until a test drove the
  SPA's own request, every claim in this report about authentication was a
  claim about the protocol only. **Closed on 2026-09-18 — see section 8.**
- **SQL Server dialect differences.** The migration runs and the mappings
  validate, but the test suite still executes against H2; paging syntax,
  collation and `DATETIME2` rounding remain exercised only in compatibility
  mode. `docs/testing.md` section 4 gives the procedure for running the suite
  against the `dev` profile, which is now possible and has not been done.
- **Nothing here says anything about a deployed environment.** The certificates
  are from a CA that exists on one machine, `start-dev` is not a production
  Keycloak mode, and the database holds a single SA credential.

---

## 8. Addendum — 2026-09-18: the sign-in is now driven by a browser

`frontend/e2e/`, run with `npm run e2e` against the compose stack. Playwright
driving Chromium; three tests, all passing, 1.9s wall clock.

### 8.1 What it does

`sign-in.e2e.ts` loads `https://localhost:8444`, is redirected to the sign-in
screen by the auth guard, clicks the button the bundle renders, types a seeded
realm user's password into **Keycloak's own login form**, and asserts the
authenticated shell and a dashboard card render. Nothing in the flow is
reconstructed by the test: the authorization request asserted on — `scope`,
`client_id`, `redirect_uri`, `code_challenge_method`, `state`, `nonce` — is read
off the request Chromium actually sent, and the granted scopes are read off the
token endpoint's response to the bundle's own exchange.

That is the distinction 7.2 item 7 turned on. A verification that rebuilds the
request proves the protocol; this one proves the product, because the value
under test is the one the shipped bundle ships.

### 8.2 The assertion has been seen to fail

Two ways, both deliberately:

- A third test replays the bundle's own authorization request with one extra
  scope the realm does not grant this client. Keycloak refuses it and renders an
  error page, so the login form never appears — which is exactly the shape of
  `hive-m50`, and exactly what the acceptance criterion asks the suite to catch.
- The sign-in test was re-run with a wrong password and failed, confirming that
  the authenticated assertions are reached and load-bearing rather than
  vacuously satisfied.

### 8.3 What it still does not prove

- **It covers sign-in, not the application.** Three tests; the teams, projects
  and task screens are still exercised only at component level with mocked HTTP.
  A browser-driven pass over the task lifecycle is the obvious next piece.
- **It needs the stack.** `playwright.config.ts` has no `webServer` entry, by
  choice — a stack Playwright started would be one the test suite configured
  rather than the one that ships — so the suite is skipped-by-absence on any
  machine without a container runtime, which is how 4.5 came to stand for as
  long as it did. It is not part of `npm run test:ci`.
- **It accepts the development certificates.** `ignoreHTTPSErrors` is on, so
  nothing here is evidence about certificate trust.

---

## 9. Addendum — 2026-09-18: the header chrome, and the stylesheet that never loaded

`hive-0lu` was a styling bug found by a person looking at the screen: the Sign
out button rendered #1A1A1A on the #1A1A1A header — 1.00:1, present in the DOM
and clickable, invisible — and the user chip's name and email were barely
legible beside it. Fixing it produced a second finding that matters more than
the first.

### 9.1 What was wrong, and what replaced it

The header is painted with the inverse surface token while the controls sitting
on it read the _light_-surface colour roles. `HiveUserChip` shipped an
`--on-dark` class that nothing ever applied; `HiveButton` had no on-dark
affordance at all; `ShellLayout` set `hive-user-chip { color: ... }` on the host,
which does nothing because the chip's inner spans set their own colours.

The header now carries `.hive-surface-inverse` (`src/styles/_tokens.scss`
section 11), which re-points the text, surface, ghost-interaction-fill, border
and focus-ring roles at their on-ink values for the whole subtree. The chip
needs no variant, the ghost button inverts by inheritance, and a control added
to the header later cannot reintroduce this bug by omission. The dead chip class
and the inert `ShellLayout` rule are gone.

### 9.2 What is now proven

`frontend/e2e/header-contrast.e2e.ts`, four tests, run against the compose
stack. It reads **computed** colours out of Chromium and turns them into WCAG
2.x contrast ratios, which is the only place this class of bug is observable:
every rule involved was individually valid, and the failure existed only in what
the cascade resolved them to.

| Pair                             | Ratio       | Floor |
| -------------------------------- | ----------- | ----- |
| Sign out, resting                | **17.40:1** | 4.5:1 |
| Sign out, hover (#2B2B2B fill)   | **14.16:1** | 4.5:1 |
| Sign out, pressed (#3D3D3D fill) | **10.86:1** | 4.5:1 |
| Sign out, keyboard-focused       | **17.40:1** | 4.5:1 |
| Chip name                        | **17.40:1** | 4.5:1 |
| Chip email                       | **9.26:1**  | 4.5:1 |

The focused case also asserts a focus ring is drawn, and a fourth test asserts
the page body under the header is _not_ inverted — the fix must not trade one
invisible button for a whole invisible page.

Two unit tests carry the part a unit test can carry — that `HiveAppShell`
applies the class, and that `ShellLayout`'s chip and button are rendered inside
it — rather than leaving either to inspection. `npm run test:ci` is **473
passed**, from the 471 in section 1's snapshot.

### 9.3 The assertion has been seen to fail

A negative control forces ink text back onto the header — exactly the state
`hive-0lu` described — and requires the same helper to report a ratio below the
AA floor. Without it, "the ratio is >= 4.5" would be an assertion no one had
watched go red, which is precisely how the invisible button shipped.

### 9.4 What it cost: an eighth bug, of the same family as the seven

The fix was written, unit-tested, built and deployed to the stack — and the
header was still ink-on-ink. The new rule was in the served `styles-*.css`, the
header element matched it, and the token still computed to `#1A1A1A`.

The production build inlines critical CSS into `index.html` and defers the real
stylesheet with `<link rel="stylesheet" media="print" onload="this.media='all'">`.
The nginx CSP (`containers/frontend/security-headers.conf`) is `script-src
'self'` with no `'unsafe-inline'`, so that inline handler never ran, the link
stayed `media="print"`, and **every global rule the inliner did not judge
critical had never applied in the deployed stack at all** — utilities, form
styles, anything matching DOM that exists only after bootstrap. Filed as
`hive-3c6`; `inlineCritical` is now off, which is the right trade for an app
that ships one small stylesheet behind a CSP that correctly forbids inline
handlers.

This belongs in section 7's list in spirit: it is another bug that no parser,
no unit test and no `curl` could have found, because the artefact was correct
and the _environment_ discarded it. It was found by a test that measures the
running product, and it would have gone on hiding behind every future change to
`src/styles/`.

### 9.5 What it still does not prove

- **Two components, one context.** The ratios above cover the chip and the
  ghost button. Every other control is still asserted only by the numbers
  recorded in `src/styles/CONTRAST.md`, which are computed by hand rather than
  measured in a browser.
- **One viewport, one colour scheme.** The suite runs Desktop Chrome at its
  default size. The header's `max-width: 720px` branch, forced-colours mode and
  `prefers-reduced-motion` are not exercised.
- **It needs the stack**, with everything 8.3 says about that: not part of
  `npm run test:ci`, and skipped-by-absence wherever no container runtime exists.

---

## 10. Addendum — 2026-09-18: sign-out that the identity provider hears

`hive-bra` was a bug found the same way `hive-0lu` was — by a person clicking
the button. Sign out returned to `/login` and looked like it had worked. Signing
in again landed straight on the dashboard as the same user, with no password
prompt, so there was no way to leave the application at all.

### 10.1 What was wrong

`AuthService.logout()` cleared the local token set and navigated to `/login`.
Nothing in the repository referenced an end-session endpoint. Keycloak's SSO
cookie was untouched, so the next `/authorize` request was answered silently
from the surviving session — a sign-out that ended a tab's state and nothing
else.

### 10.2 What replaced it

RP-initiated OIDC logout, through the seam the other endpoints already use:
`OAuthConfig.endSessionEndpoint` with an issuer-relative default
(`resolveEndSessionEndpoint`), pinned in `environment.ts` to Keycloak's
`/protocol/openid-connect/logout` — which is the URL the realm's own discovery
document advertises, checked against it rather than assumed.

`logout()` captures the `id_token` first, drops all local state
**unconditionally**, and only then hands the browser to the provider with
`id_token_hint` and a `post_logout_redirect_uri` derived from the registered
`redirectUri`, so a provider that refuses the redirect still leaves nothing
usable behind. A dev session (`TokenSet.dev`) has no provider session and no
`id_token`, so it keeps the local path.

One quieter fix came with it: a refresh grant need not re-issue an `id_token`,
and the old code dropped it when the response omitted one. After a single silent
refresh, sign-out would have degraded back to local-only. The previous token's
`id_token` is now carried forward, and a unit test holds that.

The realm seed's `post.logout.redirect.uris` gained the `:4200` dev origin
alongside the `:8444` entry it already had, matching its `redirectUris`.

### 10.3 What is now proven

`npm run test:ci` is **477 passed**, from 473 in section 9. Three of the new
tests are about this: the end-session redirect and its parameters, the dev
session's local path, and the preserved `id_token`.

The e2e suite is 7 tests. `frontend/e2e/sign-in.e2e.ts` now drives the whole
round trip against the running stack — sign in as `ada`, sign out, assert the
token storage is empty, click Sign in again, and assert **Keycloak's own
`#username` form** is shown rather than the dashboard greeting.

### 10.4 The assertion has been seen to fail

The fix was stashed, the frontend image rebuilt from the reverted source and
redeployed, and that test was run: it failed on the silent re-authentication,
at the `#username` assertion, with the dashboard greeting present. Then the fix
was restored, the image rebuilt, and all 7 pass. The assertion has been watched
go red for the real reason, not a contrived one.

### 10.5 What it still does not prove

- **Back-channel logout is not implemented.** Ending the session from another
  tab or from Keycloak's account console will not clear this tab's token set;
  it will simply fail at the next refresh. No `backchannel_logout_uri` is
  registered.
- **The redirect is not verified against a hostile `post_logout_redirect_uri`.**
  The value is derived in code from the registered `redirectUri`, so nothing
  user-supplied reaches it, but there is no test that a crafted one is refused.
- **It needs the stack**, with everything 8.3 says about that.

---

## 11. Addendum — 2026-09-18: team and project controls are now server-computed

### 11.1 What was wrong

`TaskDetail` has always carried a `TaskPermissions` block, so every task control
is rendered from an answer the server computed with the same policy that
enforces it. `TeamDetail` and `ProjectSummary` carried no such block, so the two
management screens answered "may I do this?" for themselves, by comparing the
acting user's id with the `teamLead.id` / `projectOwner.id` in the response.

That was never an authorization hole — every one of those operations is checked
server-side, so the worst case was a control that appeared and then returned 403
— but it was a second place where "who may do what" was decided, and it could
only ever express one blanket role per screen.

### 11.2 What replaced it

`TeamPermissions` (`canRename`, `canAddMember`, `canRemoveMember`,
`canTransferLead`) and `ProjectPermissions` (`canRename`,
`canTransferOwnership`, `canCreateTask`) are published on `TeamDetail` and
`ProjectSummary`, and `docs/api-contract.md` section 1.2 was amended first.

They are computed in `ViewAssembler` by running the very `checkX` functions that
enforce TM-5 to TM-9, PR-5, PR-6 and TK-1 and reporting whether they passed —
the same `permitted { }` device `TaskPermissions` already used, so there is no
second copy of any rule. The actor is threaded through `teamView`/`projectView`,
which is why every call site gained an `actor` argument.

Two decisions are worth naming:

- `canRemoveMember` and `canTransferLead` need a target, and a flag has room for
  one answer, so each is asked about the first member who is not the lead. A
  lead alone on their team therefore gets `false` for both, which is exactly
  right: INV-1 makes them unremovable (TM-7) and there is nobody to hand the
  team to.
- `canTransferOwnership` reports PR-6 only. PR-8's 409 — the incoming owner
  still holds live tasks in this project — is a fact about the *candidate*, not
  about the actor, and discovering it here would mean a task query per project
  row for a flag that cannot express it. It stays with the request that names a
  candidate, and the UI already renders that 409 verbatim.

`TeamSummary` deliberately gained nothing: it is a list row, and the detail
endpoint is where the controls live.

On the frontend, `TeamDetailPage` and `ProjectDetailPage` gate every control on
its own flag, and each controls card appears only if at least one control inside
it does. `CurrentUser` is now used for wording alone — "You lead this team"
rather than "Led by Bob Ito" — and its doc comment says so.

### 11.3 What is now proven

`./gradlew clean build --rerun-tasks` passes. `npm run test:ci` is **480
passed**, from 477 in section 10. The new tests are:

- `TeamServiceTest` / `ProjectServiceTest`: the lead and the owner hold every
  control; a plain member and a TM-3 project-owner viewer hold none; a solo lead
  may rename and add but has nobody to remove or hand the team to; each row of a
  project list carries the caller's own permissions; the response to a transfer
  already reports the outgoing holder's lost controls; and `canTransferOwnership`
  costs no task query.
- `TeamControllerTest` / `ProjectControllerTest`: the JSON carries the block with
  the contract's field names, and `TeamSummary` still does not.
- `team-detail.spec.ts` / `project-detail.spec.ts`: with one flag true and the
  rest false, exactly one control renders — so the screens are gated per
  operation, not per role.

### 11.4 What it still does not prove

- **Nothing here is an enforcement point**, and the tests do not pretend
  otherwise: they assert what is rendered, while the server tests assert what is
  allowed. A client that ignores the block still gets a 403.
- **The flags are a snapshot.** A permission that changes in another session is
  seen on the next read; the screens re-read after a 409, which is when it
  matters most, but there is no push.
- **`npm run lint` reports two pre-existing formatting failures**
  (`src/app/core/auth/auth-service.ts`, `src/environments/environment.ts`) that
  are untouched by this work and were already failing before it.
