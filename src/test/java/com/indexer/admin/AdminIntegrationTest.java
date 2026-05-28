package com.indexer.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indexer.db.*;
import com.indexer.indexing.IndexingPipeline;
import com.indexer.mcp.QueryExecutor;
import com.indexer.repository.GitOperations;
import com.indexer.repository.RepositoryManager;
import com.indexer.server.HttpServer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
@Tag("integration")
class AdminIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private static final MediaType JSON = MediaType.get("application/json");
    private static final String TOKEN = "integration-test-token";

    private HttpServer httpServer;
    private AdminService adminService;
    private OkHttpClient client;
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

        httpServer = new HttpServer(eventDao, repositoryDao);
        var adminApi = new AdminApi(adminService, TOKEN);
        adminApi.registerRoutes(httpServer.getApp());
        httpServer.start(port);

        client = new OkHttpClient();
        baseUrl = "http://localhost:" + port;
    }

    @AfterEach
    void tearDown() {
        if (adminService != null) adminService.shutdown();
        if (httpServer != null) httpServer.stop();
    }

    @Test
    void healthEndpointReturnsData() throws Exception {
        var response = client.newCall(new Request.Builder()
                .url(baseUrl + "/admin/health")
                .header("Authorization", "Bearer " + TOKEN)
                .build()).execute();

        assertThat(response.code()).isEqualTo(200);
        var body = response.body().string();
        assertThat(body).contains("repositories");
        assertThat(body).contains("totalPendingEvents");
    }

    @Test
    void reposEndpointReturnsEmptyList() throws Exception {
        var response = client.newCall(new Request.Builder()
                .url(baseUrl + "/admin/repos")
                .header("Authorization", "Bearer " + TOKEN)
                .build()).execute();

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.body().string()).isEqualTo("[]");
    }

    @Test
    void eventsEndpointWithFilters() throws Exception {
        var response = client.newCall(new Request.Builder()
                .url(baseUrl + "/admin/events?status=failed&limit=10")
                .header("Authorization", "Bearer " + TOKEN)
                .build()).execute();

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.body().string()).isEqualTo("[]");
    }

    @Test
    void retryNonexistentEventReturns404() throws Exception {
        var response = client.newCall(new Request.Builder()
                .url(baseUrl + "/admin/events/99999/retry")
                .header("Authorization", "Bearer " + TOKEN)
                .post(RequestBody.create("", JSON))
                .build()).execute();

        assertThat(response.code()).isEqualTo(404);
    }

    @Test
    void deleteNonexistentRepoReturns404() throws Exception {
        var response = client.newCall(new Request.Builder()
                .url(baseUrl + "/admin/repos/nonexistent")
                .header("Authorization", "Bearer " + TOKEN)
                .delete()
                .build()).execute();

        assertThat(response.code()).isEqualTo(404);
    }

    @Test
    void authBlocksUnauthenticatedRequests() throws Exception {
        var response = client.newCall(new Request.Builder()
                .url(baseUrl + "/admin/health")
                .build()).execute();

        assertThat(response.code()).isEqualTo(401);
    }
}
