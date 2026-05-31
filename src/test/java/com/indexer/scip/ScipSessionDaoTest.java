package com.indexer.scip;

import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class ScipSessionDaoTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private Jdbi jdbi;
    private ScipSessionDao dao;
    private int repoId;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var flyway = Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO repositories (name, url, branch, clone_path, auth_type, last_indexed_sha) "
                    + "VALUES ('r', 'git@x:r.git', 'main', '/tmp', 'ssh-key', 'AAA')");
            repoId = h.createQuery("SELECT id FROM repositories WHERE name='r'").mapTo(Integer.class).one();
        });
        dao = new ScipSessionDao(jdbi);
    }

    @Test
    void createAndFetchSession() {
        ScipUploadSession s = dao.create(repoId, "SHA1");
        assertThat(s.id()).isNotBlank();
        assertThat(s.stagingSha()).isEqualTo("__staging__:" + s.id());
        assertThat(s.status()).isEqualTo("open");

        Optional<ScipUploadSession> fetched = dao.find(s.id());
        assertThat(fetched).isPresent();
        assertThat(fetched.get().targetSha()).isEqualTo("SHA1");
    }

    @Test
    void recordPartIsTrackedAndIdempotent() {
        ScipUploadSession s = dao.create(repoId, "SHA1");
        assertThat(dao.partExists(s.id(), 1)).isFalse();

        dao.recordPart(s.id(), 1, 1000L, 5, 3);
        assertThat(dao.partExists(s.id(), 1)).isTrue();

        Optional<int[]> counts = dao.partCounts(s.id(), 1);
        assertThat(counts).isPresent();
        assertThat(counts.get()).containsExactly(5, 3); // [symbolCount, relCount]
    }

    @Test
    void markCompletedUpdatesStatus() {
        ScipUploadSession s = dao.create(repoId, "SHA1");
        dao.markStatus(s.id(), "completed");
        assertThat(dao.find(s.id()).orElseThrow().status()).isEqualTo("completed");
    }

    @Test
    void findOpenOlderThanReturnsStaleSessions() {
        ScipUploadSession s = dao.create(repoId, "SHA1");
        // Backdate updated_at by 48h.
        jdbi.useHandle(h -> h.execute(
                "UPDATE scip_upload_sessions SET updated_at = NOW() - INTERVAL '48 hours' WHERE id = ?", s.id()));
        assertThat(dao.findOpenOlderThan(24)).extracting(ScipUploadSession::id).contains(s.id());
        assertThat(dao.findOpenOlderThan(72)).isEmpty();
    }
}
