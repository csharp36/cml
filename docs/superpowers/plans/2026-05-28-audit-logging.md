# Phase C: Audit Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add SOX-grade audit logging with SHA-256 hash chain, GDPR-safe identity indirection, "no audit, no access" enforcement, and MCP tools for auditors.

**Architecture:** Inline `AuditSink.record()` calls in `QueryExecutor.executeQuery()` and `AdminApi` handlers. `PostgresAuditSink` uses `SELECT ... FOR UPDATE` on a single-row chain state table for serialization. Three new database tables via Flyway V3 migration.

**Tech Stack:** Java 21, JDBI, PostgreSQL 16, SHA-256 (java.security.MessageDigest)

**Spec:** `docs/superpowers/specs/2026-05-28-audit-logging-design.md`

---

### Task 1: Flyway V3 Migration — Audit Tables

**Files:**
- Create: `src/main/resources/db/migration/V3__audit_logging.sql`

- [ ] **Step 1: Write the migration**

```sql
-- Append-only audit trail with SHA-256 hash chain
CREATE TABLE audit_events (
    id              BIGSERIAL PRIMARY KEY,
    timestamp       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    caller_hash     VARCHAR(64) NOT NULL,
    auth_method     VARCHAR(32) NOT NULL,
    transport       VARCHAR(32) NOT NULL,
    source_ip       VARCHAR(45),
    action          VARCHAR(128) NOT NULL,
    repo            VARCHAR(256),
    authorized      BOOLEAN NOT NULL,
    result_status   VARCHAR(16) NOT NULL,
    error_message   TEXT,
    chain_hash      VARCHAR(64) NOT NULL
);

CREATE INDEX idx_audit_events_timestamp ON audit_events (timestamp);
CREATE INDEX idx_audit_events_caller ON audit_events (caller_hash, timestamp);
CREATE INDEX idx_audit_events_repo ON audit_events (repo, timestamp);

-- GDPR erasure target — delete mapping to anonymize, chain stays intact
CREATE TABLE audit_identity_map (
    caller_hash     VARCHAR(64) PRIMARY KEY,
    user_id         VARCHAR(256) NOT NULL,
    display_name    VARCHAR(256),
    first_seen      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Single-row serialization point for hash chain
CREATE TABLE audit_chain_state (
    id              INT PRIMARY KEY DEFAULT 1,
    last_hash       VARCHAR(64) NOT NULL,
    last_event_id   BIGINT NOT NULL DEFAULT 0
);

-- Seed the chain with genesis hash (SHA-256 of "genesis")
INSERT INTO audit_chain_state (id, last_hash, last_event_id)
VALUES (1, 'aeebad4a796fcc2e15dc4c6061b45ed9b373f26adfc798ca7d2d8cc58182718e', 0);
```

- [ ] **Step 2: Verify migration compiles**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (Flyway runs at app start, not compile time — this just ensures no syntax errors in the build)

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V3__audit_logging.sql
git commit -m "feat: add V3 Flyway migration for audit logging tables"
```

---

### Task 2: AuditEvent Record + AuditException + AuditSink Interface

**Files:**
- Create: `src/main/java/com/indexer/audit/AuditEvent.java`
- Create: `src/main/java/com/indexer/audit/AuditException.java`
- Create: `src/main/java/com/indexer/audit/AuditSink.java`
- Create: `src/test/java/com/indexer/audit/AuditEventTest.java`

- [ ] **Step 1: Write AuditEvent tests**

```java
package com.indexer.audit;

import com.indexer.auth.CallerIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventTest {

    @Test
    void fromCallerIdentityComputesHash() {
        var caller = CallerIdentity.fromApiKey("team-payments", "Payments Team", "10.0.0.1");
        var event = AuditEvent.from(caller, "search_symbols", "my-repo", true, "success", null);

        assertThat(event.callerHash()).hasSize(64); // SHA-256 hex
        assertThat(event.callerHash()).matches("[a-f0-9]{64}");
        assertThat(event.userId()).isEqualTo("team-payments");
        assertThat(event.displayName()).isEqualTo("Payments Team");
        assertThat(event.authMethod()).isEqualTo("api-key");
        assertThat(event.transport()).isEqualTo("streamable-http");
        assertThat(event.sourceIp()).isEqualTo("10.0.0.1");
        assertThat(event.action()).isEqualTo("search_symbols");
        assertThat(event.repo()).isEqualTo("my-repo");
        assertThat(event.authorized()).isTrue();
        assertThat(event.resultStatus()).isEqualTo("success");
        assertThat(event.errorMessage()).isNull();
    }

    @Test
    void fromAnonymousCallerUsesAnonymousHash() {
        var caller = CallerIdentity.anonymous("streamable-http");
        var event = AuditEvent.from(caller, "get_index_health", null, true, "success", null);

        assertThat(event.callerHash()).hasSize(64);
        assertThat(event.userId()).isEqualTo("anonymous");
        assertThat(event.displayName()).isEqualTo("anonymous");
    }

    @Test
    void fromStdioCallerUsesOsUser() {
        var caller = CallerIdentity.fromStdio();
        var event = AuditEvent.from(caller, "search_code", "backend", true, "success", null);

        assertThat(event.callerHash()).hasSize(64);
        assertThat(event.userId()).isEqualTo(System.getProperty("user.name"));
    }

    @Test
    void sameUserIdProducesSameHash() {
        var caller1 = CallerIdentity.fromApiKey("alice", "Alice", "10.0.0.1");
        var caller2 = CallerIdentity.fromApiKey("alice", "Alice V2", "10.0.0.2");
        var event1 = AuditEvent.from(caller1, "a", null, true, "success", null);
        var event2 = AuditEvent.from(caller2, "b", null, true, "success", null);

        assertThat(event1.callerHash()).isEqualTo(event2.callerHash());
    }

    @Test
    void deniedEventCapturesError() {
        var caller = CallerIdentity.fromOAuth("bob", "Bob", List.of("team-a"), "10.0.0.1");
        var event = AuditEvent.from(caller, "search_symbols", "secret-repo", false, "denied", "Access denied");

        assertThat(event.authorized()).isFalse();
        assertThat(event.resultStatus()).isEqualTo("denied");
        assertThat(event.errorMessage()).isEqualTo("Access denied");
    }
}
```

- [ ] **Step 2: Implement AuditException**

```java
package com.indexer.audit;

