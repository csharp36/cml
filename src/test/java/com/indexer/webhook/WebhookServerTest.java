package com.indexer.webhook;

import com.indexer.db.DatabaseManager;
import com.indexer.db.EventDao;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class WebhookServerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private static final MediaType JSON = MediaType.get("application/json");

    private EventDao eventDao;
    private Javalin app;

    @BeforeEach
    void setUp() {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        eventDao = new EventDao(dbManager.getJdbi());
        dbManager.getJdbi().useHandle(h -> h.execute("DELETE FROM indexing_events"));
        var webhookServer = new WebhookServer(eventDao);
        app = webhookServer.createApp();
    }

    @Test
    void acceptsValidWebhookPayload() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/webhook", builder ->
                    builder.post(RequestBody.create("""
                            {"repoName":"my-repo","repoPath":"/repos/my-repo","eventType":"post-commit","previousSha":"abc123","currentSha":"def456","timestamp":"2026-05-25T12:00:00Z"}
                            """, JSON)));
            assertThat(response.code()).isEqualTo(202);
            assertThat(eventDao.countByStatus("pending")).isEqualTo(1);
        });
    }

    @Test
    void rejectsMalformedPayload() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/webhook", builder ->
                    builder.post(RequestBody.create("""
                            {"repoName":"my-repo"}""", JSON)));
            assertThat(response.code()).isEqualTo(400);
            assertThat(eventDao.countByStatus("pending")).isEqualTo(0);
        });
    }

    @Test
    void rejectsEmptyBody() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/webhook", builder ->
                    builder.post(RequestBody.create("", JSON)));
            assertThat(response.code()).isEqualTo(400);
        });
    }
}
