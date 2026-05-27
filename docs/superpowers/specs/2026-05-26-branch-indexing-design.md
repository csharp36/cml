# Branch-Aware Indexing — Copy-on-Write Branch Strategy

**Date:** 2026-05-26
**Status:** Approved
**Scope:** Multi-branch indexing with copy-on-write deltas, TTL cleanup, synchronous fault-in, and branch-aware MCP tools

## 1. Problem

The indexer currently tracks one branch per repository (typically `main`). In enterprise environments, developers work on feature branches and merge via PR — they rarely push directly to main. A developer on `feature/new-auth` gets index results from `main`, where symbol locations, line numbers, and file structures may not match their working copy. This makes the indexer unreliable for the most common workflow.

## 2. Goal

Make the indexer branch-aware using a copy-on-write model: `main` is the full base index, feature branches store only their delta (changed files). This keeps storage and indexing cost proportional to branch divergence, not total repo size. Branches are automatically indexed on push, cleaned up via TTL, and rebuilt on demand via synchronous fault-in.

## 3. Design Decisions

- **Copy-on-write model** — Only files that differ from main are stored per branch. Symbols, imports, and contents cascade from file records, so branch-specific files automatically get branch-specific children.
- **Automatic indexing** — Every pushed branch is indexed automatically via the existing webhook pipeline. Branch delta indexing is sub-second (only changed files are parsed), making this cheap enough for 10-50 concurrent branches.
- **Single schema with branch column** — Add `branch` to the `files` table rather than creating parallel overlay tables. Simpler migration, fewer tables, same copy-on-write effect.
- **Database-level merging** — Branch overlay merged with main via `DISTINCT ON` in SQL, not application-level merging. Keeps QueryExecutor thin and lets Postgres optimize.
- **TTL with synchronous fault-in** — Branch data expires after configurable inactivity period (default 14 days). When a query arrives for an expired branch, the server re-indexes synchronously before returning results. Correctness over speed — returning stale/wrong data is worse than a 1-2 second wait.
- **Optional `branch` parameter on all MCP tools** — Defaults to the repo's configured branch (usually `main`). Fully backward compatible — existing clients see no change.
- **Local branches (unpushed)** — Not handled server-side. The LLM can `git diff --name-only main...HEAD` locally and read changed files directly, using the index for the rest of the codebase. This is a client-side concern, not a server feature.

## 4. Schema Changes

### Flyway Migration V2

**Add `branch` column to `files`:**

```sql
ALTER TABLE files ADD COLUMN branch TEXT NOT NULL DEFAULT 'main';

-- Replace unique constraint to include branch
ALTER TABLE files DROP CONSTRAINT files_repo_id_path_key;
ALTER TABLE files ADD CONSTRAINT files_repo_id_branch_path_key UNIQUE(repo_id, branch, path);

-- Index for branch queries
CREATE INDEX idx_files_branch ON files(repo_id, branch);
```

**Add `branch` column to `indexing_events`:**

```sql
ALTER TABLE indexing_events ADD COLUMN branch TEXT NOT NULL DEFAULT 'main';
```

**Create `branch_index` tracking table:**

```sql
CREATE TABLE branch_index (
    id               SERIAL PRIMARY KEY,
    repo_id          INT NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    branch           TEXT NOT NULL,
    base_sha         TEXT NOT NULL,
    indexed_sha      TEXT NOT NULL,
    indexed_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_accessed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(repo_id, branch)
);

CREATE INDEX idx_branch_index_ttl ON branch_index(last_accessed_at);
```

**No changes to:** `symbols`, `imports`, `type_relationships`, `file_contents` — they cascade from `files` via `file_id`.

### Table Relationships After Migration

```
repositories (1) ──── (*) files (with branch column)
                              │
                    ┌─────────┼─────────────┐──────────┐
                    │         │             │          │
                symbols   imports   type_relationships  file_contents

repositories (1) ──── (*) branch_index (tracking per-branch state)
```

## 5. Query Pattern

All existing queries join through `files`. The branch overlay is implemented as a CTE that gives branch-specific files priority over main:

```sql
WITH effective_files AS (
    SELECT DISTINCT ON (f.repo_id, f.path)
           f.id, f.repo_id, f.path, f.language, f.size_bytes,
           f.last_commit_sha, f.last_modified_at, f.branch
    FROM files f
    JOIN repositories r ON f.repo_id = r.id
    WHERE r.name = :repo
      AND f.branch IN (:branch, 'main')
    ORDER BY f.repo_id, f.path,
             CASE WHEN f.branch = :branch THEN 0 ELSE 1 END
)
SELECT s.name, s.kind, s.signature, ...
FROM symbols s
JOIN effective_files f ON s.file_id = f.id
WHERE ...
```

**Behavior:**
- If a file exists on both the branch and main, the branch version wins (priority 0 vs 1)
- If a file only exists on main, main's version is used
- If `branch` is `main`, `IN ('main', 'main')` degrades to current behavior — no performance impact

**Implementation:** Extract the CTE into a helper method in `QueryExecutor` (e.g., `effectiveFilesCte(String repo, String branch)`) so all 10 tool queries use it consistently.

**When `branch` parameter is omitted:** defaults to the repo's configured branch (usually `main`). Existing behavior is completely unchanged.

## 6. Branch Indexing Pipeline

### Trigger

Git hooks already fire for all pushes, not just main. The webhook payload includes the branch ref.

### Process

1. Webhook receives push event for branch `feature/new-auth`
2. Event inserted into `indexing_events` with `branch = 'feature/new-auth'`
3. `EventQueuePoller` picks up the event. For non-main branches:
   a. `git fetch origin` (fast — already up to date from recent push)
   b. `git diff --name-only main...<branch-sha>` to get list of changed files
   c. For each changed file: read content via `git show <branch-sha>:<filepath>` (no checkout needed — working tree stays on main)
   d. Parse with Tree-sitter, extract symbols/imports
   e. Upsert into `files` with `branch = 'feature/new-auth'` — symbols/imports/contents cascade from file records
   f. Upsert `branch_index` record: `base_sha` = main HEAD, `indexed_sha` = branch HEAD, `indexed_at` = now, `last_accessed_at` = now
4. Done. Sub-second for typical branches (5-20 changed files)

### File Deletion on Branch

If a branch deletes a file that exists on main, the overlay query handles this naturally — the branch has no record for that path, so main's version appears. This is correct: the branch removed the file, but the tool should show what main has (since the branch doesn't override it).

If we need to explicitly track deletions (file exists on main but was deleted on branch), a future enhancement could add a `deleted` flag to `files`. For v1, this edge case is acceptable — the developer's LLM knows locally which files they deleted.

## 7. TTL Cleanup

A scheduled task runs periodically (configurable, default every 24 hours):

```
FOR each branch_index WHERE last_accessed_at < NOW() - ttl_days:
    DELETE FROM files WHERE repo_id = :repo_id AND branch = :branch
    -- symbols, imports, type_relationships, file_contents cascade via ON DELETE CASCADE
    DELETE FROM branch_index WHERE id = :id
    LOG "Cleaned up branch index for :branch on :repo_name"
```

