# Source Code Indexer MCP Server

## What This Project Is

A Java 21+ MCP server that maintains a PostgreSQL-backed index of source code repositories. It clones repos on first boot, installs git hooks for live change detection via a PostgreSQL event queue, and exposes a token-efficient query interface to Claude Code.

Designed for large financial institutions: pluggable enterprise auth, stateless cloud deployment, multi-instance scaling.

**What it's measurably good at (don't oversell it).** Controlled benchmarks vs a strong `grep`
baseline (Hazelcast, ~2M LOC) found the index's real, proven edge is **type-resolved reachability** —
"list every concrete type that is-a `X`, transitively" (all implementers / subtype tree), answered
via SCIP-backed `get_type_hierarchy` in **one query at recall 0.97 / F1 0.88**, vs iterative `grep`
at **0.32 / 0.32 over ~102 queries** (validated against an independent compiled-bytecode oracle; the
win is completeness, not precision). For *implementing features* and *general code discovery*, `grep`
ties or wins and is cheaper — so pitch CML as a **type-resolution oracle that complements `grep`**,
not "faster code search." Evidence: `docs/superpowers/results/` (3 writeups) + `bench/arenaA/`.

**Tech stack:** Java 21+, Gradle (Kotlin DSL), PostgreSQL 16, Tree-sitter structural parsing via tree-sitter-ng (Java, Python, TypeScript/JavaScript, Go, C), SCIP protobuf for type-resolved semantics, MCP protocol (stdio + Streamable HTTP)

**Design spec:** `docs/superpowers/specs/2026-05-25-source-code-indexer-mcp-design.md`

## Architecture

```
Claude Code <--MCP/stdio|HTTP--> MCP Server
                                   |
                        +-----------+-----------+
                        |                       |
                  Repository Manager      Indexing Pipeline
                        |                       |        \
                  Git Clones ---hooks--> Webhook --> PostgreSQL
                        |                          (index + event queue)
                  AuthProvider
                   (pluggable)
```

- **MCP Server:** Exposes 17 tools (symbol/code/file search, implementation & reference finders, repo/file/tree summaries, health, sync, SCIP type-hierarchy & references, branch diff/search, and audit-log query/verify). Both stdio and Streamable HTTP transports run simultaneously — stdio for local Claude Code subprocess, Streamable HTTP for remote connections.
- **Webhook Endpoint:** HTTP POST receiver for git hooks. Hosted on the same HTTP port (`/webhook`). Inserts events into PostgreSQL queue.
- **GitHub Webhook:** `POST /webhook/github/{repoName}` — receives GitHub push events, authenticates via per-repo `webhookSecret` config field (HMAC-SHA256, `X-Hub-Signature-256`). On a verified push to the repo's configured branch, enqueues an indexing event handled by the existing poller. **Tag pushes** matching `tags.pattern` (default `v*`) are also indexed, as immutable refs; the event carries a persisted `ref_kind` so the poller indexes a tag/SHA correctly rather than assuming a branch (name-collision-safe — a tag named like a branch is still recorded as a tag). Other-branch pushes, tag/branch deletions, and non-push events (e.g. `ping`) are accepted but ignored. Fail-closed: repos without a `webhookSecret` reject all deliveries.
- **Repository Manager:** Clones repos, resolves auth via pluggable AuthProvider, installs git hooks.
- **Indexing Pipeline:** Polls PostgreSQL event queue with `SKIP LOCKED`. Multi-instance safe.
- **AuthProvider:** Pluggable interface supporting SSH, tokens, OAuth2, mTLS, Kerberos, GCM, Vault, AWS/GCP secret managers.
- **PostgreSQL:** 9 tables — repositories, files, symbols, imports, type_relationships, file_contents, indexing_events, scip_symbols, scip_relationships.

## Prerequisites

- Java 21+
- Gradle 8+
- PostgreSQL 16 (local install or Docker)
- Git
- Docker & Docker Compose (for containerized deployment)

## How to Build

```bash
./gradlew build
```

## How to Run Locally

### 1. Start PostgreSQL

