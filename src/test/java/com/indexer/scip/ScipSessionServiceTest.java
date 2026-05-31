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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Tag("integration")
class ScipSessionServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private Jdbi jdbi;
    private ScipSessionService service;
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
            h.execute("INSERT INTO files (repo_id, branch, path, language) VALUES (?, 'main', 'src/B.java', 'java')", repoId);
        });
        repositoryDao = new RepositoryDao(jdbi);
        service = new ScipSessionService(repositoryDao, new FileDao(jdbi), new ScipSessionDao(jdbi), jdbi);
    }

    private Repository repo() { return repositoryDao.findByName("r").orElseThrow(); }

    private byte[] indexFor(String path, String symbol) {
        var occ = Scip.Occurrence.newBuilder().setSymbol(symbol)
                .addAllRange(List.of(1, 0, 5, 0)).setSymbolRoles(Scip.SymbolRole.Definition_VALUE).build();
        var info = Scip.SymbolInformation.newBuilder().setSymbol(symbol)
                .setKind(Scip.SymbolInformation.Kind.Class).setDisplayName(symbol).build();
        var doc = Scip.Document.newBuilder().setRelativePath(path).setLanguage("java")
                .addOccurrences(occ).addSymbols(info).build();
        return Scip.Index.newBuilder().addDocuments(doc).build().toByteArray();
    }

    private int liveSymbols(String sha) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT count(*) FROM scip_symbols WHERE repo_id = ? AND upload_sha = ?")
                .bind(0, repoId).bind(1, sha).mapTo(Integer.class).one());
    }

    @Test
    void multiPartUploadPromotesAllRowsOnComplete() {
        var session = service.init(repo(), "SHA1", null);
        service.part(session.id(), 1, indexFor("src/A.java", "java . A#"));
        service.part(session.id(), 2, indexFor("src/B.java", "java . B#"));

        // Before complete: nothing visible under the real SHA.
        assertThat(liveSymbols("SHA1")).isZero();

        var result = service.complete(repo(), session.id());
        assertThat(result.symbols()).isEqualTo(2);
        assertThat(liveSymbols("SHA1")).isEqualTo(2);
        // Staging rows are gone (promoted, not copied).
        assertThat(liveSymbols(session.stagingSha())).isZero();
    }

    @Test
    void interruptedSessionIsInvisibleUntilComplete() {
        var session = service.init(repo(), "SHA1", null);
        service.part(session.id(), 1, indexFor("src/A.java", "java . A#"));
        // No complete call: a query for SHA1 sees nothing.
        assertThat(liveSymbols("SHA1")).isZero();
    }

    @Test
    void rePostingSamePartIsIdempotent() {
        var session = service.init(repo(), "SHA1", null);
        service.part(session.id(), 1, indexFor("src/A.java", "java . A#"));
        service.part(session.id(), 1, indexFor("src/A.java", "java . A#")); // retry
        service.complete(repo(), session.id());
        assertThat(liveSymbols("SHA1")).isEqualTo(1);
    }

    @Test
    void completeWithNoMatchingFilesThrows422() {
        var session = service.init(repo(), "SHA1", null);
        service.part(session.id(), 1, indexFor("src/UNKNOWN.java", "java . X#"));
        assertThatThrownBy(() -> service.complete(repo(), session.id()))
                .isInstanceOf(ScipUploadException.class)
                .hasMessageStartingWith("No SCIP document");
    }

    @Test
    void completePromotesOverOldShaData() {
        // Seed an existing SHA1 row via a first session, then re-upload SHA1 via a second session.
        var s1 = service.init(repo(), "SHA1", null);
        service.part(s1.id(), 1, indexFor("src/A.java", "java . Old#"));
        service.complete(repo(), s1.id());
        assertThat(liveSymbols("SHA1")).isEqualTo(1);

        var s2 = service.init(repo(), "SHA1", null);
        service.part(s2.id(), 1, indexFor("src/A.java", "java . New#"));
        service.complete(repo(), s2.id());

        // Still exactly 1 row for SHA1, and it is the new symbol.
        assertThat(liveSymbols("SHA1")).isEqualTo(1);
        boolean isNew = jdbi.withHandle(h -> h.createQuery(
                "SELECT count(*) > 0 FROM scip_symbols WHERE repo_id = ? AND upload_sha = 'SHA1' AND scip_symbol LIKE '%New%'")
                .bind(0, repoId).mapTo(Boolean.class).one());
        assertThat(isNew).isTrue();
    }
}
