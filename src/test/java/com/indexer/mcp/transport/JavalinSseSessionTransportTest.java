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