```bash
docker run -d --name indexer-pg \
  -e POSTGRES_DB=source_code_index \
  -e POSTGRES_USER=indexer \
  -e POSTGRES_PASSWORD=changeme \
  -p 5432:5432 \
  postgres:16
```

### 2. Create config file

```bash
mkdir -p ~/.source-code-indexer
cat > ~/.source-code-indexer/config.yaml << 'EOF'
server:
  cloneBaseDir: ~/.source-code-indexer/repos
  maxFileSizeBytes: 1048576
  indexWorkers: 4
  httpPort: 8080

database:
  host: localhost
  port: 5432
  name: source_code_index
  username: indexer
  password: ${DB_PASSWORD}

repositories:
  - url: git@github.com:your-org/your-repo.git
    branch: main
    auth:
      type: ssh-key
      keyPath: ~/.ssh/id_ed25519

admin:
  token: ${ADMIN_TOKEN}

languages:
  customExtensions: {}
EOF
```

### 3. Run the server

```bash
export DB_PASSWORD=changeme
export ADMIN_TOKEN=your-secret-token
./gradlew run
```

On first boot: clones all configured repos, runs Flyway migrations, performs full index, then starts the MCP server with both transports active simultaneously:
- **stdio** — used when Claude Code spawns the server as a subprocess (local mode)
- **Streamable HTTP** — available at `http://localhost:8080/mcp` for remote connections (MCP spec 2025-03-26)
- **Webhook** — available at `http://localhost:8080/webhook` for git hook events

All three are served from the single `httpPort` (default 8080). The HTTP server accepts request bodies up to **50 MB** (Javalin `maxRequestSize`), sized to cover large GitHub webhook payloads (GitHub caps webhooks at 25 MB) and SCIP uploads (≤50 MB); larger requests are rejected with 413.

### 4. Configure Claude Code to use this MCP server

**Local (stdio):**
```json
{
  "source-code-indexer": {
    "command": "/path/to/project/gradlew",
    "args": ["run"],
    "cwd": "/path/to/SourceCodeIndexerMCP",
    "env": {
      "DB_PASSWORD": "changeme"
    }
  }
}
```

**Remote (Streamable HTTP):**
```json
{
  "source-code-indexer": {
    "type": "http",
    "url": "http://your-server:8080/mcp"
  }
}
```

## How to Run with Docker Compose

```bash
docker compose up -d
```

Starts both indexer and PostgreSQL. Mount config and SSH keys as volumes.

## How to Test

```bash
# Unit tests
./gradlew test

# Integration tests (requires Docker for Testcontainers)
./gradlew integrationTest

# End-to-end tests
./gradlew e2eTest
```

## New Developer Onboarding — Getting Claude Code Context on a GitHub Project

### The Quick Way (with `connect-index` skill)

If the indexer is already running and the repo is already indexed:

```bash
git clone git@github.com:your-company/backend-api.git
cd backend-api
claude
> /connect-index
```

The skill detects your repo, verifies it's indexed, writes `.claude/mcp_servers.json`, and shows sample commands.

### The Manual Way (from scratch)

#### Step 1: Install prerequisites

```bash
# macOS
brew install openjdk@21 gradle postgresql@16 git docker
```

#### Step 2: Clone this project and build

```bash
git clone <this-repo-url>
cd SourceCodeIndexerMCP
./gradlew build
```

#### Step 3: Get your repo credentials ready

- **SSH:** Ensure key is in `~/.ssh/` and added to GitHub/GitLab
- **Token:** Create a personal access token with repo read access
- **Vault:** Ensure vault address and token are available
- **mTLS:** Ensure client cert and key are available

#### Step 4: Create your config

Edit `~/.source-code-indexer/config.yaml`:

```yaml
server:
  cloneBaseDir: ~/.source-code-indexer/repos
  maxFileSizeBytes: 1048576
  indexWorkers: 4
  httpPort: 8080

database:
  host: localhost
  port: 5432
  name: source_code_index
  username: indexer
  password: ${DB_PASSWORD}

repositories:
  - url: git@github.com:your-company/backend-api.git
    branch: main
    auth:
      type: ssh-key
      keyPath: ~/.ssh/id_ed25519
  - url: git@github.com:your-company/frontend-app.git
    branch: main
    auth:
      type: ssh-key
      keyPath: ~/.ssh/id_ed25519
  - url: https://github.com/your-company/data-pipeline.git
    branch: develop
    auth:
      type: token
      token: ${GITHUB_TOKEN}

admin:
  token: ${ADMIN_TOKEN}
```

