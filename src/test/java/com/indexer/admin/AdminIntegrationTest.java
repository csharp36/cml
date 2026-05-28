package com.indexer.admin;

import com.indexer.db.*;
import com.indexer.indexing.IndexingPipeline;
import com.indexer.mcp.QueryExecutor;
import com.indexer.repository.GitOperations;
import com.indexer.repository.RepositoryManager;
import com.indexer.server.HttpServer;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
@Tag("integration")
class AdminIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private static final String TOKEN = "integration-test-token";

    private HttpServer httpServer;
    private AdminService adminService;
    private HttpClient client;
    private int port;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        var jdbi = dbManager.getJdbi();

        // Clean tables
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM type_relationships");
            h.execute("DELETE FROM imports");
            h.execute("DELETE FROM symbols");
            h.execute("DELETE FROM file_contents");
            h.execute("DELETE FROM files");
            h.execute("DELETE FROM indexing_events");
            h.execute("DELETE FROM repositories");
        });

        var repositoryDao = new RepositoryDao(jdbi);
        var fileDao = new FileDao(jdbi);
        var symbolDao = new SymbolDao(jdbi);
        var eventDao = new EventDao(jdbi);
        var queryExecutor = new QueryExecutor(jdbi);
        var repoManager = mock(RepositoryManager.class);
        var indexingPipeline = mock(IndexingPipeline.class);
        var gitOps = mock(GitOperations.class);
        when(repoManager.getCloneBaseDir()).thenReturn("/tmp/test-repos");

        adminService = new AdminService(
                repoManager, repositoryDao, fileDao, symbolDao,
                eventDao, indexingPipeline, gitOps, queryExecutor, jdbi);

        try (var ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }

        httpServer = new HttpServer(eventDao, repositoryDao, null);
        var adminApi = new AdminApi(adminService, TOKEN);
        httpServer.addRoutes(adminApi::registerRoutes);
        httpServer.start(port);

        client = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + port;
    }

    @AfterEach
    void tearDown() {
        if (adminService != null) adminService.shutdown();
        if (httpServer != null) httpServer.stop();
    }

    @Test
    void healthEndpointReturnsData() throws Exception {
        var response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin/health"))
                .header("Authorization", "Bearer " + TOKEN)
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        var body = response.body();
        assertThat(body).contains("repositories");
        assertThat(body).contains("totalPendingEvents");
    }

    @Test
    void reposEndpointReturnsEmptyList() throws Exception {
        var response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin/repos"))
                .header("Authorization", "Bearer " + TOKEN)
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("[]");
    }

    @Test
    void eventsEndpointWithFilters() throws Exception {
        var response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin/events?status=failed&limit=10"))
                .header("Authorization", "Bearer " + TOKEN)
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("[]");
    }

    @Test
    void retryNonexistentEventReturns404() throws Exception {
        var response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin/events/99999/retry"))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void deleteNonexistentRepoReturns404() throws Exception {
        var response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin/repos/nonexistent"))
                .header("Authorization", "Bearer " + TOKEN)
                .DELETE()
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void authBlocksUnauthenticatedRequests() throws Exception {
        var response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin/health"))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }
}
