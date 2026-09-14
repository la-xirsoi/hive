# hive

This repository is used to store and track the results of tests against coding agents. Using a standard, static(ish), design document different tools and techniques can be tested and measured.


## Contributing

To contribute there are a few guidelines.

1. When you conduct an experiment, create a new branch specifically for that experiment. Do this from the main branch, even if it's a variant of another experiment.
2. Update the EXPERIMENT.md document to describe the purpose and criteria for the experiment. This should include any additional setup (e.g. installing other software, adding SKILLs or AGENTS, etc.)
3. Execute the experiment!
4. To the best of your ability, save the conversation you have with the the AI (if any), and include it as an artifact.
  * It is not required, though it is encouraged, to record any other useful information in a RESULTS.md file. Useful information might include tokens used, time required, or any other observations you might make.
5. Open a pull request to add your branch to the repo. It will NOT be merged to main, as that is our pristine starting condition.


---

## The Hive Application

This branch contains an implementation of the task-tracking application
described in [spec.md](spec.md).

### Documentation

| Document | Contents |
|----------|----------|
| [spec.md](spec.md) | The original, authoritative requirements |
| [docs/authorization.md](docs/authorization.md) | Normative who-may-do-what model, including every interpretation made where the spec was silent |
| [docs/api-contract.md](docs/api-contract.md) | Frozen REST contract shared by backend and frontend |
| [docs/toolchain.md](docs/toolchain.md) | Pinned versions and known environment gaps |
| [docs/architecture.md](docs/architecture.md) | Hexagonal layering as implemented |
| [docs/testing.md](docs/testing.md) | Test strategy and the embedded-database substitution |
| [docs/runbook.md](docs/runbook.md) | Running the stack locally and in containers |

### Layout

```
backend/    Kotlin + Spring Boot API, hexagonal (domain / application / adapter)
frontend/   Angular single-page application
containers/ Podman Containerfiles and the compose stack
docs/       The documents listed above
```

### Prerequisites

- JDK 21 (`JAVA_HOME` must point at it)
- Node 24 and npm 11
- Google Chrome (for the headless Karma test run)
- Podman (only needed to build and run containers)

Gradle itself is not required: the repository ships a Gradle wrapper.

### Build and test

```bash
# Backend: compile, test, coverage verification
cd backend && ./gradlew build

# Frontend: install once, then test headless and build
cd frontend && npm ci
npm run test:ci
npm run build
```

See [docs/runbook.md](docs/runbook.md) for running the full containerized stack.
