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

| Service | URL | Notes |
|---------|-----|-------|
| Frontend | https://localhost:8444 | plain HTTP on 8080 redirects here |
| Backend | https://localhost:8443 | |
| Identity provider | https://localhost:8543 | Keycloak admin console |
| SQL Server | localhost:1433 | `sa` + `MSSQL_SA_PASSWORD` |

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

> **This stack has never been executed.** Podman was not available on the
> machine where Hive was built. The compose file, both Containerfiles and the
> nginx configuration are authored and reviewed but unverified; the certificate
> script *was* run and its output verified. See
> [verification.md](verification.md).

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

**Every request returns 401.** Almost always the issuer. The `iss` claim in the
token must match `HIVE_JWT_ISSUER_URI` character for character, and the URI must
be resolvable *from inside the backend container* — `localhost` there is the
backend itself, not your machine.

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
