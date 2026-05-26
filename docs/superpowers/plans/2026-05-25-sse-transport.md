# SSE Transport Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add SSE transport so the MCP server accepts remote client connections over HTTP alongside existing stdio transport.

**Architecture:** Custom `JavalinSseServerTransportProvider` implementing the MCP SDK's `McpServerTransportProvider` interface, backed by Javalin's native SSE support. Both transports run simultaneously. A single Javalin `HttpServer` hosts webhook + SSE routes on one port.

**Tech Stack:** Java 21, MCP SDK 0.10.0 (Reactor Mono for transport interfaces), Javalin 6.4.0 (SSE + HTTP), Jackson

**Spec:** `docs/superpowers/specs/2026-05-25-sse-transport-design.md`

---

## File Structure

| File | Role |
|------|------|
| `src/main/java/com/indexer/server/HttpServer.java` | **New.** General HTTP server (Javalin). Hosts `/webhook`, `/mcp`, `/mcp/message`. Replaces `WebhookServer`. |
| `src/main/java/com/indexer/mcp/transport/JavalinSseSessionTransport.java` | **New.** Per-connection transport implementing `McpServerTransport`. Writes JSON-RPC to one SSE stream. |
| `src/main/java/com/indexer/mcp/transport/JavalinSseServerTransportProvider.java` | **New.** Transport provider implementing `McpServerTransportProvider`. Manages sessions, registers Javalin routes. |
| `src/main/java/com/indexer/config/IndexerConfig.java` | **Modified.** Remove `ssePort`, `transport`. Rename `webhookPort` → `httpPort`. |
| `src/main/java/com/indexer/config/ConfigLoader.java` | **Modified.** Parse `httpPort` instead of `ssePort`/`webhookPort`/`transport`. |
| `src/main/java/com/indexer/mcp/McpServerBootstrap.java` | **Modified.** Split `start()` into `startStdio()` + `startSse()`. Extract shared tool registration. |
| `src/main/java/com/indexer/Application.java` | **Modified.** Updated boot sequence — both transports, one HTTP server. |
| `src/main/java/com/indexer/webhook/WebhookServer.java` | **Deleted.** Replaced by `HttpServer`. |
| `src/test/java/com/indexer/server/HttpServerTest.java` | **New.** Replaces `WebhookServerTest`. |
| `src/test/java/com/indexer/mcp/transport/JavalinSseSessionTransportTest.java` | **New.** Unit tests for session transport. |
| `src/test/java/com/indexer/mcp/transport/JavalinSseServerTransportProviderTest.java` | **New.** Unit tests for transport provider. |
| `src/test/java/com/indexer/mcp/transport/SseTransportIntegrationTest.java` | **New.** End-to-end SSE integration test. |
| `src/test/java/com/indexer/webhook/WebhookServerTest.java` | **Deleted.** Replaced by `HttpServerTest`. |

---

## SDK Interface Reference

These are the exact interfaces from MCP SDK 0.10.0 that our code implements. All return types use Reactor `Mono` (reactor-core is a transitive dependency of the MCP SDK).

```java
// io.modelcontextprotocol.spec.McpServerTransportProvider
public interface McpServerTransportProvider {
    void setSessionFactory(McpServerSession.Factory factory);
    Mono<Void> notifyClients(String method, Object params);
    default void close() {}
    Mono<Void> closeGracefully();
}

// io.modelcontextprotocol.spec.McpServerSession.Factory
public interface Factory {
    McpServerSession create(McpServerTransport transport);
}

// io.modelcontextprotocol.spec.McpServerTransport extends McpTransport
public interface McpTransport {
    Mono<Void> sendMessage(McpSchema.JSONRPCMessage message);
    <T> T unmarshalFrom(Object data, TypeReference<T> typeRef);
    default void close() {}
    Mono<Void> closeGracefully();
}

// io.modelcontextprotocol.spec.McpServerSession (key methods)
public class McpServerSession {
    public Mono<Void> handle(McpSchema.JSONRPCMessage message);
    public Mono<Void> sendNotification(String method, Object params);
    public Mono<Void> closeGracefully();
}
```