public class AuditException extends RuntimeException {
    public AuditException(String message) {
        super(message);
    }

    public AuditException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 3: Implement AuditSink interface**

```java
package com.indexer.audit;

public interface AuditSink {
    /**
     * Record an audit event. Synchronous — blocks until persisted.
     * @throws AuditException if the event cannot be recorded
     */
    void record(AuditEvent event);
}
```

- [ ] **Step 4: Implement AuditEvent record**

```java
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
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests "com.indexer.audit.AuditEventTest" --rerun 2>&1 | tail -10`
Expected: All 5 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/indexer/audit/AuditEvent.java src/main/java/com/indexer/audit/AuditException.java src/main/java/com/indexer/audit/AuditSink.java src/test/java/com/indexer/audit/AuditEventTest.java
git commit -m "feat: add AuditEvent record, AuditException, and AuditSink interface"
```

---

### Task 3: PostgresAuditSink Implementation

**Files:**
- Create: `src/main/java/com/indexer/audit/PostgresAuditSink.java`
- Create: `src/test/java/com/indexer/audit/PostgresAuditSinkTest.java`

- [ ] **Step 1: Write PostgresAuditSink tests**

```java
package com.indexer.audit;

import com.indexer.auth.CallerIdentity;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PostgresAuditSinkTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    private Jdbi jdbi;
    private PostgresAuditSink sink;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());

        // Create tables (simulating Flyway V3)
        jdbi.useHandle(h -> {
            h.execute("DROP TABLE IF EXISTS audit_events, audit_identity_map, audit_chain_state CASCADE");
            h.execute("""
                CREATE TABLE audit_events (
                    id BIGSERIAL PRIMARY KEY, timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    caller_hash VARCHAR(64) NOT NULL, auth_method VARCHAR(32) NOT NULL,
                    transport VARCHAR(32) NOT NULL, source_ip VARCHAR(45),
                    action VARCHAR(128) NOT NULL, repo VARCHAR(256),
                    authorized BOOLEAN NOT NULL, result_status VARCHAR(16) NOT NULL,
                    error_message TEXT, chain_hash VARCHAR(64) NOT NULL
                )""");
            h.execute("""
                CREATE TABLE audit_identity_map (
                    caller_hash VARCHAR(64) PRIMARY KEY, user_id VARCHAR(256) NOT NULL,
                    display_name VARCHAR(256), first_seen TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    last_seen TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )""");
            h.execute("""
                CREATE TABLE audit_chain_state (
                    id INT PRIMARY KEY DEFAULT 1, last_hash VARCHAR(64) NOT NULL,
                    last_event_id BIGINT NOT NULL DEFAULT 0
                )""");
            h.execute("INSERT INTO audit_chain_state (id, last_hash, last_event_id) VALUES (1, 'aeebad4a796fcc2e15dc4c6061b45ed9b373f26adfc798ca7d2d8cc58182718e', 0)");
        });

        sink = new PostgresAuditSink(jdbi);
    }

    @Test
    void recordInsertsEventAndUpdatesChain() {
        var caller = CallerIdentity.fromApiKey("alice", "Alice", "10.0.0.1");
        var event = AuditEvent.from(caller, "search_symbols", "my-repo", true, "success", null);

        sink.record(event);

        var events = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM audit_events ORDER BY id").mapToMap().list());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("action")).isEqualTo("search_symbols");
        assertThat(events.get(0).get("result_status")).isEqualTo("success");
        assertThat(events.get(0).get("chain_hash")).isNotNull();

        var chainState = jdbi.withHandle(h ->
                h.createQuery("SELECT last_hash, last_event_id FROM audit_chain_state WHERE id = 1").mapToMap().one());
        assertThat(chainState.get("last_event_id")).isEqualTo(1L);
        assertThat(chainState.get("last_hash")).isNotEqualTo("aeebad4a796fcc2e15dc4c6061b45ed9b373f26adfc798ca7d2d8cc58182718e");
    }

    @Test
    void recordUpsertsIdentityMap() {
        var caller = CallerIdentity.fromApiKey("alice", "Alice", "10.0.0.1");
        sink.record(AuditEvent.from(caller, "search_symbols", "repo", true, "success", null));
        sink.record(AuditEvent.from(caller, "search_code", "repo", true, "success", null));

        var identities = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM audit_identity_map").mapToMap().list());
        assertThat(identities).hasSize(1);
        assertThat(identities.get(0).get("user_id")).isEqualTo("alice");
        assertThat(identities.get(0).get("display_name")).isEqualTo("Alice");
    }

    @Test
    void hashChainIsConsecutive() {
        var caller = CallerIdentity.fromApiKey("alice", "Alice", "10.0.0.1");
        sink.record(AuditEvent.from(caller, "action1", "repo", true, "success", null));
        sink.record(AuditEvent.from(caller, "action2", "repo", true, "success", null));
        sink.record(AuditEvent.from(caller, "action3", "repo", true, "error", "something broke"));

        var events = jdbi.withHandle(h ->
                h.createQuery("SELECT chain_hash FROM audit_events ORDER BY id").mapToMap().list());
        assertThat(events).hasSize(3);

        // All chain hashes are different (since input data differs)
        var hashes = events.stream().map(e -> (String) e.get("chain_hash")).toList();
        assertThat(hashes).doesNotHaveDuplicates();
    }

    @Test
    void deniedEventIsRecorded() {
        var caller = CallerIdentity.fromOAuth("bob", "Bob", List.of("team-a"), "10.0.0.1");
        var event = AuditEvent.from(caller, "search_symbols", "secret-repo", false, "denied", "Access denied");

        sink.record(event);

        var events = jdbi.withHandle(h ->
                h.createQuery("SELECT authorized, result_status, error_message FROM audit_events").mapToMap().list());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("authorized")).isEqualTo(false);
        assertThat(events.get(0).get("result_status")).isEqualTo("denied");
        assertThat(events.get(0).get("error_message")).isEqualTo("Access denied");
    }
}
```

- [ ] **Step 2: Implement PostgresAuditSink**

```java
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
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests "com.indexer.audit.PostgresAuditSinkTest" --rerun 2>&1 | tail -15`
Expected: All 4 tests pass (requires Docker for Testcontainers).

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/indexer/audit/PostgresAuditSink.java src/test/java/com/indexer/audit/PostgresAuditSinkTest.java
git commit -m "feat: add PostgresAuditSink with hash chain and identity map"
```

