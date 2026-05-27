# CML — Context Multiplier Layer

An MCP server that indexes source code repositories into PostgreSQL, providing structural code intelligence to any LLM via the [Model Context Protocol](https://modelcontextprotocol.io).

CML parses your codebase with [Tree-sitter](https://tree-sitter.github.io/tree-sitter/), extracts symbols, imports, and type relationships, and exposes them through 11 token-efficient query tools. Any MCP-compatible AI client (Claude Code, Codex, Cursor, etc.) can search symbols, trace implementations, full-text search code, and check sync status — across multiple repos and branches.

```
AI Client <--MCP/stdio|SSE--> CML Server
                                  |
                       +----------+----------+
                       |                     |
                 Repository Manager    Indexing Pipeline
                       |                     |        \
                 Git Clones ---hooks--> Webhook --> PostgreSQL
                       |                         (index + event queue)
                 AuthProvider
                  (pluggable)
```

## Features

- **11 MCP tools** — symbol search, code search, file search, implementation finder, reference finder, repo/file summaries, directory trees, health checks, sync detection
- **Branch-aware indexing** — copy-on-write model: main is fully indexed, feature branches store only their delta. Auto-indexed on push, TTL cleanup, synchronous fault-in on first query
- **Tree-sitter parsing** — structural extraction for Java, Python, TypeScript/JavaScript, Go, and C. Regex fallback for other languages
- **Pluggable auth** — SSH keys, tokens, Git Credential Manager out of the box. Interface supports Vault, mTLS, Kerberos, OAuth2, AWS/GCP secret managers
- **Dual MCP transport** — stdio (local subprocess) and SSE (remote connections) run simultaneously
- **Admin API + UI** — REST endpoints + React dashboard for repo management, event monitoring, and health checks
- **Enterprise-ready** — stateless design, multi-instance safe (SKIP LOCKED event queue), PostgreSQL-backed

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

branches:
  autoIndex: true
  ttlDays: 14
  cleanupIntervalHours: 24
EOF
```

### 3. Build and run

```bash
git clone https://github.com/csharp36/cml.git
cd cml
export DB_PASSWORD=changeme ADMIN_TOKEN=your-secret
./gradlew run
```

On first boot: clones configured repos, runs migrations, performs full index, starts MCP server (stdio + SSE) and admin UI.

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

**Any MCP client (remote/SSE):**
```json
{
  "cml": {
    "type": "sse",
    "url": "http://localhost:8080/mcp"
  }
}
```

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
| `get_index_health` | System health check | Per-repo status, queue state |
| `check_sync` | Compare local HEAD with index | Sync status, recommended action |

All tools except `get_index_health` accept an optional `branch` parameter for branch-aware queries.

## Branch Support

CML uses a copy-on-write model for branch indexing:

- **Main branch** — fully indexed (all files, symbols, imports, contents)
- **Feature branches** — only files that differ from main are stored
- **Automatic** — branches are indexed when pushed via git hooks
- **TTL cleanup** — branch data expires after configurable inactivity (default 14 days)
- **Fault-in** — expired branches are re-indexed synchronously on first query (1-2 seconds)

When querying a feature branch, pass `branch: "feature/my-branch"` to any tool. CML merges the branch delta with main transparently.

## Admin API

REST endpoints at `/admin/*` with bearer token auth.

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/admin/health` | System health stats |
| `GET` | `/admin/repos` | List all repos |
| `POST` | `/admin/repos` | Add a repo (async clone + index) |
| `DELETE` | `/admin/repos/:name` | Remove a repo |
| `POST` | `/admin/repos/:name/reindex` | Trigger full reindex |
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
  db/              DAOs (Repository, File, Symbol, Event, BranchIndex)
  model/           Records (Repository, SourceFile, Symbol, BranchIndex, ...)
  mcp/             MCP server, QueryExecutor, tool registration
  indexing/        FileIndexer, IndexingPipeline, Tree-sitter integration
  repository/      GitOperations, RepositoryManager, HookInstaller
  queue/           EventQueuePoller, deduplication
  server/          Javalin HTTP server (webhook + SSE + admin + UI)
  admin/           Admin API and service
  auth/            Pluggable auth providers
  webhook/         Webhook payload handling

admin-ui/          React SPA (Vite + shadcn/ui + TanStack Query)
skills/            Claude Code skills (connect-index)
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
- **PostgreSQL 16** — index storage, event queue, full-text search
- **Tree-sitter** via [tree-sitter-ng](https://github.com/nicktorwald/tree-sitter-ng) — structural parsing
- **Javalin** — HTTP server (webhook, SSE, admin API, static files)
- **MCP Java SDK** — Model Context Protocol implementation
- **Flyway** — database migrations
- **React + Vite + shadcn/ui** — admin dashboard
- **Testcontainers** — integration testing with real PostgreSQL

## License

MIT
