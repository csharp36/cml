# Source Code Indexer MCP Server — Design Spec

## Overview

A Java MCP server that maintains an up-to-date PostgreSQL index of an arbitrary set of source code repositories. It clones repos on first boot from a config file, installs git hooks for live change detection, and exposes a token-efficient query interface to Claude Code.

Designed for deployment at large financial institutions: supports enterprise auth mechanisms, runs on stateless cloud infrastructure, and scales to 50+ repos with 1M+ lines of code.

**Key decisions:**
- Gradle (Kotlin DSL) + Java 21+
- PostgreSQL for the index AND event queue (no filesystem state beyond repo clones)
- Real git hooks (`post-commit`, `post-merge`, `post-checkout`, `post-rewrite`) that POST to an HTTP webhook
- PostgreSQL-based event queue with `SKIP LOCKED` for multi-instance support
- Tree-sitter for unified structural parsing across Java, Python, TypeScript/JavaScript, Go
- Full-text search + metadata for all other text files (graceful degradation)
- Pluggable `AuthProvider` interface — supports SSH, tokens, OAuth2, mTLS, Kerberos, Git Credential Manager, secret managers
- Cloud-agnostic Docker Compose deployment targeting GCP or AWS
- SSE transport for remote use, stdio for local dev
- Claude Code `connect-index` skill for developer onboarding
- Admin UI (Next.js) planned as a second phase

## 1. High-Level Architecture

Six major components:

1. **MCP Server** — Implements the MCP protocol (stdio + SSE transport), exposes tools to Claude Code. Receives queries, translates to SQL, returns token-efficient results.
2. **Webhook Endpoint** — Lightweight HTTP server (single `/webhook` route) that receives git hook POST requests and inserts events into the PostgreSQL event queue.
3. **Repository Manager** — Handles git operations. On first boot, reads config and clones repos. Resolves per-repo auth via the `AuthProvider` interface. Installs git hooks into each clone.
4. **Indexing Pipeline** — Polls the PostgreSQL event queue using `SELECT ... FOR UPDATE SKIP LOCKED`. For each event: runs Tree-sitter parsing for structural symbols, updates full-text search index, updates file metadata. Operates incrementally. Multiple instances can compete for work.
5. **PostgreSQL** — Single database serving three roles: source code index (symbols, files, content), event queue (`indexing_events` table), and system metadata.
6. **Auth Provider** — Pluggable interface that resolves credentials at runtime from config references (file paths, env vars, secret manager paths). Never stores credentials in the database.

```
Claude Code <--MCP/stdio|SSE--> MCP Server
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

### Multi-instance deployment

For horizontal scaling, multiple indexer instances share the same PostgreSQL database:
- Each instance runs the indexing pipeline, competing for events via `SKIP LOCKED`
- Repo clones live on a shared volume (EFS/Filestore) or each instance is assigned a subset of repos via config
- The webhook endpoint runs behind a load balancer; any instance can receive hook POSTs
- MCP query endpoints are stateless — any instance can serve any query

## 2. Configuration & Repository Management

### Config file

YAML at a configurable path (default: `~/.source-code-indexer/config.yaml`). Supports `${ENV_VAR}` substitution for secrets.

```yaml
server:
  cloneBaseDir: ~/.source-code-indexer/repos
  maxFileSizeBytes: 1048576  # 1MB default
  indexWorkers: 4            # thread pool size for parallel indexing
  transport: stdio           # stdio or sse
  ssePort: 8080              # MCP SSE transport port
  webhookPort: 8081          # HTTP endpoint for git hook callbacks

database:
  host: localhost
  port: 5432
  name: source_code_index
  username: indexer
  password: ${DB_PASSWORD}