---

### Task 4: Extend CallerIdentity with auditReader + fromAdminToken

**Files:**
- Modify: `src/main/java/com/indexer/auth/CallerIdentity.java`
- Modify: `src/test/java/com/indexer/auth/CallerIdentityTest.java`

- [ ] **Step 1: Add auditReader field and fromAdminToken factory**

Replace the entire `CallerIdentity.java`:

```java
package com.indexer.auth;

import java.util.List;

public record CallerIdentity(
        String userId,
        String displayName,
        String authMethod,
        String transport,
        String sourceIp,
        String clientName,
        String clientVersion,
        List<String> groups,
        boolean auditReader
) {
    public CallerIdentity {
        groups = groups != null ? List.copyOf(groups) : List.of();
    }

    public static final String CONTEXT_KEY = "callerIdentity";

    public static CallerIdentity anonymous(String transport) {
        return new CallerIdentity(null, "anonymous", "none", transport, null, null, null, List.of(), false);
    }

    public static CallerIdentity fromStdio() {
        String osUser = System.getProperty("user.name");
        return new CallerIdentity(osUser, osUser, "stdio-os-user", "stdio", null, null, null, List.of(), true);
    }

    public static CallerIdentity fromApiKey(String id, String name, String sourceIp) {
        return new CallerIdentity(id, name, "api-key", "streamable-http", sourceIp, null, null, List.of(), false);
    }

    public static CallerIdentity fromApiKey(String id, String name, String sourceIp, boolean auditReader) {
        return new CallerIdentity(id, name, "api-key", "streamable-http", sourceIp, null, null, List.of(), auditReader);
    }

    public static CallerIdentity fromOAuth(String sub, String name, List<String> groups, String sourceIp) {
        return new CallerIdentity(sub, name, "oauth", "streamable-http", sourceIp, null, null, groups, false);
    }

    public static CallerIdentity fromAdminToken(String sourceIp) {
        return new CallerIdentity("admin", "Admin", "admin-token", "streamable-http", sourceIp, null, null, List.of(), false);
    }
}
```

- [ ] **Step 2: Add tests for new factories**

Add to `CallerIdentityTest.java`:

```java
@Test
void fromAdminTokenCreatesAdminIdentity() {
    var identity = CallerIdentity.fromAdminToken("10.0.0.1");
    assertThat(identity.userId()).isEqualTo("admin");
    assertThat(identity.displayName()).isEqualTo("Admin");
    assertThat(identity.authMethod()).isEqualTo("admin-token");
    assertThat(identity.transport()).isEqualTo("streamable-http");
    assertThat(identity.sourceIp()).isEqualTo("10.0.0.1");
    assertThat(identity.auditReader()).isFalse();
}

@Test
void fromStdioHasAuditReaderTrue() {
    var identity = CallerIdentity.fromStdio();
    assertThat(identity.auditReader()).isTrue();
}

@Test
void fromApiKeyWithAuditReader() {
    var identity = CallerIdentity.fromApiKey("compliance", "Compliance Team", "10.0.0.1", true);
    assertThat(identity.auditReader()).isTrue();
}

@Test
void fromApiKeyDefaultsAuditReaderFalse() {
    var identity = CallerIdentity.fromApiKey("dev", "Dev Team", "10.0.0.1");
    assertThat(identity.auditReader()).isFalse();
}
```

- [ ] **Step 3: Fix compilation errors from the auditReader field addition**

The `auditReader` field changes the canonical constructor. All code constructing CallerIdentity directly (not via factory methods) may need updating. Check:
- `CallerIdentity.fromOAuth` — factory method, updated above
- `JwtValidator.validate()` — calls `CallerIdentity.fromOAuth()`, OK
- Tests — use factory methods, OK

Run: `./gradlew compileJava compileTestJava 2>&1 | tail -10`

- [ ] **Step 4: Run all tests**

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/auth/CallerIdentity.java src/test/java/com/indexer/auth/CallerIdentityTest.java
git commit -m "feat: add auditReader field and fromAdminToken factory to CallerIdentity"
```

---

### Task 5: Config Extension — auditReader on ApiKeyEntry

**Files:**
- Modify: `src/main/java/com/indexer/config/IndexerConfig.java`
- Modify: `src/main/java/com/indexer/config/ConfigLoader.java`
- Modify: `src/main/java/com/indexer/Application.java`

- [ ] **Step 1: Add auditReader to ApiKeyEntry**

In `IndexerConfig.java`, replace the `ApiKeyEntry` record (line 84):

From: `public record ApiKeyEntry(String key, String id, String name) {}`

To: `public record ApiKeyEntry(String key, String id, String name, boolean auditReader) {}`

- [ ] **Step 2: Update ConfigLoader to parse auditReader**

In `ConfigLoader.java`, in the `parseMcpAuth` method, update the API key parsing loop. Replace the line that creates `ApiKeyEntry` (around line 187):

From:
```java
keys.add(new IndexerConfig.McpAuthConfig.ApiKeyEntry(key, id, name != null ? name : id));
```

To:
```java
boolean auditReader = keyNode.has("auditReader") && keyNode.get("auditReader").asBoolean(false);
keys.add(new IndexerConfig.McpAuthConfig.ApiKeyEntry(key, id, name != null ? name : id, auditReader));
```

- [ ] **Step 3: Update Application.java to pass auditReader through to CallerIdentity**

In `Application.java`, update where `ApiKeyAuthenticator.ApiKeyConfig` is constructed (around line 115). The `ApiKeyConfig` record doesn't carry `auditReader` — the simplest approach is to update `ApiKeyConfig` to include it, and update `ApiKeyAuthenticator.authenticate()` to pass it through.

In `ApiKeyAuthenticator.java`, replace the `ApiKeyConfig` record:

From: `public record ApiKeyConfig(String key, String id, String name) {}`

To: `public record ApiKeyConfig(String key, String id, String name, boolean auditReader) {}`

Update `authenticate()` to use the new overload:

From:
```java
return Optional.of(CallerIdentity.fromApiKey(keyConfig.id(), keyConfig.name(), sourceIp));
```

To:
```java
return Optional.of(CallerIdentity.fromApiKey(keyConfig.id(), keyConfig.name(), sourceIp, keyConfig.auditReader()));
```

Update `Application.java` where `ApiKeyConfig` is constructed (around line 115):

From:
```java
.map(e -> new ApiKeyAuthenticator.ApiKeyConfig(e.key(), e.id(), e.name()))
```

To:
```java
.map(e -> new ApiKeyAuthenticator.ApiKeyConfig(e.key(), e.id(), e.name(), e.auditReader()))
```

- [ ] **Step 4: Verify compilation and tests**

Run: `./gradlew compileJava compileTestJava 2>&1 | tail -10`
Fix any compilation errors — the `ApiKeyConfig` constructor changed. Check test files that construct `ApiKeyConfig` directly.

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/config/IndexerConfig.java src/main/java/com/indexer/config/ConfigLoader.java src/main/java/com/indexer/auth/ApiKeyAuthenticator.java src/main/java/com/indexer/Application.java
git commit -m "feat: add auditReader config flag to API keys and wire through to CallerIdentity"
```

