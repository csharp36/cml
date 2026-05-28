# Phase D: Cross-Branch Query Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two new MCP tools for cross-branch analysis: `diff_branches` (compare two branches at file or symbol level) and `search_branches` (fan-out symbol search across indexed branches).

**Architecture:** Query-layer only — no schema changes. Both tools add methods to `QueryExecutor` using existing `files`, `symbols`, and `branch_index` tables with `DISTINCT ON` overlay CTEs. Registered in `McpServerBootstrap` following the existing tool pattern.

**Tech Stack:** Java 21, JDBI, PostgreSQL 16 (`DISTINCT ON`, `FULL OUTER JOIN`, `~` regex)

**Spec:** `docs/superpowers/specs/2026-05-28-cross-branch-queries-design.md`

---

### Task 1: `diff_branches` — File-Level Diff

**Files:**
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java`

- [ ] **Step 1: Add diffBranches method to QueryExecutor**

Add this method after `verifyAuditChain` and before the internal helpers section. This handles both `"files"` and `"symbols"` detail levels.

```java
/**
 * Compare two branches and return differences at file or symbol granularity.
 * Uses DISTINCT ON overlay for each branch to get effective file sets, then FULL OUTER JOIN to find diffs.
 */
public Map<String, Object> diffBranches(String repo, String branchA, String branchB, String detail, int limit) {
    String effectiveDetail = (detail != null && "files".equalsIgnoreCase(detail)) ? "files" : "symbols";
    ensureBranchIndexed(repo, branchA);
    ensureBranchIndexed(repo, branchB);

    return jdbi.withHandle(handle -> {
        // Resolve repo ID
        var optRepo = handle.createQuery("SELECT id FROM repositories WHERE name = :name")
                .bind("name", repo)
                .mapTo(Integer.class)
                .findOne();
        if (optRepo.isEmpty()) {
            return Map.<String, Object>of("error", "Repository '" + repo + "' not found");
        }
        int repoId = optRepo.get();

        if ("files".equals(effectiveDetail)) {
            return diffFiles(handle, repoId, branchA, branchB, limit);
        } else {
            return diffSymbols(handle, repoId, branchA, branchB, limit);
        }
    });
}

private Map<String, Object> diffFiles(org.jdbi.v3.core.Handle handle, int repoId,
                                       String branchA, String branchB, int limit) {
    var rows = handle.createQuery("""
            WITH effective_a AS (
                SELECT DISTINCT ON (path) path, language, last_commit_sha
                FROM files WHERE repo_id = :repoId AND branch IN (:branchA, 'main')
                ORDER BY path, CASE WHEN branch = :branchA THEN 0 ELSE 1 END
            ),
            effective_b AS (
                SELECT DISTINCT ON (path) path, language, last_commit_sha
                FROM files WHERE repo_id = :repoId AND branch IN (:branchB, 'main')
                ORDER BY path, CASE WHEN branch = :branchB THEN 0 ELSE 1 END
            )
            SELECT a.path AS a_path, a.language AS a_lang, a.last_commit_sha AS a_sha,
                   b.path AS b_path, b.language AS b_lang, b.last_commit_sha AS b_sha
            FROM effective_a a
            FULL OUTER JOIN effective_b b ON a.path = b.path
            WHERE a.path IS NULL OR b.path IS NULL OR a.last_commit_sha != b.last_commit_sha
            LIMIT :limit
            """)
            .bind("repoId", repoId)
            .bind("branchA", branchA)
            .bind("branchB", branchB)
            .bind("limit", limit)
            .mapToMap()
            .list();

    var added = new java.util.ArrayList<Map<String, Object>>();
    var removed = new java.util.ArrayList<Map<String, Object>>();
    var modified = new java.util.ArrayList<Map<String, Object>>();

    for (var row : rows) {
        if (row.get("b_path") == null) {
            added.add(Map.of("path", row.get("a_path"), "language", nullSafe(row.get("a_lang"))));
        } else if (row.get("a_path") == null) {
            removed.add(Map.of("path", row.get("b_path"), "language", nullSafe(row.get("b_lang"))));
        } else {
            modified.add(Map.of("path", row.get("a_path"), "language", nullSafe(row.get("a_lang"))));
        }
    }

    return Map.of("detail", "files", "branch_a", branchA, "branch_b", branchB,
            "added", added, "removed", removed, "modified", modified);
}