---

### Task 1: Config Changes

**Files:**
- Modify: `src/main/java/com/indexer/config/IndexerConfig.java`
- Modify: `src/main/java/com/indexer/config/ConfigLoader.java`
- Modify: `src/test/java/com/indexer/config/ConfigLoaderTest.java`

- [ ] **Step 1: Update ConfigLoaderTest for new config shape**

Read `src/test/java/com/indexer/config/ConfigLoaderTest.java` first. Then update all test YAML fixtures: replace `transport`, `ssePort`, and `webhookPort` fields with `httpPort`. Update all assertions that reference `transport()`, `ssePort()`, or `webhookPort()` to use `httpPort()`.

Example — if a test has:
```java
assertThat(config.server().webhookPort()).isEqualTo(8081);
assertThat(config.server().ssePort()).isEqualTo(8080);
assertThat(config.server().transport()).isEqualTo("stdio");
```
Replace with:
```java
assertThat(config.server().httpPort()).isEqualTo(8080);
```

And in YAML fixtures, replace:
```yaml
transport: stdio
ssePort: 8080
webhookPort: 8081
```
with:
```yaml
httpPort: 8080
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.indexer.config.ConfigLoaderTest" 2>&1 | tail -20`
Expected: Compilation failure — `httpPort()` doesn't exist yet.

- [ ] **Step 3: Update IndexerConfig.ServerConfig**

In `src/main/java/com/indexer/config/IndexerConfig.java`, replace the `ServerConfig` record:

```java
public record ServerConfig(
        String cloneBaseDir,
        @JsonProperty("maxFileSizeBytes") long maxFileSizeBytes,
        int indexWorkers,
        int httpPort
) {
    public ServerConfig {
        if (cloneBaseDir == null) throw new ConfigValidationException("server.cloneBaseDir is required");
        if (maxFileSizeBytes <= 0) maxFileSizeBytes = 1_048_576;
        if (indexWorkers <= 0) indexWorkers = 4;
        if (httpPort <= 0) httpPort = 8080;
    }
}
```

- [ ] **Step 4: Update ConfigLoader.parseServer**

In `src/main/java/com/indexer/config/ConfigLoader.java`, replace the `parseServer` method:

```java
private IndexerConfig.ServerConfig parseServer(JsonNode node) {
    if (node == null) return null;
    String cloneBaseDir = textOrNull(node, "cloneBaseDir");
    long maxFileSizeBytes = node.has("maxFileSizeBytes") ? node.get("maxFileSizeBytes").asLong(0) : 0;
    int indexWorkers = node.has("indexWorkers") ? node.get("indexWorkers").asInt(0) : 0;
    int httpPort = node.has("httpPort") ? node.get("httpPort").asInt(0) : 0;
    return new IndexerConfig.ServerConfig(cloneBaseDir, maxFileSizeBytes, indexWorkers, httpPort);
}
```

- [ ] **Step 5: Fix compilation errors in Application.java**

`Application.java` references `config.server().webhookPort()` and `config.server().transport()` / `config.server().ssePort()`. Update these temporarily so the project compiles. Replace `config.server().webhookPort()` with `config.server().httpPort()`. Remove or comment out the `transport`/`ssePort` references in the SSE stub block (lines 116-121). Replace the entire if/else block with just `mcpServer.start();` for now. This will be fully rewritten in Task 6.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests "com.indexer.config.ConfigLoaderTest" 2>&1 | tail -20`
Expected: All config tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/indexer/config/IndexerConfig.java \
       src/main/java/com/indexer/config/ConfigLoader.java \
       src/test/java/com/indexer/config/ConfigLoaderTest.java \
       src/main/java/com/indexer/Application.java
git commit -m "refactor: simplify ServerConfig — remove ssePort/transport, rename webhookPort to httpPort"
```