#### Step 5: Start PostgreSQL and the indexer

```bash
# Option A: Docker Compose (easiest)
docker compose up -d

# Option B: Manual
docker run -d --name indexer-pg \
  -e POSTGRES_DB=source_code_index \
  -e POSTGRES_USER=indexer \
  -e POSTGRES_PASSWORD=changeme \
  -p 5432:5432 \
  postgres:16

export DB_PASSWORD=changeme
export GITHUB_TOKEN=ghp_your_token
./gradlew run
```

First boot clones all repos and runs full index. Watch logs for progress.

#### Step 6: Wire into Claude Code

Add to `.claude/mcp_servers.json` in your project:

```json
{
  "source-code-indexer": {
    "command": "/absolute/path/to/SourceCodeIndexerMCP/gradlew",
    "args": ["run"],
    "cwd": "/absolute/path/to/SourceCodeIndexerMCP",
    "env": {
      "DB_PASSWORD": "changeme",
      "GITHUB_TOKEN": "ghp_your_token"
    }
  }
}
```

#### Step 7: Verify

In Claude Code:
- "Use get_index_health to check the indexer" — confirms all repos indexed
- "Use get_repo_summary for backend-api" — see language breakdown
- "Use search_symbols to find all classes named Controller" — structural query
- "Use search_code for authentication" — full-text search

#### Step 8: Keep it running

Git hooks fire on every commit/merge/rebase. Events POST to the webhook and get processed automatically. If the server restarts, pending events in PostgreSQL are picked up immediately.

## MCP Tools Reference

| Tool | Purpose | Returns |
|------|---------|---------|
| `search_symbols` | Find symbols by name/kind/pattern | Signatures, locations (no source) |
| `get_symbol_detail` | Full detail + source for one symbol | Source code, children, relationships |
| `find_implementations` | Classes implementing an interface | Class names, locations |
| `find_references` | Files importing/referencing a symbol | File paths, import lines |
| `search_code` | Full-text search | Matching lines with 3-line context |
| `search_files` | Find files by path/name pattern | Paths, languages, sizes |
| `get_repo_summary` | Repository overview | File count, language breakdown |
| `get_file_summary` | File structure without content | Symbols, imports, index depth |
| `get_directory_tree` | Directory tree | Nested structure with types |
| `get_index_health` | System health check | Per-repo status, errors, queue state, SCIP staleness |
| `check_sync` | Compare local HEAD SHA with indexed SHA | Sync status, recommended action |
| `get_type_hierarchy` | Type hierarchy from SCIP data (ref-aware: resolves SCIP at the given branch/tag/SHA) | Supertypes, subtypes (recursive tree) |
| `get_symbol_references` | Symbol relationships from SCIP data (ref-aware: resolves SCIP at the given branch/tag/SHA) | Flat list of related symbols |
| `diff_branches` | Compare two branches/tags/SHAs | Files/symbols added, removed, changed |
| `search_branches` | Search symbols across many branches | Matches grouped by branch |
| `query_audit_log` | Query the tamper-evident audit log | Filtered audit events |
| `verify_audit_chain` | Verify audit-log hash-chain integrity | Validation result |

All tools except `get_index_health` accept an optional `branch` parameter. When omitted, queries default to the repo's configured branch (usually `main`). When on a feature branch, pass `branch` to get branch-aware results using the copy-on-write overlay. `get_type_hierarchy` and `get_symbol_references` also accept `branch` (any branch/tag/SHA); the param resolves SCIP at that ref's SHA, enabling type-resolved queries against any indexed release or commit.

## Branch Support

The indexer supports multiple branches per repository using a copy-on-write model:

- **Base branch:** Each repo's configured `branch` (e.g. `main`, `develop`) is the fully-indexed base (all files, symbols, imports, contents). The indexer keys the overlay off this configured branch — not the literal `main` — so repos whose default branch is `develop`/`master` index and query correctly. If the configured branch is blank, the base falls back to the remote default (`origin/HEAD`).
- **Feature branches:** Only files that differ from the base branch are stored. Queries merge branch-specific files with the base layer via an overlay.
- **Automatic indexing:** Feature branches are indexed automatically when pushed via webhook; tag pushes matching `tags.pattern` are pre-indexed on push (others fault in on first query)
- **TTL cleanup:** Access-based. Branch data expires after `ttlDays` inactivity (default 14); immutable refs (tags/SHAs) after `immutableRefTtlDays` (default 90)
- **Pinning:** Any indexed ref can be pinned via the Admin API (`POST /admin/repos/:name/refs/:ref/pin`) to exempt it from TTL cleanup; re-indexing a pinned ref preserves the pin
- **Fault-in:** If branch data is expired or missing, the first query triggers a synchronous re-index (1-2 seconds)

### Configuration

```yaml
branches:
  autoIndex: true           # Index branches automatically on push
  ttlDays: 14               # Branch index TTL — days of inactivity before cleanup (access-based)
  immutableRefTtlDays: 90   # Tag/SHA index TTL — immutable refs live longer (access-based)
  cleanupIntervalHours: 24  # How often the cleanup task runs

tags:
  autoIndex: true           # Pre-index tag pushes whose name matches the pattern
  pattern: "v*"             # Glob (only * and ? are wildcards); non-matching tags index lazily on first query

scip:
  pruneGraceDays: 7         # SCIP uploads within this window are kept even if the ref is no longer live
  uploadSessionTtlHours: 24 # Abandoned multi-part upload sessions are reaped after this many hours
```

### Any ref: branches, tags, and commit SHAs

The `branch` parameter on every branch-aware tool accepts **any git ref** — a
remote branch name, a tag (e.g. `v2.3.1`), or a commit SHA. On first query the
indexer resolves it (remote branch → tag → raw commit) and faults it in as a
copy-on-write overlay vs the repo's base branch, exactly like a feature branch. The ref kind
(branch / tag / SHA) is recorded in `branch_index.ref_kind`. This powers the
"debug a production release by tag or SHA, with no local checkout" workflow:
pass the release tag as `branch` to any query or to `diff_branches`.

Tags and SHAs are immutable, so once indexed they never need re-indexing. They are
retained on an access-based `immutableRefTtlDays` window (default 90, vs 14 for
branches), and any ref can be pinned via the Admin API to exempt it from cleanup
entirely.

## Admin API

REST endpoints under `/admin/*` for operational management. Requires bearer token authentication.

### Configuration

Add to your `config.yaml`:

```yaml
admin:
  token: ${ADMIN_TOKEN}
```

If no token is configured, all admin endpoints return 503.

### Authentication

All requests to `/admin/*` require:
```
Authorization: Bearer <your-admin-token>
```

### Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/admin/health` | System health stats |
| `GET` | `/admin/repos` | List all repos with file counts and status |
| `POST` | `/admin/repos` | Add a new repo (async clone + index) |
| `DELETE` | `/admin/repos/:name` | Purge repo (DB records + disk clone) |
| `POST` | `/admin/repos/:name/reindex` | Trigger full reindex (async) |
| `POST` | `/admin/repos/:name/refs/:ref/pin` | Pin a ref (tag/SHA/branch) — exempt from TTL cleanup |
| `DELETE` | `/admin/repos/:name/refs/:ref/pin` | Unpin a ref |
| `GET` | `/admin/events` | Query events (`?repo=&status=&since=&limit=50`) |
| `POST` | `/admin/events/:id/retry` | Retry a failed event |

### Adding a Repository

```bash
curl -X POST http://localhost:8080/admin/repos \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"git@github.com:org/repo.git","branch":"main","auth":{"type":"ssh-key","keyPath":"~/.ssh/id_ed25519"}}'
```

Returns 202 — clone and indexing run in the background.

## SCIP Upload API

REST endpoint for receiving SCIP (Source Code Intelligence Protocol) protobuf files from CI/CD pipelines. Provides type-resolved symbol definitions and relationships that complement Tree-sitter structural parsing.

### Configuration

