# Check Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `check_sync` MCP tool that compares a developer's local HEAD SHA against the indexed SHA, returning sync status with a recommended action.

**Architecture:** New `checkSync()` method in `QueryExecutor` queries `RepositoryDao.findByName()`, compares SHAs with case-insensitive prefix matching, returns a status map. Tool registered in `McpServerBootstrap` following the existing 10-tool pattern. `connect-index` skill updated to call the tool on connect.

**Tech Stack:** Java 21, JDBI, MCP Java SDK, JUnit 5 + Testcontainers + AssertJ

**Spec:** `docs/superpowers/specs/2026-05-26-check-sync-design.md`

---

## File Structure

```
src/main/java/com/indexer/mcp/QueryExecutor.java       (modify — add checkSync method)
src/main/java/com/indexer/mcp/McpServerBootstrap.java   (modify — register check_sync tool)
src/test/java/com/indexer/mcp/tools/CheckSyncToolTest.java  (create — integration tests)
skills/connect-index.md                                  (modify — add sync check step)
```

---

### Task 1: Add `checkSync()` to QueryExecutor with Tests

**Files:**
- Create: `src/test/java/com/indexer/mcp/tools/CheckSyncToolTest.java`
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java`

- [ ] **Step 1: Write the failing test class**

Create `src/test/java/com/indexer/mcp/tools/CheckSyncToolTest.java`:

```java
package com.indexer.mcp.tools;

