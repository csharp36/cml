package com.indexer.server;

import com.indexer.db.DatabaseManager;
import com.indexer.db.EventDao;
import com.indexer.db.RepositoryDao;
import com.indexer.model.Repository;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class HttpServerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private EventDao eventDao;
    private RepositoryDao repositoryDao;
    private Javalin app;

    @BeforeEach
    void setUp() {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        var jdbi = dbManager.getJdbi();
        eventDao = new EventDao(jdbi);
        repositoryDao = new RepositoryDao(jdbi);
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM indexing_events");
            h.execute("DELETE FROM repositories");
        });
        // Register a test repo so webhook validation passes
        repositoryDao.insert(new Repository(0, "my-repo", "https://example.com/my-repo.git", "main", "/repos/my-repo", "none", null, null));
        var httpServer = new HttpServer(eventDao, repositoryDao, null);
        app = httpServer.createApp();
    }

    @Test
    void acceptsValidWebhookPayload() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/webhook", builder ->
                    builder.header("Content-Type", "application/json")
                            .post(HttpRequest.BodyPublishers.ofString("""
                            {"repoName":"my-repo","repoPath":"/repos/my-repo","eventType":"post-commit","previousSha":"abc123","currentSha":"def456","timestamp":"2026-05-25T12:00:00Z"}
                            """)));
            assertThat(response.code()).isEqualTo(202);
            assertThat(eventDao.countByStatus("pending")).isEqualTo(1);
        });
    }

    @Test
    void rejectsMalformedPayload() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/webhook", builder ->
                    builder.header("Content-Type", "application/json")
                            .post(HttpRequest.BodyPublishers.ofString("""
                            {"repoName":"my-repo"}""")));
            assertThat(response.code()).isEqualTo(400);
            assertThat(eventDao.countByStatus("pending")).isEqualTo(0);
        });
    }

    @Test
    void rejectsUnknownRepo() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/webhook", builder ->
                    builder.header("Content-Type", "application/json")
                            .post(HttpRequest.BodyPublishers.ofString("""
                            {"repoName":"unknown-repo","repoPath":"/repos/unknown","eventType":"post-commit","previousSha":"abc123","currentSha":"def456","timestamp":"2026-05-25T12:00:00Z"}
                            """)));
            assertThat(response.code()).isEqualTo(404);
            assertThat(eventDao.countByStatus("pending")).isEqualTo(0);
        });
    }

    @Test
    void rejectsEmptyBody() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/webhook", builder ->
                    builder.header("Content-Type", "application/json")
                            .post(HttpRequest.BodyPublishers.ofString("")));
            assertThat(response.code()).isEqualTo(400);
        });
    }
}