---

### Task 6: Wire AuditSink into QueryExecutor — "No Audit, No Access"

**Files:**
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java`
- Modify: `src/main/java/com/indexer/Application.java`

- [ ] **Step 1: Add AuditSink field and constructor to QueryExecutor**

Add import:
```java
import com.indexer.audit.AuditEvent;
import com.indexer.audit.AuditException;
import com.indexer.audit.AuditSink;
```

Add field:
```java
private final AuditSink auditSink;
```

Replace the three-constructor chain with a four-constructor chain:

```java
public QueryExecutor(Jdbi jdbi) {
    this(jdbi, null, null, null, null, null, null);
}

public QueryExecutor(Jdbi jdbi, BranchIndexDao branchIndexDao, IndexingPipeline indexingPipeline,
                     RepositoryDao repositoryDao, GitOperations gitOps) {
    this(jdbi, branchIndexDao, indexingPipeline, repositoryDao, gitOps, null, null);
}

public QueryExecutor(Jdbi jdbi, BranchIndexDao branchIndexDao, IndexingPipeline indexingPipeline,
                     RepositoryDao repositoryDao, GitOperations gitOps, PermissionCache permissionCache) {
    this(jdbi, branchIndexDao, indexingPipeline, repositoryDao, gitOps, permissionCache, null);
}

public QueryExecutor(Jdbi jdbi, BranchIndexDao branchIndexDao, IndexingPipeline indexingPipeline,
                     RepositoryDao repositoryDao, GitOperations gitOps, PermissionCache permissionCache,
                     AuditSink auditSink) {
    this.jdbi = jdbi;
    this.branchIndexDao = branchIndexDao;
    this.indexingPipeline = indexingPipeline;
    this.repositoryDao = repositoryDao;
    this.gitOps = gitOps;
    this.permissionCache = permissionCache;
    this.auditSink = auditSink;
}
```

- [ ] **Step 2: Update executeQuery with audit integration**

Replace the `executeQuery` method:

```java
public McpSchema.CallToolResult executeQuery(
        CallerIdentity caller, String repo, String action,
        Map<String, Object> params, Supplier<Object> query) {
    log.info("Tool call: {} by {} ({})", action, caller.displayName(), caller.authMethod());

    // Authorization check — only for OAuth users with configured permissions
    if (permissionCache != null && "oauth".equals(caller.authMethod())) {
        if (repo == null) {
            log.warn("Access denied: {} called {} without repo parameter", caller.displayName(), action);
            auditBestEffort(caller, action, null, false, "denied", "Repository parameter required");
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Repository parameter is required for authenticated queries")
                    .isError(true)
                    .build();
        }
        try {
            Set<String> allowed = permissionCache.getAllowedRepos(caller);
            if (!allowed.contains(repo)) {
                log.warn("Access denied: {} attempted to query repo {}", caller.displayName(), repo);
                auditBestEffort(caller, action, repo, false, "denied", "Access denied to repository: " + repo);
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Access denied to repository: " + repo)
                        .isError(true)
                        .build();
            }
        } catch (Exception e) {
            log.error("Permission resolution failed for {}: {}", caller.displayName(), e.getMessage());
            auditBestEffort(caller, action, repo, false, "denied", "Permission resolution failed");
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Authorization failed: unable to verify permissions")
                    .isError(true)
                    .build();
        }
    }

    // Execute query
    Object result;
    String resultStatus;
    String errorMessage = null;
    try {
        result = query.get();
        resultStatus = "success";
    } catch (Exception e) {
        log.error("Tool execution error in {}: {}", action, e.getMessage(), e);
        result = null;
        resultStatus = "error";
        errorMessage = e.getMessage();
    }

    // Audit — "no audit, no access"
    if (auditSink != null) {
        try {
            auditSink.record(AuditEvent.from(caller, action, repo, true, resultStatus, errorMessage));
        } catch (AuditException e) {
            log.error("Audit write failed for {}, discarding query result: {}", action, e.getMessage());
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Audit recording failed — query result withheld")
                    .isError(true)
                    .build();
        }
    }

    // Return result
    if (result == null) {
        return McpSchema.CallToolResult.builder()
                .addTextContent("Error: " + errorMessage)
                .isError(true)
                .build();
    }

    try {
        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .isError(false)
                .build();
    } catch (Exception e) {
        log.error("Result serialization error in {}: {}", action, e.getMessage(), e);
        return McpSchema.CallToolResult.builder()
                .addTextContent("Error: " + e.getMessage())
                .isError(true)
                .build();
    }
}

