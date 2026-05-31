package com.indexer.scip;

import com.indexer.db.FileDao;
import com.indexer.db.RepositoryDao;
import com.indexer.model.Repository;
import com.sourcegraph.scip.Scip;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class ScipSessionReaperTaskTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private Jdbi jdbi;
    private ScipSessionService service;
    private ScipSessionDao sessionDao;
    private RepositoryDao repositoryDao;
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
            h.execute("INSERT INTO files (repo_id, branch, path, language) VALUES (?, 'main', 'src/A.java', 'java')", repoId);
        });
        repositoryDao = new RepositoryDao(jdbi);
        sessionDao = new ScipSessionDao(jdbi);
        service = new ScipSessionService(repositoryDao, new FileDao(jdbi), sessionDao, jdbi);
    }

    private byte[] index() {
        var occ = Scip.Occurrence.newBuilder().setSymbol("java . A#")
                .addAllRange(List.of(1, 0, 5, 0)).setSymbolRoles(Scip.SymbolRole.Definition_VALUE).build();
        var doc = Scip.Document.newBuilder().setRelativePath("src/A.java").addOccurrences(occ).build();
        return Scip.Index.newBuilder().addDocuments(doc).build().toByteArray();
    }

    private int stagingRows(String stagingSha) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT count(*) FROM scip_symbols WHERE upload_sha = ?")
                .bind(0, stagingSha).mapTo(Integer.class).one());
    }

    @Test
    void reaperDeletesAbandonedSessionAndStagingRows() {
        Repository repo = repositoryDao.findByName("r").orElseThrow();
        var session = service.init(repo, "SHA1", null);
        service.part(session.id(), 1, index());
        assertThat(stagingRows(session.stagingSha())).isEqualTo(1);

        // Age the session past the 24h TTL.
        jdbi.useHandle(h -> h.execute(
                "UPDATE scip_upload_sessions SET updated_at = NOW() - INTERVAL '48 hours' WHERE id = ?", session.id()));

        new ScipSessionReaperTask(sessionDao, 24).run();

        assertThat(sessionDao.find(session.id())).isEmpty();
        assertThat(stagingRows(session.stagingSha())).isZero();
    }

    @Test
    void reaperLeavesFreshSessionsAlone() {
        Repository repo = repositoryDao.findByName("r").orElseThrow();
        var session = service.init(repo, "SHA1", null);
        service.part(session.id(), 1, index());

        new ScipSessionReaperTask(sessionDao, 24).run();

        assertThat(sessionDao.find(session.id())).isPresent();
        assertThat(stagingRows(session.stagingSha())).isEqualTo(1);
    }
}
