package com.indexer.db;

import com.indexer.model.IndexingEvent;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class EventDaoRefKindTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private EventDao dao;

    @BeforeEach
    void setUp() {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        Jdbi jdbi = dbManager.getJdbi();
        jdbi.useHandle(h -> h.execute("DELETE FROM indexing_events"));
        dao = new EventDao(jdbi);
    }

    @Test
    void tagInsertRoundTripsRefKind() {
        dao.insert("cml", "/tmp/cml", "github_push", "old", "new", "v1.0", "tag");
        IndexingEvent claimed = dao.claimNextPending("w1").orElseThrow();
        assertThat(claimed.branch()).isEqualTo("v1.0");
        assertThat(claimed.refKind()).isEqualTo("tag");
    }

    @Test
    void legacySixArgInsertDefaultsToBranch() {
        dao.insert("cml", "/tmp/cml", "github_push", "old", "new", "main");
        IndexingEvent claimed = dao.claimNextPending("w1").orElseThrow();
        assertThat(claimed.refKind()).isEqualTo("branch");
    }
}
