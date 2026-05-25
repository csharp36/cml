package com.indexer.mcp.tools;

import com.indexer.db.*;
import com.indexer.mcp.QueryExecutor;
import com.indexer.model.Repository;
import com.indexer.model.SourceFile;
import com.indexer.model.Symbol;
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
class SearchSymbolsToolTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private QueryExecutor queryExecutor;

    @BeforeEach
    void setUp() {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        var jdbi = dbManager.getJdbi();
        jdbi.useHandle(h -> { h.execute("DELETE FROM symbols"); h.execute("DELETE FROM files"); h.execute("DELETE FROM repositories"); });
        var repoDao = new RepositoryDao(jdbi);
        int repoId = repoDao.insert(new Repository(0, "test-repo", "url", "main", "/path", "ssh-key", "abc", Instant.now()));
        var fileDao = new FileDao(jdbi);
        int fileId = fileDao.upsert(new SourceFile(0, repoId, "src/App.java", "java", 500, "abc", Instant.now()));
        var symbolDao = new SymbolDao(jdbi);
        symbolDao.insertSymbol(new Symbol(0, fileId, "App", "class", "public class App", 1, 20, null, "public", false));
        symbolDao.insertSymbol(new Symbol(0, fileId, "run", "method", "public void run()", 5, 10, null, "public", false));
        symbolDao.insertSymbol(new Symbol(0, fileId, "helper", "method", "private int helper()", 12, 18, null, "private", false));
        queryExecutor = new QueryExecutor(jdbi);
    }

    @Test
    void searchesByName() {
        var results = queryExecutor.searchSymbols("App", null, null, null, 20);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("name")).isEqualTo("App");
        assertThat(results.get(0).get("kind")).isEqualTo("class");
    }

    @Test
    void searchesByKind() {
        var results = queryExecutor.searchSymbols(null, "method", null, null, 20);
        assertThat(results).hasSize(2);
    }

    @Test
    void searchesByNamePattern() {
        var results = queryExecutor.searchSymbols("run", null, null, null, 20);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("signature")).isEqualTo("public void run()");
    }

    @Test
    void respectsLimit() {
        var results = queryExecutor.searchSymbols(null, null, null, null, 1);
        assertThat(results).hasSize(1);
    }
}
