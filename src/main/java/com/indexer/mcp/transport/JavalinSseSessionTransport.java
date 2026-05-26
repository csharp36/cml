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
 *
 * <p>Each SSE client connection gets one instance of this class. It serializes
 * {@link McpSchema.JSONRPCMessage} objects to JSON and sends them as SSE events
 * with the event name {@code "message"}, which is the conventional event name
 * for MCP over SSE.
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