---

### Task 2: Replace WebhookServer with HttpServer

**Files:**
- Create: `src/main/java/com/indexer/server/HttpServer.java`
- Delete: `src/main/java/com/indexer/webhook/WebhookServer.java`
- Create: `src/test/java/com/indexer/server/HttpServerTest.java`
- Delete: `src/test/java/com/indexer/webhook/WebhookServerTest.java`
- Modify: `src/main/java/com/indexer/Application.java`
- Modify: `src/test/java/com/indexer/IntegrationSmokeTest.java`

- [ ] **Step 1: Create HttpServerTest**

Read `src/test/java/com/indexer/webhook/WebhookServerTest.java` first. Create the new test file at `src/test/java/com/indexer/server/HttpServerTest.java` with the same test logic but targeting `HttpServer`:

```java
package com.indexer.server;

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
class HttpServerTest {

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
        var httpServer = new HttpServer(eventDao);
        app = httpServer.createApp();
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.indexer.server.HttpServerTest" 2>&1 | tail -20`
Expected: Compilation failure — `HttpServer` class doesn't exist yet.

- [ ] **Step 3: Create HttpServer**

Create `src/main/java/com/indexer/server/HttpServer.java`:

```java
package com.indexer.server;

import com.indexer.db.EventDao;
import com.indexer.webhook.WebhookPayload;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class HttpServer {
    private static final Logger log = LoggerFactory.getLogger(HttpServer.class);
    private final EventDao eventDao;
    private Javalin app;

    public HttpServer(EventDao eventDao) {
        this.eventDao = eventDao;
    }

    public Javalin createApp() {
        app = Javalin.create();
        app.post("/webhook", this::handleWebhook);
        return app;
    }

    public Javalin getApp() {
        if (app == null) createApp();
        return app;
    }

    public void start(int port) {
        if (app == null) createApp();
        app.start(port);
        log.info("HTTP server listening on port {}", port);
    }

    public void stop() {
        if (app != null) app.stop();
    }

    private void handleWebhook(Context ctx) {
        WebhookPayload payload;
        try {
            payload = ctx.bodyAsClass(WebhookPayload.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON payload"));
            return;
        }
        if (payload == null || !payload.isValid()) {
            ctx.status(400).json(Map.of("error", "Missing required fields: repoName, repoPath, eventType, currentSha"));
            return;
        }
        long eventId = eventDao.insert(payload.repoName(), payload.repoPath(), payload.eventType(),
                payload.previousSha(), payload.currentSha());
        eventDao.notifyNewEvent();
        log.info("Received webhook event #{} for repo {} ({})", eventId, payload.repoName(), payload.eventType());
        ctx.status(202).json(Map.of("eventId", eventId));
    }
}
```

Note: The `handleWebhook` method uses `Map.of()` for JSON responses instead of private record types. This is functionally identical and avoids duplicating the inner records from the old `WebhookServer`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.indexer.server.HttpServerTest" 2>&1 | tail -20`
Expected: All 3 tests pass.

- [ ] **Step 5: Update Application.java to use HttpServer**

In `src/main/java/com/indexer/Application.java`:

Replace the import:
```java
import com.indexer.webhook.WebhookServer;
```
with:
```java
import com.indexer.server.HttpServer;
```

Replace the field:
```java
private WebhookServer webhookServer;
```
with:
```java
private HttpServer httpServer;
```

Replace the webhook server creation and start (around lines 94-95):
```java
httpServer = new HttpServer(eventDao);
httpServer.start(config.server().httpPort());
```

Replace all remaining references to `webhookServer` with `httpServer` (in the shutdown method and anywhere else).

- [ ] **Step 6: Update IntegrationSmokeTest**

Read `src/test/java/com/indexer/IntegrationSmokeTest.java`. Replace all references to `WebhookServer` with `HttpServer` and update the import from `com.indexer.webhook.WebhookServer` to `com.indexer.server.HttpServer`.