The main branch is never cleaned up (it has no `branch_index` entry — it's tracked via `repositories.last_indexed_sha`).

## 8. Synchronous Fault-In

When a query arrives with `branch = 'feature/new-auth'` and no `branch_index` record exists:

1. QueryExecutor checks `branch_index` for `(repo_id, branch)`
2. Not found → check if branch exists on remote: `git branch -r --list origin/feature/new-auth`
3. If branch exists on remote:
   a. Run the branch indexing pipeline synchronously (same logic as section 6, steps 3a-3f)
   b. Proceed with the query using the freshly indexed data
   c. Return results
4. If branch doesn't exist on remote: fall back to main-only results, no error

**Latency:** 1-2 seconds for the fault-in (dominated by git operations + Tree-sitter parsing of changed files). Subsequent queries are instant.

**Access tracking:** Every query that uses branch data updates `branch_index.last_accessed_at`, resetting the TTL clock.

## 9. Configuration

Add to `config.yaml`:

```yaml
branches:
  autoIndex: true           # Index branches automatically on push
  ttlDays: 14               # Days of inactivity before branch data is cleaned up
  cleanupIntervalHours: 24  # How often the cleanup task runs
```

All fields have defaults and are optional. Existing configs without a `branches` section get the defaults.

## 10. Impact on check_sync

The `check_sync` tool spec (2026-05-26-check-sync-design.md) needs these updates:

**New parameter:** `branch` (optional, defaults to repo's configured branch)

**Logic change:**
- If branch is main: compare `local_sha` against `repositories.last_indexed_sha` (current behavior)
- If branch is a feature branch: look up `branch_index` for that repo + branch. Compare `local_sha` against `branch_index.indexed_sha`
- If no `branch_index` record: trigger synchronous fault-in, then compare

**Response adds `branch` field:**
```json
{
  "repo_name": "backend-api",
  "branch": "feature/new-auth",
  "status": "in_sync",
  "local_sha": "abc123",
  "indexed_sha": "abc123",
  "indexed_at": "2026-05-26T10:30:00Z",
  "message": "Your local repo matches the index."
}
```

## 11. Impact on MCP Tools

All 10 existing tools gain an optional `branch` parameter. When omitted, defaults to the repo's configured branch (main). This is backward compatible — existing clients see no behavior change.

| Tool | Branch parameter | Notes |
|------|-----------------|-------|
| `search_symbols` | optional | Scoped to effective files for branch |
| `get_symbol_detail` | optional | Returns symbol from branch file if it exists |
| `find_implementations` | optional | Searches branch + main overlay |
| `find_references` | optional | Searches imports in branch + main |
| `search_code` | optional | Full-text search across branch + main |
| `search_files` | optional | File listing from branch + main |
| `get_repo_summary` | optional | Summary includes branch file count |
| `get_file_summary` | optional | Returns branch version if file changed on branch |
| `get_directory_tree` | optional | Directory listing from branch + main |
| `get_index_health` | no change | System-wide health, not branch-specific |
| `check_sync` | optional | Compares against branch-specific indexed SHA |

## 12. Impact on connect-index Skill

The skill should pass `git branch --show-current` along with `git rev-parse HEAD` when calling `check_sync`. If on a feature branch, subsequent tool calls should include the `branch` parameter.

## 13. Impact on Admin API and Admin UI

**Admin API:**
- `GET /admin/repos` response should include active branch count per repo (from `branch_index`)
- `DELETE /admin/repos/:name` already cascades — branch data is cleaned up automatically

**Admin UI:**
- Dashboard tab: show branch count alongside repo stats
- No new UI needed for v1 — branch management is automatic

## 14. Files Changed

| File | Change |
|------|--------|
| `src/main/resources/db/migration/V2__branch_indexing.sql` | New migration: branch column, branch_index table |
| `src/main/java/com/indexer/mcp/QueryExecutor.java` | Add effective_files CTE helper, branch param to all queries |
| `src/main/java/com/indexer/mcp/McpServerBootstrap.java` | Add branch param to all tool schemas |
| `src/main/java/com/indexer/model/SourceFile.java` | Add branch field |
| `src/main/java/com/indexer/db/FileDao.java` | Branch-aware upsert |
| `src/main/java/com/indexer/db/BranchIndexDao.java` | New DAO for branch_index table |
| `src/main/java/com/indexer/model/BranchIndex.java` | New record |
| `src/main/java/com/indexer/indexing/IndexingPipeline.java` | Branch-aware indexing (git show, delta only) |
| `src/main/java/com/indexer/indexing/BranchCleanupTask.java` | New scheduled task for TTL cleanup |
| `src/main/java/com/indexer/config/BranchConfig.java` | New config record |
| `src/main/java/com/indexer/config/AppConfig.java` | Add BranchConfig |
| `src/main/java/com/indexer/webhook/WebhookPayload.java` | Add branch field |
| `skills/connect-index.md` | Pass branch to check_sync |
| `CLAUDE.md` | Document branch support |
| Tests | Integration tests for branch queries, fault-in, cleanup |
