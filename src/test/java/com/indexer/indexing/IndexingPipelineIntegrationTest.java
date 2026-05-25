package com.indexer.indexing;

import com.indexer.config.LanguageRegistry;
import com.indexer.db.*;
import com.indexer.repository.GitOperations;
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
@Tag("integration")
class IndexingPipelineIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private DatabaseManager dbManager;
    private RepositoryDao repositoryDao;
    private FileDao fileDao;
    private SymbolDao symbolDao;
    private IndexingPipeline pipeline;
    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        repositoryDao = new RepositoryDao(dbManager.getJdbi());
        fileDao = new FileDao(dbManager.getJdbi());
        symbolDao = new SymbolDao(dbManager.getJdbi());
        var languageRegistry = new LanguageRegistry(Map.of());
        var symbolExtractor = new SymbolExtractor();
        var fileIndexer = new FileIndexer(fileDao, symbolDao, dbManager.getJdbi(), languageRegistry, symbolExtractor, 1_048_576);
        pipeline = new IndexingPipeline(repositoryDao, fileIndexer, new GitOperations());
        // Clean state
        dbManager.getJdbi().useHandle(h -> {
            h.execute("DELETE FROM file_contents");
            h.execute("DELETE FROM type_relationships");
            h.execute("DELETE FROM imports");
            h.execute("DELETE FROM symbols");
            h.execute("DELETE FROM files");
            h.execute("DELETE FROM repositories");
        });
    }

    @Test
    void indexesNewFilesOnFullIndex() throws Exception {
        Path repoDir = tempDir.resolve("test-repo");
        Files.createDirectories(repoDir);
        new ProcessBuilder("git", "init").directory(repoDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "config", "user.email", "t@t.com").directory(repoDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "config", "user.name", "T").directory(repoDir.toFile()).start().waitFor();

        Files.writeString(repoDir.resolve("App.java"), """
                package com.example;
                public class App {
                    public void run() {}
                }
                """);
        Files.writeString(repoDir.resolve("utils.py"), """
                def helper(x):
                    return x + 1
                """);
        new ProcessBuilder("git", "add", ".").directory(repoDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "commit", "-m", "init").directory(repoDir.toFile()).start().waitFor();

        var repo = new com.indexer.model.Repository(0, "test-repo", "file://" + repoDir, "main", repoDir.toString(), "ssh-key", null, null);
        int repoId = repositoryDao.insert(repo);

        pipeline.fullIndex(repoId, repoDir);

        assertThat(fileDao.countByRepo(repoId)).isEqualTo(2);
        var javaFile = fileDao.findByRepoAndPath(repoId, "App.java").orElseThrow();
        var javaSymbols = symbolDao.findByFileId(javaFile.id());
        assertThat(javaSymbols).anyMatch(s -> s.name().equals("App") && s.kind().equals("class"));
        assertThat(javaSymbols).anyMatch(s -> s.name().equals("run") && s.kind().equals("method"));
    }
}
