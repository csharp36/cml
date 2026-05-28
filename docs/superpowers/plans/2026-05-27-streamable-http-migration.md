# Streamable HTTP Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the custom SSE transport with the MCP SDK's Streamable HTTP transport, upgrading the SDK from 0.10.0 to 1.1.2 and Javalin from 6.4.0 to 7.2.2.

**Architecture:** Mount the SDK's `HttpServletStreamableServerTransportProvider` servlet on Javalin's embedded Jetty via `config.jetty.modifyServletContextHandler()`. Delete the two custom transport files. stdio transport unchanged.

**Tech Stack:** Java 21, MCP SDK 1.1.2, Javalin 7.2.2, Jetty 12, Jakarta Servlet 6.x

**Spec:** `docs/superpowers/specs/2026-05-27-streamable-http-migration-design.md`

---

### Task 1: Upgrade Dependencies

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Update dependency versions**

Replace the MCP SDK dependency — the v1.1.2 convenience `mcp` bundle pulls in Jackson 3.x which conflicts with our Jackson 2.18.2. Use explicit `mcp-core` + `mcp-json-jackson2` instead.

```kotlin
// MCP SDK — use explicit modules to keep Jackson 2.x
implementation("io.modelcontextprotocol.sdk:mcp-core:1.1.2")
implementation("io.modelcontextprotocol.sdk:mcp-json-jackson2:1.1.2")

// HTTP server — upgrade for Jetty 12 / Jakarta Servlet 6.x compatibility
implementation("io.javalin:javalin:7.2.2")
```

And in the test dependencies:

```kotlin
testImplementation("io.javalin:javalin-testtools:7.2.2")
```

Remove the old single-line MCP dependency:
```kotlin
// DELETE: implementation("io.modelcontextprotocol.sdk:mcp:0.10.0")
```

- [ ] **Step 2: Verify the project compiles**

Run: `./gradlew compileJava 2>&1 | tail -5`

Expected: Compilation errors related to API changes (Javalin 7 route registration, MCP SDK API changes). These will be fixed in subsequent tasks. The goal here is to confirm dependency resolution succeeds — the JARs download and there are no version conflicts.

