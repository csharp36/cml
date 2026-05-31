package com.indexer.admin;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AdminApiTest {

    private static final String TOKEN = "test-secret-token";
    private AdminService adminService;
    private Javalin app;

    @BeforeEach
    void setUp() {
        adminService = mock(AdminService.class);
        var adminApi = new AdminApi(adminService, TOKEN);
        app = Javalin.create(config -> {
            adminApi.registerRoutes(config.routes);
        });
    }

    @Test
    void rejectsRequestWithoutToken() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/admin/health");
            assertThat(response.code()).isEqualTo(401);
        });
    }

    @Test
    void rejectsRequestWithInvalidToken() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/admin/health", builder ->
                    builder.header("Authorization", "Bearer wrong-token").get());
            assertThat(response.code()).isEqualTo(401);
        });
    }

    @Test
    void acceptsRequestWithValidToken() {
        when(adminService.getHealth()).thenReturn(Map.of("totalPendingEvents", 0L));

        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/admin/health", builder ->
                    builder.header("Authorization", "Bearer " + TOKEN).get());
            assertThat(response.code()).isEqualTo(200);
        });
    }

    @Test
    void returns503WhenNoTokenConfigured() {
        var adminApi = new AdminApi(adminService, null);
        var noAuthApp = Javalin.create(config -> {
            adminApi.registerRoutes(config.routes);
        });

        JavalinTest.test(noAuthApp, (server, client) -> {
            var response = client.get("/admin/health");
            assertThat(response.code()).isEqualTo(503);
        });
    }

    @Test
    void getHealthReturnsData() {
        when(adminService.getHealth()).thenReturn(Map.of("totalPendingEvents", 0L));

        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/admin/health", builder ->
                    builder.header("Authorization", "Bearer " + TOKEN).get());
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("totalPendingEvents");
        });
    }

    @Test
    void getReposReturnsRepoList() {
        when(adminService.listRepositories()).thenReturn(List.of());

        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/admin/repos", builder ->
                    builder.header("Authorization", "Bearer " + TOKEN).get());
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).isEqualTo("[]");
        });
    }

    @Test
    void postRepoReturns202() {
        when(adminService.addRepository(any(), any(), any()))
                .thenReturn(Map.of("name", "my-repo", "status", "cloning"));

        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/admin/repos", builder ->
                    builder.header("Authorization", "Bearer " + TOKEN)
                            .header("Content-Type", "application/json")
                            .post(HttpRequest.BodyPublishers.ofString("""
                                    {"url":"git@github.com:org/repo.git","branch":"main","auth":{"type":"ssh-key"}}
                                    """)));
            assertThat(response.code()).isEqualTo(202);
        });
    }

    @Test
    void deleteRepoReturns200() {
        doNothing().when(adminService).deleteRepository("my-repo");

        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/admin/repos/my-repo", builder ->
                    builder.header("Authorization", "Bearer " + TOKEN)
                            .delete(HttpRequest.BodyPublishers.noBody()));
            assertThat(response.code()).isEqualTo(200);
        });
    }

    @Test
    void deleteUnknownRepoReturns404() {
        doThrow(new AdminService.NotFoundException("Not found"))
                .when(adminService).deleteRepository("unknown");

        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/admin/repos/unknown", builder ->
                    builder.header("Authorization", "Bearer " + TOKEN)
                            .delete(HttpRequest.BodyPublishers.noBody()));
            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void retryEventReturns200() {
        when(adminService.retryEvent(42L)).thenReturn(Map.of("id", 42L, "status", "pending"));

        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/admin/events/42/retry", builder ->
                    builder.header("Authorization", "Bearer " + TOKEN)
                            .header("Content-Type", "application/json")
                            .post(HttpRequest.BodyPublishers.ofString("")));
            assertThat(response.code()).isEqualTo(200);
        });
    }

    @Test
    void pinRefReturns200() {
        when(adminService.pinRef("cml", "v1.0")).thenReturn(Map.of("repo", "cml", "ref", "v1.0", "pinned", true));
        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/admin/repos/cml/refs/v1.0/pin", builder ->
                    builder.header("Authorization", "Bearer " + TOKEN)
                            .post(HttpRequest.BodyPublishers.noBody()));
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("\"pinned\":true");
        });
    }

    @Test
    void unpinRefReturns200() {
        when(adminService.unpinRef("cml", "v1.0")).thenReturn(Map.of("repo", "cml", "ref", "v1.0", "pinned", false));
        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/admin/repos/cml/refs/v1.0/pin", builder ->
                    builder.header("Authorization", "Bearer " + TOKEN)
                            .delete(HttpRequest.BodyPublishers.noBody()));
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("\"pinned\":false");
        });
    }

    @Test
    void pinUnknownRefReturns404() {
        when(adminService.pinRef("cml", "ghost")).thenThrow(new AdminService.NotFoundException("Indexed ref not found: ghost"));
        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/admin/repos/cml/refs/ghost/pin", builder ->
                    builder.header("Authorization", "Bearer " + TOKEN)
                            .post(HttpRequest.BodyPublishers.noBody()));
            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void pinWithoutTokenReturns401() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.post("/admin/repos/cml/refs/v1.0/pin", "");
            assertThat(response.code()).isEqualTo(401);
        });
    }
}
