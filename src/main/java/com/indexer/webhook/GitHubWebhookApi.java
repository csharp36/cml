package com.indexer.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indexer.audit.AuditEvent;
import com.indexer.audit.AuditSink;
import com.indexer.auth.CallerIdentity;
import com.indexer.db.EventDao;
import com.indexer.db.RepositoryDao;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Receives GitHub push webhooks at {@code POST /webhook/github/{repoName}}.
 * Verifies a per-repo HMAC-SHA256 signature and, on a valid push to the repo's
 * configured branch, enqueues an indexing event. The existing event poller then
 * fetches and incrementally indexes. Fail-closed: no secret or bad signature -> 401.
 */
public class GitHubWebhookApi {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookApi.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, String> webhookSecrets; // repoName -> secret
    private final RepositoryDao repositoryDao;
    private final EventDao eventDao;
    private final AuditSink auditSink;

    public GitHubWebhookApi(Map<String, String> webhookSecrets, RepositoryDao repositoryDao,
                            EventDao eventDao, AuditSink auditSink) {
        this.webhookSecrets = webhookSecrets;
        this.repositoryDao = repositoryDao;
        this.eventDao = eventDao;
        this.auditSink = auditSink;
    }

    public void registerRoutes(RoutesConfig routes) {
        routes.post("/webhook/github/{repoName}", this::handle);
    }

    private void handle(Context ctx) {
        String repoName = ctx.pathParam("repoName");
        log.info("GitHub webhook delivery received: repo={}, event={}", repoName, ctx.header("X-GitHub-Event"));

        var optRepo = repositoryDao.findByName(repoName);
        if (optRepo.isEmpty()) {
            log.warn("GitHub webhook for unknown repo: {}", repoName);
            ctx.status(404).json(Map.of("error", "Unknown repository: " + repoName));
            return;
        }
        var repo = optRepo.get();

        String secret = webhookSecrets.get(repoName);
        if (secret == null) {
            auditAuthFailure(ctx, repoName, "No webhook secret configured");
            ctx.status(401).json(Map.of("error", "Webhook not configured for repository"));
            return;
        }

        byte[] body = ctx.bodyAsBytes();
        if (!GitHubWebhookVerifier.isValid(body, secret, ctx.header("X-Hub-Signature-256"))) {
            auditAuthFailure(ctx, repoName, "Invalid or missing signature");
            ctx.status(401).json(Map.of("error", "Invalid signature"));
            return;
        }

        String event = ctx.header("X-GitHub-Event");
        if (!"push".equals(event)) {
            ctx.status(200).json(Map.of("status", "ignored", "event", event == null ? "none" : event));
            return;
        }

        GitHubPushPayload payload;
        try {
            payload = MAPPER.readValue(body, GitHubPushPayload.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON payload"));
            return;
        }

        String branch = payload.branch();
        if (branch == null || !branch.equals(repo.branch())) {
            ctx.status(200).json(Map.of("status", "ignored", "reason", "branch not indexed: " + branch));
            return;
        }
        if (payload.isBranchDeletion()) {
            ctx.status(200).json(Map.of("status", "ignored", "reason", "branch deletion"));
            return;
        }

        long eventId = eventDao.insert(repoName, repo.clonePath(), "github_push",
                payload.before(), payload.after(), branch);
        eventDao.notifyNewEvent();
        auditEnqueue(ctx, repoName, eventId);
        log.info("GitHub webhook event #{} for repo {} ({} -> {})",
                eventId, repoName, payload.before(), payload.after());
        ctx.status(202).json(Map.of("eventId", eventId));
    }

    private void auditAuthFailure(Context ctx, String repo, String message) {
        audit(ctx, "githubWebhook:authFailure", repo, false, "denied", message);
    }

    /** Records the verified, state-changing enqueue — the audit event that matters most. */
    private void auditEnqueue(Context ctx, String repo, long eventId) {
        audit(ctx, "githubWebhook:enqueue", repo, true, "success", "event #" + eventId);
    }

    private void audit(Context ctx, String action, String repo,
                       boolean authorized, String resultStatus, String message) {
        if (auditSink == null) return;
        try {
            var caller = CallerIdentity.anonymous(ctx.ip());
            auditSink.record(AuditEvent.from(caller, action, repo, authorized, resultStatus, message));
        } catch (Exception e) {
            log.warn("Audit write failed for {} ({}): {}", action, repo, e.getMessage());
        }
    }
}
