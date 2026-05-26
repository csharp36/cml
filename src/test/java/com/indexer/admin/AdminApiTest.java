package com.indexer.admin;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AdminApiTest {

    private static final MediaType JSON = MediaType.get("application/json");
    private static final String TOKEN = "test-secret-token";
    private AdminService adminService;
    private Javalin app;

    @BeforeEach
    void setUp() {
        adminService = mock(AdminService.class);
        var adminApi = new AdminApi(adminService, TOKEN);
        app = Javalin.create();
        adminApi.registerRoutes(app);
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
        var noAuthApp = Javalin.create();
        adminApi.registerRoutes(noAuthApp);

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
                            .post(RequestBody.create("""
                                    {"url":"git@github.com:org/repo.git","branch":"main","auth":{"type":"ssh-key"}}
                                    """, JSON)));
            assertThat(response.code()).isEqualTo(202);
        });
    }

    @Test
    void deleteRepoReturns200() {
        doNothing().when(adminService).deleteRepository("my-repo");

        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/admin/repos/my-repo", builder ->
                    builder.header("Authorization", "Bearer " + TOKEN).delete());
            assertThat(response.code()).isEqualTo(200);
        });
    }

    @Test
    void deleteUnknownRepoReturns404() {
        doThrow(new AdminService.NotFoundException("Not found"))
                .when(adminService).deleteRepository("unknown");

        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/admin/repos/unknown", builder ->
                    builder.header("Authorization", "Bearer " + TOKEN).delete());
            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void retryEventReturns200() {
        when(adminService.retryEvent(42L)).thenReturn(Map.of("id", 42L, "status", "pending"));

        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/admin/events/42/retry", builder ->
                    builder.header("Authorization", "Bearer " + TOKEN)
                            .post(RequestBody.create("", JSON)));
            assertThat(response.code()).isEqualTo(200);
        });
    }
}