If dependency resolution fails (e.g., `mcp-json-jackson2:1.1.2` doesn't exist), fall back to `mcp-core:1.1.2` only and construct `JacksonMcpJsonMapper` manually.

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "chore: upgrade MCP SDK to 1.1.2 and Javalin to 7.2.2"
```

---

### Task 2: Delete Custom SSE Transport Files

**Files:**
- Delete: `src/main/java/com/indexer/mcp/transport/JavalinSseServerTransportProvider.java`
- Delete: `src/main/java/com/indexer/mcp/transport/JavalinSseSessionTransport.java`

- [ ] **Step 1: Delete the files**

```bash
rm src/main/java/com/indexer/mcp/transport/JavalinSseServerTransportProvider.java
rm src/main/java/com/indexer/mcp/transport/JavalinSseSessionTransport.java
```

Do NOT commit yet — compilation will fail because `McpServerBootstrap` and `Application` still reference these classes. They will be fixed in the next tasks.

---

### Task 3: Update McpServerBootstrap

**Files:**
- Modify: `src/main/java/com/indexer/mcp/McpServerBootstrap.java`

The MCP SDK 1.1.2 introduces a new transport provider interface: `McpStreamableServerTransportProvider`. The `HttpServletStreamableServerTransportProvider` implements this new interface, NOT the old `McpServerTransportProvider`. There are separate `McpServer.sync()` overloads for each.

- [ ] **Step 1: Update imports**

Remove:
```java
import com.indexer.mcp.transport.JavalinSseServerTransportProvider;
```

Add:
```java
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
```

- [ ] **Step 2: Rename the `sseServer` field to `httpServer`**

```java
private McpSyncServer httpServer;  // was: sseServer
```

- [ ] **Step 3: Replace `startSse` with `startHttp`**

The `startSse` method currently takes `JavalinSseServerTransportProvider` and calls `buildServer()`. Replace it:

```java
public void startHttp(McpStreamableServerTransportProvider httpTransport) {
    httpServer = McpServer.sync(httpTransport)
            .serverInfo("source-code-indexer", "1.0.0")
            .tool(searchSymbolsTool(), this::handleSearchSymbols)
            .tool(getSymbolDetailTool(), this::handleGetSymbolDetail)
            .tool(findImplementationsTool(), this::handleFindImplementations)
            .tool(findReferencesTool(), this::handleFindReferences)
            .tool(searchCodeTool(), this::handleSearchCode)
            .tool(searchFilesTool(), this::handleSearchFiles)
            .tool(getRepoSummaryTool(), this::handleGetRepoSummary)
            .tool(getFileSummaryTool(), this::handleGetFileSummary)
            .tool(getDirectoryTreeTool(), this::handleGetDirectoryTree)
            .tool(getIndexHealthTool(), this::handleGetIndexHealth)
            .tool(checkSyncTool(), this::handleCheckSync)
            .build();
    log.info("MCP server started over Streamable HTTP with 11 tools registered");
}
```

Note: `McpServer.sync(McpStreamableServerTransportProvider)` returns `StreamableSyncSpecification`, which is a different builder type than `SingleSessionSyncSpecification` returned by `McpServer.sync(McpServerTransportProvider)`. Both have `.tool()` and `.build()`, but they are separate types. This is why the tool registration is duplicated rather than sharing `buildServer()`. The existing `buildServer(McpServerTransportProvider)` is still used by `startStdio()`.

- [ ] **Step 4: Update `stop()` method**

```java
public void stop() {
    if (httpServer != null) {
        httpServer.closeGracefully();
    }
    if (stdioServer != null) {
        stdioServer.closeGracefully();
    }
}
```

- [ ] **Step 5: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -5`

Expected: `McpServerBootstrap` compiles. `Application.java` still has errors (references old SSE transport). This is expected and fixed in Task 4.

---

### Task 4: Update Application Wiring

**Files:**
- Modify: `src/main/java/com/indexer/Application.java`

- [ ] **Step 1: Update imports**

Remove:
```java
import com.indexer.mcp.transport.JavalinSseServerTransportProvider;
```

Add:
```java
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
```

Note: Javalin 7 uses Jetty 12 which reorganized packages. The servlet holder is in `org.eclipse.jetty.ee10.servlet`. If compilation fails with this import, try `org.eclipse.jetty.servlet.ServletHolder` (Jetty 12 may use either depending on the EE version).

- [ ] **Step 2: Build the SDK transport provider**

Replace the SSE transport construction and route registration block. Find these lines:

```java
// 6. Create HTTP server with SSE transport
httpServer = new HttpServer(eventDao, repositoryDao);
var sseTransport = new JavalinSseServerTransportProvider(new ObjectMapper());
sseTransport.registerRoutes(httpServer.getApp());
```

Replace with:

```java
// 6. Build Streamable HTTP transport
var streamableTransport = HttpServletStreamableServerTransportProvider.builder()
        .mcpEndpoint("/mcp")
        .build();
```

- [ ] **Step 3: Update HttpServer creation to mount the SDK servlet**

The `HttpServer.createApp()` needs to mount the SDK servlet on Javalin's Jetty. The SDK transport is passed to `HttpServer` so it can be registered during Javalin creation.

Update the `HttpServer` constructor and `createApp()` method. In `HttpServer.java`, add the transport as a constructor parameter:

```java
public class HttpServer {
    private static final Logger log = LoggerFactory.getLogger(HttpServer.class);
    private final EventDao eventDao;
    private final RepositoryDao repositoryDao;
    private final HttpServletStreamableServerTransportProvider mcpTransport;
    private Javalin app;

    public HttpServer(EventDao eventDao, RepositoryDao repositoryDao,
                      HttpServletStreamableServerTransportProvider mcpTransport) {
        this.eventDao = eventDao;
        this.repositoryDao = repositoryDao;
        this.mcpTransport = mcpTransport;
    }
```

Add the required imports to `HttpServer.java`:
```java
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
```

Update `createApp()` to mount the servlet:

```java
public Javalin createApp() {
    app = Javalin.create(config -> {
        config.staticFiles.add(staticFiles -> {
            staticFiles.hostedPath = "/admin/ui";
            staticFiles.directory = "admin-ui/dist";
            staticFiles.location = Location.EXTERNAL;
        });
        if (mcpTransport != null) {
            config.jetty.modifyServletContextHandler(handler -> {
                var mcpHolder = new ServletHolder("mcp-streamable", mcpTransport);
                mcpHolder.setAsyncSupported(true);
                handler.addServlet(mcpHolder, "/mcp/*");
            });
        }
    });
    app.post("/webhook", this::handleWebhook);
    app.get("/admin/ui/*", ctx -> ctx.redirect("/admin/ui/"));
    return app;
}
```

The `if (mcpTransport != null)` guard allows tests that don't need the MCP transport to pass `null`.

- [ ] **Step 4: Update Application.java wiring**

Replace the old wiring:

```java
// 6. Build Streamable HTTP transport
var streamableTransport = HttpServletStreamableServerTransportProvider.builder()
        .mcpEndpoint("/mcp")
        .build();

httpServer = new HttpServer(eventDao, repositoryDao, streamableTransport);
```

And replace the MCP server startup:

```java
// 8. Start MCP servers (both transports)
mcpServer = new McpServerBootstrap(queryExecutor);
mcpServer.startStdio();
mcpServer.startHttp(streamableTransport);
```

Remove the old SSE transport lines:
```java
// DELETE: var sseTransport = new JavalinSseServerTransportProvider(new ObjectMapper());
// DELETE: sseTransport.registerRoutes(httpServer.getApp());
// DELETE: mcpServer.startSse(sseTransport);
```

- [ ] **Step 5: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -10`

Expected: Main sources compile. Test sources may have errors from the `HttpServer` constructor change and the deleted SSE test.

---

### Task 5: Fix Test Compilation

**Files:**
- Modify: `src/test/java/com/indexer/server/HttpServerTest.java`
- Modify: `src/test/java/com/indexer/IntegrationSmokeTest.java`
- Modify: `src/test/java/com/indexer/admin/AdminIntegrationTest.java`
- Delete: `src/test/java/com/indexer/mcp/transport/SseTransportIntegrationTest.java`

- [ ] **Step 1: Delete the old SSE integration test**

```bash
rm src/test/java/com/indexer/mcp/transport/SseTransportIntegrationTest.java
```

This test is entirely SSE-specific. A new Streamable HTTP test will be written in Task 6.

- [ ] **Step 2: Update HttpServerTest constructor calls**

The `HttpServer` constructor now takes three parameters. Tests that don't need the MCP transport pass `null`:

In `HttpServerTest.java`, change:
```java
var httpServer = new HttpServer(eventDao, repositoryDao);
```
to:
```java
var httpServer = new HttpServer(eventDao, repositoryDao, null);
```

- [ ] **Step 3: Update IntegrationSmokeTest constructor call**

In `IntegrationSmokeTest.java`, change:
```java
var httpServer = new HttpServer(eventDao, repositoryDao);
```
to:
```java
var httpServer = new HttpServer(eventDao, repositoryDao, null);
```

- [ ] **Step 4: Update AdminIntegrationTest constructor call**

In `AdminIntegrationTest.java`, change:
```java
httpServer = new HttpServer(eventDao, repositoryDao);
```
to:
```java
httpServer = new HttpServer(eventDao, repositoryDao, null);
```

- [ ] **Step 5: Check for any Javalin 7 API breakages in tests**

Run: `./gradlew compileTestJava 2>&1 | tail -20`

If any tests fail to compile due to Javalin 7 changes (e.g., `JavalinTest` API, validation API), fix them. Common changes:
- `import javax.servlet.*` → `import jakarta.servlet.*`
- `JavalinTest.test()` API — verify it still works the same in 7.x

- [ ] **Step 6: Run all tests**

Run: `./gradlew test 2>&1 | tail -15`

Expected: All tests pass except the deleted SSE test (which no longer exists). Fix any runtime failures.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: migrate from SSE to Streamable HTTP transport

- Upgrade MCP SDK 0.10.0 → 1.1.2 (mcp-core + mcp-json-jackson2)
- Upgrade Javalin 6.4.0 → 7.2.2 (Jetty 12 / Servlet 6.x)
- Replace custom JavalinSseServerTransportProvider with SDK's
  HttpServletStreamableServerTransportProvider
- Mount SDK servlet on Javalin's Jetty via modifyServletContextHandler
- Delete JavalinSseServerTransportProvider.java and
  JavalinSseSessionTransport.java (189 lines removed)
- Delete SseTransportIntegrationTest (replaced in next commit)"
```

---

### Task 6: Write Streamable HTTP Integration Test

**Files:**
- Create: `src/test/java/com/indexer/mcp/transport/StreamableHttpTransportIntegrationTest.java`

- [ ] **Step 1: Write the integration test**

This test starts the full server (Javalin + MCP servlet + PostgreSQL) and exercises the Streamable HTTP protocol.

```java
package com.indexer.mcp.transport;

import com.indexer.db.DatabaseManager;
import com.indexer.db.EventDao;
import com.indexer.db.RepositoryDao;
import com.indexer.mcp.McpServerBootstrap;
import com.indexer.mcp.QueryExecutor;
import com.indexer.server.HttpServer;
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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class StreamableHttpTransportIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private HttpServer httpServer;
    private McpServerBootstrap mcpServer;
    private int port;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        var jdbi = dbManager.getJdbi();
        var eventDao = new EventDao(jdbi);
        var repositoryDao = new RepositoryDao(jdbi);

        try (var ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }

        // Build Streamable HTTP transport
        var streamableTransport = HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp")
                .build();

        // Set up HTTP server with MCP servlet
        httpServer = new HttpServer(eventDao, repositoryDao, streamableTransport);

        // Create QueryExecutor for MCP tools
        var queryExecutor = new QueryExecutor(jdbi);
        mcpServer = new McpServerBootstrap(queryExecutor);
        mcpServer.startHttp(streamableTransport);

        httpServer.start(port);

        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (mcpServer != null) mcpServer.stop();
        if (httpServer != null) httpServer.stop();
    }

    @Test
    void initializeAndListTools() throws Exception {
        // Step 1: Send InitializeRequest — should get a session ID back
        String initRequest = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0"}}}
                """;

        var initResponse = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/mcp"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(initRequest))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // The server may respond with SSE stream or JSON
        assertThat(initResponse.statusCode()).isIn(200, 202);

        // Extract session ID from response header
        String sessionId = initResponse.headers().firstValue("Mcp-Session-Id").orElse(null);
        assertThat(sessionId).isNotNull();

        // Step 2: Send initialized notification
        String initializedNotification = """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """;

        client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Mcp-Session-Id", sessionId)
                        .POST(HttpRequest.BodyPublishers.ofString(initializedNotification))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // Step 3: Send tools/list request
        String toolsListRequest = """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """;

        var toolsResponse = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Mcp-Session-Id", sessionId)
                        .POST(HttpRequest.BodyPublishers.ofString(toolsListRequest))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(toolsResponse.statusCode()).isIn(200, 202);
        String body = toolsResponse.body();
        // Verify all 11 tools are present
        assertThat(body).contains("search_symbols");
        assertThat(body).contains("get_symbol_detail");
        assertThat(body).contains("find_implementations");
        assertThat(body).contains("find_references");
        assertThat(body).contains("search_code");
        assertThat(body).contains("search_files");
        assertThat(body).contains("get_repo_summary");
        assertThat(body).contains("get_file_summary");
        assertThat(body).contains("get_directory_tree");
        assertThat(body).contains("get_index_health");
        assertThat(body).contains("check_sync");
    }

    @Test
    void callToolViaStreamableHttp() throws Exception {
        // Initialize session
        String initRequest = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0"}}}
                """;

        var initResponse = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/mcp"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(initRequest))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        String sessionId = initResponse.headers().firstValue("Mcp-Session-Id").orElse(null);
        assertThat(sessionId).isNotNull();

        // Send initialized notification
        client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Mcp-Session-Id", sessionId)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                """
                                {"jsonrpc":"2.0","method":"notifications/initialized"}
                                """))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // Call get_index_health tool
        String toolCallRequest = """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_index_health","arguments":{}}}
                """;

        var toolResponse = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Mcp-Session-Id", sessionId)
                        .POST(HttpRequest.BodyPublishers.ofString(toolCallRequest))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(toolResponse.statusCode()).isIn(200, 202);
        // get_index_health returns JSON with repository stats
        assertThat(toolResponse.body()).contains("totalPendingEvents");
    }

    @Test
    void rejectsRequestWithoutSession() throws Exception {
        // Send a tools/list request without initializing first (no session ID)
        String toolsListRequest = """
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """;

        var response = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/mcp"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(toolsListRequest))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // Should reject — no session established
        // The SDK may return 400 or handle this as an initialize request
        // Either way, it should NOT return a tools list
        assertThat(response.body()).doesNotContain("search_symbols");
    }
}
```

**Important:** The Streamable HTTP protocol uses SSE within POST responses. The `HttpClient` may receive `Content-Type: text/event-stream` instead of `application/json`. If the test sees SSE-formatted responses, the assertions need to parse SSE events (lines prefixed with `data:`) to extract the JSON payload. Adjust assertions accordingly during implementation.

- [ ] **Step 2: Run the integration test**

Run: `./gradlew test --tests "com.indexer.mcp.transport.StreamableHttpTransportIntegrationTest" --rerun 2>&1 | tail -20`

Expected: All three tests pass. If SSE response format causes issues, adjust the response parsing.

- [ ] **Step 3: Run the full test suite**

Run: `./gradlew test --rerun 2>&1 | tail -10`

Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/indexer/mcp/transport/StreamableHttpTransportIntegrationTest.java
git commit -m "test: add Streamable HTTP transport integration test

Verifies: session initialization via Mcp-Session-Id header, tools/list
returns all 11 tools, tool calls work via Streamable HTTP protocol,
requests without session are rejected."
```

---

### Task 7: Update CLAUDE.md Documentation

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update MCP transport references**

In `CLAUDE.md`, update all references from SSE to Streamable HTTP:

- Change "SSE" transport descriptions to "Streamable HTTP"
- Update the remote connection URL from `http://localhost:8080/mcp` SSE format to Streamable HTTP format
- Update the MCP server config example for remote connections:

```json
{
  "source-code-indexer": {
    "type": "http",
    "url": "http://your-server:8080/mcp"
  }
}
```

- Update the architecture diagram to show "Streamable HTTP" instead of "SSE"
- Remove any references to `GET /mcp` SSE stream or `POST /mcp/message` endpoints

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md for Streamable HTTP transport"
```

---

### Implementation Notes

**SDK API Differences (0.10.0 → 1.1.2):**

The SDK 1.1.2 introduces `McpStreamableServerTransportProvider` as a separate interface from `McpServerTransportProvider`. The `McpServer.sync()` method is overloaded:
- `McpServer.sync(McpServerTransportProvider)` → for stdio and legacy SSE
- `McpServer.sync(McpStreamableServerTransportProvider)` → for Streamable HTTP

Both return builders with `.tool()` and `.build()` methods, but they are different types (`SingleSessionSyncSpecification` vs `StreamableSyncSpecification`). This is why `McpServerBootstrap` has separate `startStdio()` and `startHttp()` methods with duplicated tool registration.

**Jackson 2 vs 3:**

The convenience `io.modelcontextprotocol.sdk:mcp:1.1.2` bundle pulls in `mcp-json-jackson3`, which uses Jackson 3.x. Our project uses Jackson 2.18.2. Use `mcp-core` + `mcp-json-jackson2` explicitly to avoid classpath conflicts. If `mcp-json-jackson2:1.1.2` doesn't exist on Maven Central, omit it and let the SDK's `McpJsonDefaults.getMapper()` auto-discover via ServiceLoader — you may need to provide a `McpJsonMapper` implementation manually.

**Jetty 12 Import Paths:**

Javalin 7 uses Jetty 12, which reorganized servlet packages under `org.eclipse.jetty.ee10.*`. If `org.eclipse.jetty.ee10.servlet.ServletHolder` doesn't compile, try `org.eclipse.jetty.servlet.ServletHolder`. The correct import depends on which Jetty 12 modules Javalin 7.2.2 transitively includes.

**SSE Response Parsing in Tests:**

The Streamable HTTP protocol wraps JSON-RPC responses in SSE format (`Content-Type: text/event-stream`). Each JSON-RPC message is a line prefixed with `data:`. The integration test may need to parse SSE events to extract JSON payloads. If `HttpClient` receives the full SSE stream as a string, split on `\ndata:` and parse each segment as JSON.
