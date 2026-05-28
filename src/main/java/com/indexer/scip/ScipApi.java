package com.indexer.scip;

import com.indexer.audit.AuditEvent;
import com.indexer.audit.AuditSink;
import com.indexer.auth.ApiKeyAuthenticator;
import com.indexer.auth.CallerIdentity;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class ScipApi {

    private static final Logger log = LoggerFactory.getLogger(ScipApi.class);

    private final ApiKeyAuthenticator authenticator;
    private final ScipService scipService;
    private final AuditSink auditSink;

    public ScipApi(ApiKeyAuthenticator authenticator, ScipService scipService, AuditSink auditSink) {
        this.authenticator = authenticator;
        this.scipService = scipService;
        this.auditSink = auditSink;
    }

    public void registerRoutes(RoutesConfig routes) {
        routes.post("/api/scip/{repoName}", this::handleUpload);
    }

    private void handleUpload(Context ctx) {
        String repoName = ctx.pathParam("repoName");

        // Authenticate
        CallerIdentity caller = authenticate(ctx);
        if (caller == null) return; // response already sent

        // Check scipUpload permission
        if (!caller.scipUpload()) {
            auditBestEffort(caller, repoName, false, "denied", "Missing scipUpload permission");
            ctx.status(403).json(Map.of("error", "API key does not have scipUpload permission"));
            return;
        }

        // Validate X-Git-SHA header
        String gitSha = ctx.header("X-Git-SHA");
        if (gitSha == null || gitSha.isBlank()) {
            auditBestEffort(caller, repoName, true, "error", "Missing X-Git-SHA header");
            ctx.status(400).json(Map.of("error", "X-Git-SHA header is required"));
            return;
        }

        // Validate repo exists
        var optRepo = scipService.findRepo(repoName);
        if (optRepo.isEmpty()) {
            auditBestEffort(caller, repoName, true, "error", "Repository not found");
            ctx.status(404).json(Map.of("error", "Repository '" + repoName + "' not found"));
            return;
        }

        // Process upload
        try {
            byte[] body = ctx.bodyAsBytes();
            var result = scipService.processUpload(optRepo.get(), gitSha, body);
            auditBestEffort(caller, repoName, true, "success", null);
            ctx.json(Map.of(
                    "repo", result.repo(),
                    "sha", result.sha(),
                    "symbols", result.symbols(),
                    "relationships", result.relationships(),
                    "documents_processed", result.documentsProcessed()
            ));
        } catch (ScipUploadException e) {
            int status = switch (e.getMessage()) {
                case String msg when msg.startsWith("Upload exceeds") -> 413;
                case String msg when msg.startsWith("Invalid SCIP") -> 400;
                case String msg when msg.startsWith("No SCIP document") -> 422;
                default -> 400;
            };
            auditBestEffort(caller, repoName, true, "error", e.getMessage());
            ctx.status(status).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("SCIP upload failed for {}: {}", repoName, e.getMessage(), e);
            auditBestEffort(caller, repoName, true, "error", e.getMessage());
            ctx.status(500).json(Map.of("error", "Internal error processing SCIP upload"));
        }
    }

    private CallerIdentity authenticate(Context ctx) {
        String authHeader = ctx.header("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            auditAuthFailure(ctx, "Missing Authorization header");
            ctx.status(401).json(Map.of("error", "Missing or invalid Authorization header"));
            return null;
        }
        String token = authHeader.substring("Bearer ".length());
        var optIdentity = authenticator.authenticate(token, ctx.ip());
        if (optIdentity.isEmpty()) {
            auditAuthFailure(ctx, "Invalid API key");
            ctx.status(401).json(Map.of("error", "Invalid API key"));
            return null;
        }
        return optIdentity.get();
    }

    private void auditAuthFailure(Context ctx, String message) {
        if (auditSink == null) return;
        try {
            var caller = CallerIdentity.anonymous("streamable-http");
            auditSink.record(AuditEvent.from(caller, "scip:authFailure", null, false, "denied", message));
        } catch (Exception e) {
            log.warn("Audit write failed for SCIP auth failure: {}", e.getMessage());
        }
    }

    private void auditBestEffort(CallerIdentity caller, String repo,
                                  boolean authorized, String resultStatus, String errorMessage) {
        if (auditSink == null) return;
        try {
            auditSink.record(AuditEvent.from(caller, "scip:upload", repo, authorized, resultStatus, errorMessage));
        } catch (Exception e) {
            log.warn("Audit write failed for SCIP upload: {}", e.getMessage());
        }
    }
}
