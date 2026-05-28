package com.indexer.audit;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

public class PostgresAuditSink implements AuditSink {

    private static final Logger log = LoggerFactory.getLogger(PostgresAuditSink.class);

    private final Jdbi jdbi;

    public PostgresAuditSink(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public void record(AuditEvent event) {
        Instant timestamp = Instant.now();

        try {
            jdbi.useTransaction(handle -> {
                // 1. Lock chain state row
                String prevHash = handle.createQuery(
                        "SELECT last_hash FROM audit_chain_state WHERE id = 1 FOR UPDATE")
                        .mapTo(String.class)
                        .one();

                // 2. Compute chain hash
                String chainInput = prevHash + "|" + event.callerHash() + "|" + event.action()
                        + "|" + nullSafe(event.repo()) + "|" + event.resultStatus()
                        + "|" + timestamp.toEpochMilli();
                String chainHash = sha256(chainInput);

                // 3. Insert audit event
                long eventId = handle.createUpdate("""
                        INSERT INTO audit_events
                            (timestamp, caller_hash, auth_method, transport, source_ip,
                             action, repo, authorized, result_status, error_message, chain_hash)
                        VALUES (:timestamp, :callerHash, :authMethod, :transport, :sourceIp,
                                :action, :repo, :authorized, :resultStatus, :errorMessage, :chainHash)
                        """)
                        .bind("timestamp", timestamp)
                        .bind("callerHash", event.callerHash())
                        .bind("authMethod", event.authMethod())
                        .bind("transport", event.transport())
                        .bind("sourceIp", event.sourceIp())
                        .bind("action", event.action())
                        .bind("repo", event.repo())
                        .bind("authorized", event.authorized())
                        .bind("resultStatus", event.resultStatus())
                        .bind("errorMessage", event.errorMessage())
                        .bind("chainHash", chainHash)
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one();

                // 4. Update chain state
                handle.createUpdate(
                        "UPDATE audit_chain_state SET last_hash = :hash, last_event_id = :eventId WHERE id = 1")
                        .bind("hash", chainHash)
                        .bind("eventId", eventId)
                        .execute();

                // 5. Upsert identity map
                handle.createUpdate("""
                        INSERT INTO audit_identity_map (caller_hash, user_id, display_name, first_seen, last_seen)
                        VALUES (:callerHash, :userId, :displayName, NOW(), NOW())
                        ON CONFLICT (caller_hash) DO UPDATE SET last_seen = NOW()
                        """)
                        .bind("callerHash", event.callerHash())
                        .bind("userId", event.userId())
                        .bind("displayName", event.displayName())
                        .execute();
            });
        } catch (Exception e) {
            log.error("Audit write failed: {}", e.getMessage(), e);
            throw new AuditException("Failed to record audit event: " + e.getMessage(), e);
        }
    }

    private static String nullSafe(String s) {
        return s != null ? s : "null";
    }

    private static String sha256(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