- [ ] **Step 7: Delete old WebhookServer and its test**

```bash
rm src/main/java/com/indexer/webhook/WebhookServer.java
rm src/test/java/com/indexer/webhook/WebhookServerTest.java
```

- [ ] **Step 8: Verify full build passes**

Run: `./gradlew build 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL. No references to `WebhookServer` remain.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "refactor: replace WebhookServer with HttpServer in com.indexer.server"
```

---

### Task 3: Create JavalinSseSessionTransport

**Files:**
- Create: `src/test/java/com/indexer/mcp/transport/JavalinSseSessionTransportTest.java`
- Create: `src/main/java/com/indexer/mcp/transport/JavalinSseSessionTransport.java`

- [ ] **Step 1: Verify reactor-core is available**

Run: `./gradlew dependencies --configuration runtimeClasspath 2>&1 | grep reactor`
Expected: `reactor-core` appears as a transitive dependency of the MCP SDK. If it does NOT appear, add to `build.gradle.kts`:
```kotlin
implementation("io.projectreactor:reactor-core:3.6.11")
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/indexer/mcp/transport/JavalinSseSessionTransportTest.java`:

```java
package com.indexer.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.sse.SseClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JavalinSseSessionTransportTest {

    private SseClient sseClient;
    private JavalinSseSessionTransport transport;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        sseClient = mock(SseClient.class);
        transport = new JavalinSseSessionTransport(sseClient, objectMapper);
    }

    @Test
    void sendMessageSerializesAndSendsAsEvent() {
        var notification = new McpSchema.JSONRPCNotification(
                McpSchema.JSONRPC_VERSION, "notifications/tools/list_changed", null);

        transport.sendMessage(notification).block();

        ArgumentCaptor<String> dataCaptor = ArgumentCaptor.forClass(String.class);
        verify(sseClient).sendEvent(eq("message"), dataCaptor.capture());
        String json = dataCaptor.getValue();
        assertThat(json).contains("notifications/tools/list_changed");
        assertThat(json).contains("jsonrpc");
    }

    @Test
    void unmarshalFromConvertsMapToType() {
        var data = Map.of("query", "MyClass", "limit", 10);
        var ref = new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {};

        Map<String, Object> result = transport.unmarshalFrom(data, ref);

        assertThat(result).containsEntry("query", "MyClass");
        assertThat(result).containsEntry("limit", 10);
    }

    @Test
    void closeGracefullyCompletes() {
        transport.closeGracefully().block();
        // Should complete without error. SseClient close is best-effort.
    }
}
```

Note: `McpSchema.JSONRPCNotification` is a concrete type in the MCP SDK. If this exact class doesn't exist or has a different constructor, check the SDK's `McpSchema` inner types via `javap` and adjust accordingly. The key point is testing with a real `JSONRPCMessage` subtype.

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "com.indexer.mcp.transport.JavalinSseSessionTransportTest" 2>&1 | tail -20`
Expected: Compilation failure — `JavalinSseSessionTransport` doesn't exist.

- [ ] **Step 4: Implement JavalinSseSessionTransport**

Create `src/main/java/com/indexer/mcp/transport/JavalinSseSessionTransport.java`:

```java
package com.indexer.mcp.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.sse.SseClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Per-connection transport that writes JSON-RPC messages to a single SSE stream.
 */
public class JavalinSseSessionTransport implements McpServerTransport {

    private static final Logger log = LoggerFactory.getLogger(JavalinSseSessionTransport.class);

    private final SseClient sseClient;
    private final ObjectMapper objectMapper;

