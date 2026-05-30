package com.indexer.scip;

import com.indexer.db.DatabaseManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class ScipSchemaMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    @Test
    void scipRelationshipsHasUploadShaAndSymbolsAreUniquePerSha() {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        var jdbi = dbManager.getJdbi();

        Integer relCols = jdbi.withHandle(h -> h.createQuery("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'scip_relationships'
                  AND column_name = 'upload_sha'
                """).mapTo(Integer.class).one());
        assertThat(relCols).isEqualTo(1);

        boolean isNotNull = jdbi.withHandle(h -> h.createQuery("""
                SELECT is_nullable = 'NO' FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'scip_relationships'
                  AND column_name = 'upload_sha'
                """).mapTo(Boolean.class).one());
        assertThat(isNotNull).isTrue();

        boolean uniqueIncludesSha = jdbi.withHandle(h -> h.createQuery("""
                SELECT count(*) > 0 FROM pg_indexes
                WHERE tablename = 'scip_symbols' AND indexdef ILIKE '%upload_sha%' AND indexdef ILIKE '%UNIQUE%'
                """).mapTo(Boolean.class).one());
        assertThat(uniqueIncludesSha).isTrue();

        boolean staleUniqueGone = jdbi.withHandle(h -> h.createQuery("""
                SELECT count(*) = 0 FROM pg_indexes
                WHERE tablename = 'scip_symbols' AND indexdef ILIKE '%UNIQUE%'
                  AND indexdef ILIKE '%, scip_symbol)%'
                  AND indexdef NOT ILIKE '%upload_sha%'
                """).mapTo(Boolean.class).one());
        assertThat(staleUniqueGone).isTrue();

        dbManager.close();
    }
}
