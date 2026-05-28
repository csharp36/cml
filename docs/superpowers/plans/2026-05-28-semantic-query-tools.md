# Phase E2: Semantic Query Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two new MCP tools (`get_type_hierarchy`, `get_symbol_references`) that query SCIP data, plus a `scip_status` precision indicator on five existing tools.

**Architecture:** New query methods in `QueryExecutor` follow existing patterns (JDBI, `Map<String, Object>` return types, `executeQuery` pipeline). A shared `resolveScipSymbol` helper resolves display names to SCIP symbol strings. Two new tool definitions and handlers in `McpServerBootstrap`. Five existing tools gain a `scip_status` field via a lightweight `getScipStatus` helper.

**Tech Stack:** Java 21, JDBI, PostgreSQL 16, MCP SDK

**Spec:** `docs/superpowers/specs/2026-05-28-semantic-query-tools-design.md`

---

### Task 1: getScipStatus Helper + Precision Indicators on Existing Tools

**Files:**
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java`

- [ ] **Step 1: Add getScipStatus helper method**

Add to `QueryExecutor.java`, after the `getIndexHealth` method (around line 720):

```java
/**
 * Get the SCIP data status for a given repository.
 * Returns "fresh", "stale", or "unavailable".
 */
public String getScipStatus(String repoName) {
    if (repoName == null || repoName.isBlank()) return null;
    return jdbi.withHandle(handle -> {
        var opt = handle.createQuery("""
                SELECT scip_sha, last_indexed_sha
                FROM repositories WHERE name = :name
                """)
                .bind("name", repoName)
                .mapToMap()
                .findOne();
        if (opt.isEmpty()) return null;
        var row = opt.get();
        Object scipSha = row.get("scip_sha");
        Object indexedSha = row.get("last_indexed_sha");
        if (scipSha == null) return "unavailable";
        return scipSha.equals(indexedSha) ? "fresh" : "stale";
    });
}
```

- [ ] **Step 2: Add scip_status to searchSymbols**

In the `searchSymbols` method, change the return statement. Replace the final lines (around line 217-220):

From:
```java
            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            return q.mapToMap().list();
        });
    }
```

To:
```java
            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            var results = q.mapToMap().list();

            // Wrap in a map with scip_status if repo is specified
            if (repo != null && !repo.isBlank()) {
                var wrapper = new LinkedHashMap<String, Object>();
                wrapper.put("results", results);
                String scipStatus = getScipStatus(repo);
                if (scipStatus != null) wrapper.put("scip_status", scipStatus);
                return wrapper;
            }
            return results;
        });
    }
```

Also change the method return type from `List<Map<String, Object>>` to `Object`:

From: `public List<Map<String, Object>> searchSymbols(`
To: `public Object searchSymbols(`

- [ ] **Step 3: Add scip_status to getSymbolDetail**

In `getSymbolDetail`, before the final `return (Map<String, Object>) symbol;` (around line 298), add:

```java
            // Add SCIP precision indicator
            String scipStatus = getScipStatus(repo);
            if (scipStatus != null) symbol.put("scip_status", scipStatus);
```

- [ ] **Step 4: Add scip_status to findImplementations**

In `findImplementations`, change the return. Replace the final lines (around line 334-337):

From:
```java
            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            return q.mapToMap().list();
        });
    }
```

To:
```java
            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            var results = q.mapToMap().list();

            if (repo != null && !repo.isBlank()) {
                var wrapper = new LinkedHashMap<String, Object>();
                wrapper.put("results", results);
                String scipStatus = getScipStatus(repo);
                if (scipStatus != null) wrapper.put("scip_status", scipStatus);
                return wrapper;
            }
            return results;
        });
    }
```

Also change the method return type:

From: `public List<Map<String, Object>> findImplementations(`
To: `public Object findImplementations(`

- [ ] **Step 5: Add scip_status to findReferences**

Same pattern as `findImplementations`. In `findReferences`, change the return and method signature:

Change return type from `List<Map<String, Object>>` to `Object`.

Replace the final return lines with:

```java
            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            var results = q.mapToMap().list();

            if (repo != null && !repo.isBlank()) {
                var wrapper = new LinkedHashMap<String, Object>();
                wrapper.put("results", results);
                String scipStatus = getScipStatus(repo);
                if (scipStatus != null) wrapper.put("scip_status", scipStatus);
                return wrapper;
            }
            return results;
        });
    }
