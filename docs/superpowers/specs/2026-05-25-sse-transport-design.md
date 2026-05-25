# SSE Transport Design

**Date:** 2026-05-25
**Status:** Approved
**Scope:** Add Server-Sent Events transport to the MCP server alongside existing stdio transport

## 1. Goal

Enable the MCP server to accept remote client connections over HTTP SSE, so Claude Code instances on other machines can query the index without running the server locally. Both stdio and SSE transports run simultaneously — stdio for local subprocess usage, SSE for cloud/remote deployment.

## 2. Architecture

```
Claude Code (local)  ──stdio──▶  McpSyncServer (stdio)
                                      │
Claude Code (remote) ──SSE───▶  McpSyncServer (SSE)
                                      │
                                 QueryExecutor ──▶ PostgreSQL

HttpServer (single Javalin instance, one port)
  ├── POST /webhook         (existing git hook receiver)
  ├── GET  /mcp             (SSE event stream)
  └── POST /mcp/message     (client→server JSON-RPC)
```

- Two `McpSyncServer` instances share one `QueryExecutor` (stateless, thread-safe).
- One `HttpServer` (Javalin) hosts webhook, SSE stream, and SSE message receiver on a single configurable port.
- Stdio transport starts unconditionally. SSE transport starts unconditionally alongside it.

## 3. SSE Protocol Flow

The MCP SSE transport follows the standard MCP protocol:

1. **Connect:** Client sends `GET /mcp`. Server opens an SSE stream and immediately sends an `endpoint` event with payload `/mcp/message?sessionId=<uuid>`.
2. **Request:** Client POSTs JSON-RPC messages to `/mcp/message?sessionId=<uuid>`. Server routes the message to the correct `McpServerSession` for processing.
3. **Response:** Server sends JSON-RPC responses and notifications back through the SSE stream as `message` events with JSON payloads.
4. **Disconnect:** Client closes the SSE connection. Server cleans up the session from its map.

## 4. New Component: `JavalinSseServerTransportProvider`

Implements the MCP SDK's `McpServerTransportProvider` interface using Javalin's native SSE support.

**Responsibilities:**
- Receives the `McpServerSession.Factory` from the SDK via `setSessionFactory()`
- Registers `GET /mcp` and `POST /mcp/message` routes on the shared Javalin app
- Manages a `ConcurrentHashMap<String, ClientSession>` mapping session IDs to their SSE connections and `McpServerSession` instances
- On new SSE connection: generates UUID, stores the `SseClient`, creates an `McpServerSession` via the factory, sends the `endpoint` event
- On POST: validates `sessionId` query param, deserializes JSON-RPC, dispatches to the session
- On SSE disconnect (`SseClient.onClose()`): removes session from map, closes `McpServerSession`

**Per-connection transport:** Each connected client gets a `JavalinSseSessionTransport` instance (implements `McpSessionTransport`) that serializes JSON-RPC messages and writes them to that client's SSE stream via `sseClient.sendEvent("message", json)`.

**SDK interface note:** The exact method signatures of `McpServerTransportProvider` and `McpSessionTransport` must be verified against MCP SDK 0.10.0 source during implementation. The descriptions above reflect the expected contract; adjust to the actual interface.

**Package:** `com.indexer.mcp.transport`

## 5. Refactored Component: `WebhookServer` → `HttpServer`

Renamed to reflect its broader role. Changes:

- Class renamed from `WebhookServer` to `HttpServer`
- Constructor takes existing webhook dependencies (EventDao, RepositoryDao)
- New method: `registerSseTransport(JavalinSseServerTransportProvider)` — the transport adds its GET and POST routes to the Javalin app before `start()` is called
- `start(port)` and `stop()` lifecycle unchanged
- Existing webhook route (`POST /webhook`) unchanged

**Package:** `com.indexer.server` (moved from `com.indexer.webhook`)

## 6. Refactored Component: `McpServerBootstrap`

Split transport creation out of the bootstrap so it can support multiple transports.

**Changes:**
- `start()` → replaced by `startStdio()` and `startSse(JavalinSseServerTransportProvider)`
- Both methods call a shared private `buildServer(McpServerTransportProvider)` that registers all 10 tools and returns an `McpSyncServer`
- `stop()` shuts down both servers if active
- `QueryExecutor` remains shared (stateless, thread-safe)

## 7. Application Startup Sequence

Updated boot sequence in `Application.java`:

1. Load config
2. Initialize database (Flyway + connection pool)
3. Create DAOs and components
4. Clone/fetch repos, run initial index
5. Report failed events
6. **Create `HttpServer`** (was `WebhookServer`)
7. **Create `JavalinSseServerTransportProvider`, register on `HttpServer`**
8. **Start `HttpServer`** on configured port
9. Start `EventQueuePoller`
10. **Call `mcpBootstrap.startStdio()`**
11. **Call `mcpBootstrap.startSse(sseTransport)`**
12. Register shutdown hook

