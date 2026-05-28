package com.indexer.server;

import com.indexer.db.EventDao;
import com.indexer.db.RepositoryDao;
import com.indexer.webhook.WebhookPayload;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class HttpServer {
    private static final Logger log = LoggerFactory.getLogger(HttpServer.class);
    private final EventDao eventDao;
    private final RepositoryDao repositoryDao;
    private Javalin app;

    public HttpServer(EventDao eventDao, RepositoryDao repositoryDao) {
        this.eventDao = eventDao;
        this.repositoryDao = repositoryDao;
    }

    public Javalin createApp() {
        app = Javalin.create(config -> {
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/admin/ui";
                staticFiles.directory = "admin-ui/dist";
                staticFiles.location = Location.EXTERNAL;
            });
        });
        app.post("/webhook", this::handleWebhook);
        // SPA fallback — serve index.html for all unmatched /admin/ui/* routes
        app.get("/admin/ui/*", ctx -> ctx.redirect("/admin/ui/"));
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
        if (repositoryDao.findByName(payload.repoName()).isEmpty()) {
            log.warn("Webhook received for unknown repo: {}", payload.repoName());
            ctx.status(404).json(Map.of("error", "Unknown repository: " + payload.repoName()));
            return;
        }
        long eventId = eventDao.insert(payload.repoName(), payload.repoPath(), payload.eventType(),
                payload.previousSha(), payload.currentSha(), payload.effectiveBranch());
        eventDao.notifyNewEvent();
        log.info("Received webhook event #{} for repo {} ({})", eventId, payload.repoName(), payload.eventType());
        ctx.status(202).json(Map.of("eventId", eventId));
    }
}