```

- [ ] **Step 6: Add scip_status to getRepoSummary**

In `getRepoSummary`, before the final `return result;` (the one that returns the populated LinkedHashMap), add:

```java
            // Add SCIP precision indicator
            String scipStatus = getScipStatus(repoName);
            if (scipStatus != null) result.put("scip_status", scipStatus);
```

- [ ] **Step 7: Verify compilation and tests**

Run: `./gradlew compileJava compileTestJava 2>&1 | tail -10`
Fix any compilation errors from the return type changes (the `Supplier<Object>` in `executeQuery` already accepts `Object`).

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/indexer/mcp/QueryExecutor.java
git commit -m "feat: add scip_status precision indicator to existing MCP tools"
```

---

### Task 2: resolveScipSymbol Helper + getTypeHierarchy in QueryExecutor

**Files:**
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java`

- [ ] **Step 1: Add resolveScipSymbol helper**

Add to `QueryExecutor.java`, after the `getScipStatus` method:

```java
/**
 * Resolve a display name to SCIP symbol row(s). Returns the first match, optionally
 * narrowed by file_path and kind.
 */
private Map<String, Object> resolveScipSymbol(org.jdbi.v3.core.Handle handle,
        int repoId, String symbolName, String filePath, String kind) {
    var sb = new StringBuilder("""
            SELECT scip_symbol, display_name, kind, documentation, file_path, start_line, end_line
            FROM scip_symbols
            WHERE repo_id = :repoId AND display_name = :symbolName
            """);
    var params = new LinkedHashMap<String, Object>();
    params.put("repoId", repoId);
    params.put("symbolName", symbolName);

    if (filePath != null && !filePath.isBlank()) {
        sb.append(" AND file_path = :filePath");
        params.put("filePath", filePath);
    }
    if (kind != null && !kind.isBlank()) {
        sb.append(" AND kind = :kind");
        params.put("kind", kind);
    }
    sb.append(" LIMIT 1");

    var q = handle.createQuery(sb.toString());
    params.forEach(q::bind);
    return q.mapToMap().findOne().orElse(null);
}

/**
 * Look up the repo ID by name. Returns -1 if not found.
 */
private int resolveRepoId(org.jdbi.v3.core.Handle handle, String repoName) {
    return handle.createQuery("SELECT id FROM repositories WHERE name = :name")
            .bind("name", repoName)
            .mapTo(Integer.class)
            .findOne()
            .orElse(-1);
}
```

- [ ] **Step 2: Add traverseHierarchy helper**

Add after the resolve helpers:

```java
/**
 * Recursively build a type hierarchy tree in one direction.
 * @param direction "up" follows from_symbol→to_symbol, "down" follows to_symbol→from_symbol
 */
