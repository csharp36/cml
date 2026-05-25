package com.indexer.db;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class MigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index")
            .withUsername("test")
            .withPassword("test");

    @Test
    void migrationCreatesAllTables() {
        var dbManager = new DatabaseManager(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
        dbManager.initialize();

        var jdbi = dbManager.getJdbi();
        var tables = jdbi.withHandle(handle ->
                handle.createQuery("""
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = 'public'
                    ORDER BY table_name
                    """)
                        .mapTo(String.class)
                        .list()
        );

        assertThat(tables).contains(
                "repositories", "files", "symbols", "imports",
                "type_relationships", "file_contents", "indexing_events"
        );

        dbManager.close();
    }
}
