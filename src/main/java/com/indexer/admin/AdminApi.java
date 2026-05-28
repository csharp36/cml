package com.indexer.admin;

import com.indexer.config.IndexerConfig;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;

public class AdminApi {

    private static final Logger log = LoggerFactory.getLogger(AdminApi.class);

    private final AdminService adminService;
    private final String adminToken;

    public AdminApi(AdminService adminService, String adminToken) {
        this.adminService = adminService;
        this.adminToken = adminToken;
    }

    public void registerRoutes(RoutesConfig routes) {
        routes.before("/admin/*", this::authenticate);

        routes.get("/admin/health", this::getHealth);
        routes.get("/admin/repos", this::listRepos);
        routes.post("/admin/repos", this::addRepo);
        routes.delete("/admin/repos/{name}", this::deleteRepo);
        routes.post("/admin/repos/{name}/reindex", this::reindexRepo);
        routes.get("/admin/events", this::listEvents);
        routes.post("/admin/events/{id}/retry", this::retryEvent);
    }

    private void authenticate(Context ctx) {
        if (adminToken == null || adminToken.isBlank()) {
            ctx.status(503).json(Map.of("error", "Admin API disabled — no admin token configured"));
            ctx.skipRemainingHandlers();
            return;
        }

        String authHeader = ctx.header("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            ctx.status(401).json(Map.of("error", "Missing or invalid Authorization header"));
            ctx.skipRemainingHandlers();
            return;
        }

        String providedToken = authHeader.substring("Bearer ".length());
        if (!constantTimeEquals(adminToken, providedToken)) {
            ctx.status(401).json(Map.of("error", "Invalid admin token"));
            ctx.skipRemainingHandlers();
            return;
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }

    private void getHealth(Context ctx) {
        ctx.json(adminService.getHealth());
    }

    private void listRepos(Context ctx) {
        ctx.json(adminService.listRepositories());
    }

    private void addRepo(Context ctx) {
        var body = ctx.bodyAsClass(AddRepoRequest.class);
        if (body.url() == null || body.url().isBlank()) {
            ctx.status(400).json(Map.of("error", "url is required"));
            return;
        }

        String branch = body.branch() != null ? body.branch() : "main";
        IndexerConfig.AuthConfig authConfig = null;
        if (body.auth() != null) {
            authConfig = new IndexerConfig.AuthConfig(body.auth().type(), body.auth().properties());
        }

        try {
            var result = adminService.addRepository(body.url(), branch, authConfig);
            ctx.status(202).json(result);
        } catch (AdminService.ConflictException e) {
            ctx.status(409).json(Map.of("error", e.getMessage()));
        }
    }

    private void deleteRepo(Context ctx) {
        String name = ctx.pathParam("name");
        try {
            adminService.deleteRepository(name);
            ctx.json(Map.of("deleted", name));
        } catch (AdminService.NotFoundException e) {
            ctx.status(404).json(Map.of("error", e.getMessage()));
        }
    }

    private void reindexRepo(Context ctx) {
        String name = ctx.pathParam("name");
        try {
            var result = adminService.triggerReindex(name);
            ctx.status(202).json(result);
        } catch (AdminService.NotFoundException e) {
            ctx.status(404).json(Map.of("error", e.getMessage()));
        }
    }

    private void listEvents(Context ctx) {
        String repo = ctx.queryParam("repo");
        String status = ctx.queryParam("status");
        String sinceStr = ctx.queryParam("since");
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(50);

        Instant since = null;
        if (sinceStr != null) {
            try {
                since = Instant.parse(sinceStr);
            } catch (Exception e) {
                ctx.status(400).json(Map.of("error", "Invalid 'since' format. Use ISO-8601."));
                return;
            }
        }

        ctx.json(adminService.listEvents(repo, status, since, limit));
    }

    private void retryEvent(Context ctx) {
        long id;
        try {
            id = Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "Invalid event ID"));
            return;
        }

        try {
            ctx.json(adminService.retryEvent(id));
        } catch (AdminService.NotFoundException e) {
            ctx.status(404).json(Map.of("error", e.getMessage()));
        } catch (AdminService.BadRequestException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        }
    }

    private record AddRepoRequest(String url, String branch, AuthDto auth) {}
    private record AuthDto(String type, java.util.Map<String, String> properties) {}
}
