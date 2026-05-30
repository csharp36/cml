package com.indexer.scip;

import com.indexer.db.FileDao;
import com.indexer.db.RepositoryDao;
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
class ScipServiceRetentionTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index")
            .withUsername("test")
            .withPassword("test");

    private Jdbi jdbi;
    private ScipService scipService;
    private int repoId;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());

        var flyway = Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        jdbi.useHandle(handle -> {
            handle.execute(
                    "INSERT INTO repositories (name, url, branch, clone_path, auth_type, last_indexed_sha) " +
                    "VALUES ('test-repo', 'git@example.com:test.git', 'main', '/tmp/test', 'ssh-key', 'AAA')");
            repoId = handle.createQuery("SELECT id FROM repositories WHERE name='test-repo'")
                    .mapTo(Integer.class).one();
            // seed a files row whose path matches the document relativePath used in fixtures
            handle.execute(
                    "INSERT INTO files (repo_id, branch, path, language) VALUES (?, 'main', 'src/Foo.java', 'java')",
                    repoId);
        });

        scipService = new ScipService(new RepositoryDao(jdbi), new FileDao(jdbi), jdbi);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private int countSymbols(int repoId, String sha) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT count(*) FROM scip_symbols WHERE repo_id = ? AND upload_sha = ?")
                .bind(0, repoId).bind(1, sha)
                .mapTo(Integer.class).one());
    }

    private int countRels(int repoId, String sha) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT count(*) FROM scip_relationships WHERE repo_id = ? AND upload_sha = ?")
                .bind(0, repoId).bind(1, sha)
                .mapTo(Integer.class).one());
    }

    private boolean symbolExists(int repoId, String sha, String likePattern) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT count(*) > 0 FROM scip_symbols WHERE repo_id = ? AND upload_sha = ? AND scip_symbol LIKE ?")
                .bind(0, repoId).bind(1, sha).bind(2, likePattern)
                .mapTo(Boolean.class).one());
    }

    // ─── fixture builders ────────────────────────────────────────────────────

    /** Build a SCIP index with one document defining symbolName and a relationship to relTarget. */
    private byte[] buildIndex(String symbolName, String relTarget) {
        var relationship = Scip.Relationship.newBuilder()
                .setSymbol(relTarget)
                .setIsImplementation(true)
                .build();

        var symbolInfo = Scip.SymbolInformation.newBuilder()
                .setSymbol(symbolName)
                .setKind(Scip.SymbolInformation.Kind.Class)
                .setDisplayName(symbolName)
                .addRelationships(relationship)
                .build();

        var occurrence = Scip.Occurrence.newBuilder()
                .setSymbol(symbolName)
                .addAllRange(List.of(1, 0, 5, 0))
                .setSymbolRoles(Scip.SymbolRole.Definition_VALUE)
                .build();

        var document = Scip.Document.newBuilder()
                .setRelativePath("src/Foo.java")
                .setLanguage("java")
                .addOccurrences(occurrence)
                .addSymbols(symbolInfo)
                .build();

        return Scip.Index.newBuilder().addDocuments(document).build().toByteArray();
    }

    // ─── tests ────────────────────────────────────────────────────────────────

    @Test
    void twoShasCoexistAfterSuccessiveUploads() {
        var repo = scipService.findRepo("test-repo").orElseThrow();

        byte[] bytesAAA = buildIndex(
                "java maven . com/example/Foo#.",
                "java maven . com/example/Bar#.");
        byte[] bytesBBB = buildIndex(
                "java maven . com/example/Foo#.",
                "java maven . com/example/Bar#.");

        scipService.processUpload(repo, "AAA", bytesAAA);
        scipService.processUpload(repo, "BBB", bytesBBB);

        assertThat(countSymbols(repoId, "AAA")).isEqualTo(1);
        assertThat(countSymbols(repoId, "BBB")).isEqualTo(1);
        assertThat(countRels(repoId, "AAA")).isEqualTo(1);
        assertThat(countRels(repoId, "BBB")).isEqualTo(1);
        // Guard against hidden duplication: total across both SHAs must be exactly 2
        assertThat(countSymbols(repoId, "AAA") + countSymbols(repoId, "BBB")).isEqualTo(2);
    }

    @Test
    void reUploadingShaReplacesOnlyThatSha() {
        var repo = scipService.findRepo("test-repo").orElseThrow();

        // Upload AAA with Foo symbol, then BBB with Foo symbol
        scipService.processUpload(repo, "AAA",
                buildIndex("java maven . com/example/Foo#.", "java maven . com/example/Bar#."));
        scipService.processUpload(repo, "BBB",
                buildIndex("java maven . com/example/Foo#.", "java maven . com/example/Bar#."));

        // Re-upload AAA with a DIFFERENT symbol (Baz instead of Foo)
        scipService.processUpload(repo, "AAA",
                buildIndex("java maven . com/example/Baz#.", "java maven . com/example/Bar#."));

        // AAA should now have only Baz (1 symbol), and Foo should be gone from AAA
        assertThat(countSymbols(repoId, "AAA")).isEqualTo(1);
        assertThat(symbolExists(repoId, "AAA", "%Baz%")).isTrue();
        assertThat(symbolExists(repoId, "AAA", "%Foo%")).isFalse();

        // BBB must be completely untouched
        assertThat(countSymbols(repoId, "BBB")).isEqualTo(1);
        assertThat(symbolExists(repoId, "BBB", "%Foo%")).isTrue();
        assertThat(countRels(repoId, "BBB")).isEqualTo(1);
    }

    @Test
    void reUploadingSameShaIsIdempotent() {
        var repo = scipService.findRepo("test-repo").orElseThrow();
        byte[] bytes = buildIndex(
                "java maven . com/example/Foo#.",
                "java maven . com/example/Bar#.");

        scipService.processUpload(repo, "AAA", bytes);
        scipService.processUpload(repo, "AAA", bytes);  // idempotent re-upload

        assertThat(countSymbols(repoId, "AAA")).isEqualTo(1);
        assertThat(countRels(repoId, "AAA")).isEqualTo(1);
    }
}