private List<Map<String, Object>> traverseHierarchy(org.jdbi.v3.core.Handle handle,
        int repoId, String scipSymbol, String direction, int maxDepth, int currentDepth) {
    if (currentDepth >= maxDepth) return List.of();

    List<Map<String, Object>> edges;
    if ("up".equals(direction)) {
        edges = handle.createQuery("""
                SELECT to_symbol AS related_symbol, kind AS relationship
                FROM scip_relationships
                WHERE repo_id = :repoId AND from_symbol = :symbol AND kind IN ('implements', 'extends')
                """)
                .bind("repoId", repoId)
                .bind("symbol", scipSymbol)
                .mapToMap()
                .list();
    } else {
        edges = handle.createQuery("""
                SELECT from_symbol AS related_symbol, kind AS relationship
                FROM scip_relationships
                WHERE repo_id = :repoId AND to_symbol = :symbol AND kind IN ('implements', 'extends')
                """)
                .bind("repoId", repoId)
                .bind("symbol", scipSymbol)
                .mapToMap()
                .list();
    }

    var results = new ArrayList<Map<String, Object>>();
    for (var edge : edges) {
        String relatedSymbol = (String) edge.get("related_symbol");
        String relationship = (String) edge.get("relationship");

        var node = new LinkedHashMap<String, Object>();
        // Enrich with symbol metadata
        var symRow = handle.createQuery("""
                SELECT display_name, kind, file_path, start_line, documentation
                FROM scip_symbols
                WHERE repo_id = :repoId AND scip_symbol = :symbol
                """)
                .bind("repoId", repoId)
                .bind("symbol", relatedSymbol)
                .mapToMap()
                .findOne();

        if (symRow.isPresent()) {
            node.put("symbol", symRow.get().get("display_name"));
            node.put("scip_symbol", relatedSymbol);
            node.put("kind", symRow.get().get("kind"));
            node.put("file_path", symRow.get().get("file_path"));
            node.put("line", symRow.get().get("start_line"));
        } else {
            node.put("scip_symbol", relatedSymbol);
        }
        node.put("relationship", relationship);

        // Recurse
        String childKey = "up".equals(direction) ? "supertypes" : "subtypes";
        var children = traverseHierarchy(handle, repoId, relatedSymbol, direction, maxDepth, currentDepth + 1);
        if (!children.isEmpty()) {
            node.put(childKey, children);
        }

        results.add(node);
    }
    return results;
}
```

- [ ] **Step 3: Add getTypeHierarchy method**

Add after the traverse helper:

```java
/**
 * Get the type hierarchy for a symbol — supertypes, subtypes, or both.
 */
