package com.indexer.webhook;

import com.indexer.audit.AuditEvent;
import com.indexer.audit.AuditSink;
import com.indexer.db.DatabaseManager;
import com.indexer.db.EventDao;
import com.indexer.db.RepositoryDao;
import com.indexer.model.Repository;
import com.indexer.server.HttpServer;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

@Testcontainers
@Tag("integration")
class GitHubWebhookApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private static final String SECRET = "test-webhook-secret";

    private HttpServer httpServer;
    private EventDao eventDao;
    private RepositoryDao repositoryDao;
    private AuditSink auditSink;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        var jdbi = dbManager.getJdbi();
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM indexing_events");
            h.execute("DELETE FROM repositories");
        });

        repositoryDao = new RepositoryDao(jdbi);
        eventDao = new EventDao(jdbi);
        // Seed an indexed repo named "cml" on branch "main".
        repositoryDao.insert(new Repository(0, "cml", "git@github.com:org/cml.git",
                "main", "/tmp/cml", "SSH_KEY", "abc", null));

        auditSink = mock(AuditSink.class);
        var api = new GitHubWebhookApi(Map.of("cml", SECRET), repositoryDao, eventDao, auditSink);

        int port;
        try (var ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        httpServer = new HttpServer(eventDao, repositoryDao, null);
        httpServer.addRoutes(api::registerRoutes);
        httpServer.start(port);

        client = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + port;
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) httpServer.stop();
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private HttpResponse<String> post(String path, String body, String event, String signature) throws Exception {
        var b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (event != null) b.header("X-GitHub-Event", event);
        if (signature != null) b.header("X-Hub-Signature-256", signature);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void validPushToMainEnqueuesEvent() throws Exception {
        String body = "{\"ref\":\"refs/heads/main\",\"before\":\"old\",\"after\":\"new\",\"repository\":{\"name\":\"cml\"}}";
        var resp = post("/webhook/github/cml", body, "push", sign(body));
        assertThat(resp.statusCode()).isEqualTo(202);
        assertThat(eventDao.countByStatus("pending")).isEqualTo(1);
    }

    @Test
    void pingEventReturns200AndEnqueuesNothing() throws Exception {
        String body = "{\"zen\":\"Keep it simple\"}";
        var resp = post("/webhook/github/cml", body, "ping", sign(body));
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(eventDao.countByStatus("pending")).isEqualTo(0);
    }

    @Test
    void pushToNonConfiguredBranchIsNoOp() throws Exception {
        String body = "{\"ref\":\"refs/heads/feature/x\",\"before\":\"old\",\"after\":\"new\",\"repository\":{\"name\":\"cml\"}}";
        var resp = post("/webhook/github/cml", body, "push", sign(body));
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(eventDao.countByStatus("pending")).isEqualTo(0);
    }

    @Test
    void badSignatureReturns401() throws Exception {
        String body = "{\"ref\":\"refs/heads/main\",\"before\":\"old\",\"after\":\"new\",\"repository\":{\"name\":\"cml\"}}";
        var resp = post("/webhook/github/cml", body, "push", "sha256=deadbeef");
        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(eventDao.countByStatus("pending")).isEqualTo(0);
    }

    @Test
    void unknownRepoReturns404() throws Exception {
        String body = "{\"ref\":\"refs/heads/main\",\"before\":\"old\",\"after\":\"new\"}";
        var resp = post("/webhook/github/unknown", body, "push", sign(body));
        assertThat(resp.statusCode()).isEqualTo(404);
    }

    @Test
    void configuredRepoWithoutSecretReturns401() throws Exception {
        // Repo exists in the DB but has no entry in the webhook-secret map -> fail-closed 401.
        repositoryDao.insert(new Repository(0, "nosecret", "git@github.com:org/nosecret.git",
                "main", "/tmp/nosecret", "SSH_KEY", "abc", null));
        String body = "{\"ref\":\"refs/heads/main\",\"before\":\"old\",\"after\":\"new\",\"repository\":{\"name\":\"nosecret\"}}";
        var resp = post("/webhook/github/nosecret", body, "push", sign(body));
        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(eventDao.countByStatus("pending")).isEqualTo(0);
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        String body = "{not valid json";
        var resp = post("/webhook/github/cml", body, "push", sign(body));
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(eventDao.countByStatus("pending")).isEqualTo(0);
    }

    @Test
    void branchDeletionIsNoOp() throws Exception {
        String body = "{\"ref\":\"refs/heads/main\",\"before\":\"old\","
                + "\"after\":\"0000000000000000000000000000000000000000\",\"repository\":{\"name\":\"cml\"}}";
        var resp = post("/webhook/github/cml", body, "push", sign(body));
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(eventDao.countByStatus("pending")).isEqualTo(0);
    }

    @Test
    void largePushAboveDefaultLimitIsAccepted() throws Exception {
        // ~2 MB body — exceeds Javalin's default 1 MB maxRequestSize. With the raised
        // limit it must be read in full, verified, and enqueued (not truncated/rejected).
        String filler = "x".repeat(2 * 1024 * 1024);
        String body = "{\"ref\":\"refs/heads/main\",\"before\":\"old\",\"after\":\"new\","
                + "\"repository\":{\"name\":\"cml\"},\"_filler\":\"" + filler + "\"}";
        var resp = post("/webhook/github/cml", body, "push", sign(body));
        assertThat(resp.statusCode()).isEqualTo(202);
        assertThat(eventDao.countByStatus("pending")).isEqualTo(1);
    }

    @Test
    void validPushRecordsSuccessAudit() throws Exception {
        String body = "{\"ref\":\"refs/heads/main\",\"before\":\"old\",\"after\":\"new\",\"repository\":{\"name\":\"cml\"}}";
        var resp = post("/webhook/github/cml", body, "push", sign(body));
        assertThat(resp.statusCode()).isEqualTo(202);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditSink, atLeastOnce()).record(captor.capture());
        assertThat(captor.getAllValues())
                .anySatisfy(e -> {
                    assertThat(e.action()).isEqualTo("githubWebhook:enqueue");
                    assertThat(e.repo()).isEqualTo("cml");
                    assertThat(e.authorized()).isTrue();
                    assertThat(e.resultStatus()).isEqualTo("success");
                });
    }
}
