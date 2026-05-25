# Source Code Indexer MCP Server

## What This Project Is

A Java 21+ MCP server that maintains a PostgreSQL-backed index of source code repositories. It clones repos on first boot, installs git hooks for live change detection via a file-based event queue, and exposes a token-efficient query interface to Claude Code.

**Tech stack:** Java 21+, Gradle (Kotlin DSL), PostgreSQL 16, Tree-sitter (JNI), MCP protocol (stdio + SSE)

**Design spec:** `docs/superpowers/specs/2026-05-25-source-code-indexer-mcp-design.md`

## Architecture

```
Claude Code <--MCP/stdio|SSE--> MCP Server
                                   |
                        +-----------+-----------+
                        |                       |
                  Repository Manager      Indexing Pipeline
                        |                       |
                  Git Clones ---hooks---> Event Queue (fs)
                                                |
                                          PostgreSQL
```

- **MCP Server:** Exposes 10 tools (9 query + 1 health). stdio for local, SSE for cloud.
- **Repository Manager:** Clones repos, manages auth (SSH keys / tokens), installs git hooks.
- **Event Queue:** File-based. Hooks write JSON files to a watched directory. Resilient to server restarts.
- **Indexing Pipeline:** Tree-sitter for structural parsing (Java, Python, TS/JS, Go). Full-text + metadata for all other languages.
- **PostgreSQL:** 6 tables — repositories, files, symbols, imports, type_relationships, file_contents.

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

If using Docker:
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
  eventQueueDir: ~/.source-code-indexer/events
  cloneBaseDir: ~/.source-code-indexer/repos
  maxFileSizeBytes: 1048576
  indexWorkers: 4
  transport: stdio
  ssePort: 8080

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

languages:
  customExtensions: {}
EOF
```

### 3. Run the server

```bash
export DB_PASSWORD=changeme
./gradlew run
```

On first boot, the server will: clone all configured repos, run Flyway migrations, perform a full index, then start listening for MCP requests and file-based events.

### 4. Configure Claude Code to use this MCP server

Add to your Claude Code MCP config (`~/.claude/mcp_servers.json` or project-level `.claude/mcp_servers.json`):

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

For remote (cloud) deployment using SSE transport:
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

This starts both the indexer and PostgreSQL containers. Mount your config file and SSH keys as volumes.

## How to Test

### Unit tests
```bash
./gradlew test
```

### Integration tests (requires Docker for Testcontainers)
```bash
./gradlew integrationTest
```

### End-to-end tests
```bash
./gradlew e2eTest
```

## New Developer Onboarding — Getting Claude Code Context on a GitHub Project

If you're new to a company and want to give Claude Code rich, indexed context over an arbitrary GitHub project, here's what to do:

### Step 1: Install prerequisites

```bash
# macOS
brew install openjdk@21 gradle postgresql@16 git docker

# Or just ensure Docker is running — PostgreSQL can run in a container
```

### Step 2: Clone this project and build

```bash
git clone <this-repo-url>
cd SourceCodeIndexerMCP
./gradlew build
```

### Step 3: Get your repo credentials ready

You need auth for the private repos you want to index:
- **SSH:** Ensure your SSH key is in `~/.ssh/` and added to GitHub/GitLab
- **Token:** Create a personal access token with repo read access

### Step 4: Create your config

```bash
mkdir -p ~/.source-code-indexer
```

Edit `~/.source-code-indexer/config.yaml` and add each repo you want indexed:

```yaml
server:
  eventQueueDir: ~/.source-code-indexer/events
  cloneBaseDir: ~/.source-code-indexer/repos
  maxFileSizeBytes: 1048576
  indexWorkers: 4
  transport: stdio
  ssePort: 8080

database:
  host: localhost
  port: 5432
  name: source_code_index
  username: indexer
  password: ${DB_PASSWORD}

repositories:
  # Add every repo you want Claude to know about:
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
```

### Step 5: Start PostgreSQL and the indexer

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
export GITHUB_TOKEN=ghp_your_token  # if using token auth
./gradlew run
```

The first boot will clone all repos and run a full index. For large codebases this may take several minutes — watch the logs.

### Step 6: Wire it into Claude Code

Add the MCP server to your Claude Code config:

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

### Step 7: Verify it works

In Claude Code, ask:
- "Use get_index_health to check the indexer status" — confirms all repos cloned and indexed
- "Use get_repo_summary for backend-api" — see language breakdown, file count
- "Use search_symbols to find all classes named Controller" — structural query
- "Use search_code for authentication" — full-text search

### Step 8: Keep it running

As long as the server is running, git hooks in the cloned repos fire on every commit/merge/rebase and the index stays current automatically. If you stop the server, events queue up as files and get processed when it restarts.

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

## Project Conventions

- Flyway for DB migrations (in `src/main/resources/db/migration/`)
- Tree-sitter query files (`.scm`) per language (in `src/main/resources/queries/`)
- Config supports `${ENV_VAR}` substitution for secrets
- Structured JSON logging to `~/.source-code-indexer/logs/indexer.log`
- Event files: `{timestamp}-{repo-name}-{event-type}.json`
