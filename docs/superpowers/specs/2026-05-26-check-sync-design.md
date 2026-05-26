# Check Sync — Local/Index SHA Mismatch Detection

**Date:** 2026-05-26
**Status:** Approved
**Scope:** New `check_sync` MCP tool + connect-index skill update

## 1. Problem

When multiple developers share an indexed repository, the index updates when any developer pushes. Other developers with older local copies then have a mismatch: the index reflects newer code than their working copy. Symbol locations, line numbers, and file structures returned by the indexer don't match what they see locally. Without detection, developers get silently wrong results.

## 2. Goal

Add a `check_sync` MCP tool that compares a developer's local HEAD SHA against the indexed SHA and returns sync status with a recommended action. Any MCP client (Claude Code, Codex, Cursor, etc.) can call it — no vendor-specific logic required.

Update the `connect-index` Claude Code skill to call `check_sync` automatically on connect as a convenience layer.

## 3. Design Decisions

- **Dedicated MCP tool** over embedding in existing tools — more discoverable, clearer purpose, LLM-agnostic.
- **Client passes `local_sha`** — the server can't know the developer's local HEAD. The LLM runs `git rev-parse HEAD` and passes the result.
- **Single repo per call** — simpler interface. MCP calls are cheap; batch support is unnecessary complexity.
- **Two statuses: `in_sync` / `out_of_sync`** — determining direction (behind/ahead/diverged) would require git operations on the server clone. Simple equality check is sufficient; the recommended action covers both directions.
- **Recommended action in response** — makes the tool self-contained. LLMs that don't know git can still relay useful advice.

## 4. MCP Tool: `check_sync`

### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `repo_name` | string | yes | Name of the repository to check |
| `local_sha` | string | yes | Developer's local HEAD SHA (from `git rev-parse HEAD`) |

### Tool Description

> Check whether a local repository is in sync with the indexed version. Pass the repo name and your local HEAD SHA (from `git rev-parse HEAD`). Returns sync status and recommended action if out of sync.

### Response: `in_sync`

```json
{
  "repo_name": "backend-api",
  "status": "in_sync",
  "local_sha": "abc1234def5678",
  "indexed_sha": "abc1234def5678",
  "indexed_at": "2026-05-26T10:30:00Z",
  "message": "Your local repo matches the index."
}
```

### Response: `out_of_sync`

```json
{
  "repo_name": "backend-api",
  "status": "out_of_sync",
  "local_sha": "def789",
  "indexed_sha": "abc123",
  "indexed_at": "2026-05-26T10:30:00Z",
  "message": "Your local repo does not match the index.",
  "action": "Run 'git pull' to sync, or push your changes to trigger re-indexing."
}
```

### Response: `not_indexed`

When the repository exists in the DB but `last_indexed_sha` is null (initial clone still in progress):

```json
{
  "repo_name": "backend-api",
  "status": "not_indexed",
  "local_sha": "def789",
  "indexed_sha": null,
  "indexed_at": null,
  "message": "Repository exists but has not been indexed yet."
}
```

### Error: Repository Not Found

```json
{
  "error": "Repository 'foo' not found in index"
}
```

## 5. Implementation

### QueryExecutor.java

Add method `checkSync(String repoName, String localSha)`:
- Queries `RepositoryDao.findByName(repoName)`
- If not found, returns error map
- If `lastIndexedSha` is null, returns `not_indexed` status
- Compares `localSha` against `lastIndexedSha` (case-insensitive prefix match — full vs. abbreviated SHAs)
- Returns appropriate status map with message and action

**SHA comparison note:** Git SHAs may be passed as full (40-char) or abbreviated (7+ char). The comparison should handle this by checking if either SHA is a prefix of the other, not requiring exact equality. This makes the tool forgiving of clients that pass abbreviated SHAs.

### McpServerBootstrap.java

Register `check_sync` tool with:
- Two required string parameters: `repo_name`, `local_sha`
- Tool description as specified in section 4
- Delegates to `QueryExecutor.checkSync()`

### skills/connect-index.md

Add a sync check step after the existing "verify repo is indexed" step:

1. Run `git rev-parse HEAD` to get local SHA
2. Call `check_sync` with repo name and local SHA
3. If `out_of_sync`: warn the developer and suggest `git pull`
4. If `in_sync`: confirm sync status
5. If `not_indexed`: inform that indexing is still in progress

### Tests

Unit test class for `checkSync`:
- `in_sync` — matching SHAs
- `out_of_sync` — different SHAs
- `not_found` — unknown repo name
- `not_indexed` — repo exists, null SHA
- `abbreviated_sha` — 7-char SHA matches full 40-char SHA

## 6. Files Changed

| File | Change |
|------|--------|
| `src/main/java/com/indexer/query/QueryExecutor.java` | Add `checkSync()` method |
| `src/main/java/com/indexer/mcp/McpServerBootstrap.java` | Register `check_sync` tool |
| `skills/connect-index.md` | Add sync check step |
| `src/test/java/com/indexer/query/QueryExecutorCheckSyncTest.java` | Unit tests |
