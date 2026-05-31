package com.indexer.integration;

import com.indexer.config.LanguageRegistry;
import com.indexer.db.*;
import com.indexer.indexing.FileIndexer;
import com.indexer.indexing.IndexingPipeline;
import com.indexer.indexing.SymbolExtractor;
import com.indexer.mcp.QueryExecutor;
import com.indexer.model.Repository;
import com.indexer.repository.GitOperations;
import com.indexer.repository.RefKind;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test proving the default-branch generalization works for repos
 * whose configured base branch is NOT "main".
 *
 * Test 1 — develop-default overlay + diff:
 *   A repo initialised with "develop" as the default branch is fully indexed,
 *   a feature branch is overlaid, and we assert that:
 *   - getRepoSummary(repo, null) returns fileCount > 0  (regression: returned 0 with hardcoded 'main')
 *   - searchSymbols with branch=null finds symbols on the develop baseline
 *   - diffBranches(feature/x, develop) reports the one changed file
 *
 * Test 2 — origin/HEAD fallback:
 *   A clone where origin/HEAD points at develop; GitOperations.detectDefaultBranch
 *   must return Optional.of("develop").
 */
@Testcontainers
@Tag("integration")
class DefaultBranchGeneralizationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    @TempDir
    Path tempDir;

    private Jdbi jdbi;
    private RepositoryDao repositoryDao;
    private BranchIndexDao branchIndexDao;
    private FileDao fileDao;
    private SymbolDao symbolDao;
    private IndexingPipeline pipeline;
    private GitOperations gitOps;
    private QueryExecutor queryExecutor;

    @BeforeEach
    void setUp() {
        var dbManager = new DatabaseManager(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        jdbi = dbManager.getJdbi();

        // Clean slate between tests (shared static container)
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM branch_index");
            h.execute("DELETE FROM type_relationships");
            h.execute("DELETE FROM imports");
            h.execute("DELETE FROM symbols");
            h.execute("DELETE FROM file_contents");
            h.execute("DELETE FROM files");
            h.execute("DELETE FROM repositories");
        });

        repositoryDao = new RepositoryDao(jdbi);
        branchIndexDao = new BranchIndexDao(jdbi);
        fileDao = new FileDao(jdbi);
        symbolDao = new SymbolDao(jdbi);
        gitOps = new GitOperations();

        var languageRegistry = new LanguageRegistry(Map.of());
        var symbolExtractor = new SymbolExtractor();
        var fileIndexer = new FileIndexer(fileDao, symbolDao, jdbi, languageRegistry, symbolExtractor, 1_048_576);
        pipeline = new IndexingPipeline(repositoryDao, fileIndexer, gitOps, branchIndexDao);

        queryExecutor = new QueryExecutor(jdbi, branchIndexDao, pipeline, repositoryDao, gitOps);
    }

    // -----------------------------------------------------------------------
    // Test 1: develop-default repo — overlay + diff regression
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void overlayAndDiffWorkForDevelopDefaultRepo() throws Exception {
        // 1. Create a git repo whose default branch is "develop" (NOT "main").
        Path repoDir = tempDir.resolve("develop-repo");
        Files.createDirectories(repoDir);
        git(repoDir, "init", "-b", "develop");
        git(repoDir, "config", "user.email", "t@t.com");
        git(repoDir, "config", "user.name", "T");
        git(repoDir, "config", "commit.gpgsign", "false");

        // Commit two Java files on develop so we have multiple symbols to search.
        Files.writeString(repoDir.resolve("Service.java"),
                "public class DevelopService { public void start() {} }");
        Files.writeString(repoDir.resolve("Config.java"),
                "public class DevelopConfig { public String url; }");
        git(repoDir, "add", ".");
        git(repoDir, "commit", "-m", "initial-develop");

        // 2. Register repo with configured branch = "develop".
        int repoId = repositoryDao.insert(new Repository(
                0, "develop-repo",
                "file://" + repoDir,
                "develop",            // <-- base branch is develop, not main
                repoDir.toString(),
                "none", null, Instant.now()));

        // 3. fullIndex on develop — base files stored under branch='develop'.
        pipeline.fullIndex(repoId, "develop", repoDir);

        // 4. Create a feature branch off develop, modify one file, commit.
        git(repoDir, "checkout", "-b", "feature/x");
        Files.writeString(repoDir.resolve("Service.java"),
                "public class DevelopService { public void start() {} public void stop() {} }");
        git(repoDir, "add", "Service.java");
        git(repoDir, "commit", "-m", "add-stop");

        String featureSha = gitCapture(repoDir, "rev-parse", "HEAD");

        // 5. branchIndex with baseBranch="develop" (this is the refactored signature).
        pipeline.branchIndex(repoId, "feature/x", repoDir, featureSha, RefKind.BRANCH, "develop");

        // ---- Assertions that would have FAILED with hardcoded 'main' ----

        // 5a. getRepoSummary with branch=null resolves against develop (not main).
        //     With the old code, branch='main' would match 0 files => fileCount == 0.
        var summary = queryExecutor.getRepoSummary("develop-repo", null);
        assertThat(summary).as("repo must exist").isNotEmpty();
        long fileCount = ((Number) summary.get("fileCount")).longValue();
        assertThat(fileCount)
                .as("getRepoSummary(null) must resolve against develop base, not literal 'main'")
                .isGreaterThan(0);

        // 5b. searchSymbols with branch=null finds symbols from the develop baseline.
        var raw = (Map<String, Object>) queryExecutor.searchSymbols(
                "DevelopConfig", null, null, "develop-repo", null, 20);
        var results = (List<Map<String, Object>>) raw.get("results");
        assertThat(results)
                .as("searchSymbols(branch=null) must see develop-indexed DevelopConfig class")
                .anyMatch(s -> "DevelopConfig".equals(s.get("name")));

        // 5c. diffBranches(feature/x, develop, "files") reports exactly the one changed file.
        //     Note: diffBranches compares branchA vs branchB using the repo's baseBranch for
        //     the overlay — both branches fall back to develop as their base layer, so
        //     Config.java appears on both sides (via overlay) and only the changed
        //     Service.java shows as modified.
        var diff = queryExecutor.diffBranches("develop-repo", "feature/x", "develop", "files", 50);
        assertThat(diff).doesNotContainKey("error");

        // The diff should report Service.java as modified (different last_commit_sha).
        @SuppressWarnings("unchecked")
        var modified = (List<Map<String, Object>>) diff.get("modified");
        assertThat(modified)
                .as("Service.java must appear as modified in the diff")
                .anyMatch(r -> "Service.java".equals(r.get("path")));

        // Config.java is unchanged — must not appear in added or removed.
        @SuppressWarnings("unchecked")
        var added = (List<Map<String, Object>>) diff.get("added");
        @SuppressWarnings("unchecked")
        var removed = (List<Map<String, Object>>) diff.get("removed");
        assertThat(added).as("Config.java must not appear as added").noneMatch(r -> "Config.java".equals(r.get("path")));
        assertThat(removed).as("Config.java must not appear as removed").noneMatch(r -> "Config.java".equals(r.get("path")));
    }

    // -----------------------------------------------------------------------
    // Test 2: origin/HEAD fallback — detectDefaultBranch returns "develop"
    // -----------------------------------------------------------------------

    @Test
    void baseBranchFallsBackToOriginHead() throws Exception {
        // Build a bare origin repo whose default branch is "develop".
        Path origin = tempDir.resolve("origin-bare");
        Files.createDirectories(origin);
        git(origin, "init", "--bare", "-b", "develop");
        // Bare repos need at least one commit; use a temporary working tree to push.
        Path work = tempDir.resolve("origin-work");
        Files.createDirectories(work);
        git(work, "init", "-b", "develop");
        git(work, "config", "user.email", "t@t.com");
        git(work, "config", "user.name", "T");
        git(work, "config", "commit.gpgsign", "false");
        Files.writeString(work.resolve("R.java"), "public class R {}");
        git(work, "add", "R.java");
        git(work, "commit", "-m", "init");
        git(work, "remote", "add", "origin", origin.toString());
        git(work, "push", "origin", "develop");

        // Clone from the bare origin — this establishes origin/HEAD -> origin/develop.
        Path clone = tempDir.resolve("clone");
        gitIn(tempDir, "clone", origin.toString(), clone.toString());

        // GitOperations.detectDefaultBranch should parse origin/HEAD and return "develop".
        Optional<String> detected = gitOps.detectDefaultBranch(clone);
        assertThat(detected)
                .as("detectDefaultBranch must parse origin/HEAD and return 'develop'")
                .isPresent()
                .hasValue("develop");
    }

    // -----------------------------------------------------------------------
    // Git helpers (mirroring RefFaultInIntegrationTest pattern)
    // -----------------------------------------------------------------------

    private void git(Path dir, String... args) throws Exception {
        var cmd = new ArrayList<String>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + List.of(args) + " failed in " + dir + ": " + output);
        }
    }

    /** Run a git command in {@code workingDir} rather than a per-repo dir. */
    private void gitIn(Path workingDir, String... args) throws Exception {
        git(workingDir, args);
    }

    private String gitCapture(Path dir, String... args) throws Exception {
        var cmd = new ArrayList<String>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + List.of(args) + " failed in " + dir + ": " + output);
        }
        return output;
    }
}