    public JavalinSseSessionTransport(SseClient sseClient, ObjectMapper objectMapper) {
        this.sseClient = sseClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.fromRunnable(() -> {
            try {
                String json = objectMapper.writeValueAsString(message);
                sseClient.sendEvent("message", json);
            } catch (Exception e) {
                log.warn("Failed to send SSE message: {}", e.getMessage());
            }
        });
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
        return objectMapper.convertValue(data, typeRef);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> {
            try {
                sseClient.close();
            } catch (Exception e) {
                log.debug("Error closing SSE client: {}", e.getMessage());
            }
        });
    }

    @Override
    public void close() {
        try {
            sseClient.close();
        } catch (Exception e) {
            log.debug("Error closing SSE client: {}", e.getMessage());
        }
    }
}
```

If `SseClient.close()` does not exist in Javalin 6.4.0, replace with:
```java
sseClient.ctx().res().getOutputStream().close();
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.indexer.mcp.transport.JavalinSseSessionTransportTest" 2>&1 | tail -20`
Expected: All 3 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/indexer/mcp/transport/JavalinSseSessionTransport.java \
       src/test/java/com/indexer/mcp/transport/JavalinSseSessionTransportTest.java
git commit -m "feat: add JavalinSseSessionTransport — per-connection MCP transport over SSE"
```

---

### Task 4: Create JavalinSseServerTransportProvider

**Files:**
- Create: `src/test/java/com/indexer/mcp/transport/JavalinSseServerTransportProviderTest.java`
- Create: `src/main/java/com/indexer/mcp/transport/JavalinSseServerTransportProvider.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/indexer/mcp/transport/JavalinSseServerTransportProviderTest.java`:

```java
package com.indexer.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JavalinSseServerTransportProviderTest {

    private static final MediaType JSON = MediaType.get("application/json");
    private JavalinSseServerTransportProvider provider;
    private McpServerSession.Factory sessionFactory;
    private McpServerSession mockSession;
    private Javalin app;

    @BeforeEach
    void setUp() {
        provider = new JavalinSseServerTransportProvider(new ObjectMapper());
        mockSession = mock(McpServerSession.class);
        when(mockSession.handle(any())).thenReturn(Mono.empty());
        when(mockSession.closeGracefully()).thenReturn(Mono.empty());

        sessionFactory = mock(McpServerSession.Factory.class);
        when(sessionFactory.create(any(McpServerTransport.class))).thenReturn(mockSession);
        provider.setSessionFactory(sessionFactory);

        app = Javalin.create();
        provider.registerRoutes(app);
    }

    @Test
    void postToUnknownSessionReturns404() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/mcp/message?sessionId=nonexistent", builder ->
                    builder.post(RequestBody.create("{}", JSON)));
            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void postWithoutSessionIdReturns404() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/mcp/message", builder ->
                    builder.post(RequestBody.create("{}", JSON)));
            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void getActiveSessionCountStartsAtZero() {
        assertThat(provider.getActiveSessionCount()).isEqualTo(0);
    }

    @Test
    void closeGracefullyClearsAllSessions() {
        provider.closeGracefully().block();
        assertThat(provider.getActiveSessionCount()).isEqualTo(0);
    }
}
```

Note: Testing the SSE connection flow (`GET /mcp`) with Javalin TestTools is difficult because SSE connections are long-lived. The full SSE flow is covered in the integration test (Task 7). These unit tests verify the POST endpoint routing and session lifecycle.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.indexer.mcp.transport.JavalinSseServerTransportProviderTest" 2>&1 | tail -20`
Expected: Compilation failure — `JavalinSseServerTransportProvider` doesn't exist.

- [ ] **Step 3: Implement JavalinSseServerTransportProvider**

Create `src/main/java/com/indexer/mcp/transport/JavalinSseServerTransportProvider.java`:

