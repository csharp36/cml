package com.indexer.repository;

import com.indexer.auth.AuthProviderRegistry;
import com.indexer.auth.GitCredentials;
import com.indexer.config.IndexerConfig;
import com.indexer.db.RepositoryDao;
import com.indexer.model.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepositoryManagerTest {

    /**
     * Regression: a freshly cloned repo must be inserted with a null lastIndexedSha so the
     * boot loop in {@code Application} runs the initial full index. Seeding it with the
     * clone's current SHA made the repo look already-indexed, so boot skipped indexing and
     * the repo had zero files until a manual reindex.
     */
    @Test
    void freshCloneInsertsRepoWithNullLastIndexedSha(@TempDir Path baseDir) {
        var auth = new IndexerConfig.AuthConfig("token", Map.of("token", "ghp_x"));
        var repoConfig = new IndexerConfig.RepositoryConfig(
                "https://github.com/org/myrepo.git", "main", auth);
        var server = new IndexerConfig.ServerConfig(baseDir.toString(), 1_048_576, 4, 8080);
        var database = new IndexerConfig.DatabaseConfig("localhost", 5432, "db", "u", "p");
        var config = new IndexerConfig(server, database, List.of(repoConfig), null, null, null, null);

        var authRegistry = mock(AuthProviderRegistry.class);
        when(authRegistry.resolve(any())).thenReturn(GitCredentials.token("ghp_x"));

        var dao = mock(RepositoryDao.class);
        when(dao.findByName("myrepo")).thenReturn(Optional.empty());
        when(dao.insert(any())).thenReturn(1);

        // clone()/fetch() are no-ops; we only care about what gets persisted.
        var gitOps = mock(GitOperations.class);

        var manager = new RepositoryManager(config, authRegistry, dao, gitOps);
        manager.initializeRepositories();

        var captor = ArgumentCaptor.forClass(Repository.class);
        verify(dao).insert(captor.capture());
        Repository inserted = captor.getValue();

        assertThat(inserted.name()).isEqualTo("myrepo");
        assertThat(inserted.lastIndexedSha())
                .as("a freshly cloned repo has indexed nothing yet")
                .isNull();
        assertThat(inserted.clonePath())
                .isEqualTo(baseDir.resolve("myrepo").toAbsolutePath().toString());
    }
}
