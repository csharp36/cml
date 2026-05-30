package com.indexer.indexing;

import com.indexer.config.LanguageRegistry;
import com.indexer.db.*;
import com.indexer.repository.GitOperations;
import com.indexer.repository.RefKind;
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
    private GitOperations gitOps;
    private org.jdbi.v3.core.Jdbi jdbi;
    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        jdbi = dbManager.getJdbi();
        repositoryDao = new RepositoryDao(jdbi);
        fileDao = new FileDao(jdbi);
        symbolDao = new SymbolDao(jdbi);
        gitOps = new GitOperations();
        var languageRegistry = new LanguageRegistry(Map.of());
        var symbolExtractor = new SymbolExtractor();
        var fileIndexer = new FileIndexer(fileDao, symbolDao, jdbi, languageRegistry, symbolExtractor, 1_048_576);
        pipeline = new IndexingPipeline(repositoryDao, fileIndexer, gitOps, new BranchIndexDao(jdbi));
        // Clean state
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM branch_index");
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
        // -b main so the repo's default branch is 'main' regardless of the host's
        // init.defaultBranch (CI runners default to 'master'); diffFromMain assumes 'main'.
        new ProcessBuilder("git", "init", "-b", "main").directory(repoDir.toFile()).start().waitFor();
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

    @Test
    void branchIndexRecordsRefKindForTag() throws Exception {
        Path repoDir = tempDir.resolve("tag-repo");
        Files.createDirectories(repoDir);
        new ProcessBuilder("git", "init", "-b", "main").directory(repoDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "config", "user.email", "t@t.com").directory(repoDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "config", "user.name", "T").directory(repoDir.toFile()).start().waitFor();

        // Initial commit on main
        Files.writeString(repoDir.resolve("Main.java"), "public class Main {}");
        new ProcessBuilder("git", "add", ".").directory(repoDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "commit", "-m", "init").directory(repoDir.toFile()).start().waitFor();

        // Add a file and create a tag pointing at that commit
        Files.writeString(repoDir.resolve("Release.java"), "public class Release {}");
        new ProcessBuilder("git", "add", ".").directory(repoDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "commit", "-m", "release").directory(repoDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "tag", "v1.0").directory(repoDir.toFile()).start().waitFor();

        // Leave HEAD as-is; branchIndex diffs against main HEAD

        var repo = new com.indexer.model.Repository(0, "tag-repo", "file://" + repoDir, "main", repoDir.toString(), "ssh-key", null, null);
        int repoId = repositoryDao.insert(repo);

        // Full-index main so there is a baseline
        pipeline.fullIndex(repoId, repoDir);

        // Resolve tag commit SHA
        String tagSha = gitOps.getShaForRef(repoDir, "refs/tags/v1.0^{commit}");

        // Branch-index the tag
        pipeline.branchIndex(repoId, "v1.0", repoDir, tagSha, RefKind.TAG);

        // Verify the branch_index row has ref_kind = "tag"
        var entry = new BranchIndexDao(jdbi).find(repoId, "v1.0");
        assertThat(entry).isPresent();
        assertThat(entry.get().refKind()).isEqualTo(RefKind.TAG);
        assertThat(entry.get().indexedSha()).isEqualTo(tagSha);
    }
}
