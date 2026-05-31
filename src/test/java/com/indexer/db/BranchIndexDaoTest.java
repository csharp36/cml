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
    private Jdbi jdbi;

    @BeforeEach
    void setUp() {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        this.jdbi = dbManager.getJdbi();
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

    @Test
    void setPinnedTogglesAndIsReturned() {
        dao.upsert(repoId, "v1.0", "m", "s", "tag");
        assertThat(dao.find(repoId, "v1.0").orElseThrow().pinned()).isFalse();
        int updated = dao.setPinned(repoId, "v1.0", true);
        assertThat(updated).isEqualTo(1);
        assertThat(dao.find(repoId, "v1.0").orElseThrow().pinned()).isTrue();
    }

    @Test
    void setPinnedOnMissingRefReturnsZero() {
        assertThat(dao.setPinned(repoId, "ghost", true)).isEqualTo(0);
    }

    @Test
    void expiryIsRefKindAwareAndPinAware() {
        dao.upsert(repoId, "feature/x", "m", "s1", "branch");
        dao.upsert(repoId, "v1.0", "m", "s2", "tag");
        dao.upsert(repoId, "pinnedtag", "m", "s3", "tag");
        dao.setPinned(repoId, "pinnedtag", true);
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE branch_index SET last_accessed_at = NOW() - INTERVAL '20 days' WHERE repo_id = :r")
                .bind("r", repoId).execute());

        // branchTtl=14, immutableTtl=90: the 20-day-old branch expires; the tags don't (and pinned never would).
        var expired = dao.findExpired(14, 90);
        assertThat(expired).extracting(bi -> bi.branch()).containsExactly("feature/x");
    }

    @Test
    void pinnedBranchSurvivesEvenPastBranchTtl() {
        dao.upsert(repoId, "longlived", "m", "s", "branch");
        dao.setPinned(repoId, "longlived", true);
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE branch_index SET last_accessed_at = NOW() - INTERVAL '400 days' WHERE repo_id = :r")
                .bind("r", repoId).execute());
        assertThat(dao.findExpired(14, 90)).isEmpty();
    }
}