**Shutdown order:**
1. Stop event queue poller
2. Close all SSE sessions (notify connected clients)
3. Stop both MCP servers (stdio + SSE)
4. Stop HTTP server
5. Close database pool

## 8. Config Changes

**`ServerConfig` record:**
- Remove `ssePort` field (SSE now shares the HTTP server port)
- Rename `webhookPort` → `httpPort`

**Updated YAML:**
```yaml
server:
  cloneBaseDir: ~/.source-code-indexer/repos
  maxFileSizeBytes: 1048576
  indexWorkers: 4
  httpPort: 8080
```

`transport` field is no longer needed — both transports always run.

**Claude Code SSE client config:**
```json
{
  "source-code-indexer": {
    "type": "sse",
    "url": "http://your-server:8080/mcp"
  }
}
```

## 9. Error Handling

- **Client disconnects mid-request:** `SseClient.onClose()` fires, session removed from map. In-flight writes to a closed stream are caught and logged, not propagated.
- **Invalid `sessionId` on POST:** Return 404 with JSON error body. Don't leak session IDs.
- **Malformed JSON-RPC on POST:** Return 400 with JSON error body. The session's SSE stream is unaffected.
- **HTTP server fails to bind port:** Application exits with a clear error. No partial startup.
- **Multiple simultaneous SSE clients:** Each gets an independent session. No shared mutable state beyond the `ConcurrentHashMap`.
- **Tool execution blocking:** `McpSyncServer` handles tools synchronously, but each POST runs on its own Javalin thread. One slow query doesn't block other sessions.

## 10. Security

- **SSE endpoint is unauthenticated in this phase.** The pluggable `AuthProvider` system handles repository access credentials, not MCP client connections.
- **MCP client authentication** (API keys, mTLS, OAuth2 bearer tokens) is tracked as future work item #8 in project status. Natural addition when the admin API lands.
- **No credentials exposed over SSE.** Tool responses contain index data (symbols, file paths, code snippets from indexed repos), never auth credentials.

## 11. Testing

### Unit Tests

- **`JavalinSseServerTransportProvider`:** Session lifecycle — connect creates session, POST routes to correct session, disconnect cleans up. Mock `McpServerSession.Factory` and verify callbacks.
- **`HttpServer`:** Existing webhook tests updated for renamed class. New test verifying both `/webhook` and `/mcp` routes are registered.
- **`McpServerBootstrap`:** Test that `startStdio()` and `startSse()` both register the same 10 tools. Mock transport providers.

### Integration Test

- Start `HttpServer` on a random port with SSE transport wired up
- Connect an SSE client to `GET /mcp`
- Assert the `endpoint` event arrives with a valid session URL
- POST a `tools/list` JSON-RPC request to the session URL
- Assert the SSE stream receives a response listing all 10 tools
- Disconnect and verify session cleanup

### Existing Tests

- E2E test updated to use `HttpServer` instead of `WebhookServer`
- Webhook integration tests updated for class rename
- All existing tests continue to pass

## 12. Files Changed

| File | Change |
|------|--------|
| `src/main/java/com/indexer/mcp/transport/JavalinSseServerTransportProvider.java` | **New** — SSE transport provider |
| `src/main/java/com/indexer/mcp/transport/JavalinSseSessionTransport.java` | **New** — per-connection session transport |
| `src/main/java/com/indexer/server/HttpServer.java` | **New** (replaces `webhook/WebhookServer.java`) |
| `src/main/java/com/indexer/webhook/WebhookServer.java` | **Deleted** |
| `src/main/java/com/indexer/mcp/McpServerBootstrap.java` | **Modified** — split into `startStdio()`/`startSse()` |
| `src/main/java/com/indexer/Application.java` | **Modified** — updated boot sequence |
| `src/main/java/com/indexer/config/IndexerConfig.java` | **Modified** — remove `ssePort`, rename `webhookPort` → `httpPort` |
| `src/main/java/com/indexer/config/ConfigLoader.java` | **Modified** — parse `httpPort` |
| `src/test/java/com/indexer/mcp/transport/JavalinSseServerTransportProviderTest.java` | **New** |
| `src/test/java/com/indexer/server/HttpServerTest.java` | **New** (replaces webhook test) |
| `src/test/java/com/indexer/mcp/McpServerBootstrapTest.java` | **Modified** |
| Existing webhook/E2E tests | **Modified** — class renames |
| `build.gradle.kts` | No changes needed (Javalin already included) |
| `docker-compose.yml` | Port mapping update if needed |
| `CLAUDE.md` | Update config examples |
