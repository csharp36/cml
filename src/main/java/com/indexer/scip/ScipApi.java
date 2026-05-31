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
    private final ScipSessionService sessionService;
    private final AuditSink auditSink;

    public ScipApi(ApiKeyAuthenticator authenticator, ScipService scipService,
                   ScipSessionService sessionService, AuditSink auditSink) {
        this.authenticator = authenticator;
        this.scipService = scipService;
        this.sessionService = sessionService;
        this.auditSink = auditSink;
    }

    public void registerRoutes(RoutesConfig routes) {
        routes.post("/api/scip/{repoName}", this::handleUpload);
        routes.post("/api/scip/{repoName}/uploads", this::handleInit);
        routes.post("/api/scip/{repoName}/uploads/{uploadId}/parts/{partNumber}", this::handlePart);
        routes.post("/api/scip/{repoName}/uploads/{uploadId}/complete", this::handleComplete);
        routes.delete("/api/scip/{repoName}/uploads/{uploadId}", this::handleAbort);
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

    private void handleInit(Context ctx) {
        String repoName = ctx.pathParam("repoName");
        CallerIdentity caller = authorizeUpload(ctx, repoName);
        if (caller == null) return;
        String gitSha = ctx.header("X-Git-SHA");
        if (gitSha == null || gitSha.isBlank()) {
            ctx.status(400).json(Map.of("error", "X-Git-SHA header is required"));
            return;
        }
        var optRepo = scipService.findRepo(repoName);
        if (optRepo.isEmpty()) {
            ctx.status(404).json(Map.of("error", "Repository '" + repoName + "' not found"));
            return;
        }
        Integer expectedParts = parseIntHeader(ctx.header("X-Scip-Parts"));
        var session = sessionService.init(optRepo.get(), gitSha, expectedParts);
        auditBestEffort(caller, repoName, true, "success", "session init " + session.id());
        ctx.status(201).json(Map.of("uploadId", session.id(), "stagingKey", session.stagingSha()));
    }

    private void handlePart(Context ctx) {
        String repoName = ctx.pathParam("repoName");
        CallerIdentity caller = authorizeUpload(ctx, repoName);
        if (caller == null) return;
        String uploadId = ctx.pathParam("uploadId");
        int partNumber;
        try {
            partNumber = Integer.parseInt(ctx.pathParam("partNumber"));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "Invalid part number"));
            return;
        }
        try {
            var result = sessionService.part(uploadId, partNumber, ctx.bodyAsBytes());
            ctx.json(Map.of("part", partNumber, "symbols", result.symbols(), "relationships", result.relationships()));
        } catch (ScipUploadException e) {
            ctx.status(statusFor(e)).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("SCIP part upload failed for {} session {}: {}", repoName, uploadId, e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error processing SCIP part"));
        }
    }

    private void handleComplete(Context ctx) {
        String repoName = ctx.pathParam("repoName");
        CallerIdentity caller = authorizeUpload(ctx, repoName);
        if (caller == null) return;
        String uploadId = ctx.pathParam("uploadId");
        var optRepo = scipService.findRepo(repoName);
        if (optRepo.isEmpty()) {
            ctx.status(404).json(Map.of("error", "Repository '" + repoName + "' not found"));
            return;
        }
        try {
            var result = sessionService.complete(optRepo.get(), uploadId);
            auditBestEffort(caller, repoName, true, "success", "session complete " + uploadId);
            ctx.json(Map.of("repo", result.repo(), "sha", result.sha(),
                    "symbols", result.symbols(), "relationships", result.relationships()));
        } catch (ScipUploadException e) {
            auditBestEffort(caller, repoName, true, "error", e.getMessage());
            ctx.status(statusFor(e)).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("SCIP complete failed for {} session {}: {}", repoName, uploadId, e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error completing SCIP upload"));
        }
    }

    private void handleAbort(Context ctx) {
        String repoName = ctx.pathParam("repoName");
        CallerIdentity caller = authorizeUpload(ctx, repoName);
        if (caller == null) return;
        sessionService.abort(ctx.pathParam("uploadId"));
        ctx.status(204);
    }

    /** Authenticate + scipUpload permission, shared by all session handlers. Returns null if a response was sent. */
    private CallerIdentity authorizeUpload(Context ctx, String repoName) {
        CallerIdentity caller = authenticate(ctx);
        if (caller == null) return null;
        if (!caller.scipUpload()) {
            auditBestEffort(caller, repoName, false, "denied", "Missing scipUpload permission");
            ctx.status(403).json(Map.of("error", "API key does not have scipUpload permission"));
            return null;
        }
        return caller;
    }

    private static int statusFor(ScipUploadException e) {
        return switch (e.getMessage()) {
            case String msg when msg.startsWith("Upload exceeds") -> 413;
            case String msg when msg.startsWith("Invalid SCIP") -> 400;
            case String msg when msg.startsWith("No SCIP document") -> 422;
            case String msg when msg.startsWith("Invalid SCIP upload session") -> 404;
            case String msg when msg.startsWith("Invalid SCIP session state") -> 409;
            default -> 400;
        };
    }

    private static Integer parseIntHeader(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return null; }
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