import com.indexer.db.DatabaseManager;
import com.indexer.db.RepositoryDao;
import com.indexer.mcp.QueryExecutor;
import com.indexer.model.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class CheckSyncToolTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private QueryExecutor queryExecutor;

    @BeforeEach
    void setUp() {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        var jdbi = dbManager.getJdbi();
        jdbi.useHandle(h -> h.execute("DELETE FROM repositories"));
        var repoDao = new RepositoryDao(jdbi);
        repoDao.insert(new Repository(0, "backend-api", "git@github.com:org/backend.git", "main",
                "/repos/backend", "ssh-key", "abc1234def5678901234567890abcdef12345678", Instant.parse("2026-05-26T10:30:00Z")));
        repoDao.insert(new Repository(0, "not-indexed-yet", "git@github.com:org/new.git", "main",
                "/repos/new", "ssh-key", null, null));
        queryExecutor = new QueryExecutor(jdbi);
    }

    @Test
    void returnsInSyncWhenShasMatch() {
        var result = queryExecutor.checkSync("backend-api", "abc1234def5678901234567890abcdef12345678");

        assertThat(result.get("status")).isEqualTo("in_sync");
        assertThat(result.get("repo_name")).isEqualTo("backend-api");
        assertThat(result.get("message")).isEqualTo("Your local repo matches the index.");
        assertThat(result).doesNotContainKey("action");
    }

    @Test
    void returnsOutOfSyncWhenShasDiffer() {
        var result = queryExecutor.checkSync("backend-api", "ffffffffffffffffffffffffffffffffffffffff");

        assertThat(result.get("status")).isEqualTo("out_of_sync");
        assertThat(result.get("repo_name")).isEqualTo("backend-api");
        assertThat(result.get("indexed_sha")).isEqualTo("abc1234def5678901234567890abcdef12345678");
        assertThat(result.get("local_sha")).isEqualTo("ffffffffffffffffffffffffffffffffffffffff");
        assertThat(result.get("action")).isEqualTo("Run 'git pull' to sync, or push your changes to trigger re-indexing.");
    }

    @Test
    void returnsErrorWhenRepoNotFound() {
        var result = queryExecutor.checkSync("nonexistent", "abc123");

        assertThat(result.get("error")).isEqualTo("Repository 'nonexistent' not found in index");
        assertThat(result).doesNotContainKey("status");
    }

    @Test
    void returnsNotIndexedWhenShaIsNull() {
        var result = queryExecutor.checkSync("not-indexed-yet", "abc123");

        assertThat(result.get("status")).isEqualTo("not_indexed");
        assertThat(result.get("message")).isEqualTo("Repository exists but has not been indexed yet.");
    }

    @Test
    void matchesAbbreviatedShaAgainstFullSha() {
        // 7-char abbreviated SHA should match the full 40-char SHA
        var result = queryExecutor.checkSync("backend-api", "abc1234");

        assertThat(result.get("status")).isEqualTo("in_sync");
    }

    @Test
    void matchesFullShaAgainstAbbreviatedIndexedSha() {
        // If somehow the indexed SHA were shorter, full local SHA should still match
        // This is handled by checking prefix in both directions
        var result = queryExecutor.checkSync("backend-api", "abc1234def5678901234567890abcdef12345678");

        assertThat(result.get("status")).isEqualTo("in_sync");
    }

    @Test
    void comparisonIsCaseInsensitive() {
        var result = queryExecutor.checkSync("backend-api", "ABC1234DEF5678901234567890ABCDEF12345678");

        assertThat(result.get("status")).isEqualTo("in_sync");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
./gradlew test --tests "com.indexer.mcp.tools.CheckSyncToolTest" 2>&1 | tail -10
```

Expected: Compilation failure — `checkSync` method does not exist.

- [ ] **Step 3: Implement `checkSync()` in QueryExecutor**

Add this method to `src/main/java/com/indexer/mcp/QueryExecutor.java`, after the `getIndexHealth()` method (before the `// Internal helpers` comment):

```java
    /**
     * Check whether a local repository HEAD SHA matches the indexed SHA.
     */
    public Map<String, Object> checkSync(String repoName, String localSha) {
        return jdbi.withHandle(handle -> {
            var optRepo = handle.createQuery(
                            "SELECT name, last_indexed_sha, last_indexed_at FROM repositories WHERE name = :name")
                    .bind("name", repoName)
                    .mapToMap()
                    .findOne();

            if (optRepo.isEmpty()) {
                return Map.<String, Object>of("error",
                        "Repository '" + repoName + "' not found in index");
            }

            var repo = optRepo.get();
            String indexedSha = (String) repo.get("last_indexed_sha");
            Object indexedAt = repo.get("last_indexed_at");

            if (indexedSha == null) {
                var result = new LinkedHashMap<String, Object>();
                result.put("repo_name", repoName);
                result.put("status", "not_indexed");
                result.put("local_sha", localSha);
                result.put("indexed_sha", null);
                result.put("indexed_at", null);
                result.put("message", "Repository exists but has not been indexed yet.");
                return result;
            }

            boolean inSync = shaMatches(localSha, indexedSha);

            var result = new LinkedHashMap<String, Object>();
            result.put("repo_name", repoName);
            result.put("status", inSync ? "in_sync" : "out_of_sync");
            result.put("local_sha", localSha);
            result.put("indexed_sha", indexedSha);
            result.put("indexed_at", indexedAt != null ? indexedAt.toString() : null);
            result.put("message", inSync
                    ? "Your local repo matches the index."
                    : "Your local repo does not match the index.");
            if (!inSync) {
                result.put("action", "Run 'git pull' to sync, or push your changes to trigger re-indexing.");
            }
            return (Map<String, Object>) result;
        });
    }

    /**
     * Compare two git SHAs, handling abbreviated SHAs and case differences.
     * Returns true if either SHA is a case-insensitive prefix of the other.
     */
    private boolean shaMatches(String sha1, String sha2) {
        if (sha1 == null || sha2 == null) return false;
        String lower1 = sha1.toLowerCase();
        String lower2 = sha2.toLowerCase();
        return lower1.startsWith(lower2) || lower2.startsWith(lower1);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
./gradlew test --tests "com.indexer.mcp.tools.CheckSyncToolTest" 2>&1 | tail -15
```

Expected: All 7 tests pass.

- [ ] **Step 5: Run full build to verify nothing is broken**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
./gradlew build 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
git add src/main/java/com/indexer/mcp/QueryExecutor.java src/test/java/com/indexer/mcp/tools/CheckSyncToolTest.java
git commit -m "feat: add checkSync() to QueryExecutor — SHA comparison with prefix matching"
```

---

### Task 2: Register `check_sync` Tool in McpServerBootstrap

**Files:**
- Modify: `src/main/java/com/indexer/mcp/McpServerBootstrap.java`

- [ ] **Step 1: Add tool definition method**

Add this method to `McpServerBootstrap.java` in the "Tool definitions" section, after `getIndexHealthTool()`:

```java
    private McpSchema.Tool checkSyncTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(
                        "repo_name",  Map.of("type", "string", "description", "Name of the repository to check"),
                        "local_sha",  Map.of("type", "string", "description", "Your local HEAD SHA from 'git rev-parse HEAD'")
                ),
                List.of("repo_name", "local_sha"),
                false, null, null);
        return new McpSchema.Tool("check_sync",
                "Check whether a local repository is in sync with the indexed version. Pass the repo name and your local HEAD SHA (from 'git rev-parse HEAD'). Returns sync status and recommended action if out of sync.",
                schema);
    }
```

- [ ] **Step 2: Add tool handler method**

Add this method in the "Tool handlers" section, after `handleGetIndexHealth()`:

```java
    private McpSchema.CallToolResult handleCheckSync(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            String repoName = stringArg(args, "repo_name");
            String localSha = stringArg(args, "local_sha");

            var result = queryExecutor.checkSync(repoName, localSha);
            return jsonResult(result);
        } catch (Exception e) {
            return errorResult(e);
        }
    }
