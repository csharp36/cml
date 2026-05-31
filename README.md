# CML — Context Multiplier Layer

An MCP server that indexes source code repositories into PostgreSQL, providing structural code intelligence to any LLM via the [Model Context Protocol](https://modelcontextprotocol.io).

CML parses your codebase with [Tree-sitter](https://tree-sitter.github.io/tree-sitter/) for structural symbols and ingests [SCIP](https://github.com/sourcegraph/scip) for type-resolved semantics, then exposes them through 17 token-efficient query tools. Any MCP-compatible AI client (Claude Code, Codex, Cursor, etc.) can search symbols, trace implementations and type hierarchies, full-text search code, diff branches, and check sync status — across multiple repos and branches.

```
AI Client <--MCP/stdio|HTTP--> CML Server
                                  |
                       +----------+----------+
                       |                     |
                 Repository Manager    Indexing Pipeline
                       |                     |        \
                 Git Clones ---hooks--> Webhook --> PostgreSQL
                       |                         (index + event queue)
                 AuthProvider                    SCIP upload (CI/CD)
                  (pluggable)
```

## Features

- **17 MCP tools** — symbol/code/file search, implementation & reference finders, repo/file summaries, directory trees, health checks, sync detection, type hierarchies, symbol relationships, branch diff/search, and audit-log queries
- **Branch-aware indexing** — copy-on-write model: main is fully indexed, feature branches store only their delta. Auto-indexed on push, TTL cleanup, synchronous fault-in on first query
- **Tree-sitter parsing** — structural extraction for Java, Python, TypeScript/JavaScript, Go, and C. Regex fallback for other languages
- **SCIP semantic layer** — CI/CD pipelines upload [SCIP](https://github.com/sourcegraph/scip) protobuf for type-resolved symbols and relationships, powering `get_type_hierarchy` and `get_symbol_references`. Per-repo freshness tracking (`fresh`/`stale`/`unavailable`)
- **Pluggable auth** — SSH keys, tokens, Git Credential Manager out of the box. Interface supports Vault, mTLS, Kerberos, OAuth2, AWS/GCP secret managers
- **MCP endpoint security** — API keys (with scoped permissions: read-only, SCIP upload, audit reader) and OAuth2/JWT bearer-token validation
- **Tamper-evident audit log** — hash-chained record of every query, queryable via MCP and verifiable for integrity
- **Dual MCP transport** — stdio (local subprocess) and Streamable HTTP (remote connections) run simultaneously
- **Admin API + UI** — REST endpoints + React dashboard for repo management, event monitoring, and health checks
- **Enterprise-ready** — stateless design, multi-instance safe (SKIP LOCKED event queue), PostgreSQL-backed

## When to Use CML (and When Not)

CML is **not** a replacement for `git`, `grep`/`ripgrep`, or your IDE. It's a code-intelligence
layer optimized for one consumer — an LLM — and measured in tokens, latency, and retrieval
quality rather than human ergonomics. Be clear-eyed about where it helps:

**CML is the better tool when:**

- **The code isn't checked out locally — or spans many repos.** Queries hit CML's *server-side*
  clones, so an agent on a laptop, a CI runner, or a web client can search and navigate code it
  has never cloned. `git`/`grep` require a local clone of every repo first. One server-side clone
  is shared by every client.
- **You need type-resolved semantics.** `find_implementations`, `get_type_hierarchy`, and
  `get_symbol_references` answer "who implements this interface / what's the subtype tree / who
  references this symbol" from SCIP data. `grep` finds *text* — it can't resolve types or follow
  `implements`/`extends` edges without running a compiler or LSP yourself.
- **Token economy matters.** Tools return *shaped* results — signatures + locations, file
  structure without bodies — instead of raw file dumps or `grep -A/-B` context. For an LLM
  navigating a large codebase, that's the difference between answering a question and blowing the
  context window.
- **You're comparing two builds you don't have on disk** (see the stack-trace workflow below).

**Plain `git` / `ripgrep` / your IDE is better when:**

- **You already have the repo checked out and it's a single repo.** `rg "pattern"` is faster and
  cheaper than a network round-trip, and `git diff a..b` is exact and battle-tested.
- **You want an authoritative file-level diff.** `git diff` is the source of truth; CML's *symbol*
  diff is a convenience layered on top, and only as good as its parser.
- **You need full file content, `blame`, or history.** That's git's job — CML stores an index, not
  your version control.

> **Rule of thumb:** reach for CML when the alternative is *"clone it first"* or *"read 20 files
> into the model to answer a semantic question."* For a repo already in front of you, use your
> normal tools.

### Flagship example: debugging a prod regression from a stack trace

Build `001` ran clean in production. You deploy `002` and start seeing `NullPointerException`s.
Paste the stack trace to your AI client connected to CML:

```
java.lang.NullPointerException: Cannot invoke "Customer.getId()"
        because the return value of "Order.getCustomer()" is null
    at com.acme.payments.PaymentValidator.validate(PaymentValidator.java:9)
    at com.acme.payments.PaymentService.process(PaymentService.java:34)
```

The agent — **without cloning either build** — can:

1. **Parse the trace** to the top application frame: `PaymentValidator.validate`.
2. **`diff_branches(002, 001)`** to see what changed between the deployed builds, and intersect the
   result with the trace's frames. If `validate` shows up as **added** or **modified**, the failing
   frame is new/changed code — the regression is in *this* deploy, not pre-existing logic.
3. **`get_symbol_detail` / `find_references`** on the suspect symbol to read it and reason about the
   null.

Why CML beats `git diff` here: it needs **neither build checked out on your machine** (only indexed
once, server-side), it **narrows** the diff to symbols on the failing path instead of dumping a full
tree into the model, and the comparison is semantic, not textual.

**Caveats (be honest):**
- Both builds must be indexed as refs CML knows. CML indexes **any git ref** — a branch, a release
  **tag**, or a raw **commit SHA** — so you can pass `001`/`002` as tags or SHAs and CML faults them
  in on first query (no local checkout). Release tags matching `tags.pattern` are pre-indexed
  automatically on push.
- Full type-resolved precision needs **SCIP uploaded per build SHA** (see [Semantic Indexing](#semantic-indexing-scip)).
- The symbol-level `diff_branches` is only as good as its parser; reach for `git diff` when you want
  the authoritative file-level answer.

## Quick Start

### Prerequisites

- Java 21+
- Docker (for PostgreSQL)
- Git

### 1. Start PostgreSQL

```bash
docker run -d --name cml-pg \
  -e POSTGRES_DB=source_code_index \
  -e POSTGRES_USER=indexer \
  -e POSTGRES_PASSWORD=changeme \
  -p 5432:5432 \
  postgres:16
```

### 2. Create config

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

# API keys for the remote MCP/HTTP endpoint and CI SCIP uploads.
# A key with no extra flags is read-only (query tools only).
auth:
  apiKeys:
    - key: ${MCP_API_KEY}
      id: readonly
      name: Read-Only Access
    - key: ${CI_UPLOAD_KEY}
      id: ci-pipeline
      name: CI Pipeline
      scipUpload: true

branches:
  autoIndex: true
  ttlDays: 14               # branch index TTL (access-based)
  immutableRefTtlDays: 90   # tag/SHA index TTL (immutable refs live longer)
  cleanupIntervalHours: 24

tags:
  autoIndex: true           # pre-index tag pushes matching the pattern
  pattern: "v*"             # non-matching tags index lazily on first query
EOF
```

### 3. Build and run

```bash
git clone https://github.com/csharp36/cml.git
cd cml
export DB_PASSWORD=changeme ADMIN_TOKEN=your-secret
export MCP_API_KEY=local-readonly-key CI_UPLOAD_KEY=local-ci-key
./gradlew run
```

On first boot: clones configured repos, runs migrations, performs full index, starts MCP server (stdio + Streamable HTTP) and admin UI.

### 4. Connect your AI client

**Claude Code (local/stdio):**
```json
{
  "cml": {
    "command": "/path/to/cml/gradlew",
    "args": ["run"],
    "cwd": "/path/to/cml",
    "env": { "DB_PASSWORD": "changeme" }
  }
}
```

**Any MCP client (remote/Streamable HTTP):**
```json
{
  "cml": {
    "type": "http",
    "url": "http://localhost:8080/mcp",
    "headers": {
      "Authorization": "Bearer ${MCP_API_KEY}"
    }
  }
}
```

The `/mcp` endpoint requires authentication — pass an API key (see `auth.apiKeys` in
your config) or an OAuth2 bearer token. A read-only key is enough for the query tools.

### 5. Query your code

```
"Search for all classes named Controller"     → search_symbols
"Show me the AuthService class"               → get_symbol_detail
"What implements the Repository interface?"   → find_implementations
"Search for authentication logic"             → search_code
"Is my local repo in sync with the index?"    → check_sync
```

## Docker Compose

```bash
docker compose up -d
```

Starts both CML and PostgreSQL. Mount your config and SSH keys as volumes.

## MCP Tools

| Tool | Purpose | Returns |
|------|---------|---------|
| `search_symbols` | Find symbols by name/kind/pattern | Signatures, locations |
| `get_symbol_detail` | Full detail + source for one symbol | Source code, children, relationships |
| `find_implementations` | Classes implementing an interface | Class names, locations |
| `find_references` | Files importing/referencing a symbol | File paths, import lines |
| `search_code` | Full-text search | Matching lines with context |
| `search_files` | Find files by path/name pattern | Paths, languages, sizes |
| `get_repo_summary` | Repository overview | File count, language breakdown |
| `get_file_summary` | File structure without content | Symbols, imports |
| `get_directory_tree` | Directory listing | Nested structure with types |
| `get_index_health` | System health check | Per-repo status, queue state, SCIP staleness |
| `check_sync` | Compare local HEAD with index | Sync status, recommended action |
| `get_type_hierarchy` | Type hierarchy from SCIP data | Supertypes/subtypes (recursive tree) |
| `get_symbol_references` | Symbol relationships from SCIP data | Flat list of related symbols |
| `diff_branches` | Compare two branches | Files/symbols added, removed, changed |
| `search_branches` | Search symbols across many branches | Matches grouped by branch |
| `query_audit_log` | Query the tamper-evident audit log | Filtered audit events |
| `verify_audit_chain` | Verify audit-log integrity | Hash-chain validation result |

Tools that operate on indexed code accept an optional `branch` parameter for branch-aware
queries. The SCIP (`get_type_hierarchy`, `get_symbol_references`), health, and audit tools
operate at the repo level and don't take a `branch`.

## Branch Support

CML uses a copy-on-write model for branch indexing:

- **Base branch** — each repo's configured `branch` (e.g. `main`, `develop`) is the fully-indexed base (all files, symbols, imports, contents); the overlay keys off this configured branch, not the literal `main`, so non-`main` defaults work (falls back to `origin/HEAD` if the configured branch is blank)
- **Feature branches** — only files that differ from the base branch are stored
- **Any git ref** — the `branch` parameter accepts a branch, a **tag**, or a **commit SHA**. CML resolves it (branch → tag → SHA) and faults it in as a copy-on-write overlay vs the base branch on first query — ideal for debugging a release by tag/SHA with no local checkout
- **Automatic** — feature branches index on push via git hooks; tag pushes matching `tags.pattern` are pre-indexed on push (others index lazily on first query)
- **TTL cleanup** — access-based: branches expire after `ttlDays` (default 14); immutable refs (tags/SHAs) after `immutableRefTtlDays` (default 90). Any ref can be **pinned** to exempt it from cleanup (see [Admin API](#admin-api))
- **Fault-in** — expired or never-indexed refs are re-indexed synchronously on first query (1-2 seconds)

When querying a feature branch, tag, or SHA, pass `branch: "<ref>"` to any tool. CML merges the ref's delta with main transparently.

## Semantic Indexing (SCIP)

Tree-sitter gives fast structural symbols; [SCIP](https://github.com/sourcegraph/scip) adds
type-resolved semantics (precise supertype/subtype and cross-symbol relationships). CI/CD
pipelines generate a SCIP index and upload it:

```
POST /api/scip/{repoName}
Authorization: Bearer <api-key-with-scipUpload>
X-Git-SHA: <commit-sha>
Content-Type: application/x-protobuf
Body: raw SCIP protobuf bytes
```

Each upload replaces the repo's SCIP data and powers the `get_type_hierarchy` and
`get_symbol_references` tools. `get_index_health` reports per-repo SCIP freshness:
`fresh` (matches indexed SHA), `stale` (behind), or `unavailable` (never uploaded).

A portable wrapper script auto-detects the language and uploads:

```bash
./scripts/scip-upload.sh --server http://localhost:8080 --repo my-repo --api-key "$KEY"
```

See [`docs/ci-pipeline-guide.md`](docs/ci-pipeline-guide.md) for GitHub Actions, GitLab CI, and generic examples.

## GitHub Webhook (live main-branch sync)

Keep a repo's index in sync automatically when changes merge to its configured branch. Add a per-repo signing secret to config:

```yaml
repositories:
  - url: git@github.com:your-org/your-repo.git
    branch: main
    auth:
      type: ssh-key
      keyPath: ~/.ssh/id_ed25519
    webhookSecret: ${REPO_WEBHOOK_SECRET}   # HMAC-SHA256 shared secret
```

Then in the repo on GitHub: **Settings → Webhooks → Add webhook**
- **Payload URL:** `https://<cml-host>/webhook/github/<repoName>`
- **Content type:** `application/json`
- **Secret:** the same value as `webhookSecret`
- **Events:** "Just the push event"

On a verified push to the configured branch, CML returns `202` and enqueues an indexing event; the index updates asynchronously via the event queue. **Tag pushes** matching `tags.pattern` (default `v*`) are likewise indexed, as immutable refs; non-matching tags are accepted but left to lazy fault-in on first query. Pushes to other branches, tag/branch deletions, and non-push events (e.g. `ping`) are accepted but ignored. Repos without a `webhookSecret` reject webhook deliveries (fail-closed).

## Authentication & Audit

The remote MCP/HTTP endpoint and admin/SCIP APIs are authenticated:

- **API keys** (`auth.apiKeys`) — bearer tokens with scoped permissions. A bare key is
  read-only (query tools); `scipUpload: true` allows SCIP uploads; `auditReader: true`
  allows audit-log queries.
- **OAuth2 / JWT** (`auth.oauth`) — validate bearer tokens against a JWKS endpoint, with
  optional issuer/audience checks and group-based repo permissions.
- **Audit log** — every query is appended to a hash-chained, tamper-evident audit log in
  PostgreSQL. Query it with `query_audit_log` and verify chain integrity with
  `verify_audit_chain` (both require an `auditReader` key).

## Admin API

REST endpoints at `/admin/*` with bearer token auth.

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/admin/health` | System health stats |
| `GET` | `/admin/repos` | List all repos |
| `POST` | `/admin/repos` | Add a repo (async clone + index) |
| `DELETE` | `/admin/repos/:name` | Remove a repo |
| `POST` | `/admin/repos/:name/reindex` | Trigger full reindex |
| `POST` | `/admin/repos/:name/refs/:ref/pin` | Pin a ref (tag/SHA/branch) — exempt from TTL cleanup |
| `DELETE` | `/admin/repos/:name/refs/:ref/pin` | Unpin a ref |
| `GET` | `/admin/events` | Query indexing events |
| `POST` | `/admin/events/:id/retry` | Retry a failed event |

Admin UI dashboard available at `http://localhost:8080/admin/ui/`.

## Development

### Build

```bash
./gradlew build
```

### Test

```bash
# Unit tests
./gradlew test

# Integration tests (requires Docker for Testcontainers)
./gradlew integrationTest

# End-to-end tests
./gradlew e2eTest
```

### Admin UI development

```bash
# Terminal 1: Java server
export DB_PASSWORD=changeme ADMIN_TOKEN=dev-token
./gradlew run

# Terminal 2: Vite dev server with hot reload
cd admin-ui && npm run dev
# http://localhost:5173/admin/ui/
```

### Project structure

```
src/main/java/com/indexer/
  config/          Config loading, language registry
  db/              DAOs (Repository, File, Symbol, Event, BranchIndex, SCIP)
  model/           Records (Repository, SourceFile, Symbol, BranchIndex, ...)
  mcp/             MCP server, QueryExecutor, tool registration
  indexing/        FileIndexer, IndexingPipeline, Tree-sitter integration
  repository/      GitOperations, RepositoryManager, HookInstaller
  queue/           EventQueuePoller, deduplication
  server/          Javalin HTTP server (webhook + Streamable HTTP + admin + UI)
  admin/           Admin API and service
  auth/            Pluggable auth providers, API key & JWT validation
  scip/            SCIP protobuf parsing, upload API, semantic queries
  audit/           Hash-chained tamper-evident audit log
  webhook/         Webhook payload handling
  util/            Shared helpers (path expansion, ...)

admin-ui/          React SPA (Vite + shadcn/ui + TanStack Query)
scripts/           SCIP upload CLI wrapper
skills/            Claude Code skills (connect-index)
docs/              Design specs and CI pipeline guide
```

## Auth Providers

| Type | Use case |
|------|----------|
| `ssh-key` | GitHub, GitLab, Bitbucket |
| `token` | Personal access tokens |
| `git-credential-manager` | OS-level credential store |

Additional providers (Vault, OAuth2, mTLS, Kerberos, AWS/GCP secret managers) can be added by implementing the `AuthProvider` interface.

## Tech Stack

- **Java 21** + Gradle (Kotlin DSL)
- **PostgreSQL 16** — index storage, event queue, full-text search, audit log
- **Tree-sitter** via [tree-sitter-ng](https://github.com/nicktorwald/tree-sitter-ng) — structural parsing
- **SCIP** (protobuf) — type-resolved semantic intelligence
- **Javalin** — HTTP server (webhook, Streamable HTTP, admin API, static files)
- **MCP Java SDK** — Model Context Protocol implementation
- **Nimbus JOSE + JWT** — OAuth2/JWT bearer-token validation
- **Flyway** — database migrations
- **React + Vite + shadcn/ui** — admin dashboard
- **Testcontainers** — integration testing with real PostgreSQL

## License

MIT
