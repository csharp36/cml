package com.indexer;

import com.indexer.config.LanguageRegistry;
import com.indexer.db.*;
import com.indexer.indexing.*;
import com.indexer.mcp.QueryExecutor;
import com.indexer.repository.GitOperations;
import com.indexer.server.HttpServer;
import io.javalin.testtools.JavalinTest;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("e2e")
class IntegrationSmokeTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;

    @BeforeEach
    void setUp() {
        dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        dbManager.getJdbi().useHandle(h -> {
            h.execute("DELETE FROM file_contents");
            h.execute("DELETE FROM type_relationships");
            h.execute("DELETE FROM imports");
            h.execute("DELETE FROM symbols");
            h.execute("DELETE FROM files");
            h.execute("DELETE FROM indexing_events");
            h.execute("DELETE FROM repositories");
        });
    }

    @Test
    void fullPipelineFromWebhookToQuery() throws Exception {
        var jdbi = dbManager.getJdbi();
        var eventDao = new EventDao(jdbi);
        var repositoryDao = new RepositoryDao(jdbi);
        var queryExecutor = new QueryExecutor(jdbi);

        // Create test git repo
        Path repoDir = tempDir.resolve("smoke-repo");
        Files.createDirectories(repoDir);
        new ProcessBuilder("git", "init").directory(repoDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "config", "user.email", "t@t.com").directory(repoDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "config", "user.name", "T").directory(repoDir.toFile()).start().waitFor();

        Files.writeString(repoDir.resolve("Service.java"), """
                package com.example;
                import java.util.List;
                public interface Service {
                    List<String> getData();
                }
                """);
        Files.writeString(repoDir.resolve("ServiceImpl.java"), """
                package com.example;
                import java.util.List;
                import java.util.ArrayList;
                public class ServiceImpl implements Service {
                    public List<String> getData() {
                        return new ArrayList<>();
                    }
                    private void helper() {}
                }
                """);

        new ProcessBuilder("git", "add", ".").directory(repoDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "commit", "-m", "init").directory(repoDir.toFile()).start().waitFor();

        // Register repo in DB and run full index
        var repo = new com.indexer.model.Repository(
                0, "smoke-repo", "file://" + repoDir, "main", repoDir.toString(), "ssh-key", null, null);
        int repoId = repositoryDao.insert(repo);

        var langReg = new LanguageRegistry(Map.of());
        var extractor = new SymbolExtractor();
        var fileDao = new FileDao(jdbi);
        var symbolDao = new SymbolDao(jdbi);
        var fileIndexer = new FileIndexer(fileDao, symbolDao, jdbi, langReg, extractor, 1_048_576);
        var pipeline = new IndexingPipeline(repositoryDao, fileIndexer, new GitOperations());
        pipeline.fullIndex(repoId, repoDir);

        // Verify: search_symbols finds ServiceImpl
        var symbols = queryExecutor.searchSymbols("ServiceImpl", null, null, null, null, 20);
        assertThat(symbols).anyMatch(s -> "ServiceImpl".equals(s.get("name")));

        // Verify: find_implementations finds ServiceImpl as implementing Service
        var impls = queryExecutor.findImplementations("Service", null, null);
        assertThat(impls).anyMatch(s -> "ServiceImpl".equals(s.get("class_name")));

        // Verify: search_code finds text content
        var codeResults = queryExecutor.searchCode("getData", null, null, null, 10);
        assertThat(codeResults).isNotEmpty();

        // Verify: get_repo_summary returns correct file count (2 Java files)
        var summary = queryExecutor.getRepoSummary("smoke-repo", null);
        assertThat(((Number) summary.get("fileCount")).intValue()).isEqualTo(2);

        // Verify: get_index_health returns zero pending events
        var health = queryExecutor.getIndexHealth();
        assertThat(((Number) health.get("totalPendingEvents")).intValue()).isEqualTo(0);

        // Verify: webhook POST inserts a pending event in DB
        var httpServer = new HttpServer(eventDao);
        var app = httpServer.createApp();
        JavalinTest.test(app, (server, client) -> {
            var requestBody = RequestBody.create(
                    """
                    {"repoName":"smoke-repo","repoPath":"%s","eventType":"post-commit","previousSha":"abc","currentSha":"def","timestamp":"2026-05-25T12:00:00Z"}
                    """.formatted(repoDir),
                    MediaType.get("application/json")
            );
            var response = client.request("/webhook", builder -> builder.post(requestBody));
            assertThat(response.code()).isEqualTo(202);
            assertThat(eventDao.countByStatus("pending")).isEqualTo(1);
        });
    }
}
