package com.indexer.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indexer.auth.ApiKeyAuthenticator;
import com.indexer.auth.CallerIdentity;
import com.indexer.db.DatabaseManager;
import com.indexer.db.EventDao;
import com.indexer.db.RepositoryDao;
import com.indexer.mcp.McpServerBootstrap;
import com.indexer.mcp.QueryExecutor;
import com.indexer.server.HttpServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that verifies the MCP server works over the Streamable HTTP protocol.
 * Starts a full Javalin + MCP servlet + PostgreSQL (Testcontainers) stack and exercises
 * the JSON-RPC lifecycle: initialize -> initialized notification -> tools/list -> tools/call.
 */
@Testcontainers
@Tag("integration")
class StreamableHttpTransportIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index")
            .withUsername("test")
            .withPassword("test");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer httpServer;
    private McpServerBootstrap mcpBootstrap;
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
        var eventDao = new EventDao(jdbi);
        var queryExecutor = new QueryExecutor(jdbi);

        // Build the Streamable HTTP transport
        var streamableTransport = HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp")
                .build();

        // Pick a random available port
        try (var ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }

        // Wire up the HTTP server with the MCP transport
        httpServer = new HttpServer(eventDao, repositoryDao, streamableTransport);

        // Start the MCP bootstrap before the HTTP server so the transport is ready
        mcpBootstrap = new McpServerBootstrap(queryExecutor);
        mcpBootstrap.startHttp(streamableTransport);

        httpServer.start(port);

        client = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + port;
    }

    @AfterEach
    void tearDown() {
        if (mcpBootstrap != null) mcpBootstrap.stop();
        if (httpServer != null) httpServer.stop();
    }

    @Test
    void initializeAndListTools() throws Exception {
        // Step 1: Send initialize request
        String sessionId = sendInitializeAndGetSessionId();

        // Step 2: Send initialized notification (no response expected for notifications)
        sendInitializedNotification(sessionId);

        // Step 3: Send tools/list request
        String toolsListBody = """
                {
                    "jsonrpc": "2.0",
                    "id": 2,
                    "method": "tools/list",
                    "params": {}
                }
                """;

        var toolsResponse = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .header("Mcp-Session-Id", sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(toolsListBody))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(toolsResponse.statusCode()).isEqualTo(200);

        JsonNode toolsResult = parseSseResponse(toolsResponse.body());
        assertThat(toolsResult.has("result")).isTrue();

        JsonNode tools = toolsResult.get("result").get("tools");
        assertThat(tools.isArray()).isTrue();
        assertThat(tools.size()).isEqualTo(15);

        // Verify expected tool names
        List<String> toolNames = new java.util.ArrayList<>();
        tools.forEach(tool -> toolNames.add(tool.get("name").asText()));

        assertThat(toolNames).containsExactlyInAnyOrder(
                "search_symbols",
                "get_symbol_detail",
                "find_implementations",
                "find_references",
                "search_code",
                "search_files",
                "get_repo_summary",
                "get_file_summary",
                "get_directory_tree",
                "get_index_health",
                "check_sync",
                "query_audit_log",
                "verify_audit_chain",
                "diff_branches",
                "search_branches"
        );
    }

    @Test
    void callToolViaStreamableHttp() throws Exception {
        // Step 1: Initialize session
        String sessionId = sendInitializeAndGetSessionId();
        sendInitializedNotification(sessionId);

        // Step 2: Call get_index_health tool
        String callBody = """
                {
                    "jsonrpc": "2.0",
                    "id": 3,
                    "method": "tools/call",
                    "params": {
                        "name": "get_index_health",
                        "arguments": {}
                    }
                }
                """;

        var callResponse = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .header("Mcp-Session-Id", sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(callBody))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(callResponse.statusCode()).isEqualTo(200);

        JsonNode callResult = parseSseResponse(callResponse.body());
        assertThat(callResult.has("result")).isTrue();

        // The result should contain content with health data
        JsonNode content = callResult.get("result").get("content");
        assertThat(content).isNotNull();
        assertThat(content.isArray()).isTrue();
        assertThat(content.size()).isGreaterThan(0);

        // Parse the text content — it's JSON from QueryExecutor.getIndexHealth()
        String healthJson = content.get(0).get("text").asText();
        JsonNode healthData = MAPPER.readTree(healthJson);
        assertThat(healthData.has("repositories")).isTrue();
        assertThat(healthData.has("totalPendingEvents")).isTrue();
        assertThat(healthData.has("totalFailedEvents")).isTrue();
        assertThat(healthData.has("recentFailures")).isTrue();
    }

    @Test
    void rejectsRequestWithoutSession() throws Exception {
        // Send a tools/list request without initializing first (no session ID)
        String toolsListBody = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "tools/list",
                    "params": {}
                }
                """;

        var response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toolsListBody))
                .build(), HttpResponse.BodyHandlers.ofString());

        // The MCP SDK should reject this — either with a 4xx status or an error in the response.
        // Without a session, the server may return 400/404 or a JSON-RPC error.
        // The key assertion: it should NOT return a valid tools list.
        if (response.statusCode() == 200) {
            // If 200, it might be an SSE error response
            JsonNode result = parseSseResponse(response.body());
            // Should have an error, not a valid result with tools
            boolean hasError = result.has("error");
            boolean hasToolsResult = result.has("result")
                    && result.get("result").has("tools")
                    && result.get("result").get("tools").size() > 0;
            assertThat(hasError || !hasToolsResult)
                    .as("Request without session should not return a valid tools list")
                    .isTrue();
        } else {
            // Non-200 status is an acceptable rejection
            assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
        }
    }

    @Test
    void authenticatedRequestSucceeds() throws Exception {
        // Stop the default server started by @BeforeEach
        if (mcpBootstrap != null) mcpBootstrap.stop();
        if (httpServer != null) httpServer.stop();

        // Set up a new server with API key authentication enabled
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        var jdbi = dbManager.getJdbi();
        var repositoryDao = new RepositoryDao(jdbi);
        var eventDao = new EventDao(jdbi);
        var queryExecutor = new QueryExecutor(jdbi);

        var authenticator = new ApiKeyAuthenticator(List.of(
                new ApiKeyAuthenticator.ApiKeyConfig("test-secret-key", "test-user", "Test User", false)
        ));

        var authTransport = HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp")
                .contextExtractor(request -> {
                    String authHeader = request.getHeader("Authorization");
                    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                        throw new RuntimeException("Missing Authorization header");
                    }
                    String token = authHeader.substring("Bearer ".length());
                    CallerIdentity identity = authenticator.authenticate(token, request.getRemoteAddr())
                            .orElseThrow(() -> new RuntimeException("Invalid API key"));
                    return McpTransportContext.create(Map.of(CallerIdentity.CONTEXT_KEY, identity));
                })
                .build();

        int authPort;
        try (var ss = new ServerSocket(0)) {
            authPort = ss.getLocalPort();
        }

        var authHttpServer = new HttpServer(eventDao, repositoryDao, authTransport);
        var authMcpBootstrap = new McpServerBootstrap(queryExecutor);
        authMcpBootstrap.startHttp(authTransport);
        authHttpServer.start(authPort);

        try {
            String authBaseUrl = "http://localhost:" + authPort;
            String initBody = """
                    {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": "initialize",
                        "params": {
                            "protocolVersion": "2025-03-26",
                            "capabilities": {},
                            "clientInfo": {
                                "name": "integration-test",
                                "version": "1.0.0"
                            }
                        }
                    }
                    """;

            var initResponse = client.send(HttpRequest.newBuilder()
                    .uri(URI.create(authBaseUrl + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream, application/json")
                    .header("Authorization", "Bearer test-secret-key")
                    .POST(HttpRequest.BodyPublishers.ofString(initBody))
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertThat(initResponse.statusCode()).isEqualTo(200);

            String sessionId = initResponse.headers().firstValue("Mcp-Session-Id").orElse(
                    initResponse.headers().firstValue("mcp-session-id").orElse(null));
            assertThat(sessionId)
                    .as("Authenticated initialize should return a session ID")
                    .isNotNull()
                    .isNotBlank();

            JsonNode initResult = parseSseResponse(initResponse.body());
            assertThat(initResult.has("result")).isTrue();
            assertThat(initResult.get("result").has("serverInfo")).isTrue();
        } finally {
            authMcpBootstrap.stop();
            authHttpServer.stop();
        }
    }

    @Test
    void unauthenticatedRequestRejectedWhenAuthConfigured() throws Exception {
        // Stop the default server started by @BeforeEach
        if (mcpBootstrap != null) mcpBootstrap.stop();
        if (httpServer != null) httpServer.stop();

        // Set up a new server with API key authentication enabled
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        var jdbi = dbManager.getJdbi();
        var repositoryDao = new RepositoryDao(jdbi);
        var eventDao = new EventDao(jdbi);
        var queryExecutor = new QueryExecutor(jdbi);

        var authenticator = new ApiKeyAuthenticator(List.of(
                new ApiKeyAuthenticator.ApiKeyConfig("test-secret-key", "test-user", "Test User", false)
        ));

        var authTransport = HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp")
                .contextExtractor(request -> {
                    String authHeader = request.getHeader("Authorization");
                    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                        throw new RuntimeException("Missing Authorization header");
                    }
                    String token = authHeader.substring("Bearer ".length());
                    CallerIdentity identity = authenticator.authenticate(token, request.getRemoteAddr())
                            .orElseThrow(() -> new RuntimeException("Invalid API key"));
                    return McpTransportContext.create(Map.of(CallerIdentity.CONTEXT_KEY, identity));
                })
                .build();

        int authPort;
        try (var ss = new ServerSocket(0)) {
            authPort = ss.getLocalPort();
        }

        var authHttpServer = new HttpServer(eventDao, repositoryDao, authTransport);
        var authMcpBootstrap = new McpServerBootstrap(queryExecutor);
        authMcpBootstrap.startHttp(authTransport);
        authHttpServer.start(authPort);

        try {
            String authBaseUrl = "http://localhost:" + authPort;
            String initBody = """
                    {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": "initialize",
                        "params": {
                            "protocolVersion": "2025-03-26",
                            "capabilities": {},
                            "clientInfo": {
                                "name": "integration-test",
                                "version": "1.0.0"
                            }
                        }
                    }
                    """;

            // Send request WITHOUT Authorization header
            var response = client.send(HttpRequest.newBuilder()
                    .uri(URI.create(authBaseUrl + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream, application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(initBody))
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode())
                    .as("Request without Authorization header should be rejected with a 4xx status")
                    .isGreaterThanOrEqualTo(400);
        } finally {
            authMcpBootstrap.stop();
            authHttpServer.stop();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Send an InitializeRequest and return the Mcp-Session-Id from the response.
     */
    private String sendInitializeAndGetSessionId() throws Exception {
        String initBody = """
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "initialize",
                    "params": {
                        "protocolVersion": "2025-03-26",
                        "capabilities": {},
                        "clientInfo": {
                            "name": "integration-test",
                            "version": "1.0.0"
                        }
                    }
                }
                """;

        var initResponse = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .POST(HttpRequest.BodyPublishers.ofString(initBody))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(initResponse.statusCode()).isEqualTo(200);

        // Mcp-Session-Id should be in response headers
        String sessionId = initResponse.headers().firstValue("Mcp-Session-Id").orElse(
                initResponse.headers().firstValue("mcp-session-id").orElse(null));
        assertThat(sessionId)
                .as("Initialize response should contain Mcp-Session-Id header")
                .isNotNull()
                .isNotBlank();

        // Verify the SSE response contains a valid initialize result
        JsonNode initResult = parseSseResponse(initResponse.body());
        assertThat(initResult.has("result")).isTrue();
        assertThat(initResult.get("result").has("serverInfo")).isTrue();
        assertThat(initResult.get("result").get("serverInfo").get("name").asText())
                .isEqualTo("source-code-indexer");

        return sessionId;
    }

    /**
     * Send the initialized notification. Notifications have no id and expect no response body.
     */
    private void sendInitializedNotification(String sessionId) throws Exception {
        String notifyBody = """
                {
                    "jsonrpc": "2.0",
                    "method": "notifications/initialized"
                }
                """;

        var notifyResponse = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .header("Mcp-Session-Id", sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(notifyBody))
                .build(), HttpResponse.BodyHandlers.ofString());

        // Notifications should return 200 or 202 (no response payload required)
        assertThat(notifyResponse.statusCode()).isBetween(200, 204);
    }

    /**
     * Parse an SSE (Server-Sent Events) response body to extract the JSON-RPC message.
     * SSE lines are formatted as "data: {json...}" potentially across multiple lines.
     */
    private JsonNode parseSseResponse(String body) throws Exception {
        // Try direct JSON parse first (some responses may be plain JSON)
        body = body.trim();
        if (body.startsWith("{")) {
            return MAPPER.readTree(body);
        }

        // Parse SSE format: find lines starting with "data:"
        String[] lines = body.split("\n");
        StringBuilder jsonBuilder = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("data:")) {
                String data = line.substring("data:".length()).trim();
                if (!data.isEmpty()) {
                    jsonBuilder.append(data);
                }
            }
        }

        String json = jsonBuilder.toString();
        assertThat(json)
                .as("SSE response should contain at least one data line, body was: %s", body)
                .isNotBlank();
        return MAPPER.readTree(json);
    }
}
