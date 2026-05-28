# Phase C: Audit Logging — Design Spec

## Overview

SOX-grade audit trail for every MCP tool call and mutating admin action. Append-only, tamper-evident via SHA-256 hash chain, GDPR-safe via UUID indirection. Synchronous writes with "no audit, no access" fail-closed enforcement.

**Depends on:** Phase B (Identity/AuthZ) — CallerIdentity, executeQuery pipeline, API key config.

**Gate:** A SOX auditor can query all access to any repo via Claude Code using the `query_audit_log` and `verify_audit_chain` MCP tools.

---

## Data Model

### Flyway Migration V3

Three new tables.

**`audit_events`** (append-only — application enforces INSERT/SELECT only):

| Column | Type | Notes |
|--------|------|-------|
| `id` | `BIGSERIAL PRIMARY KEY` | Monotonic sequence |
| `timestamp` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | Server time at recording |
| `caller_hash` | `VARCHAR(64) NOT NULL` | SHA-256 of userId (GDPR indirection) |
| `auth_method` | `VARCHAR(32) NOT NULL` | stdio-os-user, api-key, oauth, admin-token |
| `transport` | `VARCHAR(32) NOT NULL` | stdio, streamable-http |
| `source_ip` | `VARCHAR(45)` | Nullable (null for stdio) |
| `action` | `VARCHAR(128) NOT NULL` | Tool name or `admin:<endpoint>` |
| `repo` | `VARCHAR(256)` | Nullable (cross-repo tools, admin actions) |
| `authorized` | `BOOLEAN NOT NULL` | True if allowed, false if denied |
| `result_status` | `VARCHAR(16) NOT NULL` | success, error, denied |
| `error_message` | `TEXT` | Null on success |
| `chain_hash` | `VARCHAR(64) NOT NULL` | SHA-256(prev_hash + this_row) |

Index on `(timestamp)` for time-range queries. Index on `(caller_hash, timestamp)` for per-user queries. Index on `(repo, timestamp)` for per-repo queries.

**`audit_identity_map`** (GDPR erasure target):

| Column | Type | Notes |
|--------|------|-------|
| `caller_hash` | `VARCHAR(64) PRIMARY KEY` | SHA-256 of userId |
| `user_id` | `VARCHAR(256) NOT NULL` | Original userId |
| `display_name` | `VARCHAR(256)` | Human-readable name |
| `first_seen` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | When first recorded |
| `last_seen` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | Updated on each audit write |

GDPR erasure = `DELETE FROM audit_identity_map WHERE caller_hash = ?`. Audit events remain with opaque hash. Chain is unbroken, identity is gone.

**`audit_chain_state`** (single-row serialization point):

| Column | Type | Notes |
|--------|------|-------|
| `id` | `INT PRIMARY KEY DEFAULT 1` | Always 1 — single row |
| `last_hash` | `VARCHAR(64) NOT NULL` | Current chain head |
| `last_event_id` | `BIGINT NOT NULL DEFAULT 0` | Last recorded event id |

Seeded by migration: `INSERT INTO audit_chain_state (id, last_hash, last_event_id) VALUES (1, 'aeebad4a796fcc2e15dc4c6061b45ed9b373f26adfc798ca7d2d8cc58182718e', 0)` — the SHA-256 of the literal string `"genesis"`.

---

## AuditEvent Record

```java
public record AuditEvent(
    String callerHash,      // SHA-256(userId) — stored in audit_events
    String userId,          // original userId — for identity map upsert only, NOT stored in audit_events
    String displayName,     // display name — for identity map upsert only, NOT stored in audit_events
    String authMethod,      // stdio-os-user, api-key, oauth, admin-token
    String transport,       // stdio, streamable-http
    String sourceIp,        // nullable
    String action,          // tool name or "admin:addRepo", "admin:deleteRepo", etc.
    String repo,            // nullable
    boolean authorized,     // true if allowed
    String resultStatus,    // "success", "error", "denied"
    String errorMessage     // nullable
)
```

Static factory: `AuditEvent.from(CallerIdentity caller, String action, String repo, boolean authorized, String resultStatus, String errorMessage)` — computes `callerHash` as `SHA-256(caller.userId())`, carries `userId` and `displayName` from the identity for the identity map upsert. For anonymous callers (null userId), uses `SHA-256("anonymous")` and `userId="anonymous"`.