repositories:
  - url: git@github.com:org/repo-one.git
    branch: main
    auth:
      type: ssh-key
      keyPath: ~/.ssh/id_ed25519

  - url: https://github.com/org/repo-two.git
    branch: develop
    auth:
      type: token
      token: ${GITHUB_TOKEN}

  - url: https://git.internal.bank.com/platform.git
    branch: main
    auth:
      type: vault
      vaultAddr: ${VAULT_ADDR}
      vaultPath: secret/data/git/platform-credentials
      vaultField: token

  - url: https://gitlab.bank.com/trading/engine.git
    branch: main
    auth:
      type: client-cert
      certPath: /etc/pki/git-client.pem
      keyPath: /etc/pki/git-client-key.pem

  - url: https://git.bank.com/infra/core.git
    branch: main
    auth:
      type: git-credential-manager

languages:
  customExtensions:
    .proto: protobuf
    .tf: hcl
    .jsx: javascript
```

### Authentication

Credentials are **never stored in the database**. The config stores the auth type and a reference. The `AuthProvider` interface resolves credentials at clone/fetch time.

| Auth type | Config fields | Resolution |
|-----------|--------------|------------|
| `ssh-key` | `keyPath` | Reads key from filesystem |
| `token` | `token` (supports `${ENV_VAR}`) | Resolves env var or literal |
| `oauth2` | `tokenEndpoint`, `clientId`, `clientSecret`, `scope` | OAuth2 client credentials flow at runtime |
| `client-cert` (mTLS) | `certPath`, `keyPath` | Reads cert/key from filesystem |
| `kerberos` | `principal`, `keytabPath` | Uses JAAS/Kerberos for SPNEGO auth |
| `git-credential-manager` | (none) | Delegates to OS-level GCM |
| `vault` | `vaultAddr`, `vaultPath`, `vaultField` | Fetches from HashiCorp Vault at runtime |
| `aws-secrets-manager` | `secretArn`, `secretField` | Fetches from AWS Secrets Manager |
| `gcp-secret-manager` | `project`, `secretName`, `version` | Fetches from GCP Secret Manager |

The `AuthProvider` interface:

```java
public interface AuthProvider {
    GitCredentials resolve(AuthConfig config);
    boolean supports(String authType);
}
```

Implementations are loaded via service discovery (ServiceLoader). Financial institutions can add custom providers by dropping a JAR on the classpath.

### First boot sequence

1. Read and validate config
2. Create clone base directory if missing
3. Initialize PostgreSQL schema (Flyway migrations)
4. For each repo: resolve auth, clone (or verify existing clone), checkout target branch, install git hooks
5. Run full initial index of all files across all repos (parallelized across repos)
6. Start MCP server, webhook endpoint, and event queue poller

### Subsequent boots

1. Read config, connect to DB
2. For each repo: resolve auth, `git fetch` + fast-forward, diff against `lastIndexedSha`, incrementally re-index changes missed while server was down
3. Start MCP server, webhook endpoint, and event queue poller

### Git hooks installed

`post-commit`, `post-merge`, `post-checkout`, `post-rewrite`. Each is a shell script that POSTs a JSON payload to the webhook endpoint:

```bash
#!/bin/sh
curl -s -X POST http://localhost:8081/webhook \
  -H "Content-Type: application/json" \
  -d "{\"repoName\":\"$(basename $(git rev-parse --show-toplevel))\",\"repoPath\":\"$(git rev-parse --show-toplevel)\",\"eventType\":\"post-commit\",\"previousSha\":\"$1\",\"currentSha\":\"$(git rev-parse HEAD)\",\"timestamp\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"
```

The webhook URL is configurable in the hook script (set at installation time based on `server.webhookPort`).

## 3. Event Queue & Indexing Pipeline

### PostgreSQL event queue

Events are stored in an `indexing_events` table rather than the filesystem. This enables stateless containers and multi-instance processing.

```sql
indexing_events (
  id              BIGSERIAL PRIMARY KEY,
  repo_name       TEXT NOT NULL,
  repo_path       TEXT NOT NULL,
  event_type      TEXT NOT NULL,
  previous_sha    TEXT,
  current_sha     TEXT NOT NULL,
  status          TEXT NOT NULL DEFAULT 'pending',  -- pending, processing, completed, failed
  error_message   TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  started_at      TIMESTAMPTZ,
  completed_at    TIMESTAMPTZ,
  worker_id       TEXT                              -- identifies which instance picked it up
)

