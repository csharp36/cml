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
 * Registers two routes on a shared Javalin app:
 *   GET /mcp — SSE stream. Sends an endpoint event with the POST URL.
 *   POST /mcp/message?sessionId=... — receives JSON-RPC messages from clients.
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
        return Mono.when(
                sessions.values().stream()
                        .map(cs -> cs.session().closeGracefully())
                        .toList()
        ).then(Mono.fromRunnable(sessions::clear));
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
            var message = McpSchema.deserializeJsonRpcMessage(objectMapper, ctx.body());
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