/** Best-effort audit for denied queries — log warning if audit itself fails. */
private void auditBestEffort(CallerIdentity caller, String action, String repo,
                             boolean authorized, String resultStatus, String errorMessage) {
    if (auditSink == null) return;
    try {
        auditSink.record(AuditEvent.from(caller, action, repo, authorized, resultStatus, errorMessage));
    } catch (Exception e) {
        log.warn("Audit write failed for denied query {}: {}", action, e.getMessage());
    }
}
```

- [ ] **Step 3: Wire PostgresAuditSink in Application.java**

In `Application.java`, after the PermissionCache setup and before the Streamable HTTP transport builder, add:

```java
// 5e. Set up audit sink
var auditSink = new com.indexer.audit.PostgresAuditSink(jdbi);
```

Update the `QueryExecutor` construction to pass `auditSink`:

From:
```java
var queryExecutor = new com.indexer.mcp.QueryExecutor(jdbi, branchIndexDao, indexingPipeline, repositoryDao, gitOps, permissionCache);
```

To:
```java
var queryExecutor = new com.indexer.mcp.QueryExecutor(jdbi, branchIndexDao, indexingPipeline, repositoryDao, gitOps, permissionCache, auditSink);
```

- [ ] **Step 4: Verify compilation and tests**

Run: `./gradlew compileJava compileTestJava 2>&1 | tail -10`
Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/mcp/QueryExecutor.java src/main/java/com/indexer/Application.java
git commit -m "feat: wire AuditSink into QueryExecutor with no-audit-no-access enforcement"
```

---

### Task 7: Wire AuditSink into AdminApi

**Files:**
- Modify: `src/main/java/com/indexer/admin/AdminApi.java`
- Modify: `src/main/java/com/indexer/Application.java`

- [ ] **Step 1: Add AuditSink to AdminApi constructor**

Add imports:
```java
import com.indexer.audit.AuditEvent;
import com.indexer.audit.AuditSink;
import com.indexer.auth.CallerIdentity;
```

Add field and update constructor:

```java
private final AuditSink auditSink;

public AdminApi(AdminService adminService, String adminToken) {
    this(adminService, adminToken, null);
}

public AdminApi(AdminService adminService, String adminToken, AuditSink auditSink) {
    this.adminService = adminService;
    this.adminToken = adminToken;
    this.auditSink = auditSink;
}
```

- [ ] **Step 2: Audit auth failures in authenticate()**

Update the `authenticate` method. After the "Invalid admin token" block (line 53-54), add audit:

```java
if (!constantTimeEquals(adminToken, providedToken)) {
    auditAdminBestEffort(ctx, "admin:authFailure", false, "denied", "Invalid admin token");
    ctx.status(401).json(Map.of("error", "Invalid admin token"));
    ctx.skipRemainingHandlers();
    return;
}
```

Also audit missing auth header (after line 47):

```java
if (authHeader == null || !authHeader.startsWith("Bearer ")) {
    auditAdminBestEffort(ctx, "admin:authFailure", false, "denied", "Missing Authorization header");
    ctx.status(401).json(Map.of("error", "Missing or invalid Authorization header"));
    ctx.skipRemainingHandlers();
    return;
}
```

- [ ] **Step 3: Audit mutating admin actions**

Update the four mutating handlers. Example for `addRepo`:

```java
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
        auditAdminBestEffort(ctx, "admin:addRepo", true, "success", null);
        ctx.status(202).json(result);
    } catch (AdminService.ConflictException e) {
        auditAdminBestEffort(ctx, "admin:addRepo", true, "error", e.getMessage());
        ctx.status(409).json(Map.of("error", e.getMessage()));
    }
}
```

Apply the same pattern to `deleteRepo` (`"admin:deleteRepo"`), `reindexRepo` (`"admin:reindex"`), and `retryEvent` (`"admin:retryEvent"`).

- [ ] **Step 4: Add auditAdminBestEffort helper**

```java
private void auditAdminBestEffort(Context ctx, String action,
                                  boolean authorized, String resultStatus, String errorMessage) {
    if (auditSink == null) return;
    try {
        var caller = CallerIdentity.fromAdminToken(ctx.ip());
        auditSink.record(AuditEvent.from(caller, action, null, authorized, resultStatus, errorMessage));
    } catch (Exception e) {
        log.error("Audit write failed for admin action {}: {}", action, e.getMessage());
    }
}
```

- [ ] **Step 5: Wire auditSink to AdminApi in Application.java**

Update the AdminApi construction:

From:
```java
var adminApi = new AdminApi(adminService, adminToken);
```

To:
```java
var adminApi = new AdminApi(adminService, adminToken, auditSink);
```

- [ ] **Step 6: Verify compilation and tests**

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/indexer/admin/AdminApi.java src/main/java/com/indexer/Application.java
git commit -m "feat: audit mutating admin actions and auth failures in AdminApi"
```

---

### Task 8: query_audit_log MCP Tool

**Files:**
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java`
- Modify: `src/main/java/com/indexer/mcp/McpServerBootstrap.java`

- [ ] **Step 1: Add queryAuditLog method to QueryExecutor**

