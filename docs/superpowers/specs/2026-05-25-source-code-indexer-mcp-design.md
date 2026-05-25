# Source Code Indexer MCP Server — Design Spec

## Overview

A Java MCP server that maintains an up-to-date PostgreSQL index of an arbitrary set of source code repositories. It clones repos on first boot from a config file, installs git hooks for live change detection, and exposes a token-efficient query interface to Claude Code.

**Key decisions:**
- Gradle (Kotlin DSL) + Java 21+
- PostgreSQL for the index
- Real git hooks (`post-commit`, `post-merge`, `post-checkout`, `post-rewrite`) installed into cloned repos
- File-based event queue for hook-to-server communication
- Tree-sitter for unified structural parsing across Java, Python, TypeScript/JavaScript, Go
- Full-text search + metadata for all other text files (graceful degradation)
- Cloud-agnostic Docker Compose deployment targeting GCP or AWS
- SSE transport for remote use, stdio for local dev

## 1. High-Level Architecture

Five major components:

1. **MCP Server** — Implements the MCP protocol (stdio + SSE transport), exposes tools to Claude Code. Receives queries, translates to SQL, returns token-efficient results.
2. **Repository Manager** — Handles git operations. On first boot, reads config and clones repos. Manages per-repo auth credentials (SSH keys or tokens). Installs git hooks into each clone.
3. **Event Queue (File-Based)** — A watched directory where git hooks drop JSON event files. The server watches this directory and processes events in order.
4. **Indexing Pipeline** — Consumes events from the queue. For each changed file: runs Tree-sitter parsing for structural symbols, updates full-text search index, updates file metadata. Operates incrementally.
5. **PostgreSQL Index** — Persistent store. Holds symbol table, full-text content, file metadata, and repo metadata.

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

## 2. Configuration & Repository Management

### Config file

YAML at a configurable path (default: `~/.source-code-indexer/config.yaml`).

```yaml
server:
  eventQueueDir: ~/.source-code-indexer/events
  cloneBaseDir: ~/.source-code-indexer/repos
  maxFileSizeBytes: 1048576  # 1MB default
  indexWorkers: 4            # thread pool size for parallel indexing
  transport: stdio           # stdio or sse
  ssePort: 8080              # only used when transport is sse

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

languages:
  customExtensions:
    .proto: protobuf
    .tf: hcl
    .jsx: javascript
```

### First boot sequence

1. Read and validate config
2. Create event queue directory and clone base directory if missing
3. Initialize PostgreSQL schema (Flyway migrations)
4. For each repo: clone (or verify existing clone), checkout target branch, install git hooks
5. Run full initial index of all files across all repos (parallelized across repos)
6. Start MCP server and event queue watcher

### Subsequent boots

1. Read config, connect to DB
2. For each repo: `git fetch` + fast-forward, diff against `lastIndexedSha`, incrementally re-index changes missed while server was down
3. Start MCP server and event queue watcher

### Git hooks installed

`post-commit`, `post-merge`, `post-checkout`, `post-rewrite`. Each is a shell script that writes a JSON event file to the queue directory.

## 3. Event Queue & Indexing Pipeline

### Event file format

Filename: `{timestamp}-{repo-name}-{event-type}.json`

```json
{
  "repoName": "repo-one",
  "repoPath": "/home/user/.source-code-indexer/repos/repo-one",
  "eventType": "post-commit",
  "previousSha": "abc1234",
  "currentSha": "def5678",
  "timestamp": "2026-05-25T12:00:00Z"
}
```

Events do not list individual files. The server computes the diff via `git diff --name-status previousSha currentSha`.

### Queue processing

1. `WatchService` (Java NIO) monitors the event queue directory
2. Events are parsed and deduplicated — if multiple events arrive for the same repo in quick succession, collapse into a single re-index from earliest `previousSha` to latest `currentSha`
3. Processed event files move to a `processed/` subdirectory

### Indexing pipeline per event

1. Compute changed files via `git diff --name-status`
2. Deleted files: remove all symbols, text content, and metadata from DB
3. Added/modified files:
   - Detect language from file extension
   - Core language (Java, Python, TS/JS, Go): Tree-sitter parse, extract symbols via language-specific `.scm` query files, upsert into DB
   - All text files: update full-text search content (`tsvector`)
   - Update file metadata
