package com.indexer.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indexer.db.DatabaseManager;
import com.indexer.db.EventDao;
import com.indexer.mcp.McpServerBootstrap;
import com.indexer.server.HttpServer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class SseTransportIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private HttpServer httpServer;
    private McpServerBootstrap mcpServer;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        var jdbi = dbManager.getJdbi();
        var eventDao = new EventDao(jdbi);

        // Find a free port
        try (var ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }

        // Set up HTTP server with SSE transport
        httpServer = new HttpServer(eventDao);
        var sseTransport = new JavalinSseServerTransportProvider(new ObjectMapper());
        sseTransport.registerRoutes(httpServer.getApp());
        httpServer.start(port);

        // Start MCP server over SSE
        mcpServer = new McpServerBootstrap(jdbi);
        mcpServer.startSse(sseTransport);
    }

    @AfterEach
    void tearDown() {
        if (mcpServer != null) mcpServer.stop();
        if (httpServer != null) httpServer.stop();
    }

    @Test
    void sseConnectionReceivesEndpointEvent() throws Exception {
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        // Connect to SSE endpoint and read the first event
        var future = CompletableFuture.supplyAsync(() -> {
            try {
                var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                assertThat(response.statusCode()).isEqualTo(200);
                var reader = new BufferedReader(new InputStreamReader(response.body()));
                StringBuilder event = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    event.append(line).append("\n");
                    // SSE events are terminated by a blank line
                    if (line.isEmpty() && event.length() > 1) {
                        break;
                    }
                }
                return event.toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        String firstEvent = future.get(10, TimeUnit.SECONDS);
        assertThat(firstEvent).contains("endpoint");
        assertThat(firstEvent).contains("/mcp/message?sessionId=");
    }

    @Test
    void toolsListOverSse() throws Exception {
        var client = HttpClient.newHttpClient();

        // Step 1: Connect to SSE and get the session endpoint
        var sseRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        var sessionEndpoint = CompletableFuture.supplyAsync(() -> {
            try {
                var response = client.send(sseRequest, HttpResponse.BodyHandlers.ofInputStream());
                var reader = new BufferedReader(new InputStreamReader(response.body()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        return line.substring(5).trim();
                    }
                }
                throw new RuntimeException("No endpoint event received");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        String endpoint = sessionEndpoint.get(10, TimeUnit.SECONDS);
        assertThat(endpoint).contains("/mcp/message?sessionId=");

        // Step 2: Send initialize request
        String initRequest = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}
                """;

        var initResponse = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + endpoint))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(initRequest))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(initResponse.statusCode()).isEqualTo(202);
    }
}
