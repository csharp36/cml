# Admin API Design

**Date:** 2026-05-25
**Status:** Approved
**Scope:** REST API at `/admin/*` for managing repositories, monitoring health, and operating the indexer

## 1. Goal

Expose a REST API for operational management of the indexer: adding/removing repositories at runtime, triggering reindexing, monitoring health, viewing event history, and retrying failures. This API serves both human operators and the Phase 2 admin UI.

## 2. Architecture

```
Admin UI / curl  ──HTTP──▶  AdminApi (route handlers)
                                 │
                           Bearer token auth
                                 │
                            AdminService
                           ┌─────┼─────┐
                           │     │     │
                 RepositoryManager  │  QueryExecutor
                           │     │     │
                        DAOs  IndexingPipeline
                           │
                       PostgreSQL
```

Three new components:

- **`AdminService`** (`com.indexer.admin`) — Business logic for all admin operations. Owns an `ExecutorService` for background work (clone, index). Tracks in-progress operations in a `ConcurrentHashMap<String, OperationStatus>`.
- **`AdminApi`** (`com.indexer.admin`) — HTTP layer. Registers 7 routes under `/admin/*` on the shared Javalin app. Bearer token auth via `before("/admin/*")` filter. Parses requests, calls `AdminService`, returns JSON.
- **RepositoryManager additions** — New public methods `addRepository()` and `deleteRepository()` extending the repo lifecycle it already manages.

Wiring in `Application.java`:
- Create `AdminService` with all dependencies
- Create `AdminApi` with `AdminService` and admin token from config
- Call `adminApi.registerRoutes(httpServer.getApp())` before `httpServer.start()`

## 3. Endpoints

| Method | Path | Purpose | Response Code |
|--------|------|---------|---------------|
| `GET` | `/admin/health` | System health stats | 200 |
| `GET` | `/admin/repos` | List all repos with stats | 200 |
| `POST` | `/admin/repos` | Add a new repo (async clone + index) | 202 |
| `DELETE` | `/admin/repos/:name` | Purge repo (DB + disk) | 200 |
| `POST` | `/admin/repos/:name/reindex` | Trigger full reindex (async) | 202 |
| `GET` | `/admin/events` | Query event history | 200 |
| `POST` | `/admin/events/:id/retry` | Retry a failed event | 200 |

### POST /admin/repos

Request body:
```json
{
  "url": "git@github.com:org/repo.git",
  "branch": "main",
  "auth": { "type": "ssh-key", "keyPath": "~/.ssh/id_ed25519" }
}
```

Response (202):
```json
{
  "name": "repo",
  "status": "cloning"
}
```

The server uses its own credentials referenced by the `auth` block — the admin is telling the server *which* credential to use, not providing the credential itself. SSH keys, tokens, and Vault paths must be accessible to the server process.

### GET /admin/repos

Response (200):
```json
[
  {
    "name": "repo-name",
    "url": "git@github.com:org/repo.git",
    "branch": "main",
    "fileCount": 342,
    "lastIndexedSha": "abc123",
    "lastIndexedAt": "2026-05-25T12:00:00Z",
    "status": "ready"
  }
]
```

Repo status values: `ready`, `cloning`, `indexing`, `error`.

### DELETE /admin/repos/:name

Full purge: deletes all DB records (repository, files, symbols, imports, type_relationships, events) and removes the cloned repo from disk.

Response (200):
```json
{
  "deleted": "repo-name"
}
```

### POST /admin/repos/:name/reindex

Triggers a background full reindex. Fetches latest from remote first.

Response (202):
```json
{
  "name": "repo-name",
  "status": "indexing"
}
```

### GET /admin/events

Query params: `?repo=name&status=failed&since=2026-05-25T00:00:00Z&limit=50`

All params are optional. Default limit is 50.

Response (200):
```json
[
  {
    "id": 42,
    "repoName": "backend-api",
    "eventType": "post-commit",
    "status": "failed",
    "errorMessage": "Git fetch failed: authentication error",
    "createdAt": "2026-05-25T11:30:00Z"
  }
]
```

### POST /admin/events/:id/retry