4. Update repo's `lastIndexedSha`
5. All DB writes in a single transaction per event

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
```

### Design rationale

- `symbols.parent_id` models containment (method inside class) without a join table
- `type_relationships` is separate because a class can implement multiple interfaces
- `file_contents` is its own table so full-text queries don't scan symbol tables and content blobs stay out of metadata queries
- `indexDepth` is not stored — it's computed at query time from the file's `language` field (core language = `full`, known text extension = `text`, binary = `metadata`)

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
- Returns per-repo: last indexed SHA, last indexed timestamp, pending event count, error count since last success, last error message
- Returns system-wide: total pending events, total errors in `errors/`, queue watcher status, uptime

## 6. Error Handling, Observability & Language Fallback

### Error handling

**Git operations:**
- Clone failures (auth, network): log, skip repo, continue. Report failed repos on startup via stderr.
- Hook installation failures: log, continue. Repo gets indexed on boot but no live updates.
- `git fetch` failures: retry once, log, skip. Repo stays at last-indexed SHA.

**Event queue:**
- Malformed events: move to `errors/` with `.error` suffix, log, continue.
- Server was down: events persist as files, processed on startup before accepting MCP requests.
- Duplicate events: deduplication collapses overlapping SHAs.

**Indexing pipeline:**
- Tree-sitter parse failure: log, skip structural indexing, still do full-text. One file never blocks others.
- DB transaction failure: retry once, log, move event to `errors/`. `lastIndexedSha` doesn't advance.
- Oversized files (above `maxFileSizeBytes`): metadata-only indexing.

**MCP server:**
- Query against failed repo: clear error naming repo and failure reason.
- Query during indexing: DB is transactionally consistent; in-progress indexing invisible until commit.

### Observability

1. **`get_index_health` MCP tool** — described above
2. **Structured log file** — JSON-line log at `~/.source-code-indexer/logs/indexer.log`. Every failure gets a structured entry. Rotated by size (default 50MB, configurable).
3. **Error summary on startup** — if unprocessed events exist in `errors/`, log count and affected repos to stderr.

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

Single-node PostgreSQL handles this comfortably.

### Performance mitigations

1. **Initial index:** batch inserts (`COPY` or multi-row `INSERT`), parallelized across repos via configurable thread pool. Target: 1M LOC in under 10 minutes.
2. **Full-text search:** GIN index on `search_vector`. Files over size threshold get metadata-only indexing.
3. **Symbol queries:** composite indexes on `(name, kind)` and `(file_id, kind)`.
4. **Incremental re-indexing:** event-driven, only changed files. Typical commit is sub-second to re-index.

### What we do NOT add

No sharding, read replicas, connection pooling, or caching layer. At this scale, single PostgreSQL with buffer cache is sufficient. MCP query pattern is low-QPS.

## 8. Deployment & Cost

### Docker Compose (cloud-agnostic)

Two containers:
1. **`indexer`** — Java MCP server with Tree-sitter native libs and git client
2. **`postgres`** — Stock PostgreSQL 16

Runs identically on GCP (GCE/Cloud Run), AWS (EC2/ECS), or local.

### Cost profile

| Resource | Sizing | Monthly cost |
|----------|--------|-------------|
| Compute (e2-standard-2 / t3.medium) | 2 vCPU, 4-8GB RAM | $30-60 |
| Persistent disk | 50-100GB SSD | $5-15 |
| Network (git fetch) | Delta fetches only | ~$1-2 |
| **Total** | | **~$35-80/month** |

### Cost optimizations

- Preemptible/spot instances: file-based queue makes this safe
- Smaller VM with slower initial indexing (incremental updates are cheap)
- Shared PostgreSQL instance: skip the second container

### MCP transport

- **Local dev:** stdio (Claude Code launches as subprocess)
- **Cloud:** SSE endpoint on configurable port. Config toggle between transports.

## 9. Testing Strategy

### Unit tests (JUnit 5 + Mockito)

- Tree-sitter query files: given known source files per language, assert correct symbol extraction
- Event file parsing and deduplication
- Language detection
- Config parsing and validation
- SQL query builders for each MCP tool

### Integration tests (Testcontainers + PostgreSQL)

- Full indexing pipeline: clone test repo, index, verify DB state
- Incremental indexing: modify files, process event, verify delta update
- Full-text search accuracy
- Each MCP tool against known DB state
- Flyway migrations against fresh database

### End-to-end tests

Curated test repo (git bundle in test resources) with files in all four core languages plus one unsupported language:
- First boot: config -> clone -> full index -> query -> verify
- Hook simulation: write event files, verify pickup and processing
- Degradation: unsupported language gets metadata + text, no symbols
- Error paths: malformed events in `errors/`, oversized files metadata-only
- Health tool: `get_index_health` reflects actual state

### Not tested

- Real git hook installation into remote repos (too environment-dependent)
- Tree-sitter native library loading (build/packaging concern)
