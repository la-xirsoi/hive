# Hive Runbook

How to build, test, run and configure Hive.

Companions: [architecture.md](architecture.md) for the layering,
[testing.md](testing.md) for what the test suite does and does not prove,
[toolchain.md](toolchain.md) for pinned versions and environment gaps.

---

## 1. Prerequisites

| Tool | Version | Needed for |
|------|---------|-----------|
| JDK | 21 | backend build and run (`JAVA_HOME` must point at it) |
| Node | 24 | frontend build and test |
| Google Chrome | any recent | the headless Karma run |
| Podman | 4+ | containers only |
| openssl | 1.1+ | generating development certificates |

Gradle is **not** required — the repository ships a Gradle wrapper, and the
wrapper jar is committed precisely so a fresh clone can build with nothing else
installed.

---

## 2. Build and test

### Backend

```bash
cd backend
./gradlew build          # compile, test, coverage report
./gradlew test           # tests only
./gradlew koverHtmlReport # coverage at build/reports/kover/html/index.html
```

Scoped runs, useful while working in one layer:

```bash
./gradlew test --tests 'hive.domain.*'
./gradlew test --tests 'hive.application.*'
./gradlew test --tests 'hive.adapter.out.*'
```

The test profile uses an embedded database; **no SQL Server is needed to run the
test suite**. See [testing.md](testing.md) for what that substitution costs.

### Frontend

```bash
cd frontend
npm ci
npm run test:ci    # headless Chrome, with coverage
npm run build      # production bundle into dist/hive-web
npm run lint       # prettier --check
npm run format     # prettier --write
```

> On Windows, run the Karma suite from PowerShell rather than Git Bash. The
> Bash environment used during development is sandboxed away from
> `C:\Program Files`, so Chrome cannot be located from there. This affects only
> that sandbox, not developers or CI.

---

## 3. Running the full stack in containers

```bash
cd containers
cp .env.example .env          # then edit: set the three passwords
./scripts/generate-certs.sh   # writes certs/ (gitignored)
podman compose up -d
podman compose ps
podman compose logs -f backend
```

The whole stack answers on **one origin**, `https://localhost:8444`. nginx
serves the SPA and proxies the two upstreams, so the browser never sees a second
host — which is what keeps the OAuth issuer, the `iss` claim in the token and
the value the backend validates identical without anyone maintaining three
copies of a hostname.

| Service | URL | Notes |
|---------|-----|-------|
| Frontend | https://localhost:8444 | plain HTTP on 8080 redirects here |
| API | https://localhost:8444/api/v1 | proxied to the backend |
| Identity provider | https://localhost:8444/idp | realm `hive`; admin console at `/idp/admin` |
| Backend (direct) | https://localhost:8443 | published for debugging; the app does not use it |
| SQL Server | localhost:1433 | `sa` + `MSSQL_SA_PASSWORD` |

The identity provider has no published port of its own. Reaching it on a second
origin would mint tokens whose `iss` is that origin, and the backend would
reject every one of them.

Shut down with `podman compose down`, or `podman compose down -v` to discard the
database volume as well.

**Trust the development CA** before first use:

```bash
# Windows
certutil -addstore -user Root certs\hive-ca.crt
# macOS
sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain certs/hive-ca.crt
# Linux
sudo cp certs/hive-ca.crt /usr/local/share/ca-certificates/ && sudo update-ca-certificates
```

This matters more than usual: nginx sends HSTS with a two-year `max-age`, and
once a browser has seen that header for a host it will not accept a
click-through exception for it.

That header has a cost worth knowing before you load the app: HSTS is scoped to
the host and ignores the port, so it forces HTTPS on `http://localhost:<any
port>` in that browser — other projects of yours included. Section 6 has the
symptom and how to clear it.

> **Verified on 2026-09-15.** The stack builds and runs under Podman 5 on
> Windows 11, and a real authorization-code + PKCE login yields a token the
> backend accepts. What that first run cost, and what is still unproven, is in
> [verification.md](verification.md) section 7.

---

## 4. Running without containers

Useful for day-to-day development, and the only option on a machine with no
container runtime.

**Backend** — needs a reachable SQL Server; point it at one:

```bash
cd backend
export HIVE_DB_URL='jdbc:sqlserver://localhost:1433;databaseName=hive;encrypt=true;trustServerCertificate=true'
export HIVE_DB_USERNAME=sa
export HIVE_DB_PASSWORD='...'
./gradlew bootRun --args='--spring.profiles.active=dev'
```

**Frontend**:

```bash
cd frontend
npm start        # http://localhost:4200
```

### Signing in without an identity provider

The `dev` profile exposes `POST /api/v1/dev/token`, which mints a locally
signed JWT validated by exactly the same resource-server configuration that
validates real tokens — only the issuer differs, so the token path through the
application is identical in dev and prod.

```bash
curl -k -X POST https://localhost:8443/api/v1/dev/token \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@hive.example","name":"Ada Lovelace"}'
```

The Angular app calls this automatically when `environment.devAuth` is true.
The endpoint is registered under `@Profile("dev")` and **does not exist** in
`prod` — there is a test asserting its absence, because "we'll remember to turn
it off" is not a security control.

---

## 5. Configuration

Nothing secret is committed. Every credential is read from the environment.

