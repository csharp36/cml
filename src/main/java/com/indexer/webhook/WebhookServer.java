package com.indexer.webhook;

import com.indexer.db.EventDao;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebhookServer {
    private static final Logger log = LoggerFactory.getLogger(WebhookServer.class);
    private final EventDao eventDao;
    private Javalin app;

    public WebhookServer(EventDao eventDao) {
        this.eventDao = eventDao;
    }

    public Javalin createApp() {
        app = Javalin.create();
        app.post("/webhook", this::handleWebhook);
        return app;
    }

    public void start(int port) {
        if (app == null) createApp();
        app.start(port);
        log.info("Webhook server listening on port {}", port);
    }

    public void stop() {
        if (app != null) app.stop();
    }

    private void handleWebhook(Context ctx) {
        WebhookPayload payload;
        try {
            payload = ctx.bodyAsClass(WebhookPayload.class);
        } catch (Exception e) {
            ctx.status(400).json(new ErrorResponse("Invalid JSON payload"));
            return;
        }
        if (payload == null || !payload.isValid()) {
            ctx.status(400).json(new ErrorResponse("Missing required fields: repoName, repoPath, eventType, currentSha"));
            return;
        }
        long eventId = eventDao.insert(payload.repoName(), payload.repoPath(), payload.eventType(), payload.previousSha(), payload.currentSha());
        eventDao.notifyNewEvent();
        log.info("Received webhook event #{} for repo {} ({})", eventId, payload.repoName(), payload.eventType());
        ctx.status(202).json(new AcceptedResponse(eventId));
    }

    private record ErrorResponse(String error) {}
    private record AcceptedResponse(long eventId) {}
}
