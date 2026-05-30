package com.indexer.db;

import com.indexer.model.Repository;
import com.indexer.repository.RefKind;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class BranchIndexDaoTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private BranchIndexDao dao;
    private int repoId;

    @BeforeEach
    void setUp() {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        Jdbi jdbi = dbManager.getJdbi();
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM branch_index");
            h.execute("DELETE FROM repositories");
        });
        repoId = new RepositoryDao(jdbi).insert(
                new Repository(0, "repo", "url", "main", "/path", "ssh-key", "abc", Instant.now()));
        dao = new BranchIndexDao(jdbi);
    }

    @Test
    void upsertPersistsRefKind() {
        dao.upsert(repoId, "v1.0", "mainsha", "tagsha", "tag");
        var found = dao.find(repoId, "v1.0");
        assertThat(found).isPresent();
        assertThat(found.get().refKind()).isEqualTo(RefKind.TAG);
        assertThat(found.get().indexedSha()).isEqualTo("tagsha");
    }

    @Test
    void upsertUpdatesRefKindOnConflict() {
        dao.upsert(repoId, "x", "m1", "s1", "branch");
        dao.upsert(repoId, "x", "m2", "s2", "sha");
        assertThat(dao.find(repoId, "x").orElseThrow().refKind()).isEqualTo(RefKind.SHA);
    }
}