public Map<String, Object> getTypeHierarchy(String repo, String symbolName, String filePath,
        String kind, String direction, int depth) {
    if (direction == null || direction.isBlank()) direction = "both";
    if (depth <= 0) depth = 3;
    final String dir = direction;
    final int maxDepth = depth;

    return jdbi.withHandle(handle -> {
        int repoId = resolveRepoId(handle, repo);
        if (repoId < 0) {
            return Map.<String, Object>of("error", "Repository '" + repo + "' not found");
        }

        var resolved = resolveScipSymbol(handle, repoId, symbolName, filePath, kind);
        if (resolved == null) {
            var result = new LinkedHashMap<String, Object>();
            result.put("symbol", symbolName);
            result.put("message", "Symbol not found in SCIP data");
            String scipStatus = getScipStatus(repo);
            if (scipStatus != null) result.put("scip_status", scipStatus);
            return result;
        }

        String scipSymbol = (String) resolved.get("scip_symbol");

        var result = new LinkedHashMap<String, Object>();
        result.put("symbol", resolved.get("display_name"));
        result.put("scip_symbol", scipSymbol);
        result.put("kind", resolved.get("kind"));
        result.put("file_path", resolved.get("file_path"));
        result.put("line", resolved.get("start_line"));
        if (resolved.get("documentation") != null) {
            result.put("documentation", resolved.get("documentation"));
        }

        if ("up".equals(dir) || "both".equals(dir)) {
            result.put("supertypes", traverseHierarchy(handle, repoId, scipSymbol, "up", maxDepth, 0));
        }
        if ("down".equals(dir) || "both".equals(dir)) {
            result.put("subtypes", traverseHierarchy(handle, repoId, scipSymbol, "down", maxDepth, 0));
        }

        String scipStatus = getScipStatus(repo);
        if (scipStatus != null) result.put("scip_status", scipStatus);

        return result;
    });
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/mcp/QueryExecutor.java
git commit -m "feat: add getTypeHierarchy and SCIP symbol resolution helpers to QueryExecutor"
```

---

### Task 3: getSymbolReferences in QueryExecutor

**Files:**
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java`

- [ ] **Step 1: Add getSymbolReferences method**

Add after `getTypeHierarchy` in `QueryExecutor.java`:

```java
/**
 * Find symbols related to a given symbol through SCIP relationships.
 * Flat list of direct edges (not recursive).
 */
public Map<String, Object> getSymbolReferences(String repo, String symbolName, String filePath,
        String relationshipKind, String direction, int limit) {
    if (direction == null || direction.isBlank()) direction = "inbound";
    if (limit <= 0) limit = 50;
    final String dir = direction;
    final int maxResults = limit;

    return jdbi.withHandle(handle -> {
        int repoId = resolveRepoId(handle, repo);
        if (repoId < 0) {
            return Map.<String, Object>of("error", "Repository '" + repo + "' not found");
        }

        var resolved = resolveScipSymbol(handle, repoId, symbolName, filePath, null);
        if (resolved == null) {
            var result = new LinkedHashMap<String, Object>();
            result.put("symbol", symbolName);
            result.put("message", "Symbol not found in SCIP data");
            String scipStatus = getScipStatus(repo);
            if (scipStatus != null) result.put("scip_status", scipStatus);
            return result;
        }

        String scipSymbol = (String) resolved.get("scip_symbol");

        var references = new ArrayList<Map<String, Object>>();

        // Inbound: who references this symbol?
        if ("inbound".equals(dir) || "both".equals(dir)) {
            var sb = new StringBuilder("""
                    SELECT sr.from_symbol, sr.kind AS relationship, sr.file_path, sr.line
                    FROM scip_relationships sr
                    WHERE sr.repo_id = :repoId AND sr.to_symbol = :symbol
                    """);
            var params = new LinkedHashMap<String, Object>();
            params.put("repoId", repoId);
            params.put("symbol", scipSymbol);
            if (relationshipKind != null && !relationshipKind.isBlank()) {
                sb.append(" AND sr.kind = :kind");
                params.put("kind", relationshipKind);
            }
            sb.append(" LIMIT :limit");
            params.put("limit", maxResults);

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            for (var row : q.mapToMap().list()) {
                var ref = new LinkedHashMap<String, Object>();
                String fromSymbol = (String) row.get("from_symbol");
                // Enrich with symbol metadata
                var symRow = handle.createQuery("""
                        SELECT display_name, kind FROM scip_symbols
                        WHERE repo_id = :repoId AND scip_symbol = :symbol
                        """)
                        .bind("repoId", repoId)
                        .bind("symbol", fromSymbol)
                        .mapToMap()
                        .findOne();
                if (symRow.isPresent()) {
                    ref.put("symbol", symRow.get().get("display_name"));
                    ref.put("kind", symRow.get().get("kind"));
                }
                ref.put("scip_symbol", fromSymbol);
                ref.put("relationship", row.get("relationship"));
                ref.put("file_path", row.get("file_path"));
                ref.put("line", row.get("line"));
                ref.put("direction", "inbound");
                references.add(ref);
            }
        }

        // Outbound: what does this symbol reference?
        if ("outbound".equals(dir) || "both".equals(dir)) {
            var sb = new StringBuilder("""
                    SELECT sr.to_symbol, sr.kind AS relationship, sr.file_path, sr.line
                    FROM scip_relationships sr
                    WHERE sr.repo_id = :repoId AND sr.from_symbol = :symbol
                    """);
            var params = new LinkedHashMap<String, Object>();
            params.put("repoId", repoId);
            params.put("symbol", scipSymbol);
            if (relationshipKind != null && !relationshipKind.isBlank()) {
                sb.append(" AND sr.kind = :kind");
                params.put("kind", relationshipKind);
            }
            sb.append(" LIMIT :limit");
            params.put("limit", maxResults);

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            for (var row : q.mapToMap().list()) {
                var ref = new LinkedHashMap<String, Object>();
                String toSymbol = (String) row.get("to_symbol");
                var symRow = handle.createQuery("""
                        SELECT display_name, kind FROM scip_symbols
                        WHERE repo_id = :repoId AND scip_symbol = :symbol
                        """)
                        .bind("repoId", repoId)
                        .bind("symbol", toSymbol)
                        .mapToMap()
                        .findOne();
                if (symRow.isPresent()) {
                    ref.put("symbol", symRow.get().get("display_name"));
                    ref.put("kind", symRow.get().get("kind"));
                }
                ref.put("scip_symbol", toSymbol);
                ref.put("relationship", row.get("relationship"));
                ref.put("file_path", row.get("file_path"));
                ref.put("line", row.get("line"));
                ref.put("direction", "outbound");
                references.add(ref);
            }
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("symbol", resolved.get("display_name"));
        result.put("scip_symbol", scipSymbol);
        result.put("kind", resolved.get("kind"));
        result.put("file_path", resolved.get("file_path"));
        result.put("references", references);
        result.put("total", references.size());
        String scipStatus = getScipStatus(repo);
        if (scipStatus != null) result.put("scip_status", scipStatus);

        return result;
    });
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/indexer/mcp/QueryExecutor.java
git commit -m "feat: add getSymbolReferences method to QueryExecutor"
```

---

### Task 4: Register get_type_hierarchy MCP Tool

**Files:**
- Modify: `src/main/java/com/indexer/mcp/McpServerBootstrap.java`

- [ ] **Step 1: Add tool definition**

Add after `searchBranchesTool()` (around line 337):

```java
    private McpSchema.Tool getTypeHierarchyTool() {
        var props = new LinkedHashMap<String, Object>();
        props.put("repo",        Map.of("type", "string", "description", "Repository name"));
        props.put("symbol_name", Map.of("type", "string", "description", "Display name of the type (e.g., PaymentProcessor)"));
        props.put("file_path",   Map.of("type", "string", "description", "File path to disambiguate when multiple symbols share a name"));
        props.put("kind",        Map.of("type", "string", "description", "Filter by symbol kind (Class, Interface, etc.)"));
        props.put("direction",   Map.of("type", "string", "description", "Traversal direction: up (supertypes), down (subtypes), or both (default: both)"));
        props.put("depth",       Map.of("type", "integer", "description", "Max traversal depth (default: 3)", "default", 3));
        var schema = new McpSchema.JsonSchema("object", props,
                List.of("repo", "symbol_name"), false, null, null);
        return McpSchema.Tool.builder()
                .name("get_type_hierarchy")
                .description("Get the type hierarchy for a symbol using SCIP semantic data. Shows supertypes (interfaces/base classes) and subtypes (implementations/subclasses). Requires SCIP data to be uploaded for the repo.")
                .inputSchema(schema)
                .build();
    }
```

- [ ] **Step 2: Add handler**

Add after `handleSearchBranches` (around line 554):

```java
    private McpSchema.CallToolResult handleGetTypeHierarchy(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        var args = request.arguments();
        var caller = extractIdentity(exchange);
        String repo = stringArg(args, "repo");
        return queryExecutor.executeQuery(caller, repo, "get_type_hierarchy", args,
                () -> queryExecutor.getTypeHierarchy(
                        repo, stringArg(args, "symbol_name"),
                        stringArg(args, "file_path"), stringArg(args, "kind"),
                        stringArg(args, "direction"), intArg(args, "depth", 3)));
    }
```

- [ ] **Step 3: Register in buildServer()**

In `buildServer()`, add before `.build()` (after the `searchBranches` line):

```java
                .toolCall(getTypeHierarchyTool(), this::handleGetTypeHierarchy)
```

- [ ] **Step 4: Register in startHttp()**

In `startHttp()`, add before `.build()` (after the `searchBranches` line):

```java
                .toolCall(getTypeHierarchyTool(), this::handleGetTypeHierarchy)
```

- [ ] **Step 5: Update tool count in log messages**

Update both log messages:
- `startStdio()`: change `"15 tools"` to `"17 tools"`
- `startHttp()`: change `"15 tools"` to `"17 tools"`

- [ ] **Step 6: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/indexer/mcp/McpServerBootstrap.java
git commit -m "feat: register get_type_hierarchy MCP tool"
```

---

### Task 5: Register get_symbol_references MCP Tool

**Files:**
- Modify: `src/main/java/com/indexer/mcp/McpServerBootstrap.java`

- [ ] **Step 1: Add tool definition**

Add after `getTypeHierarchyTool()`:

```java
    private McpSchema.Tool getSymbolReferencesTool() {
        var props = new LinkedHashMap<String, Object>();
        props.put("repo",              Map.of("type", "string", "description", "Repository name"));
        props.put("symbol_name",       Map.of("type", "string", "description", "Display name of the symbol to look up"));
        props.put("file_path",         Map.of("type", "string", "description", "File path to disambiguate when multiple symbols share a name"));
        props.put("relationship_kind", Map.of("type", "string", "description", "Filter by relationship: implements, extends, references, or all (default: all)"));
        props.put("direction",         Map.of("type", "string", "description", "inbound (who references this), outbound (what this references), or both (default: inbound)"));
        props.put("limit",             Map.of("type", "integer", "description", "Max results (default: 50)", "default", 50));
        var schema = new McpSchema.JsonSchema("object", props,
                List.of("repo", "symbol_name"), false, null, null);
        return McpSchema.Tool.builder()
                .name("get_symbol_references")
                .description("Find symbols related to a given symbol through SCIP semantic relationships (implements, extends, references). Returns a flat list of direct edges. Requires SCIP data to be uploaded for the repo.")
                .inputSchema(schema)
                .build();
    }
```

- [ ] **Step 2: Add handler**

Add after `handleGetTypeHierarchy`:

```java
    private McpSchema.CallToolResult handleGetSymbolReferences(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        var args = request.arguments();
        var caller = extractIdentity(exchange);
        String repo = stringArg(args, "repo");
        return queryExecutor.executeQuery(caller, repo, "get_symbol_references", args,
                () -> queryExecutor.getSymbolReferences(
                        repo, stringArg(args, "symbol_name"),
                        stringArg(args, "file_path"), stringArg(args, "relationship_kind"),
                        stringArg(args, "direction"), intArg(args, "limit", 50)));
    }
```

- [ ] **Step 3: Register in buildServer() and startHttp()**

Add to both, after the `getTypeHierarchy` line:

```java
                .toolCall(getSymbolReferencesTool(), this::handleGetSymbolReferences)
```

- [ ] **Step 4: Verify compilation and run tests**

Run: `./gradlew compileJava compileTestJava 2>&1 | tail -10`
Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/mcp/McpServerBootstrap.java
git commit -m "feat: register get_symbol_references MCP tool"
```

---

### Task 6: Unit Tests for Semantic Query Methods

**Files:**
- Create: `src/test/java/com/indexer/mcp/SemanticQueryTest.java`

- [ ] **Step 1: Write tests**

```java
package com.indexer.mcp;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class SemanticQueryTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index")
            .withUsername("test")
            .withPassword("test");

    private Jdbi jdbi;
    private QueryExecutor queryExecutor;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());

        // Run migrations
        var flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        // Insert test data
        jdbi.useHandle(handle -> {
            // Repository
            handle.execute("""
                    INSERT INTO repositories (name, url, branch, clone_path, auth_type, last_indexed_sha)
                    VALUES ('test-repo', 'git@example.com:test.git', 'main', '/tmp/test', 'ssh-key', 'abc123')
                    """);
            int repoId = handle.createQuery("SELECT id FROM repositories WHERE name = 'test-repo'")
                    .mapTo(Integer.class).one();

            // SCIP symbols
            handle.execute("""
                    INSERT INTO scip_symbols (repo_id, scip_symbol, display_name, kind, documentation, file_path, start_line, end_line, upload_sha)
                    VALUES (?, 'java maven . com/example/PaymentProcessor#.', 'PaymentProcessor', 'Interface', 'Payment processing interface', 'src/PaymentProcessor.java', 3, 20, 'abc123')
                    """, repoId);
            handle.execute("""
                    INSERT INTO scip_symbols (repo_id, scip_symbol, display_name, kind, file_path, start_line, end_line, upload_sha)
                    VALUES (?, 'java maven . com/example/StripeProcessor#.', 'StripeProcessor', 'Class', 'src/StripeProcessor.java', 5, 50, 'abc123')
                    """, repoId);
            handle.execute("""
                    INSERT INTO scip_symbols (repo_id, scip_symbol, display_name, kind, file_path, start_line, end_line, upload_sha)
                    VALUES (?, 'java maven . com/example/PayPalProcessor#.', 'PayPalProcessor', 'Class', 'src/PayPalProcessor.java', 3, 40, 'abc123')
                    """, repoId);
            handle.execute("""
                    INSERT INTO scip_symbols (repo_id, scip_symbol, display_name, kind, file_path, start_line, end_line, upload_sha)
                    VALUES (?, 'java maven . com/example/Serializable#.', 'Serializable', 'Interface', 'src/Serializable.java', 1, 5, 'abc123')
                    """, repoId);

            // SCIP relationships
            handle.execute("""
                    INSERT INTO scip_relationships (repo_id, from_symbol, to_symbol, kind, file_path, line)
                    VALUES (?, 'java maven . com/example/StripeProcessor#.', 'java maven . com/example/PaymentProcessor#.', 'implements', 'src/StripeProcessor.java', 5)
                    """, repoId);
            handle.execute("""
                    INSERT INTO scip_relationships (repo_id, from_symbol, to_symbol, kind, file_path, line)
                    VALUES (?, 'java maven . com/example/PayPalProcessor#.', 'java maven . com/example/PaymentProcessor#.', 'implements', 'src/PayPalProcessor.java', 3)
                    """, repoId);
            handle.execute("""
                    INSERT INTO scip_relationships (repo_id, from_symbol, to_symbol, kind, file_path, line)
                    VALUES (?, 'java maven . com/example/StripeProcessor#.', 'java maven . com/example/Serializable#.', 'implements', 'src/StripeProcessor.java', 5)
                    """, repoId);

            // Set SCIP SHA to match indexed SHA (fresh)
            handle.execute("UPDATE repositories SET scip_sha = 'abc123' WHERE id = ?", repoId);
        });

        queryExecutor = new QueryExecutor(jdbi);
    }

    @Test
    void getScipStatusFresh() {
        assertThat(queryExecutor.getScipStatus("test-repo")).isEqualTo("fresh");
    }

    @Test
    void getScipStatusStale() {
        jdbi.useHandle(h -> h.execute("UPDATE repositories SET scip_sha = 'old-sha' WHERE name = 'test-repo'"));
        assertThat(queryExecutor.getScipStatus("test-repo")).isEqualTo("stale");
    }

    @Test
    void getScipStatusUnavailable() {
        jdbi.useHandle(h -> h.execute("UPDATE repositories SET scip_sha = NULL WHERE name = 'test-repo'"));
        assertThat(queryExecutor.getScipStatus("test-repo")).isEqualTo("unavailable");
    }

    @Test
    void getScipStatusUnknownRepo() {
        assertThat(queryExecutor.getScipStatus("nonexistent")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTypeHierarchyBothDirections() {
        var result = queryExecutor.getTypeHierarchy("test-repo", "PaymentProcessor", null, null, "both", 3);
        assertThat(result.get("symbol")).isEqualTo("PaymentProcessor");
        assertThat(result.get("kind")).isEqualTo("Interface");
        assertThat(result.get("scip_status")).isEqualTo("fresh");

        var subtypes = (List<Map<String, Object>>) result.get("subtypes");
        assertThat(subtypes).hasSize(2);
        assertThat(subtypes).extracting(m -> m.get("symbol"))
                .containsExactlyInAnyOrder("StripeProcessor", "PayPalProcessor");
        assertThat(subtypes).allMatch(m -> "implements".equals(m.get("relationship")));

        var supertypes = (List<Map<String, Object>>) result.get("supertypes");
        assertThat(supertypes).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTypeHierarchyUpDirection() {
        var result = queryExecutor.getTypeHierarchy("test-repo", "StripeProcessor", null, null, "up", 3);
        assertThat(result.get("symbol")).isEqualTo("StripeProcessor");

        var supertypes = (List<Map<String, Object>>) result.get("supertypes");
        assertThat(supertypes).hasSize(2);
        assertThat(supertypes).extracting(m -> m.get("symbol"))
                .containsExactlyInAnyOrder("PaymentProcessor", "Serializable");

        assertThat(result).doesNotContainKey("subtypes");
    }

    @Test
    void getTypeHierarchySymbolNotFound() {
        var result = queryExecutor.getTypeHierarchy("test-repo", "NonExistent", null, null, "both", 3);
        assertThat(result.get("message")).isEqualTo("Symbol not found in SCIP data");
        assertThat(result.get("scip_status")).isEqualTo("fresh");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getSymbolReferencesInbound() {
        var result = queryExecutor.getSymbolReferences("test-repo", "PaymentProcessor", null, null, "inbound", 50);
        assertThat(result.get("symbol")).isEqualTo("PaymentProcessor");

        var refs = (List<Map<String, Object>>) result.get("references");
        assertThat(refs).hasSize(2);
        assertThat(refs).allMatch(r -> "inbound".equals(r.get("direction")));
        assertThat(refs).extracting(m -> m.get("symbol"))
                .containsExactlyInAnyOrder("StripeProcessor", "PayPalProcessor");
        assertThat((int) result.get("total")).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getSymbolReferencesOutbound() {
        var result = queryExecutor.getSymbolReferences("test-repo", "StripeProcessor", null, null, "outbound", 50);

        var refs = (List<Map<String, Object>>) result.get("references");
        assertThat(refs).hasSize(2);
        assertThat(refs).allMatch(r -> "outbound".equals(r.get("direction")));
        assertThat(refs).extracting(m -> m.get("symbol"))
                .containsExactlyInAnyOrder("PaymentProcessor", "Serializable");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getSymbolReferencesFilterByKind() {
        var result = queryExecutor.getSymbolReferences("test-repo", "PaymentProcessor", null, "implements", "inbound", 50);

        var refs = (List<Map<String, Object>>) result.get("references");
        assertThat(refs).hasSize(2);
        assertThat(refs).allMatch(r -> "implements".equals(r.get("relationship")));
    }

    @Test
    void getSymbolReferencesNotFound() {
        var result = queryExecutor.getSymbolReferences("test-repo", "NonExistent", null, null, "inbound", 50);
        assertThat(result.get("message")).isEqualTo("Symbol not found in SCIP data");
    }
}
```

- [ ] **Step 2: Run integration tests**

Run: `./gradlew test --tests "com.indexer.mcp.SemanticQueryTest" --rerun 2>&1 | tail -15`
Expected: All tests pass.

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/indexer/mcp/SemanticQueryTest.java
git commit -m "test: add integration tests for semantic query tools"
```

---

### Task 7: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update MCP Tools Reference table**

Add two rows to the table after `check_sync`:

```markdown
| `get_type_hierarchy` | Type hierarchy from SCIP data | Supertypes, subtypes (recursive tree) |
| `get_symbol_references` | Symbol relationships from SCIP data | Flat list of related symbols |
```

- [ ] **Step 2: Update tool count**

Change `11 tools (9 query + 1 health + 1 sync check)` to `13 tools (11 query + 1 health + 1 sync check)`.

- [ ] **Step 3: Update the comment in McpServerBootstrap.java**

Change the Javadoc on `McpServerBootstrap` (line 22) from `"all 13 MCP tools"` to `"all 17 MCP tools"`.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md src/main/java/com/indexer/mcp/McpServerBootstrap.java
git commit -m "docs: add semantic query tools to CLAUDE.md and update tool count"
```

---

### Implementation Notes

**Task dependency chain:** Task 1 (scip_status helper) must come first — Tasks 2-3 use `getScipStatus()` and Tasks 4-5 use return types from Task 1. Task 2 (type hierarchy) depends on Task 1. Task 3 (symbol references) depends on Task 1. Tasks 4-5 (tool registration) depend on Tasks 2-3 respectively. Task 6 (tests) depends on Tasks 1-3. Task 7 (docs) is independent.

**Return type changes in Task 1:** `searchSymbols`, `findImplementations`, and `findReferences` change from `List<Map<String, Object>>` to `Object` to allow wrapping results with `scip_status`. The `executeQuery` pipeline already accepts `Supplier<Object>`, so this is safe. No callers outside `McpServerBootstrap` depend on the specific return types.

**No branch support:** SCIP tables have no branch column. The new tools do not call `resolveBranch()` or `ensureBranchIndexed()`. This is documented in tool descriptions.

**Recursive traversal safety:** `traverseHierarchy` is bounded by the `depth` parameter (default 3). Circular references in SCIP data (rare but possible) would hit the depth limit rather than infinite-loop.

**Symbol enrichment cost:** Each related symbol is enriched with a separate lookup query. For large hierarchies or reference lists, this could be N+1. Acceptable for initial implementation — if performance becomes an issue, a batch IN-query can replace the per-symbol lookups.