API keys with `scipUpload: true` can upload SCIP data:

```yaml
auth:
  apiKeys:
    - key: ${CI_UPLOAD_KEY}
      id: ci-pipeline
      name: CI Pipeline
      scipUpload: true
      repos: ["*"]          # query scope — see "API key authorization" below
```

### API key authorization (per-repo scoping)

API keys are **scoped per key** for read/query access via the `repos:` field, mirroring the OAuth per-repo entitlement model (groups → allowed repos). This ensures the shared indexer never exposes a repo's code (source, symbols, file tree, search) to a remote caller who isn't entitled to it — regardless of auth method.

- `repos: ["*"]` — full read access to all indexed repos (explicit, auditable).
- `repos: [backend-api, data-pipeline]` — restricts the key to those repos.
- **`repos:` omitted → the key is denied all queries (fail-closed)** — every key must declare its scope. (Migration note: existing keys must add `repos:` or they will be denied.)

Authorization is enforced centrally in `QueryExecutor.executeQuery`, the single choke point for all repo-scoped MCP tools. Repo-less/system tools (`get_index_health`, `query_audit_log`, `verify_audit_chain`) require a `["*"]` key — same as OAuth callers, which cannot call them. The local **stdio** transport (a subprocess run as the OS user) is trusted and unscoped. **SCIP upload** (`POST /api/scip/{repoName}`) is a separate write path gated by the `scipUpload` flag and the repo name in the URL, not by `repos:`.

### Upload Endpoint

```
POST /api/scip/{repoName}
Authorization: Bearer <api-key-with-scipUpload>
X-Git-SHA: <commit-sha>
Content-Type: application/x-protobuf
Body: raw SCIP protobuf bytes
```

**Response (200):**
```json
{
  "repo": "payments-api",
  "sha": "abc123",
  "symbols": 1247,
  "relationships": 3891,
  "documents_processed": 156
}
```

**Error codes:** 401 (bad auth), 403 (no `scipUpload` permission), 404 (repo not found), 413 (>50MB), 400 (bad protobuf/missing SHA), 422 (no file overlap with indexed repo).

Uploads are retained **per `X-Git-SHA`** — not replace-all. Re-uploading the same SHA replaces only that SHA's data; different SHAs coexist, so a tagged release keeps its own type-resolution layer independent of main. CI uploads SCIP on both branch pushes and tag pushes (see `.github/workflows/scip-upload.yml`).

### Large uploads — multi-part session API

SCIP indexes larger than the 50 MB request cap (e.g. a 484 MB index for a large monorepo) are
uploaded in parts. The CLI wrapper (`scripts/scip-upload.sh`) does this automatically; the
underlying endpoints are:

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/scip/{repo}/uploads` | Open a session (`X-Git-SHA`, optional `X-Scip-Parts`). Returns `{uploadId, stagingKey}`. |
| `POST` | `/api/scip/{repo}/uploads/{id}/parts/{n}` | Upload one valid sub-index part (≤50 MB). Idempotent per part number. |
| `POST` | `/api/scip/{repo}/uploads/{id}/complete` | Atomically promote staged data to the SHA. |
| `DELETE`| `/api/scip/{repo}/uploads/{id}` | Abort a session, discarding staged data. |

Parts are produced by splitting the index at SCIP `Document` boundaries — each part is itself a
fully-valid SCIP `Index` (protobuf concatenation semantics), so the server parses each part with the
same code path as a single-shot upload and never holds the whole index in memory. Staged parts are
written under a synthetic `__staging__:<uploadId>` `upload_sha` and are invisible to queries until
`complete` atomically promotes them to the real SHA; an interrupted session leaves no visible data
and is garbage-collected after `scip.uploadSessionTtlHours` (default 24 h).

Splitting is done by the `scip-split` sub-command of the **runnable fat jar** (built by
`./gradlew shadowJar` → `build/libs/indexer.jar`; the plain `application` jar is not runnable via
`java -jar`):

    ./gradlew shadowJar
    java -jar build/libs/indexer.jar scip-split index.scip --max-bytes 47185920 --out parts/

A periodic retention policy (`ScipPruneTask`) keeps SCIP for: the current main SHA, all live indexed refs (branches/tags tracked in `branch_index`), and any upload within a configurable grace window (default 7 days, controlled by `scip.pruneGraceDays`). Older SHA rows are pruned automatically.

`get_index_health` reports SCIP staleness per repo (existence-based, checked against main's `last_indexed_sha` — this tool is repo-global and not ref-aware): `fresh` (SCIP exists for the main indexed SHA), `stale` (main has advanced past the SCIP SHAs on hand), or `unavailable` (no SCIP upload for this repo yet).

## SCIP CLI Wrapper

A portable Bash script for generating and uploading SCIP data from CI pipelines.

### Usage

```bash
# Auto-detect language and upload
./scripts/scip-upload.sh --server http://indexer:8080 --repo my-repo --api-key "$KEY"