```java
public List<Map<String, Object>> queryAuditLog(String callerHash, String action, String repo,
                                                String resultStatus, Instant since, Instant until, int limit) {
    return jdbi.withHandle(handle -> {
        var sb = new StringBuilder("SELECT ae.*, aim.user_id, aim.display_name ");
        sb.append("FROM audit_events ae ");
        sb.append("LEFT JOIN audit_identity_map aim ON ae.caller_hash = aim.caller_hash ");
        sb.append("WHERE 1=1 ");

        var params = new java.util.LinkedHashMap<String, Object>();

        if (callerHash != null && !callerHash.isBlank()) {
            sb.append("AND ae.caller_hash = :callerHash ");
            params.put("callerHash", callerHash);
        }
        if (action != null && !action.isBlank()) {
            sb.append("AND ae.action = :action ");
            params.put("action", action);
        }
        if (repo != null && !repo.isBlank()) {
            sb.append("AND ae.repo = :repo ");
            params.put("repo", repo);
        }
        if (resultStatus != null && !resultStatus.isBlank()) {
            sb.append("AND ae.result_status = :resultStatus ");
            params.put("resultStatus", resultStatus);
        }
        if (since != null) {
            sb.append("AND ae.timestamp >= :since ");
            params.put("since", since);
        }
        if (until != null) {
            sb.append("AND ae.timestamp <= :until ");
            params.put("until", until);
        }

        sb.append("ORDER BY ae.timestamp DESC LIMIT :limit");
        params.put("limit", Math.min(limit, 500));

        var q = handle.createQuery(sb.toString());
        params.forEach(q::bind);
        return q.mapToMap().list();
    });
}
```

Add import: `import java.time.Instant;`

- [ ] **Step 2: Add tool definition and handler in McpServerBootstrap**

Add tool definition method:

```java
private McpSchema.Tool queryAuditLogTool() {
    var props = new LinkedHashMap<String, Object>();
    props.put("caller_hash",   Map.of("type", "string", "description", "Filter by caller hash"));
    props.put("action",        Map.of("type", "string", "description", "Filter by tool name or admin action"));
    props.put("repo",          Map.of("type", "string", "description", "Filter by repository name"));
    props.put("result_status", Map.of("type", "string", "description", "Filter: success, error, denied"));
    props.put("since",         Map.of("type", "string", "description", "ISO 8601 timestamp lower bound"));
    props.put("until",         Map.of("type", "string", "description", "ISO 8601 timestamp upper bound"));
    props.put("limit",         Map.of("type", "integer", "description", "Max results (default 50, max 500)", "default", 50));
    var schema = new McpSchema.JsonSchema("object", props, List.of(), false, null, null);
    return McpSchema.Tool.builder()
            .name("query_audit_log")
            .description("Query the audit log. Requires audit reader access (stdio or API key with auditReader: true).")
            .inputSchema(schema)
            .build();
}
```

Add handler:

```java
private McpSchema.CallToolResult handleQueryAuditLog(
        McpSyncServerExchange exchange,
        McpSchema.CallToolRequest request) {
    var args = request.arguments();
    var caller = extractIdentity(exchange);

    // Access control: must be audit reader
    if (!caller.auditReader()) {
        return McpSchema.CallToolResult.builder()
                .addTextContent("Access denied: audit reader permission required")
                .isError(true)
                .build();
    }

    java.time.Instant since = parseInstant(stringArg(args, "since"));
    java.time.Instant until = parseInstant(stringArg(args, "until"));

    return queryExecutor.executeQuery(caller, null, "query_audit_log", args,
            () -> queryExecutor.queryAuditLog(
                    stringArg(args, "caller_hash"), stringArg(args, "action"),
                    stringArg(args, "repo"), stringArg(args, "result_status"),
                    since, until, intArg(args, "limit", 50)));
}

private java.time.Instant parseInstant(String s) {
    if (s == null || s.isBlank()) return null;
    try {
        return java.time.Instant.parse(s);
    } catch (Exception e) {
        return null;
    }
}
```

- [ ] **Step 3: Register the tool in both buildServer and startHttp**

Add `.toolCall(queryAuditLogTool(), this::handleQueryAuditLog)` to both the `buildServer` method and the `startHttp` method, after the `checkSyncTool` registration.

Update the log messages from "11 tools" to "13 tools" in both `startStdio()` and `startHttp()`.

- [ ] **Step 4: Verify compilation and tests**

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/mcp/QueryExecutor.java src/main/java/com/indexer/mcp/McpServerBootstrap.java
git commit -m "feat: add query_audit_log MCP tool with audit reader access control"
```

---

### Task 9: verify_audit_chain MCP Tool

**Files:**
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java`
- Modify: `src/main/java/com/indexer/mcp/McpServerBootstrap.java`

- [ ] **Step 1: Add verifyAuditChain method to QueryExecutor**

