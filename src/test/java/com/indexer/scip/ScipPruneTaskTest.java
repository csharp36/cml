package com.indexer.scip;

import com.indexer.db.RepositoryDao;
import org.flywaydb.core.Flyway;
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
class ScipPruneTaskTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index")
            .withUsername("test")
            .withPassword("test");

    private Jdbi jdbi;
    private int repoId;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());

        // Clean + migrate schema
        Flyway flyway = Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        // Seed repository: last_indexed_sha = 'MAIN'
        repoId = jdbi.withHandle(handle ->
                handle.createQuery("""
                        INSERT INTO repositories (name, url, branch, clone_path, auth_type, last_indexed_sha)
                        VALUES ('test-repo', 'git@example.com:test.git', 'main', '/tmp/test', 'ssh-key', 'MAIN')
                        RETURNING id
                        """)
                        .mapTo(Integer.class)
                        .one()
        );

        // Seed branch_index: v1.0 tag -> indexed_sha = 'TAGSHA'
        jdbi.useHandle(handle ->
                handle.execute("""
                        INSERT INTO branch_index (repo_id, branch, base_sha, indexed_sha)
                        VALUES (?, 'v1.0', 'MAIN', 'TAGSHA')
                        """, repoId)
        );

        // Seed scip_symbols for all four SHAs
        jdbi.useHandle(handle -> {
            // MAIN — current main, keep
            handle.execute("""
                    INSERT INTO scip_symbols (repo_id, scip_symbol, display_name, kind, file_path, start_line, end_line, upload_sha, uploaded_at)
                    VALUES (?, 'sym.main', 'MainSym', 'Class', 'src/Main.java', 1, 10, 'MAIN', NOW())
                    """, repoId);

            // TAGSHA — live branch_index ref, keep
            handle.execute("""
                    INSERT INTO scip_symbols (repo_id, scip_symbol, display_name, kind, file_path, start_line, end_line, upload_sha, uploaded_at)
                    VALUES (?, 'sym.tag', 'TagSym', 'Class', 'src/Tag.java', 1, 10, 'TAGSHA', NOW())
                    """, repoId);

            // OLD — 30 days old and unreferenced, PRUNE
            handle.execute("""
                    INSERT INTO scip_symbols (repo_id, scip_symbol, display_name, kind, file_path, start_line, end_line, upload_sha, uploaded_at)
                    VALUES (?, 'sym.old', 'OldSym', 'Class', 'src/Old.java', 1, 10, 'OLD', NOW() - INTERVAL '30 days')
                    """, repoId);

            // RECENT — within grace window (today), keep
            handle.execute("""
                    INSERT INTO scip_symbols (repo_id, scip_symbol, display_name, kind, file_path, start_line, end_line, upload_sha, uploaded_at)
                    VALUES (?, 'sym.recent', 'RecentSym', 'Class', 'src/Recent.java', 1, 10, 'RECENT', NOW())
                    """, repoId);
        });

        // Seed scip_relationships for all four SHAs
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO scip_relationships (repo_id, from_symbol, to_symbol, kind, file_path, line, upload_sha)
                    VALUES (?, 'sym.main', 'sym.dep', 'implements', 'src/Main.java', 1, 'MAIN')
                    """, repoId);
            handle.execute("""
                    INSERT INTO scip_relationships (repo_id, from_symbol, to_symbol, kind, file_path, line, upload_sha)
                    VALUES (?, 'sym.tag', 'sym.dep', 'implements', 'src/Tag.java', 1, 'TAGSHA')
                    """, repoId);
            handle.execute("""
                    INSERT INTO scip_relationships (repo_id, from_symbol, to_symbol, kind, file_path, line, upload_sha)
                    VALUES (?, 'sym.old', 'sym.dep', 'implements', 'src/Old.java', 1, 'OLD')
                    """, repoId);
            handle.execute("""
                    INSERT INTO scip_relationships (repo_id, from_symbol, to_symbol, kind, file_path, line, upload_sha)
                    VALUES (?, 'sym.recent', 'sym.dep', 'implements', 'src/Recent.java', 1, 'RECENT')
                    """, repoId);
        });
    }

    @Test
    void pruneRemovesOldUnreferencedShaAndKeepsRetainedOnes() {
        new ScipPruneTask(new ScipDao(jdbi), new RepositoryDao(jdbi), 7).run();

        // OLD should be pruned
        assertThat(countSymbols(repoId, "OLD")).isZero();
        assertThat(countRels(repoId, "OLD")).isZero();

        // MAIN, TAGSHA, RECENT should all be kept
        assertThat(countSymbols(repoId, "MAIN")).isPositive();
        assertThat(countSymbols(repoId, "TAGSHA")).isPositive();
        assertThat(countSymbols(repoId, "RECENT")).isPositive();
        assertThat(countRels(repoId, "MAIN")).isPositive();
        assertThat(countRels(repoId, "TAGSHA")).isPositive();
        assertThat(countRels(repoId, "RECENT")).isPositive();
    }

    @Test
    void pruneIsIdempotent() {
        var task = new ScipPruneTask(new ScipDao(jdbi), new RepositoryDao(jdbi), 7);
        task.run();
        task.run(); // second run should not throw and OLD remains zero

        assertThat(countSymbols(repoId, "OLD")).isZero();
        assertThat(countRels(repoId, "OLD")).isZero();
        assertThat(countSymbols(repoId, "MAIN")).isPositive();
    }

    @Test
    void pruneWithZeroGraceDaysDoesNotPruneCurrentMainOrLiveBranchRef() {
        // graceDays=0 means no grace window — but MAIN and TAGSHA are still protected by
        // the primary retention rule (repositories.last_indexed_sha + branch_index.indexed_sha).
        // With graceDays=0, RECENT (uploaded_at=NOW()) also becomes prunable, so we assert only the primary retention rules (current main SHA + live branch_index ref are kept regardless of grace).
        new ScipPruneTask(new ScipDao(jdbi), new RepositoryDao(jdbi), 0).run();

        assertThat(countSymbols(repoId, "MAIN")).isPositive();
        assertThat(countSymbols(repoId, "TAGSHA")).isPositive();
        // OLD should still be pruned
        assertThat(countSymbols(repoId, "OLD")).isZero();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private int countSymbols(int repoId, String sha) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT count(*) FROM scip_symbols WHERE repo_id = :repoId AND upload_sha = :sha")
                        .bind("repoId", repoId)
                        .bind("sha", sha)
                        .mapTo(Integer.class)
                        .one()
        );
    }

    private int countRels(int repoId, String sha) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT count(*) FROM scip_relationships WHERE repo_id = :repoId AND upload_sha = :sha")
                        .bind("repoId", repoId)
                        .bind("sha", sha)
                        .mapTo(Integer.class)
                        .one()
        );
    }
}