---

## AuditSink Interface

```java
public interface AuditSink {
    /**
     * Record an audit event. Synchronous — blocks until persisted.
     * @throws AuditException if the event cannot be recorded
     */
    void record(AuditEvent event);
}
```

Single method. `AuditException` is an unchecked `RuntimeException` — callers handle it for fail-closed enforcement.

---

## PostgresAuditSink Implementation

All operations in a single JDBI transaction:

1. `SELECT last_hash FROM audit_chain_state WHERE id = 1 FOR UPDATE` — acquires row lock, serializes concurrent writers
2. Compute `chain_hash = SHA-256(last_hash + callerHash + action + repo + resultStatus + timestamp)` where timestamp is `Instant.now()` captured before the transaction
3. `INSERT INTO audit_events (...) VALUES (...)` — returns generated `id`
4. `UPDATE audit_chain_state SET last_hash = :chainHash, last_event_id = :eventId`
5. `INSERT INTO audit_identity_map (caller_hash, user_id, display_name, first_seen, last_seen) VALUES (?, ?, ?, NOW(), NOW()) ON CONFLICT (caller_hash) DO UPDATE SET last_seen = NOW()`
6. Commit

If any step fails, the transaction rolls back and `AuditException` is thrown.

**Hash chain input format:** `SHA-256(prevHash + "|" + callerHash + "|" + action + "|" + repo + "|" + resultStatus + "|" + timestamp.toEpochMilli())`. Pipe-delimited, null fields become the literal string `"null"`. This is deterministic and reproducible for chain verification.

**Constructor:** `PostgresAuditSink(Jdbi jdbi)` — uses the existing Jdbi instance.

---

## Integration: QueryExecutor

New constructor parameter: `AuditSink auditSink` (nullable for backward compat).

Updated `executeQuery` flow:

```
executeQuery(caller, repo, action, params, query):
  1. log the call (existing)
  2. authorization check (existing)
     if denied:
       try { auditSink.record(denied event) } catch { log warning }
       return denied error
     if auth resolution fails:
       try { auditSink.record(denied event) } catch { log warning }
       return auth error
  3. execute query
  4. if auditSink != null:
       try {
         auditSink.record(success or error event)
       } catch (AuditException e) {
         log.error("Audit write failed, discarding query result")
         return error("Audit recording failed — query result withheld")
       }
  5. return result
```

**Key behavior:** At step 4, if the query succeeded but the audit write fails, the result is discarded and an error is returned. This is "no audit, no access." At step 2, audit failure on a denied query is best-effort (the query was already blocked).

---

## Integration: AdminApi

**CallerIdentity for admin requests:**

Add `CallerIdentity.fromAdminToken(String sourceIp)` factory:
```java
public static CallerIdentity fromAdminToken(String sourceIp) {
    return new CallerIdentity("admin", "Admin", "admin-token",
            "streamable-http", sourceIp, null, null, List.of());
}
```

**Three audit points in AdminApi:**