```java
package com.indexer.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP transport provider backed by Javalin's native SSE support.
 * <p>
 * Registers two routes on a shared Javalin app:
 * <ul>
 *   <li>{@code GET /mcp} — SSE stream. Sends an {@code endpoint} event with the POST URL.</li>
 *   <li>{@code POST /mcp/message?sessionId=...} — receives JSON-RPC messages from clients.</li>
 * </ul>
 */
public class JavalinSseServerTransportProvider implements McpServerTransportProvider {

    private static final Logger log = LoggerFactory.getLogger(JavalinSseServerTransportProvider.class);

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private McpServerSession.Factory sessionFactory;

    private record ClientSession(McpServerSession session, JavalinSseSessionTransport transport) {}

    public JavalinSseServerTransportProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Register SSE and message routes on the given Javalin app.
     * Call this before starting the Javalin server.
     */
    public void registerRoutes(Javalin app) {
        app.sse("/mcp", this::handleSseConnection);
        app.post("/mcp/message", this::handleMessage);
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory factory) {
        this.sessionFactory = factory;
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        if (sessions.isEmpty()) {
            return Mono.empty();
        }
        return Mono.when(
                sessions.values().stream()
                        .map(cs -> cs.session().sendNotification(method, params))
                        .toList()
        );
    }

    @Override
    public Mono<Void> closeGracefully() {
        if (sessions.isEmpty()) {
            return Mono.empty();
        }
        var closeMono = Mono.when(
                sessions.values().stream()
                        .map(cs -> cs.session().closeGracefully())
                        .toList()
        ).then(Mono.fromRunnable(sessions::clear));
        return closeMono;
    }

    @Override
    public void close() {
        sessions.values().forEach(cs -> cs.session().close());
        sessions.clear();
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }

    private void handleSseConnection(SseClient client) {
        String sessionId = UUID.randomUUID().toString();
        var transport = new JavalinSseSessionTransport(client, objectMapper);
        var session = sessionFactory.create(transport);
        sessions.put(sessionId, new ClientSession(session, transport));

        log.info("SSE client connected: sessionId={}", sessionId);
        client.sendEvent("endpoint", "/mcp/message?sessionId=" + sessionId);
        client.keepAlive();
        client.onClose(() -> {
            log.info("SSE client disconnected: sessionId={}", sessionId);
            sessions.remove(sessionId);
            session.closeGracefully().subscribe();
        });
    }

    private void handleMessage(Context ctx) {
        String sessionId = ctx.queryParam("sessionId");
        if (sessionId == null || !sessions.containsKey(sessionId)) {
            ctx.status(404).json(Map.of("error", "Unknown session"));
            return;
        }

        try {
            var message = objectMapper.readValue(ctx.body(), McpSchema.JSONRPCMessage.class);
            var clientSession = sessions.get(sessionId);
            if (clientSession != null) {
                clientSession.session().handle(message).block();
            }
            ctx.status(202).result("");
        } catch (Exception e) {
            log.warn("Failed to handle message for session {}: {}", sessionId, e.getMessage());
            ctx.status(400).json(Map.of("error", "Invalid message: " + e.getMessage()));
        }
    }
}
```

**Important:** `McpSchema.JSONRPCMessage` deserialization. If `objectMapper.readValue(body, McpSchema.JSONRPCMessage.class)` fails because `JSONRPCMessage` is a sealed interface without Jackson type info, check how `StdioServerTransportProvider` deserializes messages. You may need to use the MCP SDK's internal ObjectMapper configuration. The `StdioServerTransportProvider(ObjectMapper)` constructor accepts a plain `ObjectMapper`, so standard Jackson should work.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.indexer.mcp.transport.JavalinSseServerTransportProviderTest" 2>&1 | tail -20`
Expected: All 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/mcp/transport/JavalinSseServerTransportProvider.java \
       src/test/java/com/indexer/mcp/transport/JavalinSseServerTransportProviderTest.java
