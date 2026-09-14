# Hive Architecture

Hive is a Kotlin/Spring Boot API with an Angular single-page frontend, built to
the Hexagonal (Ports and Adapters) pattern mandated by `spec.md`.

```
                    +-------------------------------+
                    |         Adapter In            |
   HTTP / JWT  -->  |  REST controllers, DTOs,      |
                    |  error handler, security      |
                    +---------------+---------------+
                                    | inbound ports (use case interfaces)
                    +---------------v---------------+
                    |         Application           |
                    |  services orchestrating the   |
                    |  domain; transaction boundary |
                    +---------------+---------------+
                                    | outbound ports (repository interfaces)
                    +---------------v---------------+
                    |           Domain              |
                    |  entities, value objects,     |
                    |  transition machine, policy   |
                    +---------------+---------------+
                                    ^ implemented by
                    +---------------+---------------+
                    |         Adapter Out           |
   SQL Server  <--  |  JPA entities, Spring Data,   |
                    |  mappers, Flyway migrations   |
                    +-------------------------------+
```

## The dependency rule

Dependencies point **inward only**.

| Package | May depend on | Must never depend on |
|---------|---------------|----------------------|
| `hive.domain` | Kotlin stdlib, `java.time` | anything else at all -- no Spring, no Jakarta, no Hibernate, no other Hive package |
| `hive.application` | `hive.domain`, Spring annotations for wiring only | either adapter package |
| `hive.adapter.in` | `hive.application`, `hive.domain` (for exception types) | `hive.adapter.out` |
| `hive.adapter.out` | `hive.domain` (to implement its ports) | `hive.adapter.in`, `hive.application` |

This is verified mechanically in the final verification pass, not merely by
convention.

> `hive.adapter.in` is a legal Kotlin package despite `in` being a keyword: the
> directory is plain `in` and source declarations escape it with backticks --
> ``package hive.adapter.`in`.controller``. Imports need the same escaping.
> Spring component scanning into the escaped package is proven by test.

## Why the domain is framework-free

The rules in `docs/authorization.md` are the product. Keeping them in a package
with no framework imports means they can be exercised by fast, deterministic
unit tests with no Spring context, no database, and no HTTP -- which is what
makes exhaustive table-driven testing of the transition machine practical. It
also means a rule change never requires reasoning about transaction proxies or
lazy-loading.

## Layer responsibilities

**Domain** owns every rule. The transition machine decides what status changes
are possible; `AuthorizationPolicy` decides who may do what. Both are pure
functions over already-loaded aggregates. Entities are immutable; operations
return new instances.

**Application** loads the aggregates the policy needs, asks it, then performs
the mutation and saves. It contains orchestration and transaction boundaries but
**no rules** -- if you find yourself writing an `if` about roles or statuses in a
service, it belongs in the domain instead. The acting user arrives as an explicit
parameter; the application layer never reaches into a security context.

**Adapter In** translates HTTP to use case calls and domain exceptions to status
codes. Controllers are thin: parse, delegate, map. The global exception handler
is the single place where the status-code table in `spec.md` is realized.

**Adapter Out** implements the domain's repository ports against SQL Server. JPA
entities are a separate set of classes from domain entities, with explicit
mappers between them. Role-scoped queries are pushed into SQL rather than
filtered in memory, so that visibility rules stay correct and cheap as the data
grows.

## Three consequences worth knowing

1. **Status codes are decided in one place.** The domain distinguishes
   "impossible" (`ConflictException`, 409) from "not yours" (`AuthorizationException`,
   403) from "you cannot see it" (`NotFoundException`, 404), and the exception
   handler maps them one-to-one. Controllers never choose a status code.

2. **Visibility is a query concern, not a filter.** `findVisibleInProject` takes
   the viewer and returns only what they may see. Fetching then filtering would
   be both slow and a place for a leak to hide.

3. **The frontend receives its permissions from the server.** `TaskDetail`
   carries a `permissions` object computed by the same policy that enforces the
   rules, so the UI shows exactly the controls the user can actually use. The UI
   still cannot be trusted -- every request is independently authorized -- but
   the user never sees a button that will 403.

## Frontend structure

```
src/app/
  core/        auth (OAuth PKCE, JWT interceptor, guards), typed API clients
  shared/      design system: tokens, UI primitives, layout shell
  features/    dashboard, teams, projects, tasks -- lazily routed
```

`core` is imported by everything and imports nothing from `features`. The design
system in `shared` is presentational only: no HTTP, no routing decisions.
