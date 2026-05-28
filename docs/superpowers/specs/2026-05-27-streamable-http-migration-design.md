# Streamable HTTP Migration Design

## Context

The MCP spec deprecated the HTTP+SSE transport in March 2025, replacing it with Streamable HTTP. The current server uses a custom `JavalinSseServerTransportProvider` implementing the old protocol (two endpoints: `GET /mcp` for SSE stream, `POST /mcp/message` for JSON-RPC). The MCP Java SDK v1.1.2 ships `HttpServletStreamableServerTransportProvider` — a standard Jakarta Servlet implementing the new protocol out of the box.

This migration drops SSE entirely and adopts Streamable HTTP. No backwards compatibility layer — clean cut, no tech debt.

### Why Now

This is the foundation for the enterprise hardening roadmap. Identity propagation (Phase B) needs the SDK's `contextExtractor` hook on the Streamable HTTP transport to extract `CallerIdentity` from HTTP requests. Building enterprise auth on the deprecated SSE transport would be tech debt from day one.

## Dependencies Upgrade

### Servlet API Version Mismatch

Javalin 6.4.0 embeds Jetty 11, which implements Jakarta Servlet 5.x. MCP SDK 1.1.2's `HttpServletStreamableServerTransportProvider` is compiled against Jakarta Servlet 6.1.0 (Jetty 12). These are binary-incompatible — mounting the SDK servlet on Javalin 6's Jetty will produce `NoSuchMethodError` at runtime.

**Resolution:** Upgrade Javalin from 6.4.0 to 7.2.2. Javalin 7 uses Jetty 12 and Jakarta Servlet 6.x.

### Version Matrix

| Dependency | Before | After |
|---|---|---|
| MCP SDK (`io.modelcontextprotocol.sdk:mcp`) | 0.10.0 | 1.1.2 |
| Javalin (`io.javalin:javalin`) | 6.4.0 | 7.2.2 |
| Javalin testtools (`io.javalin:javalin-testtools`) | 6.4.0 | 7.2.2 |
| Java minimum | 21 (unchanged) | 21 (Javalin 7 requires 17+, we exceed) |

### Javalin 6 → 7 Breaking Changes (Relevant to This Project)

1. **Routes must be defined in `Javalin.create()` config block** — can no longer add routes after `.start()`. The current code registers webhook and admin routes via separate methods (`registerRoutes(app)`). These calls must move into the `Javalin.create()` config block or use `config.router.apiBuilder(...)`.
2. **`javax.servlet.*` → `jakarta.servlet.*`** — any raw servlet imports need updating (mainly in tests).
3. **Validation API changes** — `.required()` before `.get()`. Check if any handler code uses Javalin's validation.

## Architecture

### Before

```
Javalin (embedded Jetty 11)
├── GET /mcp           → JavalinSseServerTransportProvider (custom SSE stream)
├── POST /mcp/message  → JavalinSseServerTransportProvider (custom JSON-RPC)
├── POST /webhook      → HttpServer.handleWebhook
├── /admin/*           → AdminApi routes
└── /admin/ui/*        → Static files (admin SPA)
```

### After

```
Javalin (embedded Jetty 12)
├── /mcp/* (servlet)   → HttpServletStreamableServerTransportProvider (SDK)
│   POST /mcp          → Client requests (JSON-RPC over HTTP)
│   GET /mcp           → Server-initiated SSE notifications (optional)
│   DELETE /mcp        → Session termination
├── POST /webhook      → HttpServer.handleWebhook (unchanged)
├── /admin/*           → AdminApi routes (unchanged)
└── /admin/ui/*        → Static files (unchanged)

stdio                  → StdioServerTransportProvider (unchanged)
```

### Servlet Mounting Strategy

Javalin 7 exposes `config.jetty.modifyServletContextHandler()` which provides access to the underlying Jetty `ServletContextHandler`. The SDK servlet is registered directly on this handler:

```java
Javalin app = Javalin.create(config -> {
    config.jetty.modifyServletContextHandler(handler -> {
        ServletHolder mcpHolder = new ServletHolder("mcp", streamableTransport);
        mcpHolder.setAsyncSupported(true);
        handler.addServlet(mcpHolder, "/mcp/*");
    });
    // ... other config
});
```

The `/mcp/*` path is handled entirely by the SDK servlet. All other paths are handled by Javalin's normal routing. Both run on the same port, same Jetty server, same `ServletContextHandler`.

## Components Changed

### Files Deleted

- `src/main/java/com/indexer/mcp/transport/JavalinSseServerTransportProvider.java` — 121 lines, entirely replaced by SDK
- `src/main/java/com/indexer/mcp/transport/JavalinSseSessionTransport.java` — 68 lines, entirely replaced by SDK

### Files Modified