1. **Auth failure** — When bearer token check fails, record `AuditEvent` with `action="admin:authFailure"`, `authorized=false`, `resultStatus="denied"`, `sourceIp` from request. Best-effort (don't block the 401 response on audit failure).

2. **Mutating endpoints** — After execution of:
   - `POST /admin/repos` → `action="admin:addRepo"`
   - `DELETE /admin/repos/:name` → `action="admin:deleteRepo"`
   - `POST /admin/repos/:name/reindex` → `action="admin:reindex"`
   - `POST /admin/events/:id/retry` → `action="admin:retryEvent"`

   Record with `authorized=true`, `resultStatus="success"` or `"error"`. If audit write fails after a successful admin action, log a critical error — the action already happened. The hash chain gap is itself evidence.

3. **AuditSink wiring** — `AdminApi` constructor gains an `AuditSink` parameter (nullable for backward compat).

---

## Audit Query MCP Tools

### `query_audit_log`

**Access control:** Stdio callers always have access. API key callers require `auditReader: true` in config. OAuth callers are blocked. Access check happens in the tool handler before calling executeQuery.

**Parameters:**

| Parameter | Type | Required | Default |
|-----------|------|----------|---------|
| `caller_hash` | string | no | all callers |
| `action` | string | no | all actions |
| `repo` | string | no | all repos |
| `result_status` | string | no | all statuses |
| `since` | string (ISO 8601) | no | no lower bound |
| `until` | string (ISO 8601) | no | no upper bound |
| `limit` | int | no | 50 (max 500) |

**Returns:** Audit events with identity resolved from `audit_identity_map`. If GDPR-erased, `caller_hash` is returned without userId/displayName fields.

**Self-auditing:** Calls to `query_audit_log` are themselves audited (via executeQuery). An auditor querying the log creates an audit record of that query.

### `verify_audit_chain`

**Access control:** Same as `query_audit_log`.

**Parameters:**

| Parameter | Type | Required | Default |
|-----------|------|----------|---------|
| `count` | int | no | 100 (max 1000) |

**Returns:** Verification result: number of events checked, whether chain is intact, and if broken, the event ID and position where the break occurred.

**Implementation:** Reads the last N events ordered by id, recomputes each `chain_hash` from the previous hash and row data, compares to stored hash. Reports first break if any.

---

## Config Extension

**`ApiKeyEntry` gains `auditReader` field:**

```java
public record ApiKeyEntry(String key, String id, String name, boolean auditReader) {
    public ApiKeyEntry {
        // auditReader defaults to false via record default
    }
}
```

**ConfigLoader update:** Parse `auditReader` from API key YAML entries. Default to false if absent.

```yaml
auth:
  apiKeys:
    - key: ${AUDIT_KEY}
      id: compliance-team
      name: Compliance Team
      auditReader: true
    - key: ${DEV_KEY}
      id: dev-team
      name: Dev Team
```

**AuditReader access lookup:** The tool handler checks the caller's API key config for `auditReader: true`. This means the `ApiKeyAuthenticator.authenticate()` return value or a lookup by key ID is needed in the tool handler. Simplest approach: pass the `auditReader` flag through to `CallerIdentity` as a transient field, or have the tool handler call back to the authenticator. The former avoids coupling the tool handler to the authenticator.

**Decision:** Add an `auditReader` boolean to `CallerIdentity`. Factory methods default to false. `fromApiKey` passes through the config value. `fromStdio` sets true. `fromOAuth` and `anonymous` set false.

---

## Error Handling Matrix

| Scenario | Behavior |
|----------|----------|
| Query succeeds, audit succeeds | Return result |
| Query succeeds, audit fails | Discard result, return error |
| Query fails, audit succeeds | Return query error |
| Query fails, audit fails | Return audit error (takes precedence) |
| Auth denied, audit succeeds | Return denied error |
| Auth denied, audit fails | Return denied error, log warning |
| Admin action succeeds, audit succeeds | Return success |
| Admin action succeeds, audit fails | Return 500, log critical error |
| Admin auth fails, audit succeeds | Return 401 |
| Admin auth fails, audit fails | Return 401, log warning |

---

## Hash Chain Verification

**Genesis:** Migration seeds `audit_chain_state.last_hash` with `SHA-256("genesis")`.

**Chain break semantics:** A break means either tampering or a database restore. Per the brainstorming session decision: "Chain break = evidence of restore. Document, don't prevent." The `verify_audit_chain` tool reports breaks but does not block system operation.

**Multi-instance safety:** `SELECT ... FOR UPDATE` on `audit_chain_state` serializes all writers. At MCP query throughput (tens/sec), this is not a bottleneck. If throughput needs grow, the `AuditSink` interface allows swapping to an advisory-lock or partitioned implementation without changing callers.

---

## Scope Boundaries

**In scope:**
- Audit table + identity map + chain state (V3 migration)
- AuditSink interface + PostgresAuditSink
- AuditEvent record with CallerIdentity integration
- executeQuery audit integration (fail-closed)
- AdminApi audit integration (mutating actions + auth failures)
- query_audit_log MCP tool
- verify_audit_chain MCP tool
- Config extension for auditReader on API keys
- CallerIdentity auditReader field

**Out of scope:**
- Separate DB role for audit (application-enforced only)
- Retention/partitioning (infrastructure concern)
- Audit log export/streaming
- UI for audit queries (use MCP tools via Claude Code)
