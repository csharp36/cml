# Branch-Aware Indexing Phase 1: Schema + Branch-Aware Queries

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `branch` column to the `files` table, a `branch_index` tracking table, update the `SourceFile` model and `FileDao`, and make all QueryExecutor queries branch-aware using a `DISTINCT ON` CTE overlay pattern.

**Architecture:** Flyway migration adds `branch` to `files` (default `'main'`), replaces the unique constraint, and creates `branch_index`. The `SourceFile` record gains a `branch` field. `FileDao` becomes branch-aware. `QueryExecutor` gets an `effectiveFilesCte()` helper that all 10 tool queries use. `McpServerBootstrap` adds an optional `branch` parameter to all tools that accept `repo`. Fully backward compatible — omitting `branch` defaults to the repo's configured branch.

**Tech Stack:** Java 21, Flyway, JDBI, PostgreSQL 16, MCP Java SDK, JUnit 5 + Testcontainers + AssertJ

**Spec:** `docs/superpowers/specs/2026-05-26-branch-indexing-design.md` (sections 4, 5, 11)

---

## File Structure

```
src/main/resources/db/migration/V2__branch_indexing.sql    (create — Flyway migration)
src/main/java/com/indexer/model/SourceFile.java            (modify — add branch field)
src/main/java/com/indexer/db/FileDao.java                  (modify — branch-aware upsert + queries)
src/main/java/com/indexer/indexing/FileIndexer.java        (modify — pass branch to SourceFile)
src/main/java/com/indexer/mcp/QueryExecutor.java           (modify — effectiveFilesCte + branch param on all queries)
src/main/java/com/indexer/mcp/McpServerBootstrap.java      (modify — add branch param to tool schemas + handlers)
src/test/java/com/indexer/mcp/tools/SearchSymbolsToolTest.java  (modify — update SourceFile constructor)
src/test/java/com/indexer/mcp/tools/BranchQueryTest.java   (create — integration tests for branch overlay)
src/test/java/com/indexer/IntegrationSmokeTest.java        (modify — add null branch param to QueryExecutor calls)
```

---

### Task 1: Flyway Migration V2

**Files:**
- Create: `src/main/resources/db/migration/V2__branch_indexing.sql`

- [ ] **Step 1: Create the migration file**

Create `src/main/resources/db/migration/V2__branch_indexing.sql`:

```sql
-- Add branch column to files table
ALTER TABLE files ADD COLUMN branch TEXT NOT NULL DEFAULT 'main';

-- Replace unique constraint to include branch
ALTER TABLE files DROP CONSTRAINT files_repo_id_path_key;
ALTER TABLE files ADD CONSTRAINT files_repo_id_branch_path_key UNIQUE(repo_id, branch, path);

-- Index for branch queries
CREATE INDEX idx_files_branch ON files(repo_id, branch);

-- Add branch column to indexing_events
ALTER TABLE indexing_events ADD COLUMN branch TEXT NOT NULL DEFAULT 'main';

-- Branch index tracking table
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

- [ ] **Step 2: Verify migration applies**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
./gradlew build 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL (migration is applied during integration tests via Testcontainers).

- [ ] **Step 3: Commit**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
git add src/main/resources/db/migration/V2__branch_indexing.sql
git commit -m "feat: add V2 migration — branch column on files, branch_index table"
```

---

### Task 2: Update SourceFile Model + FileDao

**Files:**
- Modify: `src/main/java/com/indexer/model/SourceFile.java`
- Modify: `src/main/java/com/indexer/db/FileDao.java`
- Modify: `src/main/java/com/indexer/indexing/FileIndexer.java`
- Modify: `src/test/java/com/indexer/mcp/tools/SearchSymbolsToolTest.java`

- [ ] **Step 1: Add `branch` field to SourceFile record**

Replace `src/main/java/com/indexer/model/SourceFile.java`:

```java
package com.indexer.model;

import java.time.Instant;

public record SourceFile(int id, int repoId, String path, String language, int sizeBytes, String lastCommitSha, Instant lastModifiedAt, String branch) {}
```

- [ ] **Step 2: Update FileDao for branch-aware operations**

In `src/main/java/com/indexer/db/FileDao.java`, update the `upsert` method to include `branch`:

Replace the upsert method:

```java
    public int upsert(SourceFile file) {
        return jdbi.withHandle(handle ->
                handle.createUpdate("""
                        INSERT INTO files (repo_id, path, language, size_bytes, last_commit_sha, last_modified_at, branch)
                        VALUES (:repoId, :path, :language, :sizeBytes, :lastCommitSha, :lastModifiedAt, :branch)
                        ON CONFLICT (repo_id, branch, path) DO UPDATE
                            SET language        = EXCLUDED.language,
                                size_bytes      = EXCLUDED.size_bytes,
                                last_commit_sha = EXCLUDED.last_commit_sha,
                                last_modified_at = EXCLUDED.last_modified_at
                        """)
                        .bind("repoId", file.repoId())
                        .bind("path", file.path())
                        .bind("language", file.language())
                        .bind("sizeBytes", file.sizeBytes())
                        .bind("lastCommitSha", file.lastCommitSha())
                        .bind("lastModifiedAt", file.lastModifiedAt())
                        .bind("branch", file.branch())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Integer.class)
                        .one()
        );
    }
```