git commit -m "feat: add JavalinSseServerTransportProvider — MCP SSE transport for Javalin"
```

---

### Task 5: Refactor McpServerBootstrap for Dual Transport

**Files:**
- Modify: `src/main/java/com/indexer/mcp/McpServerBootstrap.java`

- [ ] **Step 1: Refactor McpServerBootstrap**

Read `src/main/java/com/indexer/mcp/McpServerBootstrap.java`. Refactor to support both transports:

1. Replace the `start()` method with `startStdio()` and `startSse(JavalinSseServerTransportProvider)`.
2. Extract a private `buildServer(McpServerTransportProvider)` method that registers all 10 tools and returns `McpSyncServer`.
3. Store both servers for shutdown.

Replace the class fields and constructor area:

```java
public class McpServerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(McpServerBootstrap.class);

    private final QueryExecutor queryExecutor;
    private McpSyncServer stdioServer;
    private McpSyncServer sseServer;

    public McpServerBootstrap(Jdbi jdbi) {
        this.queryExecutor = new QueryExecutor(jdbi);
    }
```

Replace the `start()` method with:

```java
    public void startStdio() {
        var transport = new StdioServerTransportProvider(new ObjectMapper());
        stdioServer = buildServer(transport);
        log.info("MCP server started over stdio with 10 tools registered");
    }

    public void startSse(JavalinSseServerTransportProvider sseTransport) {
        sseServer = buildServer(sseTransport);
        log.info("MCP server started over SSE with 10 tools registered");
    }

    private McpSyncServer buildServer(McpServerTransportProvider transport) {
        return McpServer.sync(transport)
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
                .build();
    }
```

Replace the `stop()` method:

```java
    public void stop() {
        if (sseServer != null) {
            sseServer.closeGracefully();
        }
        if (stdioServer != null) {
            stdioServer.closeGracefully();
        }
    }
```

Add the new import at the top:

```java
import com.indexer.mcp.transport.JavalinSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
```

Remove the old `private McpSyncServer server;` field and any references to it.

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew compileJava 2>&1 | tail -10`
Expected: Compilation succeeds. `Application.java` may have issues if it still calls `mcpServer.start()` — update it to call `mcpServer.startStdio()` for now.

- [ ] **Step 3: Run full test suite**

Run: `./gradlew test 2>&1 | tail -20`
Expected: All tests pass. The `McpServerBootstrap` is not directly unit tested (tools are tested via `SearchSymbolsToolTest`), but compilation and integration tests verify correctness.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/indexer/mcp/McpServerBootstrap.java \
       src/main/java/com/indexer/Application.java
git commit -m "refactor: split McpServerBootstrap into startStdio/startSse for dual transport"
```

---

### Task 6: Wire Up Application.java

**Files:**
- Modify: `src/main/java/com/indexer/Application.java`

- [ ] **Step 1: Rewrite the boot sequence**

Read `src/main/java/com/indexer/Application.java`. Replace the MCP server and HTTP server sections (approximately lines 94-121) with the new boot sequence.

Add imports:

```java
import com.indexer.mcp.transport.JavalinSseServerTransportProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
```

Replace the field:

```java
private HttpServer httpServer;
```
(Should already be done from Task 2.)

Replace the boot sequence from HTTP server creation through MCP server start:

```java
            // 6. Create HTTP server with SSE transport
            httpServer = new HttpServer(eventDao);
            var sseTransport = new JavalinSseServerTransportProvider(new ObjectMapper());
            sseTransport.registerRoutes(httpServer.getApp());
            httpServer.start(config.server().httpPort());

            // 7. Start event queue poller
            executor = Executors.newFixedThreadPool(config.server().indexWorkers());
            poller = new EventQueuePoller(eventDao, event -> {
                var repo = repositoryDao.findByName(event.repoName()).orElse(null);
                if (repo == null) {
                    throw new RuntimeException("Unknown repo: " + event.repoName());
                }
                try {
                    indexingPipeline.incrementalIndex(repo.id(), Path.of(repo.clonePath()),
                            event.previousSha(), event.currentSha());
                } catch (Exception e) {
                    throw new RuntimeException("Indexing failed: " + e.getMessage(), e);
                }
            }, 1000);
            executor.submit(poller);

            // 8. Start MCP servers (both transports)
            mcpServer = new McpServerBootstrap(jdbi);
            mcpServer.startStdio();
            mcpServer.startSse(sseTransport);
