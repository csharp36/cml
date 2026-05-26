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
