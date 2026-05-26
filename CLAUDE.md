# Source Code Indexer MCP Server

## What This Project Is

A Java 21+ MCP server that maintains a PostgreSQL-backed index of source code repositories. It clones repos on first boot, installs git hooks for live change detection via a PostgreSQL event queue, and exposes a token-efficient query interface to Claude Code.

Designed for large financial institutions: pluggable enterprise auth, stateless cloud deployment, multi-instance scaling.

**Tech stack:** Java 21+, Gradle (Kotlin DSL), PostgreSQL 16, Tree-sitter structural parsing via tree-sitter-ng (Java, Python, TypeScript/JavaScript, Go, C), MCP protocol (stdio + SSE)

**Design spec:** `docs/superpowers/specs/2026-05-25-source-code-indexer-mcp-design.md`

## Architecture

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

- **MCP Server:** Exposes 10 tools (9 query + 1 health). Both stdio and SSE transports always run simultaneously — stdio for local Claude Code subprocess, SSE for remote connections.
- **Webhook Endpoint:** HTTP POST receiver for git hooks. Hosted on the same HTTP port as SSE (`/webhook`). Inserts events into PostgreSQL queue.
- **Repository Manager:** Clones repos, resolves auth via pluggable AuthProvider, installs git hooks.
- **Indexing Pipeline:** Polls PostgreSQL event queue with `SKIP LOCKED`. Multi-instance safe.
- **AuthProvider:** Pluggable interface supporting SSH, tokens, OAuth2, mTLS, Kerberos, GCM, Vault, AWS/GCP secret managers.
- **PostgreSQL:** 7 tables — repositories, files, symbols, imports, type_relationships, file_contents, indexing_events.

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
- **SSE** — available at `http://localhost:8080/mcp` for remote connections
- **Webhook** — available at `http://localhost:8080/webhook` for git hook events

All three are served from the single `httpPort` (default 8080).

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

**Remote (SSE):**
```json
{
  "source-code-indexer": {
    "type": "sse",
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
| `get_index_health` | System health check | Per-repo status, errors, queue state |

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
