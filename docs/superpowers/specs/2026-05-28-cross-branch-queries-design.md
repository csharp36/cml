# Phase D: Cross-Branch Query Tools — Design Spec

## Overview

Two new dedicated MCP tools for cross-branch analysis: `diff_branches` (compare two branches at file or symbol granularity) and `search_branches` (fan-out symbol search across multiple branches). Query-layer only — no new tables, no schema changes. Uses existing copy-on-write branch indexing data.

**Depends on:** Phase B (Identity/AuthZ) for `executeQuery` pipeline, branch indexing infrastructure.

**Gate:** An LLM can answer "What symbols differ between release/q1 and main?" and "Which branches touch PaymentProcessor?" using the two new tools.

---

## Tool 1: `diff_branches`

Compare two branches and show what's different.

### Parameters

| Parameter | Type | Required | Default |
|-----------|------|----------|---------|
| `repo` | string | yes | - |
| `branch_a` | string | yes | - |
| `branch_b` | string | yes | - |
| `detail` | string | no | `"symbols"` — accepts `"files"` or `"symbols"` |
| `limit` | int | no | 100 |

### File-Level Diff (`detail: "files"`)

Computes the effective file set for each branch using the existing `DISTINCT ON` overlay pattern (branch files overlaid on main), then compares by path.

- **Added:** file exists in branch_a effective set but not branch_b
- **Removed:** file exists in branch_b effective set but not branch_a
- **Modified:** file exists in both but has different `last_commit_sha`

Convention: branch_a is the "new" branch (like the right side of a diff).

Returns:
```json
{
  "added": [{"path": "src/Payment.java", "language": "java"}],
  "removed": [{"path": "src/OldService.java", "language": "java"}],
  "modified": [{"path": "src/Gateway.java", "language": "java"}]
}
```

### Symbol-Level Diff (`detail: "symbols"`)

Same effective-file overlay, but joins to the symbols table. Compares symbols by `(file_path, name, kind)` tuple.

- **Added:** symbol in branch_a effective files but not branch_b
- **Removed:** symbol in branch_b effective files but not branch_a
- **Modified:** symbol in both but different `signature`, `start_line`, or `end_line`

Returns:
```json
{
  "added": [{"name": "processRefund", "kind": "method", "file_path": "src/Payment.java", "signature": "void processRefund(String orderId)"}],
  "removed": [],
  "modified": [{"name": "charge", "kind": "method", "file_path": "src/Gateway.java",
                 "branch_a_signature": "void charge(BigDecimal amount, String currency)",
                 "branch_b_signature": "void charge(double amount)"}]
}
```

### SQL Strategy

Two CTEs (one per branch, each using the `DISTINCT ON` overlay pattern), then a `FULL OUTER JOIN` on the comparison key. Single query.

For file-level:
```sql
WITH effective_a AS (
    SELECT DISTINCT ON (repo_id, path) id, path, language, last_commit_sha
    FROM files WHERE repo_id = :repoId AND branch IN (:branchA, 'main')
    ORDER BY repo_id, path, CASE WHEN branch = :branchA THEN 0 ELSE 1 END
),
effective_b AS (
    SELECT DISTINCT ON (repo_id, path) id, path, language, last_commit_sha
    FROM files WHERE repo_id = :repoId AND branch IN (:branchB, 'main')
    ORDER BY repo_id, path, CASE WHEN branch = :branchB THEN 0 ELSE 1 END
)
SELECT a.path AS a_path, a.language AS a_lang, a.last_commit_sha AS a_sha,
       b.path AS b_path, b.language AS b_lang, b.last_commit_sha AS b_sha
FROM effective_a a
FULL OUTER JOIN effective_b b ON a.path = b.path
WHERE a.path IS NULL OR b.path IS NULL OR a.last_commit_sha != b.last_commit_sha
LIMIT :limit
```

For symbol-level, the CTEs join through to symbols and the comparison key is `(path, symbol_name, symbol_kind)`.

### Branch Fault-In

Both branches trigger `ensureBranchIndexed` before the query. If either branch doesn't exist on the remote, return error: "Branch 'X' not found or not indexed."

---

## Tool 2: `search_branches`

Search for a symbol across multiple or all indexed branches.

### Parameters

