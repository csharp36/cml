package com.indexer.mcp.tools;

import com.indexer.db.DatabaseManager;
import com.indexer.db.RepositoryDao;
import com.indexer.db.BranchIndexDao;
import com.indexer.mcp.QueryExecutor;
import com.indexer.model.Repository;
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
class CheckSyncToolTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private QueryExecutor queryExecutor;
    private BranchIndexDao branchIndexDao;

    @BeforeEach
    void setUp() {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        var jdbi = dbManager.getJdbi();
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM branch_index");
            h.execute("DELETE FROM files");
            h.execute("DELETE FROM repositories");
        });
        var repoDao = new RepositoryDao(jdbi);
        repoDao.insert(new Repository(0, "backend-api", "git@github.com:org/backend.git", "main",
                "/repos/backend", "ssh-key", "abc1234def5678901234567890abcdef12345678", Instant.parse("2026-05-26T10:30:00Z")));

        branchIndexDao = new BranchIndexDao(jdbi);

        // Simulate indexed feature branch
        var repo = repoDao.findByName("backend-api").orElseThrow();
        branchIndexDao.upsert(repo.id(), "feature/auth", "abc1234def5678901234567890abcdef12345678", "fff0000111222333444555666777888999aaabbb", "branch");

        // Also insert a repo with null SHA (not yet indexed)
        repoDao.insert(new Repository(0, "not-indexed-yet", "git@github.com:org/new.git", "main",
                "/repos/new", "ssh-key", null, null));

        queryExecutor = new QueryExecutor(jdbi);
    }

    @Test
    void returnsInSyncForMainWhenShasMatch() {
        var result = queryExecutor.checkSync("backend-api", "abc1234def5678901234567890abcdef12345678", null);
        assertThat(result.get("status")).isEqualTo("in_sync");
        assertThat(result.get("branch")).isEqualTo("main");
        assertThat(result.get("message")).isEqualTo("Your local repo matches the index.");
        assertThat(result).doesNotContainKey("action");
    }

    @Test
    void returnsOutOfSyncForMainWhenShasDiffer() {
        var result = queryExecutor.checkSync("backend-api", "ffffffffffffffffffffffffffffffffffffffff", "main");
        assertThat(result.get("status")).isEqualTo("out_of_sync");
        assertThat(result.get("action")).isNotNull();
    }

    @Test
    void returnsInSyncForBranchWhenShasMatch() {
        var result = queryExecutor.checkSync("backend-api", "fff0000111222333444555666777888999aaabbb", "feature/auth");
        assertThat(result.get("status")).isEqualTo("in_sync");
        assertThat(result.get("branch")).isEqualTo("feature/auth");
    }

    @Test
    void returnsOutOfSyncForBranchWhenShasDiffer() {
        var result = queryExecutor.checkSync("backend-api", "0000000000000000000000000000000000000000", "feature/auth");
        assertThat(result.get("status")).isEqualTo("out_of_sync");
        assertThat(result.get("branch")).isEqualTo("feature/auth");
    }

    @Test
    void returnsErrorWhenRepoNotFound() {
        var result = queryExecutor.checkSync("nonexistent", "abc123", null);
        assertThat(result.get("error")).isEqualTo("Repository 'nonexistent' not found in index");
    }

    @Test
    void returnsNotIndexedWhenMainShaIsNull() {
        var result = queryExecutor.checkSync("not-indexed-yet", "abc123", null);
        assertThat(result.get("status")).isEqualTo("not_indexed");
    }

    @Test
    void abbreviatedShaMatchesFullSha() {
        var result = queryExecutor.checkSync("backend-api", "abc1234", null);
        assertThat(result.get("status")).isEqualTo("in_sync");
    }

    @Test
    void comparisonIsCaseInsensitive() {
        var result = queryExecutor.checkSync("backend-api", "ABC1234DEF5678901234567890ABCDEF12345678", null);
        assertThat(result.get("status")).isEqualTo("in_sync");
    }
}