Update both `findByRepoAndPath` and `findByRepo` mappers to include `branch`. Replace `findByRepoAndPath`:

```java
    public Optional<SourceFile> findByRepoAndPath(int repoId, String path) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM files WHERE repo_id = :repoId AND path = :path AND branch = 'main'")
                        .bind("repoId", repoId)
                        .bind("path", path)
                        .map((rs, ctx) -> new SourceFile(
                                rs.getInt("id"),
                                rs.getInt("repo_id"),
                                rs.getString("path"),
                                rs.getString("language"),
                                rs.getInt("size_bytes"),
                                rs.getString("last_commit_sha"),
                                rs.getTimestamp("last_modified_at") != null
                                        ? rs.getTimestamp("last_modified_at").toInstant()
                                        : null,
                                rs.getString("branch")
                        ))
                        .findOne()
        );
    }
```

Replace `findByRepo`:

```java
    public List<SourceFile> findByRepo(int repoId) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM files WHERE repo_id = :repoId AND branch = 'main'")
                        .bind("repoId", repoId)
                        .map((rs, ctx) -> new SourceFile(
                                rs.getInt("id"),
                                rs.getInt("repo_id"),
                                rs.getString("path"),
                                rs.getString("language"),
                                rs.getInt("size_bytes"),
                                rs.getString("last_commit_sha"),
                                rs.getTimestamp("last_modified_at") != null
                                        ? rs.getTimestamp("last_modified_at").toInstant()
                                        : null,
                                rs.getString("branch")
                        ))
                        .list()
        );
    }
```

- [ ] **Step 3: Update FileIndexer to pass branch**

In `src/main/java/com/indexer/indexing/FileIndexer.java`, update the two places where `new SourceFile(...)` is constructed.

At line 84 (inside `indexFile` method), change:
```java
        SourceFile sourceFile = new SourceFile(0, repoId, relativePath, language, (int) fileSize, commitSha, Instant.now());
```
to:
```java
        SourceFile sourceFile = new SourceFile(0, repoId, relativePath, language, (int) fileSize, commitSha, Instant.now(), "main");
```

At line 112 (inside `indexMetadataOnly` method), change the same pattern:
```java
        SourceFile sourceFile = new SourceFile(0, repoId, relativePath, language, (int) fileSize, commitSha, Instant.now());
```
to:
```java
        SourceFile sourceFile = new SourceFile(0, repoId, relativePath, language, (int) fileSize, commitSha, Instant.now(), "main");
```

- [ ] **Step 4: Update SearchSymbolsToolTest**

In `src/test/java/com/indexer/mcp/tools/SearchSymbolsToolTest.java`, update the `SourceFile` constructor at line 35:

Change:
```java
        int fileId = fileDao.upsert(new SourceFile(0, repoId, "src/App.java", "java", 500, "abc", Instant.now()));
```
to:
```java
        int fileId = fileDao.upsert(new SourceFile(0, repoId, "src/App.java", "java", 500, "abc", Instant.now(), "main"));
```

- [ ] **Step 5: Verify build and tests pass**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
./gradlew build 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL, all existing tests pass.

- [ ] **Step 6: Commit**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
git add src/main/java/com/indexer/model/SourceFile.java src/main/java/com/indexer/db/FileDao.java src/main/java/com/indexer/indexing/FileIndexer.java src/test/java/com/indexer/mcp/tools/SearchSymbolsToolTest.java
git commit -m "feat: add branch field to SourceFile model and FileDao"
```

---

### Task 3: Branch-Aware QueryExecutor

**Files:**
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java`
- Create: `src/test/java/com/indexer/mcp/tools/BranchQueryTest.java`

- [ ] **Step 1: Write integration tests for branch overlay queries**

Create `src/test/java/com/indexer/mcp/tools/BranchQueryTest.java`:

```java
package com.indexer.mcp.tools;

import com.indexer.db.*;
import com.indexer.mcp.QueryExecutor;
import com.indexer.model.Repository;
import com.indexer.model.SourceFile;
import com.indexer.model.Symbol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class BranchQueryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private QueryExecutor queryExecutor;

    @BeforeEach
    void setUp() {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        var jdbi = dbManager.getJdbi();
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM symbols");
            h.execute("DELETE FROM files");
            h.execute("DELETE FROM repositories");
        });

        var repoDao = new RepositoryDao(jdbi);
        int repoId = repoDao.insert(new Repository(0, "test-repo", "url", "main", "/path", "ssh-key", "abc", Instant.now()));

        var fileDao = new FileDao(jdbi);
        var symbolDao = new SymbolDao(jdbi);

        // Main branch: App.java with class App (lines 1-20)
        int mainFileId = fileDao.upsert(new SourceFile(0, repoId, "src/App.java", "java", 500, "abc", Instant.now(), "main"));
        symbolDao.insertSymbol(new Symbol(0, mainFileId, "App", "class", "public class App", 1, 20, null, "public", false));
        symbolDao.insertSymbol(new Symbol(0, mainFileId, "run", "method", "public void run()", 5, 10, null, "public", false));

        // Main branch: Utils.java (not changed on branch)
        int utilsFileId = fileDao.upsert(new SourceFile(0, repoId, "src/Utils.java", "java", 300, "abc", Instant.now(), "main"));
        symbolDao.insertSymbol(new Symbol(0, utilsFileId, "Utils", "class", "public class Utils", 1, 15, null, "public", false));

        // Feature branch: App.java with modified App class (lines 1-30, new method added)
        int branchFileId = fileDao.upsert(new SourceFile(0, repoId, "src/App.java", "java", 700, "def", Instant.now(), "feature/new-auth"));
        symbolDao.insertSymbol(new Symbol(0, branchFileId, "App", "class", "public class App", 1, 30, null, "public", false));
        symbolDao.insertSymbol(new Symbol(0, branchFileId, "run", "method", "public void run()", 5, 10, null, "public", false));
        symbolDao.insertSymbol(new Symbol(0, branchFileId, "authenticate", "method", "public boolean authenticate(String token)", 20, 28, null, "public", false));

        queryExecutor = new QueryExecutor(jdbi);
    }

    @Test
    void branchQueryReturnsOverlayedSymbols() {
        // Searching on the feature branch should find 'authenticate' (from branch App.java)
        // and 'Utils' (from main, since Utils.java is not changed on the branch)
        var results = queryExecutor.searchSymbols("authenticate|Utils", null, null, "test-repo", "feature/new-auth", 20);

        assertThat(results).hasSize(2);
        var names = results.stream().map(r -> r.get("name")).toList();
        assertThat(names).containsExactlyInAnyOrder("authenticate", "Utils");
    }

    @Test
    void branchQueryPrioritizesBranchFile() {
        // Searching for 'App' on the feature branch should return the BRANCH version
        var results = queryExecutor.searchSymbols("App", "class", null, "test-repo", "feature/new-auth", 20);

        assertThat(results).hasSize(1);
        // The branch version has end_line=30 (vs main's end_line=20)
        assertThat(((Number) results.get(0).get("end_line")).intValue()).isEqualTo(30);
    }

    @Test
    void mainQueryUnchanged() {
        // Searching on main should NOT see the branch-only 'authenticate' method
        var results = queryExecutor.searchSymbols("authenticate", null, null, "test-repo", "main", 20);

        assertThat(results).isEmpty();
    }

    @Test
    void nullBranchDefaultsToMain() {
        // Passing null for branch should behave like main
        var results = queryExecutor.searchSymbols("authenticate", null, null, "test-repo", null, 20);

        assertThat(results).isEmpty();
    }

    @Test
    void branchSeesUnchangedMainFiles() {
        // Searching on the feature branch for Utils (only on main) should find it
        var results = queryExecutor.searchSymbols("Utils", null, null, "test-repo", "feature/new-auth", 20);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("name")).isEqualTo("Utils");
    }

    @Test
    void getRepoSummaryIncludesBranchFiles() {
        // Repo summary for feature branch should count effective files (2: branch App.java + main Utils.java)
        var result = queryExecutor.getRepoSummary("test-repo", "feature/new-auth");

        assertThat(((Number) result.get("fileCount")).longValue()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
./gradlew test --tests "com.indexer.mcp.tools.BranchQueryTest" 2>&1 | tail -10
```

Expected: Compilation failure — `searchSymbols` doesn't accept a `branch` parameter yet.

- [ ] **Step 3: Add `effectiveFilesCte` helper to QueryExecutor**

In `src/main/java/com/indexer/mcp/QueryExecutor.java`, add this helper method in the "Internal helpers" section (before `readSourceLines`):

```java
    /**
     * Build a CTE that returns the effective files for a repo+branch combination.
     * Branch-specific files take priority over main files for the same path.
     * When branch is null or "main", this returns only main files.
     */
    private String effectiveFilesCte(String branch) {
        String effectiveBranch = (branch == null || branch.isBlank()) ? "main" : branch;
        if ("main".equals(effectiveBranch)) {
            return """
                    WITH effective_files AS (
                        SELECT f.id, f.repo_id, f.path, f.language, f.size_bytes,
                               f.last_commit_sha, f.last_modified_at, f.branch
                        FROM files f
                        WHERE f.branch = 'main'
                    )
                    """;
        }
        return """
                WITH effective_files AS (
                    SELECT DISTINCT ON (f.repo_id, f.path)
                           f.id, f.repo_id, f.path, f.language, f.size_bytes,
                           f.last_commit_sha, f.last_modified_at, f.branch
                    FROM files f
                    WHERE f.branch IN (:branch, 'main')
                    ORDER BY f.repo_id, f.path,
                             CASE WHEN f.branch = :branch THEN 0 ELSE 1 END
                )
                """;
    }

    /**
     * Resolve branch to a non-null value. Null or blank defaults to "main".
     */
    private String resolveBranch(String branch) {
        return (branch == null || branch.isBlank()) ? "main" : branch;
    }
```

- [ ] **Step 4: Update `searchSymbols` to accept branch**

Replace the `searchSymbols` method:

```java
    /**
     * Search symbols by name (regex), kind, language, repo, and branch.
     */
    public List<Map<String, Object>> searchSymbols(String query, String kind, String language, String repo, String branch, int limit) {
        String effectiveBranch = resolveBranch(branch);
        return jdbi.withHandle(handle -> {
            var sb = new StringBuilder(effectiveFilesCte(effectiveBranch));
            sb.append("""
                    SELECT s.name, s.kind, s.signature, s.start_line, s.end_line, s.visibility,
                           ef.path AS file_path, r.name AS repo_name
                    FROM symbols s
                    JOIN effective_files ef ON s.file_id = ef.id
                    JOIN repositories r ON ef.repo_id = r.id
                    WHERE 1=1
                    """);

            var params = new LinkedHashMap<String, Object>();
            if (!"main".equals(effectiveBranch)) {
                params.put("branch", effectiveBranch);
            }

            if (query != null && !query.isBlank()) {
                sb.append(" AND s.name ~* :query");
                params.put("query", query);
            }
            if (kind != null && !kind.isBlank()) {
                sb.append(" AND s.kind = :kind");
                params.put("kind", kind);
            }
            if (language != null && !language.isBlank()) {
                sb.append(" AND ef.language = :language");
                params.put("language", language);
            }
            if (repo != null && !repo.isBlank()) {
                sb.append(" AND r.name = :repo");
                params.put("repo", repo);
            }

            sb.append(" ORDER BY s.name LIMIT :limit");
            params.put("limit", limit);

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            return q.mapToMap().list();
        });
    }
```

- [ ] **Step 5: Update `getSymbolDetail` to accept branch**

Replace the `getSymbolDetail` method:

```java
    /**
     * Get detailed information about a specific symbol, including source code, children, and type relationships.
     */
    public Map<String, Object> getSymbolDetail(String repo, String filePath, String symbolName, Integer line, String branch) {
        String effectiveBranch = resolveBranch(branch);
        return jdbi.withHandle(handle -> {
            var sb = new StringBuilder(effectiveFilesCte(effectiveBranch));
            sb.append("""
                    SELECT s.id, s.name, s.kind, s.signature, s.start_line, s.end_line,
                           s.parent_id, s.visibility, s.is_static,
                           ef.path AS file_path, r.name AS repo_name, r.clone_path
                    FROM symbols s
                    JOIN effective_files ef ON s.file_id = ef.id
                    JOIN repositories r ON ef.repo_id = r.id
                    WHERE r.name = :repo AND ef.path = :filePath AND s.name = :symbolName
                    """);

            var params = new LinkedHashMap<String, Object>();
            params.put("repo", repo);
            params.put("filePath", filePath);
            params.put("symbolName", symbolName);
            if (!"main".equals(effectiveBranch)) {
                params.put("branch", effectiveBranch);
            }

            if (line != null) {
                sb.append(" AND s.start_line = :line");
                params.put("line", line);
            }

            sb.append(" LIMIT 1");

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            var optSymbol = q.mapToMap().findOne();

            if (optSymbol.isEmpty()) {
                return Collections.<String, Object>emptyMap();
            }

            var symbol = new LinkedHashMap<>(optSymbol.get());
            int symbolId = ((Number) symbol.get("id")).intValue();
            int startLine = ((Number) symbol.get("start_line")).intValue();
            int endLine = ((Number) symbol.get("end_line")).intValue();
            String clonePath = (String) symbol.get("clone_path");

            symbol.put("source_code", readSourceLines(clonePath, filePath, startLine, endLine));

            var children = handle.createQuery("""
                    SELECT name, kind, signature, start_line, end_line, visibility
                    FROM symbols
                    WHERE parent_id = :parentId
                    ORDER BY start_line
                    """)
                    .bind("parentId", symbolId)
                    .mapToMap()
                    .list();
            symbol.put("children", children);

            var relationships = handle.createQuery("""
                    SELECT related_name, kind
                    FROM type_relationships
                    WHERE symbol_id = :symbolId
                    """)
                    .bind("symbolId", symbolId)
                    .mapToMap()
                    .list();
            symbol.put("relationships", relationships);

            return (Map<String, Object>) symbol;
        });
    }
```

- [ ] **Step 6: Update `findImplementations` to accept branch**

Replace the `findImplementations` method:

```java
    /**
     * Find implementations of a type by looking up 'implements' relationships.
     */
    public List<Map<String, Object>> findImplementations(String typeName, String repo, String branch) {
        String effectiveBranch = resolveBranch(branch);
        return jdbi.withHandle(handle -> {
            var sb = new StringBuilder(effectiveFilesCte(effectiveBranch));
            sb.append("""
                    SELECT s.name AS class_name, s.signature, ef.path AS file_path, r.name AS repo_name
                    FROM type_relationships tr
                    JOIN symbols s ON tr.symbol_id = s.id
                    JOIN effective_files ef ON s.file_id = ef.id
                    JOIN repositories r ON ef.repo_id = r.id
                    WHERE tr.related_name = :typeName AND tr.kind = 'implements'
                    """);

            var params = new LinkedHashMap<String, Object>();
            params.put("typeName", typeName);
            if (!"main".equals(effectiveBranch)) {
                params.put("branch", effectiveBranch);
            }

            if (repo != null && !repo.isBlank()) {
                sb.append(" AND r.name = :repo");
                params.put("repo", repo);
            }

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            return q.mapToMap().list();
        });
    }
```

- [ ] **Step 7: Update `findReferences` to accept branch**

Replace the `findReferences` method:

```java
    /**
     * Find files that import a given symbol name.
     */
    public List<Map<String, Object>> findReferences(String symbolName, String repo, String branch, int limit) {
        String effectiveBranch = resolveBranch(branch);
        return jdbi.withHandle(handle -> {
            var sb = new StringBuilder(effectiveFilesCte(effectiveBranch));
            sb.append("""
                    SELECT ef.path AS file_path, r.name AS repo_name, i.import_path
                    FROM imports i
                    JOIN effective_files ef ON i.file_id = ef.id
                    JOIN repositories r ON ef.repo_id = r.id
                    WHERE i.import_path LIKE :pattern
                    """);

            var params = new LinkedHashMap<String, Object>();
            params.put("pattern", "%" + symbolName + "%");
            if (!"main".equals(effectiveBranch)) {
                params.put("branch", effectiveBranch);
            }

            if (repo != null && !repo.isBlank()) {
                sb.append(" AND r.name = :repo");
                params.put("repo", repo);
            }

            sb.append(" LIMIT :limit");
            params.put("limit", limit);

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            return q.mapToMap().list();
        });
    }
```

- [ ] **Step 8: Update `searchCode` to accept branch**

Replace the `searchCode` method:

```java
    /**
     * Full-text search across file contents using PostgreSQL tsvector/tsquery.
     */
    public List<Map<String, Object>> searchCode(String query, String language, String repo, String branch, int limit) {
        String effectiveBranch = resolveBranch(branch);
        return jdbi.withHandle(handle -> {
            var sb = new StringBuilder(effectiveFilesCte(effectiveBranch));
            sb.append("""
                    SELECT ef.path AS file_path, r.name AS repo_name,
                           ts_headline('english', fc.content, plainto_tsquery('english', :query),
                                       'StartSel=<<, StopSel=>>, MaxWords=30, MinWords=10') AS matching_lines
                    FROM file_contents fc
                    JOIN effective_files ef ON fc.file_id = ef.id
                    JOIN repositories r ON ef.repo_id = r.id
                    WHERE fc.search_vector @@ plainto_tsquery('english', :query)
                    """);

            var params = new LinkedHashMap<String, Object>();
            params.put("query", query);
            if (!"main".equals(effectiveBranch)) {
                params.put("branch", effectiveBranch);
            }

            if (language != null && !language.isBlank()) {
                sb.append(" AND ef.language = :language");
                params.put("language", language);
            }
            if (repo != null && !repo.isBlank()) {
                sb.append(" AND r.name = :repo");
                params.put("repo", repo);
            }

            sb.append(" LIMIT :limit");
            params.put("limit", limit);

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            return q.mapToMap().list();
        });
    }
```

- [ ] **Step 9: Update `searchFiles` to accept branch**

Replace the `searchFiles` method:

```java
    /**
     * Search files by glob-style pattern (e.g., "*.java", "src/**").
     */
    public List<Map<String, Object>> searchFiles(String pattern, String language, String repo, String branch, int limit) {
        String effectiveBranch = resolveBranch(branch);
        return jdbi.withHandle(handle -> {
            String sqlPattern = pattern != null ? pattern.replace("*", "%") : "%";

            var sb = new StringBuilder(effectiveFilesCte(effectiveBranch));
            sb.append("""
                    SELECT ef.path, r.name AS repo_name, ef.language, ef.size_bytes, ef.last_modified_at
                    FROM effective_files ef
                    JOIN repositories r ON ef.repo_id = r.id
                    WHERE ef.path LIKE :pattern
                    """);

            var params = new LinkedHashMap<String, Object>();
            params.put("pattern", sqlPattern);
            if (!"main".equals(effectiveBranch)) {
                params.put("branch", effectiveBranch);
            }

            if (language != null && !language.isBlank()) {
                sb.append(" AND ef.language = :language");
                params.put("language", language);
            }
            if (repo != null && !repo.isBlank()) {
                sb.append(" AND r.name = :repo");
                params.put("repo", repo);
            }

            sb.append(" ORDER BY ef.path LIMIT :limit");
            params.put("limit", limit);

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            return q.mapToMap().list();
        });
    }
```

- [ ] **Step 10: Update `getRepoSummary` to accept branch**

Replace the `getRepoSummary` method:

```java
    /**
     * Get a high-level summary of a repository.
     */
    public Map<String, Object> getRepoSummary(String repoName, String branch) {
        String effectiveBranch = resolveBranch(branch);
        return jdbi.withHandle(handle -> {
            var optRepo = handle.createQuery("""
                    SELECT id, name, url, branch, clone_path, auth_type, last_indexed_sha, last_indexed_at
                    FROM repositories WHERE name = :name
                    """)
                    .bind("name", repoName)
                    .mapToMap()
                    .findOne();

            if (optRepo.isEmpty()) {
                return Collections.<String, Object>emptyMap();
            }

            var result = new LinkedHashMap<>(optRepo.get());
            int repoId = ((Number) result.get("id")).intValue();

            // Count effective files for the branch
            String countSql;
            if ("main".equals(effectiveBranch)) {
                countSql = "SELECT COUNT(*) FROM files WHERE repo_id = :repoId AND branch = 'main'";
            } else {
                countSql = """
                        SELECT COUNT(*) FROM (
                            SELECT DISTINCT ON (path) path
                            FROM files
                            WHERE repo_id = :repoId AND branch IN (:branch, 'main')
                            ORDER BY path, CASE WHEN branch = :branch THEN 0 ELSE 1 END
                        ) effective
                        """;
            }
            var countQuery = handle.createQuery(countSql).bind("repoId", repoId);
            if (!"main".equals(effectiveBranch)) {
                countQuery.bind("branch", effectiveBranch);
            }
            long fileCount = countQuery.mapTo(Long.class).one();
            result.put("fileCount", fileCount);

            // Language breakdown from effective files
            String langSql;
            if ("main".equals(effectiveBranch)) {
                langSql = """
                        SELECT language, COUNT(*) AS count
                        FROM files WHERE repo_id = :repoId AND branch = 'main' AND language IS NOT NULL
                        GROUP BY language ORDER BY count DESC
                        """;
            } else {
                langSql = """
                        SELECT language, COUNT(*) AS count FROM (
                            SELECT DISTINCT ON (path) path, language
                            FROM files
                            WHERE repo_id = :repoId AND branch IN (:branch, 'main')
                            ORDER BY path, CASE WHEN branch = :branch THEN 0 ELSE 1 END
                        ) effective
                        WHERE language IS NOT NULL
                        GROUP BY language ORDER BY count DESC
                        """;
            }
            var langQuery = handle.createQuery(langSql).bind("repoId", repoId);
            if (!"main".equals(effectiveBranch)) {
                langQuery.bind("branch", effectiveBranch);
            }
            result.put("languageBreakdown", langQuery.mapToMap().list());

            // Top-level directories from effective files
            String dirSql;
            if ("main".equals(effectiveBranch)) {
                dirSql = """
                        SELECT DISTINCT split_part(path, '/', 1) AS dir
                        FROM files WHERE repo_id = :repoId AND branch = 'main' AND path LIKE '%/%'
                        ORDER BY dir
                        """;
            } else {
                dirSql = """
                        SELECT DISTINCT split_part(path, '/', 1) AS dir FROM (
                            SELECT DISTINCT ON (path) path
                            FROM files
                            WHERE repo_id = :repoId AND branch IN (:branch, 'main')
                            ORDER BY path, CASE WHEN branch = :branch THEN 0 ELSE 1 END
                        ) effective
                        WHERE path LIKE '%/%'
                        ORDER BY dir
                        """;
            }
            var dirQuery = handle.createQuery(dirSql).bind("repoId", repoId);
            if (!"main".equals(effectiveBranch)) {
                dirQuery.bind("branch", effectiveBranch);
            }
            result.put("topLevelDirectories", dirQuery.mapTo(String.class).list());

            return (Map<String, Object>) result;
        });
    }
```

- [ ] **Step 11: Update `getFileSummary` to accept branch**

Replace the `getFileSummary` method:

```java
    /**
     * Get a summary of a specific file including its symbols and imports.
     */
    public Map<String, Object> getFileSummary(String repoName, String filePath, String branch) {
        String effectiveBranch = resolveBranch(branch);
        return jdbi.withHandle(handle -> {
            var sb = new StringBuilder(effectiveFilesCte(effectiveBranch));
            sb.append("""
                    SELECT ef.id, ef.path, ef.language, ef.size_bytes, ef.last_commit_sha, ef.last_modified_at,
                           r.name AS repo_name
                    FROM effective_files ef
                    JOIN repositories r ON ef.repo_id = r.id
                    WHERE r.name = :repoName AND ef.path = :filePath
                    """);

            var params = new LinkedHashMap<String, Object>();
            params.put("repoName", repoName);
            params.put("filePath", filePath);
            if (!"main".equals(effectiveBranch)) {
                params.put("branch", effectiveBranch);
            }

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            var optFile = q.mapToMap().findOne();

            if (optFile.isEmpty()) {
                return Collections.<String, Object>emptyMap();
            }

            var result = new LinkedHashMap<>(optFile.get());
            int fileId = ((Number) result.get("id")).intValue();

            var symbols = handle.createQuery("""
                    SELECT name, kind, signature, start_line
                    FROM symbols WHERE file_id = :fileId
                    ORDER BY start_line
                    """)
                    .bind("fileId", fileId)
                    .mapToMap()
                    .list();
            result.put("symbols", symbols);

            var imports = handle.createQuery("""
                    SELECT import_path, alias
                    FROM imports WHERE file_id = :fileId
                    ORDER BY import_path
                    """)
                    .bind("fileId", fileId)
                    .mapToMap()
                    .list();
            result.put("imports", imports);

            return (Map<String, Object>) result;
        });
    }
```

- [ ] **Step 12: Update `getDirectoryTree` to accept branch**

Replace the `getDirectoryTree` method:

```java
    /**
     * Get a flat directory tree for a repository path prefix.
     */
    public List<Map<String, Object>> getDirectoryTree(String repoName, String path, int depth, String branch) {
        String effectiveBranch = resolveBranch(branch);
        return jdbi.withHandle(handle -> {
            String prefix = (path != null && !path.isBlank()) ? path : "";
            String pattern = prefix.isEmpty() ? "%" : (prefix.endsWith("/") ? prefix + "%" : prefix + "/%");

            var sb = new StringBuilder(effectiveFilesCte(effectiveBranch));
            sb.append("""
                    SELECT ef.path, ef.language
                    FROM effective_files ef
                    JOIN repositories r ON ef.repo_id = r.id
                    WHERE r.name = :repoName AND ef.path LIKE :pattern
                    ORDER BY ef.path
                    """);

            var params = new LinkedHashMap<String, Object>();
            params.put("repoName", repoName);
            params.put("pattern", pattern);
            if (!"main".equals(effectiveBranch)) {
                params.put("branch", effectiveBranch);
            }

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            return q.mapToMap().list();
        });
    }
```