| Parameter | Type | Required | Default |
|-----------|------|----------|---------|
| `repo` | string | yes | - |
| `query` | string | yes | - (regex pattern for symbol name) |
| `kind` | string | no | all kinds |
| `branch_pattern` | string | no | `".*"` (all indexed branches) |
| `max_branches` | int | no | 50 |
| `limit` | int | no | 20 (per branch) |

### Behavior

1. Query `branch_index` for branches matching `branch_pattern` (PostgreSQL `~` regex), capped at `max_branches`
2. Single SQL query: symbols joined to files, filtered by symbol name regex and matching branches, grouped by branch
3. Always include `main` in results as baseline

### Returns

```json
{
  "branches_searched": 12,
  "branches_matched": 3,
  "results": [
    {
      "branch": "main",
      "symbols": [
        {"name": "PaymentProcessor", "kind": "class", "file_path": "src/Payment.java", "signature": "public class PaymentProcessor"}
      ]
    },
    {
      "branch": "feature/refund-flow",
      "symbols": [
        {"name": "PaymentProcessor", "kind": "class", "file_path": "src/Payment.java", "signature": "public class PaymentProcessor"},
        {"name": "PaymentProcessorV2", "kind": "class", "file_path": "src/PaymentV2.java", "signature": "public class PaymentProcessorV2"}
      ]
    }
  ]
}
```

### Semantics

This searches branch delta files only — not the full overlay. A symbol that exists unchanged on main will not appear in a feature branch's results. Only symbols in files that the branch actually changed are returned. Main's results come from main-branch files directly.

This is correct for the use case: "which branches have *changes* involving this symbol."

### SQL Strategy

Single query joining `symbols -> files -> repositories`, with `files.branch` filtered by the matching branches from `branch_index`. Main is included via `OR` condition.

```sql
WITH matching_branches AS (
    SELECT branch FROM branch_index
    WHERE repo_id = :repoId AND branch ~ :branchPattern
    LIMIT :maxBranches
)
SELECT f.branch, s.name, s.kind, s.signature, f.path AS file_path
FROM symbols s
JOIN files f ON s.file_id = f.id
WHERE f.repo_id = :repoId
  AND s.name ~* :query
  AND (f.branch = 'main' OR f.branch IN (SELECT branch FROM matching_branches))
ORDER BY f.branch, s.name
```

Per-branch results are capped at `limit` in the Java layer after grouping.

### No Branch Fault-In

`search_branches` only searches already-indexed branches. It does not fault-in branches that haven't been indexed — that would be a potentially expensive side effect for a search tool.

---

## Integration

### QueryExecutor

Two new methods:
- `diffBranches(String repo, String branchA, String branchB, String detail, int limit)` — returns `Map<String, Object>` with added/removed/modified lists
- `searchBranches(String repo, String query, String kind, String branchPattern, int maxBranches, int limit)` — returns `Map<String, Object>` with branches_searched, branches_matched, results

Both use `ensureBranchIndexed` (diff_branches only) and the existing `Jdbi` handle for SQL.

### McpServerBootstrap

Two new tool definitions and handlers following the existing pattern:
- `diffBranchesTool()` / `handleDiffBranches()`
- `searchBranchesTool()` / `handleSearchBranches()`

Registered in both `buildServer` (stdio) and `startHttp` (streamable HTTP). Tool count goes from 13 to 15.

### Authorization

Both tools require `repo` parameter and go through `executeQuery`. OAuth repo-level permissions, audit logging, and "no audit, no access" all apply automatically.

### Error Handling

- Repo not found: standard error from query layer
- Branch not indexed (diff_branches): attempt fault-in, error if remote branch doesn't exist
- No branches match pattern (search_branches): return `{"branches_searched": 0, "branches_matched": 0, "results": []}`
- `branch_a == branch_b` in diff: return empty diff (no error — it's a valid no-op)

---

## Scope Boundaries

**In scope:**
- `diff_branches` MCP tool (file and symbol granularity)
- `search_branches` MCP tool (fan-out with pattern matching)
- QueryExecutor methods for both tools
- McpServerBootstrap registration

**Out of scope:**
- New database tables or schema changes
- Branch management tools (create, delete)
- Semantic/call-graph diff (deferred to Phase E / SCIP)
- UI for cross-branch comparison
