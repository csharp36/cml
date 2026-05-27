---
project_name: 'CML — Context Multiplier Layer'
user_name: 'Chris'
date: '2026-05-27'
sections_completed: ['technology_stack', 'language_rules', 'framework_rules', 'testing_rules', 'code_quality', 'workflow_rules', 'critical_rules']
status: 'complete'
rule_count: 42
optimized_for_llm: true
---

# Project Context for AI Agents

_This file contains critical rules and patterns that AI agents must follow when implementing code in this project. Focus on unobvious details that agents might otherwise miss._

---

## Technology Stack & Versions

**Backend (Java 21):**
- Gradle Kotlin DSL build system
- JDBI3 3.45.4 — lightweight SQL-first data access (NOT JPA/Hibernate)
- Javalin 6.4.0 — single HTTP server hosting webhook, SSE, admin API, and static files
- Flyway 10.21 — database migrations (V1 initial schema, V2 branch indexing)
- MCP Java SDK 0.10.0 — Model Context Protocol server
- Jackson 2.18.2 — JSON/YAML serialization
- HikariCP 6.2.1 — connection pooling
- Tree-sitter-ng 0.25.3 — native structural parsing (Java, Python, TypeScript, Go, C grammars)
- Logback 1.5.12, SLF4J 2.0.16 — logging
- PostgreSQL driver 42.7.4

**Frontend (React 19):**
- TypeScript 6 with verbatimModuleSyntax
- Vite 8 — build tool
- Tailwind CSS 4 via @tailwindcss/vite plugin (no postcss.config or tailwind.config needed)
- TanStack Query 5 — server state management
- Radix UI primitives (shadcn/ui copy-paste pattern, not a dependency)
- Lucide icons

**Testing:**
- JUnit Jupiter 5.11.3, Mockito 5.14.2, AssertJ 3.26.3
- Testcontainers 1.21.4 (PostgreSQL containers for integration tests)
- Vitest 4.1.7, @testing-library/react 16.3.2, @testing-library/jest-dom 6.9.1

**Infrastructure:**
- PostgreSQL 16 (8 tables, full-text search via tsvector)
- Docker multi-stage: node:20-alpine (UI build) + eclipse-temurin:21-jdk (Java build) + eclipse-temurin:21-jre (runtime)

## Critical Implementation Rules

### Language-Specific Rules

**Java:**
- All domain models are `record` types — never create mutable classes for data
- SourceFile record field order: `(id, repoId, branch, path, language, sizeBytes, lastCommitSha, lastModifiedAt)` — `branch` is the 3rd field
- JDBI uses named parameters (`:name` syntax), NOT positional (`?`)
- Use `withHandle` for reads, `useHandle` for writes — never mix
- SQL uses PostgreSQL dialect: `ON CONFLICT DO UPDATE` for upserts, `~*` for case-insensitive regex, `DISTINCT ON` for deduplication
- Multi-line SQL via triple-quoted text blocks (`"""..."""`)
- No ORM annotations — manual ResultSet mapping via `.map((rs, ctx) -> new Record(...))`

**TypeScript:**
- `verbatimModuleSyntax: true` — use `import type { X }` for type-only imports
- `noUnusedLocals: true` — all imports must be used or build fails
- Path alias `@/` maps to `./src/` — use for non-relative imports
- Test files excluded from `tsconfig.app.json` (Vitest globals, not tsc)

### Framework-Specific Rules

**MCP Server:**
- 11 tools registered in `McpServerBootstrap.buildServer()` via `.tool(schemaDef, handler)` chain
- Tool schema: `McpSchema.JsonSchema("object", propsMap, requiredList, false, null, null)`
- Use `LinkedHashMap` for property maps when >10 entries (`Map.of()` has a 10-entry limit)
- Handler signature: `McpSchema.CallToolResult handle*(Exchange, Map<String, Object> args)`
- Extract args via `stringArg()`/`intArg()` helpers, call `queryExecutor.*()`, return `jsonResult()` or `errorResult()`
- All 9 query tools (not `get_index_health`) MUST accept optional `branch` parameter
- All query methods MUST use `effectiveFilesCte(branch)` CTE for branch-aware overlay
- `ensureBranchIndexed(repo, branch)` MUST be called at top of each query method for synchronous fault-in

**Javalin HTTP:**
- Single server instance hosts ALL endpoints: `/webhook`, `/mcp` (SSE), `/admin/*` (REST), `/admin/ui/*` (static)
- Static files served via `Location.EXTERNAL` from `admin-ui/dist/`
- SPA fallback: `GET /admin/ui/*` redirects to `/admin/ui/`

**React / Admin UI:**
- `ApiContext` + `useApi()` hook for dependency injection — no prop drilling
- TanStack Query for all server state — `useQuery` with `refetchInterval` for polling
- Each tab component is self-contained with its own data fetching
- shadcn/ui components at `src/components/ui/` — copy-paste pattern, not an npm dependency
- Bearer token in React state only — never localStorage (security: re-enter on refresh)

### Testing Rules

