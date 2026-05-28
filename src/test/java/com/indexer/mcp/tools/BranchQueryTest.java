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
    private int repoId;

    @BeforeEach
    void setUp() {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        var jdbi = dbManager.getJdbi();
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM type_relationships");
            h.execute("DELETE FROM imports");
            h.execute("DELETE FROM symbols");
            h.execute("DELETE FROM file_contents");
            h.execute("DELETE FROM files");
            h.execute("DELETE FROM indexing_events");
            h.execute("DELETE FROM repositories");
        });

        var repoDao = new RepositoryDao(jdbi);
        repoId = repoDao.insert(new Repository(0, "test-repo", "url", "main", "/path", "ssh-key", "abc", Instant.now()));

        var fileDao = new FileDao(jdbi);
        var symbolDao = new SymbolDao(jdbi);

        // Main branch: src/App.java with class App, methods run + helper
        int mainAppFileId = fileDao.upsert(new SourceFile(0, repoId, "main", "src/App.java", "java", 500, "abc", Instant.now()));
        symbolDao.insertSymbol(new Symbol(0, mainAppFileId, "App", "class", "public class App", 1, 20, null, "public", false));
        symbolDao.insertSymbol(new Symbol(0, mainAppFileId, "run", "method", "public void run()", 5, 10, null, "public", false));
        symbolDao.insertSymbol(new Symbol(0, mainAppFileId, "helper", "method", "private int helper()", 12, 18, null, "private", false));

        // Main branch: src/Utils.java with class Utils
        int mainUtilsFileId = fileDao.upsert(new SourceFile(0, repoId, "main", "src/Utils.java", "java", 300, "abc", Instant.now()));
        symbolDao.insertSymbol(new Symbol(0, mainUtilsFileId, "Utils", "class", "public class Utils", 1, 15, null, "public", false));

        // Feature branch: src/App.java with class App, methods run + authenticate (different end_line=30)
        int branchAppFileId = fileDao.upsert(new SourceFile(0, repoId, "feature/new-auth", "src/App.java", "java", 600, "def", Instant.now()));
        symbolDao.insertSymbol(new Symbol(0, branchAppFileId, "App", "class", "public class App", 1, 30, null, "public", false));
        symbolDao.insertSymbol(new Symbol(0, branchAppFileId, "run", "method", "public void run()", 5, 10, null, "public", false));
        symbolDao.insertSymbol(new Symbol(0, branchAppFileId, "authenticate", "method", "public boolean authenticate()", 12, 28, null, "public", false));

        // Utils.java only on main — branch sees it via overlay

        queryExecutor = new QueryExecutor(jdbi);
    }

    @SuppressWarnings("unchecked")
    @Test
    void branchQueryReturnsOverlayedSymbols() {
        // Branch query should see symbols from branch App.java AND main Utils.java
        var results = (List<Map<String, Object>>) queryExecutor.searchSymbols(null, "class", null, null, "feature/new-auth", 20);
        assertThat(results).hasSize(2);
        assertThat(results).anyMatch(s -> "App".equals(s.get("name")));
        assertThat(results).anyMatch(s -> "Utils".equals(s.get("name")));
    }

    @SuppressWarnings("unchecked")
    @Test
    void branchFilesTakePriorityOverMainForSamePath() {
        // Search for "authenticate" method — only exists on branch App.java
        var results = (List<Map<String, Object>>) queryExecutor.searchSymbols("authenticate", null, null, null, "feature/new-auth", 20);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("name")).isEqualTo("authenticate");

        // "helper" method only exists on main App.java — should NOT appear for branch
        // because branch App.java replaces main App.java
        var helperResults = (List<Map<String, Object>>) queryExecutor.searchSymbols("helper", null, null, null, "feature/new-auth", 20);
        assertThat(helperResults).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void mainQueryDoesNotSeeBranchData() {
        // Main query should not see "authenticate" method (only on branch)
        var results = (List<Map<String, Object>>) queryExecutor.searchSymbols("authenticate", null, null, null, "main", 20);
        assertThat(results).isEmpty();

        // Main query should still see "helper" method
        var helperResults = (List<Map<String, Object>>) queryExecutor.searchSymbols("helper", null, null, null, "main", 20);
        assertThat(helperResults).hasSize(1);
    }

    @SuppressWarnings("unchecked")
    @Test
    void nullBranchDefaultsToMain() {
        // Null branch should behave exactly like "main"
        var nullResults = (List<Map<String, Object>>) queryExecutor.searchSymbols(null, null, null, null, null, 20);
        var mainResults = (List<Map<String, Object>>) queryExecutor.searchSymbols(null, null, null, null, "main", 20);
        assertThat(nullResults).hasSameSizeAs(mainResults);

        // Should not see "authenticate" (branch-only)
        assertThat(nullResults).noneMatch(s -> "authenticate".equals(s.get("name")));
    }

    @Test
    void branchSeesUnchangedMainFiles() {
        // Utils.java only exists on main — branch should see it via overlay
        var results = queryExecutor.searchFiles("src/Utils.java", null, null, "feature/new-auth", 20);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("path")).isEqualTo("src/Utils.java");
    }

    @Test
    void getRepoSummaryCountsEffectiveFilesForBranch() {
        // Main has 2 files: App.java + Utils.java
        var mainSummary = queryExecutor.getRepoSummary("test-repo", "main");
        assertThat(((Number) mainSummary.get("fileCount")).intValue()).isEqualTo(2);

        // Branch has 2 effective files: branch App.java (overlays main) + main Utils.java
        var branchSummary = queryExecutor.getRepoSummary("test-repo", "feature/new-auth");
        assertThat(((Number) branchSummary.get("fileCount")).intValue()).isEqualTo(2);
    }
}
