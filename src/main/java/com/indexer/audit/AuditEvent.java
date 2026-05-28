package com.indexer.audit;

import com.indexer.auth.CallerIdentity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record AuditEvent(
        String callerHash,
        String userId,
        String displayName,
        String authMethod,
        String transport,
        String sourceIp,
        String action,
        String repo,
        boolean authorized,
        String resultStatus,
        String errorMessage
) {
    public static AuditEvent from(CallerIdentity caller, String action, String repo,
                                  boolean authorized, String resultStatus, String errorMessage) {
        String rawUserId = caller.userId() != null ? caller.userId() : "anonymous";
        String displayName = caller.displayName() != null ? caller.displayName() : rawUserId;
        return new AuditEvent(
                sha256(rawUserId),
                rawUserId,
                displayName,
                caller.authMethod(),
                caller.transport(),
                caller.sourceIp(),
                action,
                repo,
                authorized,
                resultStatus,
                errorMessage
        );
    }

    public static String sha256(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
