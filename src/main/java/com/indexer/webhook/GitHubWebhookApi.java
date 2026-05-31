package com.indexer.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indexer.audit.AuditEvent;
import com.indexer.audit.AuditSink;
import com.indexer.auth.CallerIdentity;
import com.indexer.config.IndexerConfig.TagConfig;
import com.indexer.db.EventDao;
import com.indexer.db.RepositoryDao;
import com.indexer.repository.RefKind;
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
    private final TagConfig tags;

    public GitHubWebhookApi(Map<String, String> webhookSecrets, RepositoryDao repositoryDao,
                            EventDao eventDao, AuditSink auditSink, TagConfig tags) {
        this.webhookSecrets = webhookSecrets;
        this.repositoryDao = repositoryDao;
        this.eventDao = eventDao;
        this.auditSink = auditSink;
        this.tags = tags;
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

        String branchName = payload.branch();
        String tagName = payload.tag();

        if (branchName != null) {
            if (!branchName.equals(repo.branch())) {
                ctx.status(200).json(Map.of("status", "ignored", "reason", "branch not indexed: " + branchName));
                return;
            }
            if (payload.isBranchDeletion()) {
                ctx.status(200).json(Map.of("status", "ignored", "reason", "branch deletion"));
                return;
            }
            enqueue(ctx, repoName, repo.clonePath(), payload, branchName, RefKind.BRANCH);
            return;
        }

        if (tagName != null) {
            if (payload.isBranchDeletion()) { // all-zero `after` == tag deletion
                ctx.status(200).json(Map.of("status", "ignored", "reason", "tag deletion"));
                return;
            }
            if (!tags.autoIndex() || !tags.matches(tagName)) {
                ctx.status(200).json(Map.of("status", "ignored", "reason", "tag not auto-indexed: " + tagName));
                return;
            }
            enqueue(ctx, repoName, repo.clonePath(), payload, tagName, RefKind.TAG);
            return;
        }

        ctx.status(200).json(Map.of("status", "ignored", "reason", "unsupported ref: " + payload.ref()));
    }

    private void enqueue(Context ctx, String repoName, String clonePath,
                         GitHubPushPayload payload, String ref, RefKind kind) {
        long eventId = eventDao.insert(repoName, clonePath, "github_push",
                payload.before(), payload.after(), ref, kind.dbValue());
        eventDao.notifyNewEvent();
        auditEnqueue(ctx, repoName, eventId);
        log.info("GitHub webhook event #{} for repo {} ref {} ({}) ({} -> {})",
                eventId, repoName, ref, kind, payload.before(), payload.after());
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
