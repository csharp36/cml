# Contributing to cml (Source Code Indexer MCP)

Thanks for your interest in contributing! This document covers how to set up the
project, the quality bar, and how changes get merged.

## Prerequisites

- **Java 21+** and **Gradle 8+** (the wrapper `./gradlew` is provided)
- **Docker** — required for the integration/e2e test suites (Testcontainers spins up PostgreSQL)
- **Node 20+** — only needed if you touch the admin UI (`admin-ui/`)

## Build & test

```bash
# Compile, run the full suite, and assemble
./gradlew build

# Or run tiers individually
./gradlew test             # all tests the platform discovers
./gradlew integrationTest  # @Tag("integration") — needs Docker
./gradlew e2eTest          # @Tag("e2e") — needs Docker
```

The admin UI is a separate Vite/React app. The Javalin server serves its built
output from `admin-ui/dist`, so several HTTP tests require it to exist:

```bash
cd admin-ui && npm ci && npm run build
```

CI builds the admin UI automatically before running `./gradlew build`.

## Development workflow

We follow test-driven development: write a failing test, make it pass, then
refactor. Keep changes small and focused, with frequent, atomic commits.

1. **Branch** off `main` (`feature/...`, `fix/...`, `chore/...`, `ci/...`, `docs/...`).
2. **Open a pull request** against `main`. Direct pushes to `main` are disabled.
3. **CI must pass.** The `build` check (compile + full test suite) is required.
4. **Linear history.** `main` only accepts squash or rebase merges — no merge commits.
5. **Resolve all review conversations** before merging.

## Coding conventions

- Follow the patterns already in the surrounding code; match its naming and style.
- Database changes go through Flyway migrations in `src/main/resources/db/migration/`.
- Credentials are never stored in the database — only auth type and config references.
- Config supports `${ENV_VAR}` substitution for secrets; never commit real secrets.

See `CLAUDE.md` for a deeper tour of the architecture and conventions.

## Reporting bugs & requesting features

Open a GitHub issue with clear reproduction steps (for bugs) or a description of
the use case (for features). For **security issues**, do not open a public
issue — see [`SECURITY.md`](SECURITY.md).

## License

By contributing, you agree that your contributions will be licensed under the
project's [MIT License](LICENSE).