```

Update the shutdown method to ensure correct order:

```java
    public void shutdown() {
        log.info("Shutting down...");
        if (poller != null) poller.stop();
        if (executor != null) executor.shutdownNow();
        if (mcpServer != null) mcpServer.stop();
        if (httpServer != null) httpServer.stop();
        if (dbManager != null) dbManager.close();
        log.info("Shutdown complete");
    }
```

Note: MCP server stops before HTTP server so SSE sessions are closed before the HTTP transport goes away.

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew compileJava 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run full test suite**

Run: `./gradlew test 2>&1 | tail -20`
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/indexer/Application.java
git commit -m "feat: wire SSE transport into Application boot sequence — both transports active"
```

---

### Task 7: SSE Integration Test

**Files:**
- Create: `src/test/java/com/indexer/mcp/transport/SseTransportIntegrationTest.java`

- [ ] **Step 1: Write the integration test**

This test starts a real HTTP server with the SSE transport, connects an SSE client, and verifies the MCP protocol works end-to-end over SSE.

Create `src/test/java/com/indexer/mcp/transport/SseTransportIntegrationTest.java`:

```java
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
        assertThat(firstEvent).contains("event: endpoint");
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
```

Note: SSE integration testing is inherently complex because of the long-lived connection. If the `HttpClient`-based SSE reading proves flaky, consider using OkHttp's `EventSource` (already available via Javalin test tools dependency) or a dedicated SSE client library. The key assertions are:
1. Connecting to `/mcp` returns SSE with an `endpoint` event
2. POSTing to the endpoint URL with a valid JSON-RPC message returns 202

- [ ] **Step 2: Run the integration test**

Run: `./gradlew test --tests "com.indexer.mcp.transport.SseTransportIntegrationTest" 2>&1 | tail -30`
Expected: Both tests pass. If there are issues with `McpSchema.JSONRPCMessage` deserialization or SSE event parsing, debug and fix.

- [ ] **Step 3: Run full test suite**

Run: `./gradlew test 2>&1 | tail -20`
Expected: All tests pass. No regressions.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/indexer/mcp/transport/SseTransportIntegrationTest.java
git commit -m "test: add SSE transport integration test — endpoint event and tools/list"
```

---

### Task 8: Update Documentation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docker-compose.yml` (if port mapping needs updating)

- [ ] **Step 1: Update CLAUDE.md config examples**

In `CLAUDE.md`, update all YAML config examples. Replace:
```yaml
  transport: stdio
  ssePort: 8080
  webhookPort: 8081
```
with:
```yaml
  httpPort: 8080
```

Update the "How to Run Locally" section to reflect that both transports are always active. Remove any references to the `transport` config field.

Update the SSE client config to use the correct port:
```json
{
  "source-code-indexer": {
    "type": "sse",
    "url": "http://your-server:8080/mcp"
  }
}
```

- [ ] **Step 2: Update docker-compose.yml if needed**

Read `docker-compose.yml`. If it exposes `8080` and `8081` as separate ports, consolidate to just `8080`:

```yaml
ports:
  - "8080:8080"
```

- [ ] **Step 3: Run final build**

Run: `./gradlew build 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL with all tests passing.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md docker-compose.yml
git commit -m "docs: update config examples and port mappings for unified HTTP server"
```

---

## Verification Checklist

After all tasks are complete, verify:

1. `./gradlew build` passes (all tests green)
2. `./gradlew test --tests "*SseTransport*"` — SSE-specific tests pass
3. `./gradlew test --tests "*HttpServer*"` — webhook still works on new server
4. Config with `httpPort: 9090` starts the server on port 9090
5. `GET http://localhost:8080/mcp` opens an SSE stream and returns an `endpoint` event
6. `POST http://localhost:8080/webhook` still accepts webhook payloads
7. No references to `WebhookServer`, `ssePort`, or `transport` remain in source code