**`build.gradle.kts`**
- Update MCP SDK version: `0.10.0` → `1.1.2`
- Update Javalin version: `6.4.0` → `7.2.2`
- Update javalin-testtools version: `6.4.0` → `7.2.2`

**`McpServerBootstrap.java`**
- Remove import of `JavalinSseServerTransportProvider`
- Rename `startSse(JavalinSseServerTransportProvider)` → `startHttp(McpServerTransportProvider)` accepting the SDK's generic transport provider interface
- Remove `sseServer` field, rename to `httpServer`
- `buildServer()` method unchanged — it already uses `McpServerTransportProvider` interface
- `stop()` method updated for new field name

**`Application.java`**
- Remove import of `JavalinSseServerTransportProvider`
- Remove `new JavalinSseServerTransportProvider(new ObjectMapper())` construction
- Remove `sseTransport.registerRoutes(httpServer.getApp())` call
- Build `HttpServletStreamableServerTransportProvider` via its builder
- Mount SDK servlet on Javalin's Jetty via `config.jetty.modifyServletContextHandler`
- Handle Javalin 7 route registration changes — move `app.post("/webhook", ...)` and admin route registration into the `Javalin.create()` config block
- Call `mcpServer.startHttp(streamableTransport)` instead of `mcpServer.startSse(sseTransport)`
- Update log messages from "SSE" to "Streamable HTTP"

**`HttpServer.java`**
- Adapt to Javalin 7 route registration if needed (routes must be defined in config block)
- Alternatively: `HttpServer.createApp()` can still register routes in its method since it is called before `app.start()`

**`SseTransportIntegrationTest.java`** → rename to `StreamableHttpTransportIntegrationTest.java`
- Replace SSE-specific client code with Streamable HTTP protocol
- Send `POST /mcp` with `InitializeRequest` JSON-RPC
- Verify response contains `Mcp-Session-Id` header
- Send subsequent requests with `Mcp-Session-Id` header

### Files Unchanged

- `QueryExecutor.java` — no transport awareness
- All tool handler methods in `McpServerBootstrap` — protocol-agnostic
- `HttpServerTest.java` — tests webhook, not MCP transport
- `AdminIntegrationTest.java` — tests admin API, not MCP transport
- `IntegrationSmokeTest.java` — webhook portion may need constructor update only

## SDK Transport Builder Configuration

```java
HttpServletStreamableServerTransportProvider streamableTransport =
    HttpServletStreamableServerTransportProvider.builder()
        .jsonMapper(new ObjectMapper())
        .mcpEndpoint("/mcp")
        .build();
```

Builder options available for future use (not needed for this migration):
- `.contextExtractor(req -> ...)` — extract identity from `HttpServletRequest` (Phase B: Identity)
- `.securityValidator(validator)` — Origin/Host header validation (Phase B: Identity)
- `.keepAliveInterval(Duration.ofSeconds(30))` — periodic SSE pings
- `.disallowDelete(false)` — whether to allow client-initiated session termination

## Protocol Differences

| Aspect | Old (HTTP+SSE) | New (Streamable HTTP) |
|---|---|---|
| Endpoints | `GET /mcp` (SSE), `POST /mcp/message` (JSON-RPC) | Single `/mcp` (POST, GET, DELETE) |
| Session ID | Query parameter (`?sessionId=uuid`) | `Mcp-Session-Id` response/request header |
| Client → Server | POST to session-specific URL | POST to `/mcp` with session header |
| Server → Client | Persistent SSE stream | Per-request SSE in POST response, or GET for notifications |
| Session termination | SSE connection drop | `DELETE /mcp` with session header |
| Stream resumability | None | `Last-Event-Id` header for replay |

## Testing Strategy

1. **Unit tests** — existing tool handler tests are transport-agnostic, no changes needed
2. **Integration test** — new `StreamableHttpTransportIntegrationTest`:
   - Start server on random port
   - POST `InitializeRequest` to `/mcp`, verify 200 and `Mcp-Session-Id` header
   - POST `tools/list` with session header, verify all 11 tools returned
   - POST a tool call (`get_index_health`), verify result
   - DELETE `/mcp` with session header, verify session terminated
3. **Manual verification** — configure Claude Code with `--transport http` pointing at the server, verify tool calls work end-to-end

## Rollback Plan

If the Javalin 7 upgrade causes unexpected issues:
- The previous SSE transport code exists in git history
- Revert `build.gradle.kts` versions
- Restore the two deleted transport files
- The transport layer is isolated — no other code depends on the transport implementation

## Success Criteria

- All existing unit tests pass (114 tests)
- New Streamable HTTP integration test passes
- Claude Code connects via `--transport http` and all 11 tools work
- stdio transport continues to work unchanged
- `JavalinSseServerTransportProvider.java` and `JavalinSseSessionTransport.java` deleted
- No SSE-specific code remains in the codebase
