# Pinned Toolchain

Versions were verified available on Maven Central / npm on 2026-09-13. Every
constraint in `spec.md` section "Technology Constraints" is satisfied.

## Backend

| Component | Version | Spec constraint | Notes |
|-----------|---------|-----------------|-------|
| Kotlin | 2.4.20 | >= 2.4 | JVM target 21 |
| Spring Boot | 4.0.8 | >= 4.0.5 | latest 4.0.x patch; 4.1.x deliberately avoided to stay on the line the spec names |
| JDK | 21 (Temurin/Oracle, `JAVA_HOME=C:\Program Files\Java\jdk-21`) | -- | only JDK on this machine |
| Gradle | 9.7.1 | -- | wrapper only; Gradle is **not** installed system-wide |
| JUnit | 5 | mandated | |
| MockK | latest | mandated | |
| Kotlinx Kover | latest compatible | -- | 70% line coverage gate |
| mssql-jdbc | latest | -- | MS SQL Server 2022 driver |
| Flyway | core + sqlserver | -- | schema migrations |

## Frontend

| Component | Version | Spec constraint | Notes |
|-----------|---------|-----------------|-------|
| Angular | 22.1.x | >= 22.0.2 | |
| TypeScript | 6.0.x | >= 6.0 | Angular 22's compiler-cli peer range is `>=6.0 <6.1`. **TypeScript 7 is published but incompatible with Angular 22** -- do not upgrade. |
| Node | 24.19.0 | -- | |
| Jasmine + Karma | latest | mandated | not Vitest, despite newer CLI defaults |
| Chrome | system install at `C:\Program Files\Google\Chrome\Application\chrome.exe` | -- | `CHROME_BIN` is set in the karma config so headless runs need no developer setup |

## Datastore

MS SQL Server 2022 (`mcr.microsoft.com/mssql/server:2022-latest`), per spec.

## Language restriction

`spec.md`: *"The ONLY allowed programming languages are Kotlin >= 2.4 and
TypeScript >= 6.0."* Configuration formats (Gradle Kotlin DSL, YAML, SQL, SCSS,
HTML, Containerfiles) are not programming languages and are used as normal.
There is **no Java, no Groovy, and no JavaScript** in production source; build
scripts are Kotlin DSL and test configuration that must be JS (karma.conf) is
confined to configuration only.

## Known environment gaps

These are limitations of the machine this project was built on, not of the
design. **All three were lifted on 2026-09-15**, when Podman was installed and
the compose stack — which brings its own SQL Server and Keycloak — was run for
the first time; see `docs/verification.md` section 7. They are kept here because
they explain why parts of this codebase are shaped the way they are:

1. **No container runtime.** Podman is not installed and neither is Docker.
   Containerfiles and the compose stack are authored and syntax-reviewed but
   have **not been built or run**. See `docs/verification.md`.
2. **No SQL Server instance.** Consequently, integration tests run against an
   embedded database in SQL Server compatibility mode. Flyway migrations are
   authored for real SQL Server 2022. See `docs/testing.md`.
3. **No external identity provider.** A dev-profile local JWT issuer exists so
   the stack is demonstrable; the default configuration expects a real external
   OAuth2 issuer and the dev issuer is inert outside the `dev` profile.