- [ ] **Step 13: Update `getIndexHealth` — no branch parameter needed**

The `getIndexHealth` method stays unchanged — it reports system-wide stats, not branch-specific.

- [ ] **Step 14: Update the existing `SearchSymbolsToolTest` callers**

In `src/test/java/com/indexer/mcp/tools/SearchSymbolsToolTest.java`, update the 4 test methods that call `searchSymbols` to pass `null` for the new `branch` parameter:

Change all calls from:
```java
queryExecutor.searchSymbols("App", null, null, null, 20);
```
to:
```java
queryExecutor.searchSymbols("App", null, null, null, null, 20);
```

There are 4 calls to update:
- `searchesByName()` — `searchSymbols("App", null, null, null, null, 20)`
- `searchesByKind()` — `searchSymbols(null, "method", null, null, null, 20)`
- `searchesByNamePattern()` — `searchSymbols("run", null, null, null, null, 20)`
- `respectsLimit()` — `searchSymbols(null, null, null, null, null, 1)`

- [ ] **Step 15: Update IntegrationSmokeTest**

In `src/test/java/com/indexer/IntegrationSmokeTest.java`, update all `queryExecutor` calls to include the new `branch` parameter (pass `null` for default/main behavior):

- `queryExecutor.searchSymbols("ServiceImpl", null, null, null, 20)` → `queryExecutor.searchSymbols("ServiceImpl", null, null, null, null, 20)`
- `queryExecutor.findImplementations("Service", null)` → `queryExecutor.findImplementations("Service", null, null)`
- `queryExecutor.searchCode("getData", null, null, 10)` → `queryExecutor.searchCode("getData", null, null, null, 10)`
- `queryExecutor.getRepoSummary("smoke-repo")` → `queryExecutor.getRepoSummary("smoke-repo", null)`

`getIndexHealth()` has no branch parameter — no change needed.

- [ ] **Step 16: Run all tests**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
./gradlew build 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL, all tests pass including the new BranchQueryTest.

- [ ] **Step 17: Commit**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
git add src/main/java/com/indexer/mcp/QueryExecutor.java src/test/java/com/indexer/mcp/tools/BranchQueryTest.java src/test/java/com/indexer/mcp/tools/SearchSymbolsToolTest.java src/test/java/com/indexer/IntegrationSmokeTest.java
git commit -m "feat: branch-aware QueryExecutor — effectiveFilesCte overlay, branch param on all queries"
```

---

### Task 4: Update McpServerBootstrap — Branch Parameter on All Tools

**Files:**
- Modify: `src/main/java/com/indexer/mcp/McpServerBootstrap.java`

- [ ] **Step 1: Add `branch` to tool schemas**

In each tool definition method that has a `repo` or `repo_name` parameter, add a `branch` entry to the properties map. The `branch` parameter is optional (not in the `required` list).

Add this entry to the `Map.of(...)` in each tool schema:

```java
"branch", Map.of("type", "string", "description", "Branch name (defaults to repo's configured branch, usually 'main')")
```

Tools to update (9 of 10 — `getIndexHealth` has no repo param):
- `searchSymbolsTool()`
- `getSymbolDetailTool()`
- `findImplementationsTool()`
- `findReferencesTool()`
- `searchCodeTool()`
- `searchFilesTool()`
- `getRepoSummaryTool()`
- `getFileSummaryTool()`
- `getDirectoryTreeTool()`

For example, `searchSymbolsTool()` becomes:

```java
    private McpSchema.Tool searchSymbolsTool() {
        var props = new LinkedHashMap<String, Object>();
        props.put("query",    Map.of("type", "string", "description", "Regex pattern to match symbol names"));
        props.put("kind",     Map.of("type", "string", "description", "Symbol kind (class, method, function, ...)"));
        props.put("language", Map.of("type", "string", "description", "Programming language filter"));
        props.put("repo",     Map.of("type", "string", "description", "Repository name filter"));
        props.put("branch",   Map.of("type", "string", "description", "Branch name (defaults to repo's configured branch, usually 'main')"));
        props.put("limit",    Map.of("type", "integer", "description", "Max results to return", "default", 20));
        var schema = new McpSchema.JsonSchema("object", props, List.of(), false, null, null);
        return new McpSchema.Tool("search_symbols",
                "Search for symbols (classes, methods, functions) by name pattern, kind, language, or repo.",
                schema);
    }