```java
public Map<String, Object> verifyAuditChain(int count) {
    int effectiveCount = Math.min(Math.max(count, 1), 1000);
    return jdbi.withHandle(handle -> {
        var events = handle.createQuery("""
                SELECT id, caller_hash, action, repo, result_status,
                       EXTRACT(EPOCH FROM timestamp) * 1000 AS timestamp_millis,
                       chain_hash
                FROM audit_events ORDER BY id DESC LIMIT :count
                """)
                .bind("count", effectiveCount)
                .mapToMap()
                .list();

        if (events.isEmpty()) {
            return Map.<String, Object>of(
                    "checked", 0, "intact", true, "message", "No audit events found");
        }

        // Reverse to process oldest first
        var sorted = new java.util.ArrayList<>(events);
        java.util.Collections.reverse(sorted);

        // Get the hash before the first event in our window
        long firstId = ((Number) sorted.get(0).get("id")).longValue();
        String prevHash;
        if (firstId == 1) {
            prevHash = "aeebad4a796fcc2e15dc4c6061b45ed9b373f26adfc798ca7d2d8cc58182718e"; // genesis
        } else {
            prevHash = handle.createQuery(
                    "SELECT chain_hash FROM audit_events WHERE id = :id")
                    .bind("id", firstId - 1)
                    .mapTo(String.class)
                    .findOne()
                    .orElse("aeebad4a796fcc2e15dc4c6061b45ed9b373f26adfc798ca7d2d8cc58182718e");
        }

        for (int i = 0; i < sorted.size(); i++) {
            var event = sorted.get(i);
            String callerHash = (String) event.get("caller_hash");
            String action = (String) event.get("action");
            String repo = event.get("repo") != null ? (String) event.get("repo") : "null";
            String resultStatus = (String) event.get("result_status");
            long timestampMillis = ((Number) event.get("timestamp_millis")).longValue();
            String storedHash = (String) event.get("chain_hash");

            String chainInput = prevHash + "|" + callerHash + "|" + action
                    + "|" + repo + "|" + resultStatus + "|" + timestampMillis;
            String computedHash = com.indexer.audit.AuditEvent.sha256(chainInput);

            if (!computedHash.equals(storedHash)) {
                long eventId = ((Number) event.get("id")).longValue();
                return Map.<String, Object>of(
                        "checked", i + 1,
                        "intact", false,
                        "break_at_event_id", eventId,
                        "break_at_position", i + 1,
                        "message", "Chain break detected at event " + eventId);
            }
            prevHash = storedHash;
        }

        return Map.<String, Object>of(
                "checked", sorted.size(), "intact", true,
                "message", "Chain intact for " + sorted.size() + " events");
    });
}
```

- [ ] **Step 2: Add tool definition and handler in McpServerBootstrap**

Add tool definition:

```java
private McpSchema.Tool verifyAuditChainTool() {
    var schema = new McpSchema.JsonSchema("object",
            Map.of("count", Map.of("type", "integer", "description", "Number of recent events to verify (default 100, max 1000)", "default", 100)),
            List.of(), false, null, null);
    return McpSchema.Tool.builder()
            .name("verify_audit_chain")
            .description("Verify audit log hash chain integrity. Checks the last N events for tamper evidence.")
            .inputSchema(schema)
            .build();
}
```

Add handler:

```java
private McpSchema.CallToolResult handleVerifyAuditChain(
        McpSyncServerExchange exchange,
        McpSchema.CallToolRequest request) {
    var args = request.arguments();
    var caller = extractIdentity(exchange);

    if (!caller.auditReader()) {
        return McpSchema.CallToolResult.builder()
                .addTextContent("Access denied: audit reader permission required")
                .isError(true)
                .build();
    }

    return queryExecutor.executeQuery(caller, null, "verify_audit_chain", args,
            () -> queryExecutor.verifyAuditChain(intArg(args, "count", 100)));
}
```

- [ ] **Step 3: Register the tool in both buildServer and startHttp**

Add `.toolCall(verifyAuditChainTool(), this::handleVerifyAuditChain)` after the `queryAuditLogTool` registration in both methods.

- [ ] **Step 4: Verify compilation and tests**

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/mcp/QueryExecutor.java src/main/java/com/indexer/mcp/McpServerBootstrap.java
git commit -m "feat: add verify_audit_chain MCP tool for tamper evidence checking"
```

---

### Implementation Notes

**Task dependency chain:** Task 1 (migration) is independent. Tasks 2-3 (AuditEvent + PostgresAuditSink) depend on Task 1 for schema. Task 4 (CallerIdentity) is independent. Task 5 (config) depends on Task 4. Task 6 (QueryExecutor wiring) depends on Tasks 2-3 and 5. Task 7 (AdminApi) depends on Tasks 2-3 and 4. Tasks 8-9 (MCP tools) depend on Task 6.

**Backward compatibility:** Every modified class gains a new constructor that chains to the old one with `null` for new parameters. Existing tests pass unchanged.

**Testing strategy:** Task 2 has unit tests for AuditEvent. Task 3 has Testcontainers integration tests for PostgresAuditSink. Tasks 4-5 rely on existing test suites plus new CallerIdentity tests. Tasks 6-9 rely on compilation + existing tests passing (no new audit behavior exercised in unit tests since it requires the full pipeline).

**CLAUDE.md update needed after implementation:** Update the MCP Tools Reference table from 11 tools to 13, add `query_audit_log` and `verify_audit_chain` entries, add Audit section to the doc.