```

- [ ] **Step 3: Register the tool in `buildServer()`**

In the `buildServer()` method, add `.tool(checkSyncTool(), this::handleCheckSync)` after the `.tool(getIndexHealthTool(), this::handleGetIndexHealth)` line:

```java
    private McpSyncServer buildServer(McpServerTransportProvider transport) {
        return McpServer.sync(transport)
                .serverInfo("source-code-indexer", "1.0.0")
                .tool(searchSymbolsTool(), this::handleSearchSymbols)
                .tool(getSymbolDetailTool(), this::handleGetSymbolDetail)
                .tool(findImplementationsTool(), this::handleFindImplementations)
                .tool(findReferencesTool(), this::handleFindReferences)
                .tool(searchCodeTool(), this::handleSearchCode)
                .tool(searchFilesTool(), this::handleSearchFiles)
                .tool(getRepoSummaryTool(), this::handleGetRepoSummary)
                .tool(getFileSummaryTool(), this::handleGetFileSummary)
                .tool(getDirectoryTreeTool(), this::handleGetDirectoryTree)
                .tool(getIndexHealthTool(), this::handleGetIndexHealth)
                .tool(checkSyncTool(), this::handleCheckSync)
                .build();
    }
```

- [ ] **Step 4: Update log messages**

Update the two log messages in `startStdio()` and `startSse()` from "10 tools" to "11 tools":

In `startStdio()`:
```java
        log.info("MCP server started over stdio with 11 tools registered");
```

In `startSse()`:
```java
        log.info("MCP server started over SSE with 11 tools registered");
```

- [ ] **Step 5: Verify build**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
./gradlew build 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
git add src/main/java/com/indexer/mcp/McpServerBootstrap.java
git commit -m "feat: register check_sync MCP tool — 11th tool for local/index SHA comparison"
```

---

### Task 3: Update connect-index Skill

**Files:**
- Modify: `skills/connect-index.md`

- [ ] **Step 1: Add sync check step to the skill**

In `skills/connect-index.md`, add a new step between the existing step 3 ("Verify the repo is indexed") and step 4 ("Configure Claude Code"). The existing steps 4 and 5 become 5 and 6.

Insert after step 3:

```markdown
4. **Check sync status**

Run `git rev-parse HEAD` to get the local HEAD SHA, then call the `check_sync` tool:
- Pass the repo name (from step 3) and the local SHA
- If `status` is `in_sync`: continue silently (report in usage guide)
- If `status` is `out_of_sync`: warn the developer:
  ```
  Warning: Your local repo is out of sync with the index.
  Local SHA:   <local_sha>
  Indexed SHA: <indexed_sha> (indexed at <indexed_at>)
  Run 'git pull' to sync, or push your changes to trigger re-indexing.
  ```
- If `status` is `not_indexed`: inform the developer that indexing is still in progress
```

- [ ] **Step 2: Update step numbers**

Renumber existing step 4 to 5 and step 5 to 6.

- [ ] **Step 3: Update the usage guide in step 6 (formerly step 5)**

Add `check_sync` to the sample commands list:

```markdown
- "Is my local repo in sync with the index?" → check_sync
```

- [ ] **Step 4: Verify the skill file is valid markdown**

Read `skills/connect-index.md` and confirm the formatting is correct.

- [ ] **Step 5: Commit**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
git add skills/connect-index.md
git commit -m "feat: update connect-index skill to check sync status on connect"
```

---

### Task 4: Update Documentation

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update MCP Tools Reference table**

In `CLAUDE.md`, find the MCP Tools Reference table. Add a row for `check_sync`:

```markdown
| `check_sync` | Compare local HEAD SHA with indexed SHA | Sync status, recommended action |
```

Add it after the `get_index_health` row.

- [ ] **Step 2: Verify the table is correctly formatted**

Read `CLAUDE.md` and confirm the table renders properly.

- [ ] **Step 3: Commit**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
git add CLAUDE.md
git commit -m "docs: add check_sync to MCP Tools Reference table"
```

---

## Verification Checklist

After all tasks are complete, verify:

1. `./gradlew build` — BUILD SUCCESSFUL
2. `./gradlew test --tests "com.indexer.mcp.tools.CheckSyncToolTest"` — all 7 tests pass
3. `check_sync` tool is registered (11 tools total in McpServerBootstrap)
4. `skills/connect-index.md` has 6 steps with sync check at step 4
5. `CLAUDE.md` MCP Tools Reference table has 11 rows
