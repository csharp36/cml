package com.indexer.mcp.tools;

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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class RefFaultInIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    @TempDir Path tempDir;

    private QueryExecutor queryExecutor;
    private BranchIndexDao branchIndexDao;
    private int repoId;
    private Path repoDir;
    private String taggedSha;

    @BeforeEach
    void setUp() throws Exception {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        Jdbi jdbi = dbManager.getJdbi();
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM branch_index");
            h.execute("DELETE FROM type_relationships");
            h.execute("DELETE FROM imports");
            h.execute("DELETE FROM symbols");
            h.execute("DELETE FROM file_contents");
            h.execute("DELETE FROM files");
            h.execute("DELETE FROM repositories");
        });

        // Real git repo:
        // - main has only App.java (class App)
        // - a second commit adds Tagged.java (class Tagged), which is tagged v1.0
        // - HEAD is reset back to the first commit, so main only sees App.java
        repoDir = tempDir.resolve("repo");
        Files.createDirectories(repoDir);
        git("init");
        git("config", "user.email", "t@e.com");
        git("config", "user.name", "T");
        git("config", "commit.gpgsign", "false");

        Files.writeString(repoDir.resolve("App.java"), "public class App {}");
        git("add", "App.java");
        git("commit", "-m", "main");

        Files.writeString(repoDir.resolve("Tagged.java"), "public class Tagged {}");
        git("add", "Tagged.java");
        git("commit", "-m", "tagged");
        git("tag", "v1.0");
        taggedSha = gitCapture("rev-parse", "refs/tags/v1.0^{commit}");

        // Reset HEAD back to main (one commit back); v1.0 still points at the tagged commit
        git("reset", "--hard", "HEAD~1");

        var repoDao = new RepositoryDao(jdbi);
        repoId = repoDao.insert(new Repository(
                0, "repo", "file://" + repoDir, "main", repoDir.toString(), "none", null, Instant.now()));

        var gitOps = new GitOperations();
        var fileDao = new FileDao(jdbi);
        var symbolDao = new SymbolDao(jdbi);
        branchIndexDao = new BranchIndexDao(jdbi);
        var languageRegistry = new LanguageRegistry(Map.of());
        var symbolExtractor = new SymbolExtractor();
        var fileIndexer = new FileIndexer(fileDao, symbolDao, jdbi, languageRegistry, symbolExtractor, 1_048_576);
        var pipeline = new IndexingPipeline(repoDao, fileIndexer, gitOps, branchIndexDao);

        // Index main baseline (only App.java should be visible)
        pipeline.fullIndex(repoId, repoDir);

        queryExecutor = new QueryExecutor(jdbi, branchIndexDao, pipeline, repoDao, gitOps);
    }

    @Test
    // searchSymbols returns Object; for a repo-scoped query the shape is
    // Map<String,Object>{"results":[...]} — hence the unchecked casts below.
    @SuppressWarnings("unchecked")
    void queryingATagFaultsItInAndRecordsTagKind() {
        // Querying "v1.0" should fault in the tag and make Tagged (class) visible.
        // When repo is non-null, searchSymbols returns Map<String,Object> with a "results" key.
        var raw = (Map<String, Object>) queryExecutor.searchSymbols(
                "Tagged", null, null, "repo", "v1.0", 20);
        var results = (List<Map<String, Object>>) raw.get("results");

        assertThat(results).as("tag faulted in; Tagged class visible").hasSize(1);
        assertThat(results.get(0).get("name")).isEqualTo("Tagged");

        // The branch_index row must exist and record TAG kind
        var bi = branchIndexDao.find(repoId, "v1.0");
        assertThat(bi).isPresent();
        assertThat(bi.get().refKind()).isEqualTo(RefKind.TAG);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryingAShaFaultsItInAndRecordsShaKind() {
        // Querying by the raw commit SHA should fault in that commit and make Tagged visible.
        var raw = (Map<String, Object>) queryExecutor.searchSymbols(
                "Tagged", null, null, "repo", taggedSha, 20);
        var results = (List<Map<String, Object>>) raw.get("results");

        assertThat(results).as("sha faulted in; Tagged class visible").hasSize(1);
        assertThat(results.get(0).get("name")).isEqualTo("Tagged");

        // The branch_index row must exist and record SHA kind
        var bi = branchIndexDao.find(repoId, taggedSha);
        assertThat(bi).isPresent();
        assertThat(bi.get().refKind()).isEqualTo(RefKind.SHA);
    }

    private void git(String... args) throws Exception {
        var cmd = new java.util.ArrayList<String>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) throw new IllegalStateException("git " + List.of(args) + " failed");
    }

    private String gitCapture(String... args) throws Exception {
        var cmd = new java.util.ArrayList<String>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        if (p.waitFor() != 0) throw new IllegalStateException("git " + List.of(args) + " failed: " + output);
        return output;
    }
}