CREATE INDEX idx_events_pending ON indexing_events(status, created_at)
  WHERE status = 'pending';
```

### Webhook endpoint

Single HTTP route: `POST /webhook`
- Validates the JSON payload
- Inserts a row into `indexing_events` with status `pending`
- Returns 202 Accepted immediately (non-blocking)
- If the payload is malformed, returns 400 with error detail

### Queue processing

Each indexer instance runs a polling loop:

```sql
-- Claim the next pending event (multi-instance safe)
UPDATE indexing_events
SET status = 'processing', started_at = NOW(), worker_id = :workerId
WHERE id = (
  SELECT id FROM indexing_events
  WHERE status = 'pending'
  ORDER BY created_at
  FOR UPDATE SKIP LOCKED
  LIMIT 1
)
RETURNING *;
```

**Deduplication:** Before processing, check if there are additional pending events for the same repo. If so, collapse them — use the earliest `previous_sha` and latest `current_sha`, mark intermediate events as `completed` (subsumed).

**Polling interval:** Configurable (default 1 second). Uses `LISTEN/NOTIFY` as an optimization — the webhook endpoint issues a `NOTIFY new_event` after inserting, and pollers wake immediately rather than waiting for the next poll cycle.

### Indexing pipeline per event

1. Compute changed files via `git diff --name-status previousSha currentSha`
2. Deleted files: remove all symbols, text content, and metadata from DB
3. Added/modified files:
   - Detect language from file extension
   - Core language (Java, Python, TS/JS, Go): Tree-sitter parse, extract symbols via language-specific `.scm` query files, upsert into DB
   - All text files: update full-text search content (`tsvector`)
   - Update file metadata
4. Update repo's `lastIndexedSha`
5. All DB writes in a single transaction per event
6. On success: mark event as `completed` with `completed_at`
7. On failure: mark event as `failed` with `error_message`, `lastIndexedSha` does not advance

### Tree-sitter symbol extraction

Each language has a `.scm` query file that captures:
- **Declarations:** classes, interfaces, structs, functions, methods, enums, type aliases
- **Imports/dependencies:** import statements, require calls, package declarations
- **Signatures:** parameter types, return types (where syntactically available)
- **Relationships:** implements/extends

Symbols are normalized into a uniform model before DB insertion.

## 4. PostgreSQL Schema

### Core tables

```sql
repositories (
  id              SERIAL PRIMARY KEY,
  name            TEXT UNIQUE NOT NULL,
  url             TEXT NOT NULL,
  branch          TEXT NOT NULL,
  clone_path      TEXT NOT NULL,
  auth_type       TEXT NOT NULL,        -- ssh-key, token, vault, etc.
  last_indexed_sha TEXT,
  last_indexed_at  TIMESTAMPTZ
)

files (
  id              SERIAL PRIMARY KEY,
  repo_id         INT REFERENCES repositories(id),
  path            TEXT NOT NULL,
  language        TEXT,
  size_bytes      INT,
  last_commit_sha TEXT,
  last_modified_at TIMESTAMPTZ,
  UNIQUE(repo_id, path)
)

symbols (
  id              SERIAL PRIMARY KEY,
  file_id         INT REFERENCES files(id) ON DELETE CASCADE,
  name            TEXT NOT NULL,
  kind            TEXT NOT NULL,
  signature       TEXT,
  start_line      INT,
  end_line        INT,
  parent_id       INT REFERENCES symbols(id) ON DELETE CASCADE,
  visibility      TEXT,
  is_static       BOOLEAN DEFAULT FALSE
)