| Variable | Used by | Notes |
|----------|---------|-------|
| `HIVE_DB_URL` | backend | JDBC URL; has a localhost default in `dev` only |
| `HIVE_DB_USERNAME` | backend | no default — startup fails fast if unset |
| `HIVE_DB_PASSWORD` | backend | no default, deliberately |
| `HIVE_DB_POOL_SIZE` | backend | Hikari pool size, default 20 in `prod` |
| `SPRING_PROFILES_ACTIVE` | backend | `dev`, `test` or `prod` |
| `SERVER_SSL_KEY_STORE` / `_PASSWORD` / `_TYPE` | backend | PKCS#12 keystore for HTTPS |
| `HIVE_JWT_ISSUER_URI` | backend | OAuth2 issuer; must exactly match the `iss` claim the IdP mints. Required in `prod`, defaults to a local value in `dev`. |
| `HIVE_JWT_JWK_SET_URI` | backend | Optional. Where to fetch signing keys when the issuer URL is not reachable from the backend itself — behind a gateway it is the browser's address, not one this process can resolve. Issuer validation stays strict either way. |
| `OAUTH_PUBLIC_ORIGIN` | compose | The origin you type in the browser. Settles the issuer for the SPA, Keycloak and the backend at once. |
| `MSSQL_SA_PASSWORD` | compose | must satisfy SQL Server's password policy or the container refuses to start |
| `KEYCLOAK_ADMIN` / `_PASSWORD` | compose | IdP bootstrap admin |
| `CERT_PASSWORD` | compose, cert script | protects the PKCS#12 keystore |

The profile matrix:

| Profile | Database | Schema | Dev token endpoint |
|---------|----------|--------|--------------------|
| `dev` | SQL Server | Flyway migrate | **present** |
| `test` | embedded, SQL Server compatibility mode | Flyway migrate, then `ddl-auto: validate` | absent |
| `prod` | SQL Server | Flyway migrate, `clean` disabled | **absent** |

---

## 6. Troubleshooting

**`ERR_SSL_PROTOCOL_ERROR` on http://localhost:8080.** The browser is speaking
TLS to the plaintext listener, because it upgraded the request before sending
it. HSTS is the cause and it is working as designed: the header this stack sends
from `https://localhost:8444` is scoped to the *host*, and HSTS has no concept
of a port, so it applies to `localhost` on **every** port — including ports
served by unrelated projects on your machine. Once a browser has seen it, no URL
of the form `http://localhost:<anything>` will leave that browser as plaintext
for two years.

Use **https://localhost:8444**. Port 8080 still exists for probes and for
clients without an HSTS store (`curl -i http://localhost:8080/` returns a 301 to
the HTTPS origin, port included), but it is no longer reachable from a browser
that has loaded this app, and no server-side change can make it reachable.

To undo it in Chrome: `chrome://net-internals/#hsts` → *Delete domain security
policies* → `localhost`. That is also worth knowing if this stack's HSTS header
starts interfering with some other `http://localhost` service you run. Removing
the CA (see above) does **not** clear HSTS; the two are independent.

**Every request returns 401.** Almost always the issuer. The `iss` claim in the
token must match `HIVE_JWT_ISSUER_URI` character for character. Note that the
claim is *compared*, never fetched, so it is the browser's address; the URL that
must be resolvable from inside the backend container is `HIVE_JWT_JWK_SET_URI`,
where the signing keys come from. Conflating the two is the classic version of
this bug — `localhost` inside that container is the backend itself, not your
machine.

If the keys are fetched over HTTPS from a certificate signed by the development
CA, the backend has to trust it: the container's entrypoint builds a truststore
from `/app/certs/hive-ca.crt` at startup and logs `entrypoint: trusting ...`. No
CA on the certs volume means a PKIX path error on the first token, which also
surfaces as a 401.

**Everything proxied returns 502 after restarting a single service.** nginx
pins an upstream's IP at startup, and a recreated container comes back on a new
one. The image writes a `resolver` from the container's own DNS at startup
(`hive: nginx resolver set to ...` in the frontend log) and proxies through
variables so names are re-resolved; if that line is missing from the log, the
resolver was not written and every upstream name is stale.

**The backend starts but the schema is empty.** Check that
`spring-boot-flyway` is on the classpath. Spring Boot 4 moved Flyway's
auto-configuration into its own module; with only `flyway-core`, every
`spring.flyway.*` property is ignored silently and no migration runs.

**Hibernate fails validation at startup.** An entity and the migration have
drifted. That is the check working: fix the mapping or add a migration, and
never switch `ddl-auto` to `update` to make it go away — a schema that mutates
to match the code is one nobody can reproduce.

**The SQL Server container exits immediately.** Its password policy rejected
`MSSQL_SA_PASSWORD`: at least 8 characters with three of {upper, lower, digit,
symbol}. The reason appears once in the container log and is easy to miss.

**Karma cannot find Chrome.** Set `CHROME_BIN` to the browser's path. The karma
config already probes the usual locations; an unusual install needs the
variable.

**A task edit returns 409 unexpectedly.** The task reached a terminal state
(`Completed` or `Canceled`), after which title, description, status and assignee
are all frozen. See [authorization.md](authorization.md) section 3.