```

**Note:** The `Map.of()` calls in the existing code may need to change to `new LinkedHashMap<>()` since `Map.of()` is limited to 10 entries. Check each tool — if adding `branch` would exceed the entry limit, switch to `LinkedHashMap`.

- [ ] **Step 2: Update tool handlers to pass `branch`**

Update each handler to extract the `branch` argument and pass it to the QueryExecutor method:

`handleSearchSymbols`:
```java
    private McpSchema.CallToolResult handleSearchSymbols(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            String query    = stringArg(args, "query");
            String kind     = stringArg(args, "kind");
            String language = stringArg(args, "language");
            String repo     = stringArg(args, "repo");
            String branch   = stringArg(args, "branch");
            int limit       = intArg(args, "limit", 20);

            var results = queryExecutor.searchSymbols(query, kind, language, repo, branch, limit);
            return jsonResult(results);
        } catch (Exception e) {
            return errorResult(e);
        }
    }
```

`handleGetSymbolDetail`:
```java
    private McpSchema.CallToolResult handleGetSymbolDetail(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            String repo       = stringArg(args, "repo");
            String filePath   = stringArg(args, "file_path");
            String symbolName = stringArg(args, "symbol_name");
            Integer line      = args.containsKey("line") ? intArg(args, "line", 0) : null;
            String branch     = stringArg(args, "branch");

            var result = queryExecutor.getSymbolDetail(repo, filePath, symbolName, line, branch);
            return jsonResult(result);
        } catch (Exception e) {
            return errorResult(e);
        }
    }
```

`handleFindImplementations`:
```java
    private McpSchema.CallToolResult handleFindImplementations(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            String typeName = stringArg(args, "type_name");
            String repo     = stringArg(args, "repo");
            String branch   = stringArg(args, "branch");

            var results = queryExecutor.findImplementations(typeName, repo, branch);
            return jsonResult(results);
        } catch (Exception e) {
            return errorResult(e);
        }
    }
```

`handleFindReferences`:
```java
    private McpSchema.CallToolResult handleFindReferences(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            String symbolName = stringArg(args, "symbol_name");
            String repo       = stringArg(args, "repo");
            String branch     = stringArg(args, "branch");
            int limit         = intArg(args, "limit", 20);

            var results = queryExecutor.findReferences(symbolName, repo, branch, limit);
            return jsonResult(results);
        } catch (Exception e) {
            return errorResult(e);
        }
    }
```

`handleSearchCode`:
```java
    private McpSchema.CallToolResult handleSearchCode(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            String query    = stringArg(args, "query");
            String language = stringArg(args, "language");
            String repo     = stringArg(args, "repo");
            String branch   = stringArg(args, "branch");
            int limit       = intArg(args, "limit", 20);

            var results = queryExecutor.searchCode(query, language, repo, branch, limit);
            return jsonResult(results);
        } catch (Exception e) {
            return errorResult(e);
        }
    }
```

`handleSearchFiles`:
```java
    private McpSchema.CallToolResult handleSearchFiles(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            String pattern  = stringArg(args, "pattern");
            String language = stringArg(args, "language");
            String repo     = stringArg(args, "repo");
            String branch   = stringArg(args, "branch");
            int limit       = intArg(args, "limit", 50);

            var results = queryExecutor.searchFiles(pattern, language, repo, branch, limit);
            return jsonResult(results);
        } catch (Exception e) {
            return errorResult(e);
        }
    }
```

`handleGetRepoSummary`:
```java
    private McpSchema.CallToolResult handleGetRepoSummary(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            String repoName = stringArg(args, "repo_name");
            String branch   = stringArg(args, "branch");
            var result = queryExecutor.getRepoSummary(repoName, branch);
            return jsonResult(result);
        } catch (Exception e) {
            return errorResult(e);
        }
    }
```

`handleGetFileSummary`:
```java
    private McpSchema.CallToolResult handleGetFileSummary(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            String repoName = stringArg(args, "repo_name");
            String filePath = stringArg(args, "file_path");
            String branch   = stringArg(args, "branch");
            var result = queryExecutor.getFileSummary(repoName, filePath, branch);
            return jsonResult(result);
        } catch (Exception e) {
            return errorResult(e);
        }
    }
```

`handleGetDirectoryTree`:
```java
    private McpSchema.CallToolResult handleGetDirectoryTree(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            String repoName = stringArg(args, "repo_name");
            String path     = stringArg(args, "path");
            int depth       = intArg(args, "depth", 3);
            String branch   = stringArg(args, "branch");

            var results = queryExecutor.getDirectoryTree(repoName, path, depth, branch);
            return jsonResult(results);
        } catch (Exception e) {
            return errorResult(e);
        }
    }
```

`handleGetIndexHealth` — **no changes needed** (no branch param).

- [ ] **Step 3: Verify build and all tests pass**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
./gradlew build 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: Commit**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
git add src/main/java/com/indexer/mcp/McpServerBootstrap.java
git commit -m "feat: add optional branch parameter to all MCP tool schemas and handlers"
```

---

## Verification Checklist

After all tasks are complete, verify:

1. `./gradlew build` — BUILD SUCCESSFUL
2. `./gradlew test --tests "com.indexer.mcp.tools.BranchQueryTest"` — all 6 branch overlay tests pass
3. `./gradlew test --tests "com.indexer.mcp.tools.SearchSymbolsToolTest"` — existing tests still pass
4. `V2__branch_indexing.sql` migration applies cleanly
5. All 9 tool schemas in McpServerBootstrap include optional `branch` parameter
6. QueryExecutor methods all accept a `branch` parameter
7. Passing `branch=null` to any tool produces identical results to the pre-branch behavior