# Upload a pre-built SCIP file
./scripts/scip-upload.sh --scip-file build/index.scip --server http://indexer:8080 --repo my-repo --api-key "$KEY"
```

Supports Java (`scip-java`), Python (`scip-python`), and TypeScript (`scip-typescript`) auto-detection. Use `--scip-file` for other languages.

**One invocation handles any size.** The script checks the SCIP file size and branches itself:
files at or under the part cap upload single-shot (`POST /api/scip/{repo}`); larger files are split
at `Document` boundaries and uploaded via the multi-part session API. CI never needs to know or
branch on the size — just always provide the splitter jar so the large path is available:

```bash
./scripts/scip-upload.sh --server http://indexer:8080 --repo my-repo --api-key "$KEY" \
    --scip-file build/index.scip --splitter-jar /opt/indexer/indexer.jar
```

Passing `--splitter-jar` is harmless for small files (the single-shot path never references it).
Flags: `--splitter-jar PATH` (or `SCIP_SPLITTER_JAR`) and `--max-part-bytes N` (or
`SCIP_MAX_PART_BYTES`, default 47185920 = 45 MiB). Build the jar with `./gradlew shadowJar`
(→ `build/libs/indexer.jar`), or download it as a pinned release asset.

See `docs/ci-pipeline-guide.md` for GitHub Actions, GitLab CI, and generic CI examples.

## Admin UI

React SPA dashboard served at `http://localhost:8080/admin/ui/`.

### Development

```bash
# Terminal 1: Java server
export DB_PASSWORD=changeme ADMIN_TOKEN=dev-token
./gradlew run

# Terminal 2: Vite dev server
cd admin-ui && npm run dev
# Opens at http://localhost:5173/admin/ui/
```

### Production

The UI is built into `admin-ui/dist/` and served by Javalin as static files. Docker builds it automatically.

```bash
cd admin-ui && npm ci && npm run build
```

### Features

- **Dashboard tab:** Health stats, repository status, recent failures
- **Repositories tab:** List, add, delete, trigger reindex
- **Events tab:** Filter by repo/status/time, retry failed events

## Supported Auth Types

| Type | Use case |
|------|----------|
| `ssh-key` | GitHub, GitLab, Bitbucket |
| `token` | PAT for any git host |
| `oauth2` | Enterprise SSO-integrated hosting |
| `client-cert` | mTLS (common in finance) |
| `kerberos` | Active Directory environments |
| `git-credential-manager` | OS-level credential store |
| `vault` | HashiCorp Vault |
| `aws-secrets-manager` | AWS Secrets Manager |
| `gcp-secret-manager` | GCP Secret Manager |

Custom providers: implement `AuthProvider` interface, drop JAR on classpath.

## Project Conventions

- Flyway for DB migrations (in `src/main/resources/db/migration/`)
- Tree-sitter (via tree-sitter-ng) is the primary symbol extraction engine for Java, Python, TypeScript/JavaScript, Go, and C (`.c`, `.h`); regex fallback is used for languages without `.scm` query files
- Tree-sitter query files (`.scm`) per language (in `src/main/resources/queries/`)
- Config supports `${ENV_VAR}` substitution for secrets
- Structured JSON logging
- Credentials never stored in database — only auth type and config references
- Admin API at `/admin/*` for future UI integration
- Event queue in PostgreSQL — no filesystem state beyond repo clones