**Backend (JUnit 5 + Testcontainers):**
- Integration tests use `@Testcontainers` + `@Container` with `PostgreSQLContainer<>("postgres:16")`
- Tag integration tests with `@Tag("integration")` — run via `./gradlew integrationTest`, not `./gradlew test`
- `@BeforeEach` must clean tables manually (`DELETE FROM symbols; DELETE FROM files;` etc.) — no auto-rollback
- Seed test data by constructing records and inserting via DAOs — no SQL fixture files
- Assert with AssertJ (`assertThat(...)`), never JUnit `assertEquals`
- `DatabaseManager` runs Flyway migrations on Testcontainers instance automatically

**Frontend (Vitest + Testing Library):**
- Vitest configured with `globals: true`, `environment: 'jsdom'`
- Setup file `src/test-setup.ts` imports `@testing-library/jest-dom`
- Mock `useApi` via `vi.mock('../App', () => ({ useApi: () => mockApi }))` in `test-utils.tsx`
- `renderWithProviders()` creates fresh `QueryClient` per test (`retry: false, gcTime: 0`)
- Use `waitFor()` for all async assertions — components fetch via TanStack Query
- Test files at `src/__tests__/*.test.tsx`, excluded from `tsconfig.app.json`

### Code Quality & Style Rules

**Java:**
- Package structure mirrors responsibility: `db/` DAOs, `model/` records, `mcp/` tools, `indexing/` pipeline, `repository/` git ops
- DAO naming: `*Dao.java` — thin SQL wrappers, not the Repository pattern
- One class per file, class name matches file name
- No Javadoc on DAO methods (SQL is self-documenting); Javadoc on public API methods in QueryExecutor and pipeline classes
- `LinkedHashMap` for building result maps (preserves insertion order for JSON output)
- Credentials never stored in database — only auth type and config references

**TypeScript:**
- Component files: PascalCase (`DashboardTab.tsx`, `AddRepoDialog.tsx`)
- Utility files: camelCase (`api.ts`, `utils.ts`)
- Tailwind utility classes only — no CSS modules or styled-components
- `cn()` helper from `src/lib/utils.ts` for conditional class merging

**Shared:**
- No comments on obvious code — only where logic is non-obvious
- No unused imports (enforced by both Java and TypeScript compilers)

### Development Workflow Rules

**Build Commands:**
- `./gradlew build` — compiles Java, runs unit tests, produces JAR
- `./gradlew integrationTest` — runs `@Tag("integration")` tests (requires Docker for Testcontainers)
- `cd admin-ui && npm run build` — produces `admin-ui/dist/`
- `cd admin-ui && npm run test` — runs Vitest suite

**Git Conventions:**
- Conventional commit messages: `feat:`, `fix:`, `test:`, `docs:`, `chore:`
- Atomic commits — one logical change per commit

**Server Startup:**
- Requires `DB_PASSWORD` and `ADMIN_TOKEN` env vars
- First boot: clones repos, runs Flyway migrations, full index, starts MCP (stdio + SSE)
- Single port (default 8080) serves everything

**Adding a New MCP Tool (checklist):**
1. Add query method to `QueryExecutor.java` (with `branch` param + `effectiveFilesCte` + `ensureBranchIndexed`)
2. Add `*Tool()` schema definition in `McpServerBootstrap.java`
3. Add `handle*()` handler method
4. Register via `.tool()` in `buildServer()`
5. Update tool count in log messages
6. Add integration test
7. Update CLAUDE.md tools reference table

### Critical Don't-Miss Rules

**Anti-Patterns:**
- NEVER query `files` directly without branch awareness — always use `effectiveFilesCte` or filter by `branch`
- NEVER use JPA/Hibernate annotations — this project uses raw JDBI3
- NEVER store auth credentials in the database — only auth type and config references (key paths, vault addresses)
- NEVER use `Map.of()` with >10 entries — use `LinkedHashMap` (Java limitation)
- NEVER read branch files from disk during branch indexing — use `git show <sha>:<path>` (working tree is on main)

**Edge Cases:**
- SHA comparison must be case-insensitive and handle abbreviated SHAs (7-char vs 40-char) — use prefix matching
- `branch` param null/blank always defaults to `"main"` via `resolveBranch()`
- `FileDao.upsert()` conflict key is `(repo_id, branch, path)` — not just `(repo_id, path)`
- Tree-sitter may fail on malformed files — `SymbolExtractor` falls back to regex, never throws

**Security:**
- Admin API token compared with `MessageDigest.isEqual()` (constant-time) — never `String.equals()`
- Admin API returns 503 (not 401) when no token configured — prevents token-guessing
- Bearer token in admin UI stored in React state only — never persisted to localStorage or cookies

**Performance:**
- Event queue uses `FOR UPDATE SKIP LOCKED` — safe for multi-instance deployment
- Branch index queries touch `last_accessed_at` on every access — resets TTL clock
- `effectiveFilesCte` for main branch skips `DISTINCT ON` (optimization — no overlay needed)

---

## Usage Guidelines

**For AI Agents:**
- Read this file before implementing any code
- Follow ALL rules exactly as documented
- When in doubt, prefer the more restrictive option
- New MCP tools MUST follow the 7-step checklist in Development Workflow Rules

**For Humans:**
- Keep this file lean and focused on agent needs
- Update when technology stack or patterns change
- Remove rules that become obvious over time

Last Updated: 2026-05-27
