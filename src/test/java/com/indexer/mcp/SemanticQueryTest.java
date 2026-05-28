package com.indexer.mcp;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class SemanticQueryTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index")
            .withUsername("test")
            .withPassword("test");

    private Jdbi jdbi;
    private QueryExecutor queryExecutor;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());

        // Run migrations
        var flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        // Insert test data
        jdbi.useHandle(handle -> {
            // Repository
            handle.execute("""
                    INSERT INTO repositories (name, url, branch, clone_path, auth_type, last_indexed_sha)
                    VALUES ('test-repo', 'git@example.com:test.git', 'main', '/tmp/test', 'ssh-key', 'abc123')
                    """);
            int repoId = handle.createQuery("SELECT id FROM repositories WHERE name = 'test-repo'")
                    .mapTo(Integer.class).one();

            // SCIP symbols
            handle.execute("""
                    INSERT INTO scip_symbols (repo_id, scip_symbol, display_name, kind, documentation, file_path, start_line, end_line, upload_sha)
                    VALUES (?, 'java maven . com/example/PaymentProcessor#.', 'PaymentProcessor', 'Interface', 'Payment processing interface', 'src/PaymentProcessor.java', 3, 20, 'abc123')
                    """, repoId);
            handle.execute("""
                    INSERT INTO scip_symbols (repo_id, scip_symbol, display_name, kind, file_path, start_line, end_line, upload_sha)
                    VALUES (?, 'java maven . com/example/StripeProcessor#.', 'StripeProcessor', 'Class', 'src/StripeProcessor.java', 5, 50, 'abc123')
                    """, repoId);
            handle.execute("""
                    INSERT INTO scip_symbols (repo_id, scip_symbol, display_name, kind, file_path, start_line, end_line, upload_sha)
                    VALUES (?, 'java maven . com/example/PayPalProcessor#.', 'PayPalProcessor', 'Class', 'src/PayPalProcessor.java', 3, 40, 'abc123')
                    """, repoId);
            handle.execute("""
                    INSERT INTO scip_symbols (repo_id, scip_symbol, display_name, kind, file_path, start_line, end_line, upload_sha)
                    VALUES (?, 'java maven . com/example/Serializable#.', 'Serializable', 'Interface', 'src/Serializable.java', 1, 5, 'abc123')
                    """, repoId);

            // SCIP relationships
            handle.execute("""
                    INSERT INTO scip_relationships (repo_id, from_symbol, to_symbol, kind, file_path, line)
                    VALUES (?, 'java maven . com/example/StripeProcessor#.', 'java maven . com/example/PaymentProcessor#.', 'implements', 'src/StripeProcessor.java', 5)
                    """, repoId);
            handle.execute("""
                    INSERT INTO scip_relationships (repo_id, from_symbol, to_symbol, kind, file_path, line)
                    VALUES (?, 'java maven . com/example/PayPalProcessor#.', 'java maven . com/example/PaymentProcessor#.', 'implements', 'src/PayPalProcessor.java', 3)
                    """, repoId);
            handle.execute("""
                    INSERT INTO scip_relationships (repo_id, from_symbol, to_symbol, kind, file_path, line)
                    VALUES (?, 'java maven . com/example/StripeProcessor#.', 'java maven . com/example/Serializable#.', 'implements', 'src/StripeProcessor.java', 5)
                    """, repoId);

            // Set SCIP SHA to match indexed SHA (fresh)
            handle.execute("UPDATE repositories SET scip_sha = 'abc123' WHERE id = ?", repoId);
        });

        queryExecutor = new QueryExecutor(jdbi);
    }

    @Test
    void getScipStatusFresh() {
        assertThat(queryExecutor.getScipStatus("test-repo")).isEqualTo("fresh");
    }

    @Test
    void getScipStatusStale() {
        jdbi.useHandle(h -> h.execute("UPDATE repositories SET scip_sha = 'old-sha' WHERE name = 'test-repo'"));
        assertThat(queryExecutor.getScipStatus("test-repo")).isEqualTo("stale");
    }

    @Test
    void getScipStatusUnavailable() {
        jdbi.useHandle(h -> h.execute("UPDATE repositories SET scip_sha = NULL WHERE name = 'test-repo'"));
        assertThat(queryExecutor.getScipStatus("test-repo")).isEqualTo("unavailable");
    }

    @Test
    void getScipStatusUnknownRepo() {
        assertThat(queryExecutor.getScipStatus("nonexistent")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTypeHierarchyBothDirections() {
        var result = queryExecutor.getTypeHierarchy("test-repo", "PaymentProcessor", null, null, "both", 3);
        assertThat(result.get("symbol")).isEqualTo("PaymentProcessor");
        assertThat(result.get("kind")).isEqualTo("Interface");
        assertThat(result.get("scip_status")).isEqualTo("fresh");

        var subtypes = (List<Map<String, Object>>) result.get("subtypes");
        assertThat(subtypes).hasSize(2);
        assertThat(subtypes).extracting(m -> m.get("symbol"))
                .containsExactlyInAnyOrder("StripeProcessor", "PayPalProcessor");
        assertThat(subtypes).allMatch(m -> "implements".equals(m.get("relationship")));

        var supertypes = (List<Map<String, Object>>) result.get("supertypes");
        assertThat(supertypes).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTypeHierarchyUpDirection() {
        var result = queryExecutor.getTypeHierarchy("test-repo", "StripeProcessor", null, null, "up", 3);
        assertThat(result.get("symbol")).isEqualTo("StripeProcessor");

        var supertypes = (List<Map<String, Object>>) result.get("supertypes");
        assertThat(supertypes).hasSize(2);
        assertThat(supertypes).extracting(m -> m.get("symbol"))
                .containsExactlyInAnyOrder("PaymentProcessor", "Serializable");

        assertThat(result).doesNotContainKey("subtypes");
    }

    @Test
    void getTypeHierarchySymbolNotFound() {
        var result = queryExecutor.getTypeHierarchy("test-repo", "NonExistent", null, null, "both", 3);
        assertThat(result.get("message")).isEqualTo("Symbol not found in SCIP data");
        assertThat(result.get("scip_status")).isEqualTo("fresh");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getSymbolReferencesInbound() {
        var result = queryExecutor.getSymbolReferences("test-repo", "PaymentProcessor", null, null, "inbound", 50);
        assertThat(result.get("symbol")).isEqualTo("PaymentProcessor");

        var refs = (List<Map<String, Object>>) result.get("references");
        assertThat(refs).hasSize(2);
        assertThat(refs).allMatch(r -> "inbound".equals(r.get("direction")));
        assertThat(refs).extracting(m -> m.get("symbol"))
                .containsExactlyInAnyOrder("StripeProcessor", "PayPalProcessor");
        assertThat((int) result.get("total")).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getSymbolReferencesOutbound() {
        var result = queryExecutor.getSymbolReferences("test-repo", "StripeProcessor", null, null, "outbound", 50);

        var refs = (List<Map<String, Object>>) result.get("references");
        assertThat(refs).hasSize(2);
        assertThat(refs).allMatch(r -> "outbound".equals(r.get("direction")));
        assertThat(refs).extracting(m -> m.get("symbol"))
                .containsExactlyInAnyOrder("PaymentProcessor", "Serializable");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getSymbolReferencesFilterByKind() {
        var result = queryExecutor.getSymbolReferences("test-repo", "PaymentProcessor", null, "implements", "inbound", 50);

        var refs = (List<Map<String, Object>>) result.get("references");
        assertThat(refs).hasSize(2);
        assertThat(refs).allMatch(r -> "implements".equals(r.get("relationship")));
    }

    @Test
    void getSymbolReferencesNotFound() {
        var result = queryExecutor.getSymbolReferences("test-repo", "NonExistent", null, null, "inbound", 50);
        assertThat(result.get("message")).isEqualTo("Symbol not found in SCIP data");
    }
}