Resets a failed event to `pending` so the `EventQueuePoller` picks it up again. Only works on events with status `failed`.

Response (200):
```json
{
  "id": 42,
  "status": "pending"
}
```

## 4. AdminService

### Background Operations

`AdminService` owns a single-thread `ExecutorService` for background work and tracks in-progress operations in a `ConcurrentHashMap<String, OperationStatus>` keyed by repo name.

```java
public record OperationStatus(String status, String message) {}
```

**`addRepository(url, branch, authConfig)`:**
1. Validates URL isn't already registered (checks `RepositoryDao.findByName()`). Returns 409 if duplicate.
2. Sets in-memory status to `cloning`.
3. Submits background task: `RepositoryManager.addRepository()` (clone → install hooks → DB insert) → `IndexingPipeline.fullIndex()` → update status to `ready`.
4. On failure: updates status to `error` with message.
5. Returns immediately with 202.

**`triggerReindex(name)`:**
1. Checks repo exists in DB. Returns 404 if not found.
2. Sets in-memory status to `indexing`.
3. Submits background task: git fetch → `IndexingPipeline.fullIndex()` → update status to `ready`.
4. On failure: updates status to `error` with message.
5. Returns immediately with 202.

### Synchronous Operations

**`deleteRepository(name)`:** Calls `RepositoryManager.deleteRepository()` which cascades through all related DB tables and deletes the clone from disk. Clears any in-memory status. Returns 404 if repo not found.

**`listRepositories()`:** Queries `RepositoryDao.findAll()`, enriches each with file count from `FileDao.countByRepo()` and in-memory status. Status is `ready` if no in-memory operation is active and `lastIndexedSha` is non-null.

**`getHealth()`:** Delegates to `QueryExecutor.getIndexHealth()`.

**`listEvents(repo, status, since, limit)`:** Calls new `EventDao.findFiltered()` with optional WHERE clauses.

**`retryEvent(id)`:** Loads event, verifies status is `failed` (returns 400 if not), calls new `EventDao.resetToPending(id)`. Returns 404 if event not found.

## 5. RepositoryManager Additions

Two new public methods:

**`addRepository(String url, String branch, IndexerConfig.AuthConfig authConfig, String cloneBaseDir)`:**
- Resolves auth via `AuthProviderRegistry`
- Clones repo via `GitOperations`
- Installs hooks via `HookInstaller`
- Inserts into DB via `RepositoryDao`
- Returns the `Repository` record

This extracts and publicizes the logic currently in the private `initializeRepository()` method.

**`deleteRepository(String name)`:**
- Loads repo from DB via `RepositoryDao.findByName()`. Throws if not found.
- For each file in the repo (`FileDao.findByRepo(repoId)`):
  - Delete symbols (`SymbolDao.deleteSymbolsByFileId()`)
  - Delete imports (`SymbolDao.deleteImportsByFileId()`)
- Delete all files (`FileDao` — new `deleteByRepoId(repoId)`)
- Delete all events (`EventDao.deleteByRepoName(name)`)
- Delete the repository record (`RepositoryDao.delete(name)`)
- Delete the clone directory from disk (`Files.walkFileTree` + delete)
- Order matters: delete children before parents to respect FK constraints.

## 6. DAO Additions

### FileDao

**`deleteByRepoId(int repoId)`:**
```sql
DELETE FROM files WHERE repo_id = :repoId
```

### EventDao

**`findFiltered(String repo, String status, Instant since, int limit)`:**
```sql
SELECT * FROM indexing_events
WHERE (:repo IS NULL OR repo_name = :repo)
  AND (:status IS NULL OR status = :status)
  AND (:since IS NULL OR created_at >= :since)
ORDER BY created_at DESC
LIMIT :limit
```

**`resetToPending(long eventId)`:**
```sql
UPDATE indexing_events
SET status = 'pending', error_message = NULL, started_at = NULL, completed_at = NULL, worker_id = NULL
WHERE id = :id AND status = 'failed'
```

**`deleteByRepoName(String repoName)`:**
```sql
DELETE FROM indexing_events WHERE repo_name = :repoName
```

**`findById(long id)`:**
```sql
SELECT * FROM indexing_events WHERE id = :id
```