private static Object nullSafe(Object val) {
    return val != null ? val : "";
}
```

Note: `diffSymbols` will be added in Task 2. For now, the `else` branch in `diffBranches` will not compile — that's OK, we'll add it next.

Actually, to keep each task independently compilable, add a temporary stub:

```java
private Map<String, Object> diffSymbols(org.jdbi.v3.core.Handle handle, int repoId,
                                         String branchA, String branchB, int limit) {
    // TODO: Task 2 implements this
    return Map.of("detail", "symbols", "branch_a", branchA, "branch_b", branchB,
            "added", List.of(), "removed", List.of(), "modified", List.of());
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run all tests**

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/indexer/mcp/QueryExecutor.java
git commit -m "feat: add diffBranches method with file-level diff support"
```

---

### Task 2: `diff_branches` — Symbol-Level Diff

**Files:**
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java`

- [ ] **Step 1: Replace the diffSymbols stub with the real implementation**

Replace the `diffSymbols` method:

```java
private Map<String, Object> diffSymbols(org.jdbi.v3.core.Handle handle, int repoId,
                                         String branchA, String branchB, int limit) {
    var rows = handle.createQuery("""
            WITH effective_a AS (
                SELECT DISTINCT ON (f.path) f.id AS file_id, f.path
                FROM files f WHERE f.repo_id = :repoId AND f.branch IN (:branchA, 'main')
                ORDER BY f.path, CASE WHEN f.branch = :branchA THEN 0 ELSE 1 END
            ),
            effective_b AS (
                SELECT DISTINCT ON (f.path) f.id AS file_id, f.path
                FROM files f WHERE f.repo_id = :repoId AND f.branch IN (:branchB, 'main')
                ORDER BY f.path, CASE WHEN f.branch = :branchB THEN 0 ELSE 1 END
            ),
            syms_a AS (
                SELECT s.name, s.kind, s.signature, s.start_line, s.end_line, ea.path AS file_path
                FROM symbols s JOIN effective_a ea ON s.file_id = ea.file_id
            ),
            syms_b AS (
                SELECT s.name, s.kind, s.signature, s.start_line, s.end_line, eb.path AS file_path
                FROM symbols s JOIN effective_b eb ON s.file_id = eb.file_id
            )
            SELECT a.file_path AS a_file, a.name AS a_name, a.kind AS a_kind,
                   a.signature AS a_sig, a.start_line AS a_start, a.end_line AS a_end,
                   b.file_path AS b_file, b.name AS b_name, b.kind AS b_kind,
                   b.signature AS b_sig, b.start_line AS b_start, b.end_line AS b_end
            FROM syms_a a
            FULL OUTER JOIN syms_b b ON a.file_path = b.file_path AND a.name = b.name AND a.kind = b.kind
            WHERE a.name IS NULL OR b.name IS NULL
               OR a.signature != b.signature OR a.start_line != b.start_line OR a.end_line != b.end_line
            LIMIT :limit
            """)
            .bind("repoId", repoId)
            .bind("branchA", branchA)
            .bind("branchB", branchB)
            .bind("limit", limit)
            .mapToMap()
            .list();

    var added = new java.util.ArrayList<Map<String, Object>>();
    var removed = new java.util.ArrayList<Map<String, Object>>();
    var modified = new java.util.ArrayList<Map<String, Object>>();

    for (var row : rows) {
        if (row.get("b_name") == null) {
            added.add(Map.of(
                    "name", row.get("a_name"), "kind", row.get("a_kind"),
                    "file_path", row.get("a_file"), "signature", nullSafe(row.get("a_sig"))));
        } else if (row.get("a_name") == null) {
            removed.add(Map.of(
                    "name", row.get("b_name"), "kind", row.get("b_kind"),
                    "file_path", row.get("b_file"), "signature", nullSafe(row.get("b_sig"))));
        } else {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", row.get("a_name"));
            entry.put("kind", row.get("a_kind"));
            entry.put("file_path", row.get("a_file"));
            entry.put("branch_a_signature", nullSafe(row.get("a_sig")));
            entry.put("branch_b_signature", nullSafe(row.get("b_sig")));
            modified.add(entry);
        }
    }

    return Map.of("detail", "symbols", "branch_a", branchA, "branch_b", branchB,
            "added", added, "removed", removed, "modified", modified);
}
```

- [ ] **Step 2: Verify compilation and tests**

Run: `./gradlew compileJava 2>&1 | tail -5`
Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/indexer/mcp/QueryExecutor.java
git commit -m "feat: add symbol-level diff to diffBranches"
```

---

### Task 3: Register `diff_branches` MCP Tool

**Files:**
- Modify: `src/main/java/com/indexer/mcp/McpServerBootstrap.java`

- [ ] **Step 1: Add tool definition**

Add after `verifyAuditChainTool()`:

```java
private McpSchema.Tool diffBranchesTool() {
    var props = new LinkedHashMap<String, Object>();
    props.put("repo",     Map.of("type", "string", "description", "Repository name"));
    props.put("branch_a", Map.of("type", "string", "description", "First branch (treated as 'new')"));
    props.put("branch_b", Map.of("type", "string", "description", "Second branch (treated as 'old')"));
    props.put("detail",   Map.of("type", "string", "description", "Granularity: 'files' or 'symbols' (default: symbols)"));
    props.put("limit",    Map.of("type", "integer", "description", "Max results (default 100)", "default", 100));
    var schema = new McpSchema.JsonSchema("object", props,
            List.of("repo", "branch_a", "branch_b"), false, null, null);
    return McpSchema.Tool.builder()
            .name("diff_branches")
            .description("Compare two branches and show what's different. Returns added, removed, and modified files or symbols.")
            .inputSchema(schema)
            .build();
}
```

- [ ] **Step 2: Add handler**

Add after `handleVerifyAuditChain`:

```java
private McpSchema.CallToolResult handleDiffBranches(
        McpSyncServerExchange exchange,
        McpSchema.CallToolRequest request) {
    var args = request.arguments();
    var caller = extractIdentity(exchange);
    String repo = stringArg(args, "repo");
    return queryExecutor.executeQuery(caller, repo, "diff_branches", args,
            () -> queryExecutor.diffBranches(
                    repo, stringArg(args, "branch_a"), stringArg(args, "branch_b"),
                    stringArg(args, "detail"), intArg(args, "limit", 100)));
}
```

- [ ] **Step 3: Register in both buildServer and startHttp**

Add `.toolCall(diffBranchesTool(), this::handleDiffBranches)` after `.toolCall(verifyAuditChainTool(), this::handleVerifyAuditChain)` in both the `startHttp` method and the `buildServer` method.

Update log messages from "13 tools" to "14 tools" in both `startStdio()` and `startHttp()`.

- [ ] **Step 4: Update integration test**

In `src/test/java/com/indexer/mcp/transport/StreamableHttpTransportIntegrationTest.java`, update the tools size assertion from 13 to 14, and add `"diff_branches"` to the `containsExactlyInAnyOrder` list.

- [ ] **Step 5: Verify and commit**

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

```bash
git add src/main/java/com/indexer/mcp/McpServerBootstrap.java src/test/java/com/indexer/mcp/transport/StreamableHttpTransportIntegrationTest.java
git commit -m "feat: register diff_branches MCP tool"
```

---

### Task 4: `search_branches` QueryExecutor Method

**Files:**
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java`

- [ ] **Step 1: Add searchBranches method**

Add after `diffBranches` (before the internal helpers section):

```java
/**
 * Search for symbols matching a pattern across multiple indexed branches.
 * Returns results grouped by branch. Searches branch delta files only (not full overlay).
 */
public Map<String, Object> searchBranches(String repo, String query, String kind,
                                           String branchPattern, int maxBranches, int limit) {
    String effectivePattern = (branchPattern != null && !branchPattern.isBlank()) ? branchPattern : ".*";
    int effectiveMaxBranches = Math.min(Math.max(maxBranches, 1), 200);
    int effectiveLimit = Math.min(Math.max(limit, 1), 100);

    return jdbi.withHandle(handle -> {
        // Resolve repo ID
        var optRepo = handle.createQuery("SELECT id FROM repositories WHERE name = :name")
                .bind("name", repo)
                .mapTo(Integer.class)
                .findOne();
        if (optRepo.isEmpty()) {
            return Map.<String, Object>of("error", "Repository '" + repo + "' not found");
        }
        int repoId = optRepo.get();

        // Find matching branches
        var matchingBranches = handle.createQuery("""
                SELECT branch FROM branch_index
                WHERE repo_id = :repoId AND branch ~ :pattern
                ORDER BY branch
                LIMIT :maxBranches
                """)
                .bind("repoId", repoId)
                .bind("pattern", effectivePattern)
                .bind("maxBranches", effectiveMaxBranches)
                .mapTo(String.class)
                .list();

        int branchesSearched = matchingBranches.size() + 1; // +1 for main

        // Build the set of branches to search (main + matched)
        var allBranches = new java.util.ArrayList<String>();
        allBranches.add("main");
        allBranches.addAll(matchingBranches);

        // Query symbols across all branches
        var sb = new StringBuilder("""
                SELECT f.branch, s.name, s.kind, s.signature, f.path AS file_path
                FROM symbols s
                JOIN files f ON s.file_id = f.id
                WHERE f.repo_id = :repoId
                  AND s.name ~* :query
                  AND f.branch IN (<branches>)
                """);

        if (kind != null && !kind.isBlank()) {
            sb.append(" AND s.kind = :kind");
        }
        sb.append(" ORDER BY f.branch, s.name");

        // Replace <branches> placeholder with positional list
        String branchList = allBranches.stream()
                .map(b -> "'" + b.replace("'", "''") + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        String sql = sb.toString().replace("<branches>", branchList);

        var q = handle.createQuery(sql)
                .bind("repoId", repoId)
                .bind("query", query);
        if (kind != null && !kind.isBlank()) {
            q.bind("kind", kind);
        }

        var allResults = q.mapToMap().list();

        // Group by branch, cap per-branch results
        var grouped = new LinkedHashMap<String, java.util.List<Map<String, Object>>>();
        for (var row : allResults) {
            String branch = (String) row.get("branch");
            grouped.computeIfAbsent(branch, k -> new java.util.ArrayList<>());
            var branchResults = grouped.get(branch);
            if (branchResults.size() < effectiveLimit) {
                branchResults.add(Map.of(
                        "name", row.get("name"),
                        "kind", row.get("kind"),
                        "file_path", row.get("file_path"),
                        "signature", nullSafe(row.get("signature"))
                ));
            }
        }

        // Build response
        var results = new java.util.ArrayList<Map<String, Object>>();
        for (var entry : grouped.entrySet()) {
            results.add(Map.of("branch", entry.getKey(), "symbols", entry.getValue()));
        }

        return Map.<String, Object>of(
                "branches_searched", branchesSearched,
                "branches_matched", grouped.size(),
                "results", results);
    });
}
```

- [ ] **Step 2: Verify compilation and tests**

Run: `./gradlew compileJava 2>&1 | tail -5`
Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/indexer/mcp/QueryExecutor.java
git commit -m "feat: add searchBranches method for fan-out symbol search"
```

---

### Task 5: Register `search_branches` MCP Tool

**Files:**
- Modify: `src/main/java/com/indexer/mcp/McpServerBootstrap.java`

- [ ] **Step 1: Add tool definition**

Add after `diffBranchesTool()`:

```java
private McpSchema.Tool searchBranchesTool() {
    var props = new LinkedHashMap<String, Object>();
    props.put("repo",           Map.of("type", "string", "description", "Repository name"));
    props.put("query",          Map.of("type", "string", "description", "Regex pattern for symbol name"));
    props.put("kind",           Map.of("type", "string", "description", "Optional symbol kind filter (class, method, function, ...)"));
    props.put("branch_pattern", Map.of("type", "string", "description", "Regex to filter branches (default: all indexed branches)"));
    props.put("max_branches",   Map.of("type", "integer", "description", "Max branches to search (default 50)", "default", 50));
    props.put("limit",          Map.of("type", "integer", "description", "Max results per branch (default 20)", "default", 20));
    var schema = new McpSchema.JsonSchema("object", props,
            List.of("repo", "query"), false, null, null);
    return McpSchema.Tool.builder()
            .name("search_branches")
            .description("Search for a symbol across multiple indexed branches. Returns which branches have changes involving the matched symbol.")
            .inputSchema(schema)
            .build();
}
```

- [ ] **Step 2: Add handler**

Add after `handleDiffBranches`:

```java
private McpSchema.CallToolResult handleSearchBranches(
        McpSyncServerExchange exchange,
        McpSchema.CallToolRequest request) {
    var args = request.arguments();
    var caller = extractIdentity(exchange);
    String repo = stringArg(args, "repo");
    return queryExecutor.executeQuery(caller, repo, "search_branches", args,
            () -> queryExecutor.searchBranches(
                    repo, stringArg(args, "query"), stringArg(args, "kind"),
                    stringArg(args, "branch_pattern"),
                    intArg(args, "max_branches", 50),
                    intArg(args, "limit", 20)));
}
```

- [ ] **Step 3: Register in both buildServer and startHttp**

Add `.toolCall(searchBranchesTool(), this::handleSearchBranches)` after `.toolCall(diffBranchesTool(), this::handleDiffBranches)` in both the `startHttp` method and the `buildServer` method.

Update log messages from "14 tools" to "15 tools" in both `startStdio()` and `startHttp()`.

- [ ] **Step 4: Update integration test**

In `StreamableHttpTransportIntegrationTest.java`, update tools size assertion from 14 to 15, and add `"search_branches"` to the `containsExactlyInAnyOrder` list.

- [ ] **Step 5: Verify and commit**

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

```bash
git add src/main/java/com/indexer/mcp/McpServerBootstrap.java src/test/java/com/indexer/mcp/transport/StreamableHttpTransportIntegrationTest.java
git commit -m "feat: register search_branches MCP tool"
```

---

### Implementation Notes

**Task dependency chain:** Tasks 1-2 (QueryExecutor methods for diff_branches) are sequential. Task 3 (register diff_branches) depends on Tasks 1-2. Task 4 (searchBranches QueryExecutor method) is independent of Tasks 1-3. Task 5 (register search_branches) depends on Task 4.

**SQL injection safety:** The `branchList` construction in `searchBranches` uses string escaping (`replace("'", "''")`). Branch names come from `branch_index` rows (server-controlled data, not user input), so this is safe. The alternative — JDBI's `bindList` — requires array-type binding which varies by database.

**`nullSafe` helper:** Task 1 adds a `private static Object nullSafe(Object val)` method. This handles nullable column values in result maps, preventing NPE when constructing response maps with `Map.of()` (which rejects null values).

**CLAUDE.md update needed after implementation:** Update MCP Tools Reference table from 13 to 15 tools, add `diff_branches` and `search_branches` entries.
