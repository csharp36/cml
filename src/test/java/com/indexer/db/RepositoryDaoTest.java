package com.indexer.db;

import com.indexer.model.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class RepositoryDaoTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index")
            .withUsername("test")
            .withPassword("test");

    private DatabaseManager dbManager;
    private RepositoryDao repositoryDao;

    @BeforeEach
    void setUp() {
        dbManager = new DatabaseManager(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
        dbManager.initialize();
        repositoryDao = new RepositoryDao(dbManager.getJdbi());

        // Clean state between tests
        dbManager.getJdbi().useHandle(handle ->
                handle.execute("DELETE FROM repositories")
        );
    }

    @Test
    void insertsAndFindsRepository() {
        var repo = new Repository(0, "my-repo", "https://github.com/example/my-repo",
                "main", "/tmp/repos/my-repo", "token", null, null);

        int id = repositoryDao.insert(repo);
        assertThat(id).isPositive();

        var found = repositoryDao.findByName("my-repo");
        assertThat(found).isPresent();

        var r = found.get();
        assertThat(r.id()).isEqualTo(id);
        assertThat(r.name()).isEqualTo("my-repo");
        assertThat(r.url()).isEqualTo("https://github.com/example/my-repo");
        assertThat(r.branch()).isEqualTo("main");
        assertThat(r.clonePath()).isEqualTo("/tmp/repos/my-repo");
        assertThat(r.authType()).isEqualTo("token");
        assertThat(r.lastIndexedSha()).isNull();
        assertThat(r.lastIndexedAt()).isNull();
    }

    @Test
    void updatesLastIndexedSha() {
        var repo = new Repository(0, "update-repo", "https://github.com/example/update-repo",
                "main", "/tmp/repos/update-repo", "none", null, null);

        int id = repositoryDao.insert(repo);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        repositoryDao.updateLastIndexed(id, "abc123sha", now);

        var found = repositoryDao.findByName("update-repo");
        assertThat(found).isPresent();

        var r = found.get();
        assertThat(r.lastIndexedSha()).isEqualTo("abc123sha");
        assertThat(r.lastIndexedAt()).isNotNull();
        assertThat(r.lastIndexedAt().truncatedTo(ChronoUnit.MILLIS)).isEqualTo(now);
    }

    @Test
    void listsAllRepositories() {
        var repo1 = new Repository(0, "alpha-repo", "https://github.com/example/alpha",
                "main", "/tmp/repos/alpha", "token", null, null);
        var repo2 = new Repository(0, "beta-repo", "https://github.com/example/beta",
                "develop", "/tmp/repos/beta", "ssh", null, null);

        repositoryDao.insert(repo1);
        repositoryDao.insert(repo2);

        var all = repositoryDao.findAll();
        assertThat(all).hasSize(2);
        // findAll orders by name
        assertThat(all.get(0).name()).isEqualTo("alpha-repo");
        assertThat(all.get(1).name()).isEqualTo("beta-repo");
    }
}