## 7. Authentication

**Config:**

New `AdminConfig` record in `IndexerConfig`:
```java
public record AdminConfig(String token) {
    public AdminConfig {
        // token can be null — admin API is disabled
    }
}
```

YAML config:
```yaml
admin:
  token: ${ADMIN_TOKEN}
```

**Middleware:**

`AdminApi` registers a Javalin `before("/admin/*")` filter:
1. If no admin token is configured: return 503 with `"Admin API disabled — no admin token configured"`.
2. Extract `Authorization` header. If missing or not `Bearer <token>`: return 401.
3. Compare token using constant-time comparison (`MessageDigest.isEqual()`). If mismatch: return 401.
4. If valid: request proceeds to the route handler.

Non-admin routes (`/webhook`, `/mcp`, `/mcp/message`) are unaffected by this filter.

## 8. Error Handling

Consistent JSON error response format across all admin endpoints:
```json
{
  "error": "Repository not found: unknown-repo"
}
```

HTTP status codes:
| Code | Meaning |
|------|---------|
| 200 | Successful read/delete/retry |
| 202 | Accepted (async operation started) |
| 400 | Bad request (missing fields, invalid state for retry) |
| 401 | Unauthorized (missing/invalid bearer token) |
| 404 | Not found (unknown repo or event) |
| 409 | Conflict (duplicate repo URL) |
| 503 | Admin API disabled (no token configured) |

## 9. Testing

### Unit Tests

**`AdminServiceTest`:** Mock all dependencies. Test each operation:
- `addRepository` — validates input, rejects duplicate URLs, submits background task
- `deleteRepository` — delegates to RepositoryManager, returns appropriate error for unknown repos
- `triggerReindex` — rejects unknown repos, submits background task
- `listRepositories` — combines DAO data with in-memory status
- `listEvents` — passes filter params to DAO
- `retryEvent` — rejects non-failed events, resets to pending

**`AdminApiTest`:** Javalin TestTools. Test HTTP concerns:
- Auth: missing token → 401, invalid token → 401, valid token → passes through
- Admin disabled (no token configured) → 503
- Route mapping: each endpoint returns expected status codes
- JSON serialization of responses
- Path param / query param parsing

### Integration Test

**`AdminIntegrationTest`:** Testcontainers PostgreSQL. Start real HttpServer with AdminApi. Test full flow:
- `GET /admin/health` returns valid health data
- `GET /admin/repos` returns empty list, then add a repo, list shows it
- `DELETE /admin/repos/:name` purges, subsequent GET returns 404
- `GET /admin/events?status=failed` returns filtered results
- `POST /admin/events/:id/retry` resets event status

Background clone/index testing is deferred since it requires a real git repo. Integration test verifies the 202 response and status tracking without waiting for background completion.

## 10. Files Changed

| File | Change |
|------|--------|
| `src/main/java/com/indexer/admin/AdminService.java` | **New** |
| `src/main/java/com/indexer/admin/AdminApi.java` | **New** |
| `src/main/java/com/indexer/config/IndexerConfig.java` | **Modified** — add `AdminConfig` |
| `src/main/java/com/indexer/config/ConfigLoader.java` | **Modified** — parse `admin` section |
| `src/main/java/com/indexer/repository/RepositoryManager.java` | **Modified** — add `addRepository()`, `deleteRepository()` |
| `src/main/java/com/indexer/db/FileDao.java` | **Modified** — add `deleteByRepoId()` |
| `src/main/java/com/indexer/db/EventDao.java` | **Modified** — add `findFiltered()`, `resetToPending()`, `deleteByRepoName()`, `findById()` |
| `src/main/java/com/indexer/Application.java` | **Modified** — create and wire AdminService + AdminApi |
| `src/test/java/com/indexer/admin/AdminServiceTest.java` | **New** |
| `src/test/java/com/indexer/admin/AdminApiTest.java` | **New** |
| `src/test/java/com/indexer/admin/AdminIntegrationTest.java` | **New** |
| `src/test/java/com/indexer/config/ConfigLoaderTest.java` | **Modified** |
| `CLAUDE.md` | **Modified** — document admin API |