imports (
  id              SERIAL PRIMARY KEY,
  file_id         INT REFERENCES files(id) ON DELETE CASCADE,
  import_path     TEXT NOT NULL,
  alias           TEXT
)

type_relationships (
  id              SERIAL PRIMARY KEY,
  symbol_id       INT REFERENCES symbols(id) ON DELETE CASCADE,
  related_name    TEXT NOT NULL,
  kind            TEXT NOT NULL
)

file_contents (
  id              SERIAL PRIMARY KEY,
  file_id         INT REFERENCES files(id) ON DELETE CASCADE UNIQUE,
  content         TEXT,
  search_vector   TSVECTOR
)

indexing_events (
  id              BIGSERIAL PRIMARY KEY,
  repo_name       TEXT NOT NULL,
  repo_path       TEXT NOT NULL,
  event_type      TEXT NOT NULL,
  previous_sha    TEXT,
  current_sha     TEXT NOT NULL,
  status          TEXT NOT NULL DEFAULT 'pending',
  error_message   TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  started_at      TIMESTAMPTZ,
  completed_at    TIMESTAMPTZ,
  worker_id       TEXT
)
```

### Key indexes

```sql
CREATE INDEX idx_symbols_name ON symbols(name);
CREATE INDEX idx_symbols_kind ON symbols(kind);
CREATE INDEX idx_symbols_file_id ON symbols(file_id);
CREATE INDEX idx_files_repo_path ON files(repo_id, path);
CREATE INDEX idx_files_language ON files(language);
CREATE INDEX idx_file_contents_search ON file_contents USING GIN(search_vector);
CREATE INDEX idx_type_rel_related ON type_relationships(related_name);
CREATE INDEX idx_type_rel_symbol ON type_relationships(symbol_id);
CREATE INDEX idx_imports_path ON imports(import_path);
CREATE INDEX idx_events_pending ON indexing_events(status, created_at) WHERE status = 'pending';
CREATE INDEX idx_events_repo ON indexing_events(repo_name, status);
```

### Design rationale

- `symbols.parent_id` models containment (method inside class) without a join table
- `type_relationships` is separate because a class can implement multiple interfaces
- `file_contents` is its own table so full-text queries don't scan symbol tables and content blobs stay out of metadata queries
- `indexDepth` is not stored — it's computed at query time from the file's `language` field (core language = `full`, known text extension = `text`, binary = `metadata`)
- `repositories.auth_type` records which provider is used but stores no credentials
- `indexing_events` serves as both queue and audit trail — completed events are retained for observability

## 5. MCP Interface — Tools

Design principle: every tool returns minimum data for Claude to reason, with options to drill deeper. No tool dumps raw file contents by default.

### Structural queries

**`search_symbols`** — Find symbols by name, kind, or pattern.
- Params: `query` (text, supports regex), `kind` (optional), `language` (optional), `repo` (optional), `limit` (default 20)
- Returns: `[{name, kind, signature, filePath, repo, startLine, endLine, visibility}]`

**`get_symbol_detail`** — Full detail for a specific symbol including source code.
- Params: `repo`, `filePath`, `symbolName` (or `line`)
- Returns: `{name, kind, signature, source, children[], relationships[], imports[]}`
- Only tool that returns actual source code, scoped to a single symbol.

**`find_implementations`** — Classes implementing an interface or extending a class.
- Params: `typeName`, `repo` (optional)
- Returns: `[{className, filePath, repo, signature}]`

**`find_references`** — Files that import or reference a symbol.
- Params: `symbolName`, `repo` (optional), `limit` (default 20)
- Returns: `[{filePath, repo, importPath, line}]`

### Text/semantic search

**`search_code`** — Full-text search across indexed content.
- Params: `query` (PostgreSQL `ts_query`), `language` (optional), `repo` (optional), `limit` (default 10)
- Returns: `[{filePath, repo, matchingLines[]}]` — matching lines with 3 lines context, not entire files.

**`search_files`** — Find files by path pattern or name.
- Params: `pattern` (glob), `language` (optional), `repo` (optional), `limit` (default 20)
- Returns: `[{filePath, repo, language, sizeBytes, lastModified}]`

### Metadata queries

**`get_repo_summary`** — High-level repository overview.
- Params: `repo`
- Returns: `{name, url, branch, lastIndexedSha, lastIndexedAt, fileCount, languageBreakdown, topLevelDirectories[]}`

**`get_file_summary`** — File summary without content.
- Params: `repo`, `filePath`
- Returns: `{path, language, sizeBytes, lastModified, indexDepth, symbols[{name, kind, signature, startLine}], imports[]}`
- `indexDepth` indicates: `full` (core language), `text` (full-text only), `metadata` (metadata only)

**`get_directory_tree`** — Directory structure.
- Params: `repo`, `path` (optional, default root), `depth` (optional, default 3)
- Returns: nested `{name, type, language, childCount}`

### Observability

**`get_index_health`** — System health at a glance.
- Returns per-repo: last indexed SHA, last indexed timestamp, pending event count, failed event count, last error message
- Returns system-wide: total pending events, total failed events, active workers, uptime
- Returns recent failures: last 10 failed events with repo name, error message, and timestamp

## 6. Error Handling, Observability & Language Fallback

### Error handling

**Git operations:**
- Clone failures (auth, network): log, skip repo, continue. Report failed repos on startup via stderr.
- Auth resolution failures (vault unreachable, cert expired): log with specific auth type and error, skip repo.
- Hook installation failures: log, continue. Repo gets indexed on boot but no live updates.
- `git fetch` failures: retry once, log, skip. Repo stays at last-indexed SHA.

**Event queue:**
- Malformed webhook payloads: return HTTP 400, log, no event created.
- Processing failure: mark event as `failed` with `error_message`. Event remains in DB for inspection.
- Duplicate events: deduplication collapses overlapping SHAs before processing.
- Stale events (pending for too long): configurable timeout (default 1 hour), marked as `failed` with timeout message. Prevents stuck events from blocking the queue.

**Indexing pipeline:**
- Tree-sitter parse failure: log, skip structural indexing, still do full-text. One file never blocks others.
- DB transaction failure: retry once, mark event as `failed`. `lastIndexedSha` doesn't advance.
- Oversized files (above `maxFileSizeBytes`): metadata-only indexing.

**MCP server:**
- Query against failed repo: clear error naming repo and failure reason.
- Query during indexing: DB is transactionally consistent; in-progress indexing invisible until commit.

### Observability

1. **`get_index_health` MCP tool** — described above. This is the primary way Claude and developers discover problems.
2. **Structured log file** — JSON-line log at configurable path. Every failure gets a structured entry with: timestamp, level, component, repo, file (if applicable), error type, message, stack trace. Rotated by size (default 50MB, configurable).
3. **Error summary on startup** — if failed events exist in the DB, log count and affected repos to stderr.
4. **Event audit trail** — all events (pending, completed, failed) are retained in `indexing_events` for configurable duration (default 7 days). Enables post-mortem analysis.
5. **Admin API endpoints** (for future admin UI):
   - `GET /admin/health` — same data as `get_index_health`
   - `GET /admin/events?status=failed&repo=X` — query event history
   - `POST /admin/repos` — add a new repository
   - `DELETE /admin/repos/:name` — purge a repo from the index
   - `POST /admin/repos/:name/reindex` — trigger full re-index

### Language fallback

| Layer | Core languages (Java, Python, TS/JS, Go) | Other text files | Binary files |
|-------|------------------------------------------|-----------------|-------------|
| File metadata | Yes | Yes | Yes |
| Full-text search | Yes | Yes | No |
| Structural symbols | Yes | No | No |

Language detection uses file extension mapping, configurable via `languages.customExtensions` in config. Unknown extensions default to plain text.

## 7. Scalability & Performance

### Data profile at 1M+ LOC

| Table | Estimated rows | Estimated size |
|-------|---------------|---------------|
| `repositories` | 50-100 | Negligible |
| `files` | 20,000-80,000 | ~10MB |
| `symbols` | 200,000-1,000,000 | ~200MB |
| `imports` | 100,000-500,000 | ~50MB |
| `type_relationships` | 50,000-200,000 | ~20MB |
| `file_contents` | 20,000-80,000 | ~500MB-2GB |
| `indexing_events` | ~10,000/week (pruned) | ~5MB |

Single-node PostgreSQL handles this comfortably.

### Performance mitigations

1. **Initial index:** batch inserts (`COPY` or multi-row `INSERT`), parallelized across repos via configurable thread pool. Target: 1M LOC in under 10 minutes.
2. **Full-text search:** GIN index on `search_vector`. Files over size threshold get metadata-only indexing.
3. **Symbol queries:** composite indexes on `(name, kind)` and `(file_id, kind)`.
4. **Incremental re-indexing:** event-driven, only changed files. Typical commit is sub-second to re-index.
5. **Event queue:** `SKIP LOCKED` prevents contention. `LISTEN/NOTIFY` reduces polling overhead.

### Multi-instance scaling

- **Stateless indexers:** no local state beyond repo clones. All coordination via PostgreSQL.
- **Shared repo clones:** mount a shared volume (EFS/Filestore) for clones, or assign repo subsets per instance via config.
- **Webhook load balancing:** any instance can receive webhook POSTs — they just insert into shared PostgreSQL.
- **Query scaling:** MCP endpoints are pure reads against PostgreSQL. Add instances freely behind a load balancer.

### What we do NOT add

No sharding, read replicas, message brokers (Kafka/SQS), or caching layer. PostgreSQL job queue handles the throughput. Only add complexity when measured need arises.

## 8. Deployment & Cost

### Docker Compose (cloud-agnostic)

Two containers (single-instance):
1. **`indexer`** — Java MCP server with Tree-sitter native libs, git client, webhook endpoint
2. **`postgres`** — Stock PostgreSQL 16

For multi-instance, add a shared volume and scale the `indexer` service.

Runs identically on GCP (GCE/Cloud Run), AWS (EC2/ECS/Fargate), or local.

### Cost profile (single instance)

| Resource | Sizing | Monthly cost |
|----------|--------|-------------|
| Compute (e2-standard-2 / t3.medium) | 2 vCPU, 4-8GB RAM | $30-60 |
| Persistent disk (repos + PG data) | 50-100GB SSD | $5-15 |
| Network (git fetch) | Delta fetches only | ~$1-2 |
| **Total** | | **~$35-80/month** |

### Cost profile (multi-instance, large deployment)

| Resource | Sizing | Monthly cost |
|----------|--------|-------------|
| Compute (2-4 indexer instances) | 2 vCPU, 4GB RAM each | $60-240 |
| Managed PostgreSQL (Cloud SQL / RDS) | db-standard-2 / db.t3.medium | $50-100 |
| Shared volume (EFS/Filestore) | 100-500GB | $30-150 |
| Network | Internal traffic | ~$5 |
| **Total** | | **~$145-495/month** |

### Cost optimizations

- Preemptible/spot instances for indexer containers — PostgreSQL event queue means no data loss on eviction
- Scale indexer instances to zero when no pending events (event-driven auto-scaling)
- Use managed PostgreSQL only if operational burden justifies it; containerized PG is fine for smaller deployments
- Event retention pruning (7-day default) keeps the events table lean

### MCP transport

- **Local dev:** stdio (Claude Code launches as subprocess)
- **Cloud:** SSE endpoint on configurable port. Config toggle between transports.

## 9. Developer Onboarding — `connect-index` Skill

A Claude Code skill that automates connecting a developer's workspace to the index.

### Workflow

```
mkdir my-project && cd my-project && git clone <repo-url> . && claude
> /connect-index
```

### Skill behavior

1. **Detect repo** — reads `.git/config` to find the remote URL. If no git repo, asks the developer for the URL.
2. **Find the indexer** — checks for indexer connection info in:
   - `~/.source-code-indexer/client.yaml` (default)
   - Environment variable `SOURCE_CODE_INDEXER_URL`
3. **Verify indexed** — calls `get_repo_summary` via the MCP protocol to confirm the repo is in the index.
4. **Configure Claude Code** — writes `.claude/mcp_servers.json` for the project:
   ```json
   {
     "source-code-indexer": {
       "type": "sse",
       "url": "http://indexer.internal:8080/mcp"
     }
   }
   ```
5. **Print usage guide:**
   ```
   Connected to source-code-indexer. Your repo "trading-engine" is indexed
   (last updated: 2 minutes ago, 47,832 files, 312,091 symbols).

   Try these:
   - "What's the structure of this repo?"         → get_directory_tree
   - "Find all classes implementing OrderHandler" → find_implementations
   - "Search for rate limiting logic"             → search_code
   - "Show me the TradeExecutor class"            → get_symbol_detail
   - "What imports PaymentService?"               → find_references
   ```

### Client config file

`~/.source-code-indexer/client.yaml`:
```yaml
indexerUrl: http://indexer.internal:8080
# Or for local stdio:
# indexerCommand: /path/to/gradlew run
# indexerCwd: /path/to/SourceCodeIndexerMCP
```

This separates client config (how to reach the indexer) from server config (how to run it).

## 10. Admin UI (Phase 2)

A Next.js web application for index administration. Not part of the initial build but the server is designed to support it.

### Capabilities

- **Dashboard:** real-time stats (repos, files, symbols, events/hour, error rate)
- **Repository management:** add/remove repos, edit branch/auth config, trigger re-index
- **Event viewer:** filter events by repo/status/time, inspect failures, retry failed events
- **Health monitoring:** per-repo index freshness, auth status, queue depth

### Integration

The admin UI talks to the admin API endpoints exposed by the indexer:
- `GET /admin/health`
- `GET /admin/repos`
- `POST /admin/repos`
- `DELETE /admin/repos/:name`
- `POST /admin/repos/:name/reindex`
- `GET /admin/events`
- `POST /admin/events/:id/retry`

These endpoints are authenticated separately from the MCP interface (admin bearer token or SSO).

## 11. Error Handling, Observability & Language Fallback

(Merged into Section 6 above.)

## 12. Testing Strategy

### Unit tests (JUnit 5 + Mockito)

- Tree-sitter query files: given known source files per language, assert correct symbol extraction
- Event deduplication logic
- Language detection
- Config parsing, validation, and env var substitution
- SQL query builders for each MCP tool
- AuthProvider resolution (mock each provider type)
- Webhook payload validation

### Integration tests (Testcontainers + PostgreSQL)

- Full indexing pipeline: clone test repo, index, verify DB state
- Incremental indexing: modify files, process event, verify delta update
- Full-text search accuracy
- Each MCP tool against known DB state
- Flyway migrations against fresh database
- Event queue: concurrent processing with SKIP LOCKED (multi-thread simulation)
- Webhook endpoint: POST events, verify they appear in queue
- Admin API endpoints

### End-to-end tests

Curated test repo (git bundle in test resources) with files in all four core languages plus one unsupported language:
- First boot: config -> clone -> full index -> query -> verify
- Hook simulation: POST to webhook, verify event pickup and processing
- Degradation: unsupported language gets metadata + text, no symbols
- Error paths: malformed webhook payloads return 400, failed events retain error message
- Health tool: `get_index_health` reflects actual system state
- Auth provider: mock vault/secret manager responses, verify credential resolution

### Not tested

- Real git hook installation into remote repos (too environment-dependent)
- Tree-sitter native library loading (build/packaging concern)
- Actual HashiCorp Vault / AWS Secrets Manager / GCP Secret Manager connectivity (mocked in integration tests)
