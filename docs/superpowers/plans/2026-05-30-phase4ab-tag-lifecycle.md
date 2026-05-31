# Phase 4 (A+B) — Tag Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire GitHub tag-push indexing (glob-gated, name-collision-safe via a persisted ref kind) and give immutable refs (tags/SHAs) their own retention window plus an admin pin/unpin escape hatch.

**Architecture:** Two coordinated changes on top of the existing "any ref" overlay model. **(A)** The GitHub webhook learns to parse `refs/tags/...`, gate on a `tags.pattern` glob, and enqueue an indexing event carrying a new `ref_kind` column; the poller reads that column instead of hardcoding `RefKind.BRANCH`. **(B)** `BranchCleanupTask` becomes ref-kind-aware (14d branches, 90d tags/SHAs) and pin-aware (a new `pinned` column, exempt from cleanup), with `POST`/`DELETE /admin/repos/{name}/refs/{ref}/pin` endpoints.

**Tech Stack:** Java 21, Gradle, JDBI 3, Flyway, Javalin, JUnit 5 + AssertJ + Mockito, Testcontainers (PostgreSQL 16). Integration tests are `@Tag("integration")`, run via `./gradlew integrationTest` (needs Docker). Pure-unit tests run via `./gradlew test`.

**Spec:** `docs/superpowers/specs/2026-05-30-phase4-tag-lifecycle-design.md` (Sections A + B; Section C — default-branch generalization — is a separate plan/PR).

**Out of scope (deferred to the Section C plan):** the `"main"` literal in `QueryExecutor`/`effectiveFilesCte`/`diffFromMain`. This plan keeps the existing `main`-based base layer untouched.

---

## File Structure

**Section A — tag-push triggering**
- `src/main/resources/db/migration/V7__indexing_event_ref_kind.sql` — *create*: add `ref_kind` to `indexing_events`.
- `src/main/java/com/indexer/model/IndexingEvent.java` — *modify*: add `String refKind` field.
- `src/main/java/com/indexer/db/EventDao.java` — *modify*: extract a shared row-mapper (DRY the 5 duplicates), thread `ref_kind` through `insert` (new 7-arg overload) and the mapper.
- `src/main/java/com/indexer/webhook/GitHubPushPayload.java` — *modify*: add `tag()` parsing.
- `src/main/java/com/indexer/config/IndexerConfig.java` — *modify*: add `TagConfig` (autoIndex, pattern + glob matcher) and a `tags` field with sane defaults.
- `src/main/java/com/indexer/webhook/GitHubWebhookApi.java` — *modify*: handle tag pushes (gate + glob), accept `TagConfig`, enqueue with `RefKind`.
- `src/main/java/com/indexer/Application.java` — *modify*: pass `config.tags()` to the webhook; poller reads `RefKind.fromDb(event.refKind())`.

**Section B — retention + pinning**
- `src/main/resources/db/migration/V8__branch_index_pinned.sql` — *create*: add `pinned` to `branch_index`.
- `src/main/java/com/indexer/model/BranchIndex.java` — *modify*: add `boolean pinned`.
- `src/main/java/com/indexer/db/BranchIndexDao.java` — *modify*: map `pinned`; rewrite `findExpired` (two TTLs, pin-aware); add `setPinned`.
- `src/main/java/com/indexer/config/IndexerConfig.java` — *modify*: add `immutableRefTtlDays` to `BranchConfig`.
- `src/main/java/com/indexer/indexing/BranchCleanupTask.java` — *modify*: accept two TTLs.
- `src/main/java/com/indexer/admin/AdminService.java` — *modify*: add `BranchIndexDao` dependency + `pinRef`/`unpinRef`.
- `src/main/java/com/indexer/admin/AdminApi.java` — *modify*: register + handle pin/unpin routes.

**Tests**
- `src/test/java/com/indexer/db/EventDaoRefKindTest.java` — *create* (integration).
- `src/test/java/com/indexer/webhook/GitHubPushPayloadTest.java` — *create* (unit).
- `src/test/java/com/indexer/config/TagConfigTest.java` — *create* (unit).
- `src/test/java/com/indexer/webhook/GitHubWebhookApiIntegrationTest.java` — *modify*: add tag-push cases incl. the name-collision regression; fix constructor call.
- `src/test/java/com/indexer/db/BranchIndexDaoTest.java` — *modify*: pin + ref-kind-aware expiry cases.
- `src/test/java/com/indexer/admin/AdminApiTest.java` — *modify*: pin/unpin route cases.
- `src/test/java/com/indexer/admin/AdminServiceTest.java` — *modify*: fix constructor call (new `BranchIndexDao` arg).

---

# SECTION A — Tag-push triggering

## Task A1: V7 migration — add `ref_kind` to `indexing_events`

**Files:**
- Create: `src/main/resources/db/migration/V7__indexing_event_ref_kind.sql`

- [ ] **Step 1: Write the migration**

```sql
-- Phase 4A: persist the kind of git ref an indexing event targets so the poller
-- can index a tag/SHA correctly instead of hardcoding BRANCH. 'branch' default keeps
-- existing rows and the generic /webhook producer backward-compatible.
ALTER TABLE indexing_events
    ADD COLUMN ref_kind TEXT NOT NULL DEFAULT 'branch';
```

- [ ] **Step 2: Verify it applies on a clean DB**

Run: `./gradlew test --tests com.indexer.config.ConfigLoaderTest` is unrelated; instead compile + let a Testcontainers test drive Flyway. Quick check:
Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL (migration is picked up by Flyway at test runtime in later tasks).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V7__indexing_event_ref_kind.sql
git commit -m "feat(db): V7 add ref_kind to indexing_events"
```

## Task A2: Thread `ref_kind` through `IndexingEvent` + `EventDao`

**Files:**
- Modify: `src/main/java/com/indexer/model/IndexingEvent.java`
- Modify: `src/main/java/com/indexer/db/EventDao.java`
- Test: `src/test/java/com/indexer/db/EventDaoRefKindTest.java` (create)

- [ ] **Step 1: Write the failing test**

```java
package com.indexer.db;

import com.indexer.model.IndexingEvent;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class EventDaoRefKindTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private EventDao dao;

    @BeforeEach
    void setUp() {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        Jdbi jdbi = dbManager.getJdbi();
        jdbi.useHandle(h -> h.execute("DELETE FROM indexing_events"));
        dao = new EventDao(jdbi);
    }

    @Test
    void tagInsertRoundTripsRefKind() {
        dao.insert("cml", "/tmp/cml", "github_push", "old", "new", "v1.0", "tag");
        IndexingEvent claimed = dao.claimNextPending("w1").orElseThrow();
        assertThat(claimed.branch()).isEqualTo("v1.0");
        assertThat(claimed.refKind()).isEqualTo("tag");
    }

    @Test
    void legacySixArgInsertDefaultsToBranch() {
        dao.insert("cml", "/tmp/cml", "github_push", "old", "new", "main");
        IndexingEvent claimed = dao.claimNextPending("w1").orElseThrow();
        assertThat(claimed.refKind()).isEqualTo("branch");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew integrationTest --tests com.indexer.db.EventDaoRefKindTest`
Expected: COMPILE FAILURE — `IndexingEvent` has no `refKind()` accessor and `insert(...)` has no 7-arg form.

- [ ] **Step 3: Add `refKind` to the record**

In `src/main/java/com/indexer/model/IndexingEvent.java`, add `refKind` right after `branch`:

```java
package com.indexer.model;

import java.time.Instant;

public record IndexingEvent(long id, String repoName, String repoPath, String eventType, String previousSha, String currentSha, String branch, String refKind, String status, String errorMessage, Instant createdAt, Instant startedAt, Instant completedAt, String workerId) {}
```

- [ ] **Step 4: DRY the EventDao row-mapper, then thread `ref_kind`**

In `src/main/java/com/indexer/db/EventDao.java`, add a single shared mapper and reuse it in all five query sites (`claimNextPending`, `findPendingByRepo`, `findRecentFailed`, `findById`, `findFiltered`). Add this private method:

```java
    private static IndexingEvent mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new IndexingEvent(
                rs.getLong("id"),
                rs.getString("repo_name"),
                rs.getString("repo_path"),
                rs.getString("event_type"),
                rs.getString("previous_sha"),
                rs.getString("current_sha"),
                rs.getString("branch"),
                rs.getString("ref_kind"),
                rs.getString("status"),
                rs.getString("error_message"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant() : null,
                rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toInstant() : null,
                rs.getString("worker_id"));
    }
```

Replace each of the five `.map((rs, ctx) -> new IndexingEvent(...))` blocks with `.map((rs, ctx) -> mapRow(rs))`.

- [ ] **Step 5: Add the 7-arg `insert` and keep the 6-arg as a delegating overload**

Replace the existing `insert(...)` method with:

```java
    /** Backward-compatible overload — defaults ref_kind to 'branch' (used by the generic /webhook). */
    public long insert(String repoName, String repoPath, String eventType, String previousSha, String currentSha, String branch) {
        return insert(repoName, repoPath, eventType, previousSha, currentSha, branch, "branch");
    }

    public long insert(String repoName, String repoPath, String eventType, String previousSha, String currentSha, String branch, String refKind) {
        return jdbi.withHandle(handle ->
                handle.createUpdate("""
                        INSERT INTO indexing_events (repo_name, repo_path, event_type, previous_sha, current_sha, branch, ref_kind)
                        VALUES (:repoName, :repoPath, :eventType, :previousSha, :currentSha, :branch, :refKind)
                        """)
                        .bind("repoName", repoName)
                        .bind("repoPath", repoPath)
                        .bind("eventType", eventType)
                        .bind("previousSha", previousSha)
                        .bind("currentSha", currentSha)
                        .bind("branch", branch != null ? branch : "main")
                        .bind("refKind", refKind != null ? refKind : "branch")
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one()
        );
    }
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew integrationTest --tests com.indexer.db.EventDaoRefKindTest`
Expected: PASS (2 tests).

- [ ] **Step 7: Compile the whole module to catch other IndexingEvent constructors**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL. (If a test elsewhere constructs `IndexingEvent` positionally, add the `refKind` argument there. The mapper change keeps EventDao internal callers green.)

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/indexer/model/IndexingEvent.java src/main/java/com/indexer/db/EventDao.java src/test/java/com/indexer/db/EventDaoRefKindTest.java
git commit -m "feat(queue): thread ref_kind through IndexingEvent and EventDao"
```

## Task A3: Parse tag refs in `GitHubPushPayload`

**Files:**
- Modify: `src/main/java/com/indexer/webhook/GitHubPushPayload.java`
- Test: `src/test/java/com/indexer/webhook/GitHubPushPayloadTest.java` (create)

- [ ] **Step 1: Write the failing test**

```java
package com.indexer.webhook;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GitHubPushPayloadTest {

    @Test
    void parsesBranchRef() {
        var p = new GitHubPushPayload("refs/heads/main", "a", "b", null);
        assertThat(p.branch()).isEqualTo("main");
        assertThat(p.tag()).isNull();
    }

    @Test
    void parsesTagRef() {
        var p = new GitHubPushPayload("refs/tags/v1.2.3", "a", "b", null);
        assertThat(p.tag()).isEqualTo("v1.2.3");
        assertThat(p.branch()).isNull();
    }

    @Test
    void unknownRefIsNeitherBranchNorTag() {
        var p = new GitHubPushPayload("refs/notes/commits", "a", "b", null);
        assertThat(p.branch()).isNull();
        assertThat(p.tag()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.indexer.webhook.GitHubPushPayloadTest`
Expected: COMPILE FAILURE — no `tag()` method.

- [ ] **Step 3: Add `tag()` parsing**

In `src/main/java/com/indexer/webhook/GitHubPushPayload.java`, add the tags prefix constant and accessor next to `branch()`:

```java
    private static final String TAGS_PREFIX = "refs/tags/";

    /** The tag name from {@code ref}, or null if the ref is not a tag. */
    public String tag() {
        return (ref != null && ref.startsWith(TAGS_PREFIX))
                ? ref.substring(TAGS_PREFIX.length())
                : null;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.indexer.webhook.GitHubPushPayloadTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/webhook/GitHubPushPayload.java src/test/java/com/indexer/webhook/GitHubPushPayloadTest.java
git commit -m "feat(webhook): parse refs/tags/ in GitHubPushPayload"
```

## Task A4: Add `TagConfig` (gate + glob)

**Files:**
- Modify: `src/main/java/com/indexer/config/IndexerConfig.java`
- Test: `src/test/java/com/indexer/config/TagConfigTest.java` (create)

- [ ] **Step 1: Write the failing test**

```java
package com.indexer.config;

import com.indexer.config.IndexerConfig.TagConfig;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TagConfigTest {

    @Test
    void blankPatternDefaultsToV() {
        assertThat(new TagConfig(true, null).pattern()).isEqualTo("v*");
        assertThat(new TagConfig(true, "  ").pattern()).isEqualTo("v*");
    }

    @Test
    void globMatchesSemverStyleTags() {
        var cfg = new TagConfig(true, "v*");
        assertThat(cfg.matches("v1.2.3")).isTrue();
        assertThat(cfg.matches("v2.0")).isTrue();
        assertThat(cfg.matches("nightly-2026")).isFalse();
    }

    @Test
    void questionMarkMatchesSingleChar() {
        var cfg = new TagConfig(true, "rc?");
        assertThat(cfg.matches("rc1")).isTrue();
        assertThat(cfg.matches("rc12")).isFalse();
    }

    @Test
    void dotsAreLiteralNotWildcards() {
        var cfg = new TagConfig(true, "v1.0");
        assertThat(cfg.matches("v1.0")).isTrue();
        assertThat(cfg.matches("v1x0")).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.indexer.config.TagConfigTest`
Expected: COMPILE FAILURE — no `TagConfig`.

- [ ] **Step 3: Add the `TagConfig` record and wire it into `IndexerConfig`**

In `src/main/java/com/indexer/config/IndexerConfig.java`, add `TagConfig tags` to the top-level record components (after `branches`):

```java
        BranchConfig branches,
        TagConfig tags,
        ScipConfig scip,
```

Add the record (place it next to `BranchConfig`):

```java
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TagConfig(boolean autoIndex, String pattern) {
        public TagConfig {
            if (pattern == null || pattern.isBlank()) pattern = "v*";
        }

        /** True when {@code tagName} matches the glob (only {@code *} and {@code ?} are wildcards). */
        public boolean matches(String tagName) {
            return tagName != null && tagName.matches(globToRegex(pattern));
        }

        private static String globToRegex(String glob) {
            StringBuilder sb = new StringBuilder("^");
            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);
                switch (c) {
                    case '*' -> sb.append(".*");
                    case '?' -> sb.append('.');
                    case '.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|' -> sb.append('\\').append(c);
                    default -> sb.append(c);
                }
            }
            return sb.append('$').toString();
        }
    }
```

In the `IndexerConfig` compact constructor, default `tags` when absent (so `autoIndex` defaults to true only when the whole block is omitted):

```java
        if (branches == null) branches = new BranchConfig(true, 14, 90, 24);
        if (tags == null) tags = new TagConfig(true, "v*");
        if (scip == null) scip = new ScipConfig(7);
```

> Note: `BranchConfig` gains a 4th component (`immutableRefTtlDays`) in Task B3. If you implement A before B, temporarily use the current 3-arg form `new BranchConfig(true, 14, 24)` here and update it in B3. (Recommended: do Task B3 immediately after this task to avoid the churn.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.indexer.config.TagConfigTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/config/IndexerConfig.java src/test/java/com/indexer/config/TagConfigTest.java
git commit -m "feat(config): add tags.autoIndex + tags.pattern glob"
```

## Task A5: Handle tag pushes in `GitHubWebhookApi` (incl. name-collision regression)

**Files:**
- Modify: `src/main/java/com/indexer/webhook/GitHubWebhookApi.java`
- Test: `src/test/java/com/indexer/webhook/GitHubWebhookApiIntegrationTest.java`

- [ ] **Step 1: Add failing tests for the tag path**

In `GitHubWebhookApiIntegrationTest`, the webhook is constructed at line ~66 as
`new GitHubWebhookApi(Map.of("cml", SECRET), repositoryDao, eventDao, auditSink)`. Update that call to pass a `TagConfig` (autoIndex on, default `v*`):

```java
        var api = new GitHubWebhookApi(Map.of("cml", SECRET), repositoryDao, eventDao, auditSink,
                new com.indexer.config.IndexerConfig.TagConfig(true, "v*"));
```

Add an import for `IndexingEvent` (`import com.indexer.model.IndexingEvent;`) and these tests:

```java
    @Test
    void matchingTagPushEnqueuesWithTagRefKind() throws Exception {
        String body = "{\"ref\":\"refs/tags/v1.0\",\"before\":\"old\",\"after\":\"new\",\"repository\":{\"name\":\"cml\"}}";
        var resp = post("/webhook/github/cml", body, "push", sign(body));
        assertThat(resp.statusCode()).isEqualTo(202);
        IndexingEvent ev = eventDao.claimNextPending("w").orElseThrow();
        assertThat(ev.branch()).isEqualTo("v1.0");
        assertThat(ev.refKind()).isEqualTo("tag");
    }

    @Test
    void offPatternTagIsAcceptedButNotEnqueued() throws Exception {
        String body = "{\"ref\":\"refs/tags/nightly-1\",\"before\":\"old\",\"after\":\"new\",\"repository\":{\"name\":\"cml\"}}";
        var resp = post("/webhook/github/cml", body, "push", sign(body));
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(eventDao.countByStatus("pending")).isEqualTo(0);
    }

    @Test
    void tagDeletionIsNoOp() throws Exception {
        String body = "{\"ref\":\"refs/tags/v9.9\",\"before\":\"old\","
                + "\"after\":\"0000000000000000000000000000000000000000\",\"repository\":{\"name\":\"cml\"}}";
        var resp = post("/webhook/github/cml", body, "push", sign(body));
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(eventDao.countByStatus("pending")).isEqualTo(0);
    }

    @Test
    void nameCollisionTagPushIsRecordedAsTagNotBranch() throws Exception {
        // Regression: repo's configured branch is "main"; a tag named "main" is pushed.
        // The push event authoritatively says refs/tags/ -> must persist ref_kind=tag,
        // NOT branch (which is what resolveAnyRef would have wrongly inferred).
        String body = "{\"ref\":\"refs/tags/main\",\"before\":\"old\",\"after\":\"new\",\"repository\":{\"name\":\"cml\"}}";
        var resp = post("/webhook/github/cml", body, "push", sign(body));
        assertThat(resp.statusCode()).isEqualTo(202);
        IndexingEvent ev = eventDao.claimNextPending("w").orElseThrow();
        assertThat(ev.refKind()).isEqualTo("tag");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew integrationTest --tests com.indexer.webhook.GitHubWebhookApiIntegrationTest`
Expected: COMPILE FAILURE — the 5-arg constructor doesn't exist yet.

- [ ] **Step 3: Add `TagConfig` to the constructor and tag handling**

In `src/main/java/com/indexer/webhook/GitHubWebhookApi.java`:

Add imports:
```java
import com.indexer.config.IndexerConfig.TagConfig;
import com.indexer.repository.RefKind;
```

Add the field and update the constructor:
```java
    private final TagConfig tags;

    public GitHubWebhookApi(Map<String, String> webhookSecrets, RepositoryDao repositoryDao,
                            EventDao eventDao, AuditSink auditSink, TagConfig tags) {
        this.webhookSecrets = webhookSecrets;
        this.repositoryDao = repositoryDao;
        this.eventDao = eventDao;
        this.auditSink = auditSink;
        this.tags = tags;
    }
```

Replace the branch-handling block (currently lines ~84-100, from `String branch = payload.branch();` through the `ctx.status(202)` enqueue) with:

```java
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
```

(The closing brace shown above ends `handle`; ensure you don't duplicate the original method's closing brace.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew integrationTest --tests com.indexer.webhook.GitHubWebhookApiIntegrationTest`
Expected: PASS — all existing branch/ping/deletion/auth cases plus the 4 new tag cases.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/webhook/GitHubWebhookApi.java src/test/java/com/indexer/webhook/GitHubWebhookApiIntegrationTest.java
git commit -m "feat(webhook): index matching tag pushes; persist ref_kind (name-collision-safe)"
```

## Task A6: Poller uses the persisted ref kind

**Files:**
- Modify: `src/main/java/com/indexer/queue/EventQueuePoller.java`
- Modify: `src/main/java/com/indexer/Application.java`

> **Note (found during A2 review):** the poller's `eventProcessor` lambda in `Application.java` receives an `EventQueuePoller.ProcessableEvent`, NOT an `IndexingEvent`. `ProcessableEvent` does not yet carry `ref_kind`, so it must be threaded there first (Step 0) before the lambda can read it.

- [ ] **Step 0: Thread `refKind` through `ProcessableEvent`**

In `src/main/java/com/indexer/queue/EventQueuePoller.java`, add `refKind` to the record (after `branch`):

```java
    public record ProcessableEvent(long eventId, String repoName, String repoPath, String previousSha, String currentSha, String branch, String refKind) {}
```

Then set it from the primary event at BOTH construction sites in `run()`. The no-additional-pending site:

```java
                    processableEvent = new ProcessableEvent(
                            primary.id(),
                            primary.repoName(),
                            primary.repoPath(),
                            primary.previousSha(),
                            primary.currentSha(),
                            primary.branch(),
                            primary.refKind()
                    );
```

The deduplicated site (note it keeps `collapsed.previousSha()/currentSha()` and `primary.branch()`/`primary.refKind()`):

```java
                    processableEvent = new ProcessableEvent(
                            primary.id(),
                            primary.repoName(),
                            primary.repoPath(),
                            collapsed.previousSha(),
                            collapsed.currentSha(),
                            primary.branch(),
                            primary.refKind()
                    );
```

- [ ] **Step 1: Pass `TagConfig` into the webhook construction**

At the `GitHubWebhookApi` construction (around line 213), add `config.tags()`:

```java
            var githubWebhookApi = new com.indexer.webhook.GitHubWebhookApi(
                    webhookSecrets, repositoryDao, eventDao, auditSink, config.tags());
```

- [ ] **Step 2: Replace the hardcoded `RefKind.BRANCH` in the poller**

In the poller lambda (around line 250), replace the TODO + hardcoded call:

```java
                    } else {
                        // Feature branch / tag / sha: fetch, then delta-index from main using the kind the event recorded.
                        gitOps.fetch(Path.of(repo.clonePath()), null);
                        indexingPipeline.branchIndex(repo.id(), branch, Path.of(repo.clonePath()),
                                event.currentSha(), RefKind.fromDb(event.refKind()));
                    }
```

(`RefKind` is already imported in `Application.java`.)

- [ ] **Step 3: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Full build (the lambda has no isolated unit test — the round-trip in A2 + webhook tests in A5 prove the kind is persisted and available; this step proves the wiring compiles and the suite is green)**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (unit tests; integration covered above).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/queue/EventQueuePoller.java src/main/java/com/indexer/Application.java
git commit -m "feat(poller): index with event ref_kind instead of hardcoded BRANCH"
```

---

# SECTION B — Retention + pinning

## Task B1: V8 migration — add `pinned` to `branch_index`

**Files:**
- Create: `src/main/resources/db/migration/V8__branch_index_pinned.sql`

- [ ] **Step 1: Write the migration**

```sql
-- Phase 4B: pin flag exempts a branch_index row from TTL cleanup regardless of kind.
ALTER TABLE branch_index
    ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE;
```

- [ ] **Step 2: Compile (Flyway runs at test time)**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V8__branch_index_pinned.sql
git commit -m "feat(db): V8 add pinned to branch_index"
```

## Task B2: `BranchIndex.pinned` + DAO mapping, `setPinned`, ref-kind-aware `findExpired`

**Files:**
- Modify: `src/main/java/com/indexer/model/BranchIndex.java`
- Modify: `src/main/java/com/indexer/db/BranchIndexDao.java`
- Test: `src/test/java/com/indexer/db/BranchIndexDaoTest.java`

- [ ] **Step 1: Write failing tests**

Add to `BranchIndexDaoTest`. First, the helper to backdate `last_accessed_at` and a `Jdbi` handle — add a field `private Jdbi jdbi;` and assign it in `setUp` (`this.jdbi = jdbi;` after the local is created). Then:

```java
    @Test
    void setPinnedTogglesAndIsReturned() {
        dao.upsert(repoId, "v1.0", "m", "s", "tag");
        assertThat(dao.find(repoId, "v1.0").orElseThrow().pinned()).isFalse();
        int updated = dao.setPinned(repoId, "v1.0", true);
        assertThat(updated).isEqualTo(1);
        assertThat(dao.find(repoId, "v1.0").orElseThrow().pinned()).isTrue();
    }

    @Test
    void setPinnedOnMissingRefReturnsZero() {
        assertThat(dao.setPinned(repoId, "ghost", true)).isEqualTo(0);
    }

    @Test
    void expiryIsRefKindAwareAndPinAware() {
        // Three rows, all last accessed 20 days ago.
        dao.upsert(repoId, "feature/x", "m", "s1", "branch");
        dao.upsert(repoId, "v1.0", "m", "s2", "tag");
        dao.upsert(repoId, "pinnedtag", "m", "s3", "tag");
        dao.setPinned(repoId, "pinnedtag", true);
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE branch_index SET last_accessed_at = NOW() - INTERVAL '20 days' WHERE repo_id = :r")
                .bind("r", repoId).execute());

        // branchTtl=14, immutableTtl=90: the 20-day-old branch expires; the tags don't (and pinned never would).
        var expired = dao.findExpired(14, 90);
        assertThat(expired).extracting(bi -> bi.branch()).containsExactly("feature/x");
    }

    @Test
    void pinnedBranchSurvivesEvenPastBranchTtl() {
        dao.upsert(repoId, "longlived", "m", "s", "branch");
        dao.setPinned(repoId, "longlived", true);
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE branch_index SET last_accessed_at = NOW() - INTERVAL '400 days' WHERE repo_id = :r")
                .bind("r", repoId).execute());
        assertThat(dao.findExpired(14, 90)).isEmpty();
    }
```

Also update the existing `setUp` cleanup if needed (it already deletes `branch_index`).

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew integrationTest --tests com.indexer.db.BranchIndexDaoTest`
Expected: COMPILE FAILURE — `pinned()`, `setPinned`, and the 2-arg `findExpired` don't exist.

- [ ] **Step 3: Add `pinned` to the record**

`src/main/java/com/indexer/model/BranchIndex.java`:

```java
package com.indexer.model;

import com.indexer.repository.RefKind;
import java.time.Instant;

public record BranchIndex(int id, int repoId, String branch, String baseSha, String indexedSha,
                          Instant indexedAt, Instant lastAccessedAt, RefKind refKind, boolean pinned) {}
```

- [ ] **Step 4: Map `pinned`, rewrite `findExpired`, add `setPinned`**

In `BranchIndexDao.java`, update BOTH mappers (`find` and `findExpired`) to append `rs.getBoolean("pinned")` as the final constructor argument:

```java
                        .map((rs, ctx) -> new BranchIndex(
                                rs.getInt("id"),
                                rs.getInt("repo_id"),
                                rs.getString("branch"),
                                rs.getString("base_sha"),
                                rs.getString("indexed_sha"),
                                rs.getTimestamp("indexed_at").toInstant(),
                                rs.getTimestamp("last_accessed_at").toInstant(),
                                RefKind.fromDb(rs.getString("ref_kind")),
                                rs.getBoolean("pinned")
                        ))
```

Replace `findExpired(int ttlDays)` with the two-TTL, pin-aware version:

```java
    public List<BranchIndex> findExpired(int branchTtlDays, int immutableTtlDays) {
        return jdbi.withHandle(handle ->
                handle.createQuery("""
                        SELECT * FROM branch_index
                        WHERE pinned = FALSE
                          AND (
                                (ref_kind = 'branch'       AND last_accessed_at < NOW() - CAST(:branchTtl   || ' days' AS INTERVAL))
                             OR (ref_kind IN ('tag','sha') AND last_accessed_at < NOW() - CAST(:immutableTtl || ' days' AS INTERVAL))
                              )
                        """)
                        .bind("branchTtl", branchTtlDays)
                        .bind("immutableTtl", immutableTtlDays)
                        .map((rs, ctx) -> new BranchIndex(
                                rs.getInt("id"),
                                rs.getInt("repo_id"),
                                rs.getString("branch"),
                                rs.getString("base_sha"),
                                rs.getString("indexed_sha"),
                                rs.getTimestamp("indexed_at").toInstant(),
                                rs.getTimestamp("last_accessed_at").toInstant(),
                                RefKind.fromDb(rs.getString("ref_kind")),
                                rs.getBoolean("pinned")
                        ))
                        .list()
        );
    }

    public int setPinned(int repoId, String branch, boolean pinned) {
        return jdbi.withHandle(handle ->
                handle.createUpdate("UPDATE branch_index SET pinned = :pinned WHERE repo_id = :repoId AND branch = :branch")
                        .bind("pinned", pinned)
                        .bind("repoId", repoId)
                        .bind("branch", branch)
                        .execute()
        );
    }
```

> Note: `upsert` is intentionally left unchanged — its `ON CONFLICT` clause does not touch `pinned`, so re-indexing a pinned tag preserves the pin.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew integrationTest --tests com.indexer.db.BranchIndexDaoTest`
Expected: PASS (existing 2 + new 4).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/indexer/model/BranchIndex.java src/main/java/com/indexer/db/BranchIndexDao.java src/test/java/com/indexer/db/BranchIndexDaoTest.java
git commit -m "feat(retention): pinned column + ref-kind-aware pin-aware findExpired"
```

## Task B3: `BranchConfig.immutableRefTtlDays`

**Files:**
- Modify: `src/main/java/com/indexer/config/IndexerConfig.java`

- [ ] **Step 1: Add the field with a default**

Replace the `BranchConfig` record:

```java
    public record BranchConfig(boolean autoIndex, int ttlDays, int immutableRefTtlDays, int cleanupIntervalHours) {
        public BranchConfig {
            if (ttlDays <= 0) ttlDays = 14;
            if (immutableRefTtlDays <= 0) immutableRefTtlDays = 90;
            if (cleanupIntervalHours <= 0) cleanupIntervalHours = 24;
        }
    }
```

- [ ] **Step 2: Update the default construction in the `IndexerConfig` compact constructor**

```java
        if (branches == null) branches = new BranchConfig(true, 14, 90, 24);
```

- [ ] **Step 3: Compile + fix any other `BranchConfig(...)` constructions**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL after updating any test that built `BranchConfig` with the old 3-arg form to the 4-arg form (insert `90` as the 3rd arg). Search: `grep -rn "new BranchConfig(" src`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/indexer/config/IndexerConfig.java
git commit -m "feat(config): add branches.immutableRefTtlDays (default 90)"
```

## Task B4: `BranchCleanupTask` takes two TTLs

**Files:**
- Modify: `src/main/java/com/indexer/indexing/BranchCleanupTask.java`
- Modify: `src/main/java/com/indexer/Application.java`

- [ ] **Step 1: Update the task to accept and use both TTLs**

Replace the fields/constructor/`run` head in `BranchCleanupTask.java`:

```java
    private final BranchIndexDao branchIndexDao;
    private final FileDao fileDao;
    private final int branchTtlDays;
    private final int immutableTtlDays;

    public BranchCleanupTask(BranchIndexDao branchIndexDao, FileDao fileDao, int branchTtlDays, int immutableTtlDays) {
        this.branchIndexDao = branchIndexDao;
        this.fileDao = fileDao;
        this.branchTtlDays = branchTtlDays;
        this.immutableTtlDays = immutableTtlDays;
    }

    @Override
    public void run() {
        try {
            List<BranchIndex> expired = branchIndexDao.findExpired(branchTtlDays, immutableTtlDays);
            if (expired.isEmpty()) {
                log.debug("Branch cleanup: no expired refs (branchTTL={}d, immutableTTL={}d)", branchTtlDays, immutableTtlDays);
                return;
            }
            log.info("Branch cleanup: found {} expired refs (branchTTL={}d, immutableTTL={}d)",
                    expired.size(), branchTtlDays, immutableTtlDays);
```

(Leave the rest of `run()` — the per-row delete loop — unchanged.)

- [ ] **Step 2: Update the wiring in `Application.java`**

At the cleanup task construction (around line 261):

```java
            var cleanupTask = new BranchCleanupTask(branchIndexDao, fileDao,
                    config.branches().ttlDays(), config.branches().immutableRefTtlDays());
```

And the log line just after it:

```java
            log.info("Branch cleanup task scheduled every {}h (branchTTL={}d, immutableTTL={}d)",
                    config.branches().cleanupIntervalHours(),
                    config.branches().ttlDays(), config.branches().immutableRefTtlDays());
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/indexer/indexing/BranchCleanupTask.java src/main/java/com/indexer/Application.java
git commit -m "feat(retention): BranchCleanupTask honours branch + immutable TTLs"
```

## Task B5: Admin pin/unpin endpoints

**Files:**
- Modify: `src/main/java/com/indexer/admin/AdminService.java`
- Modify: `src/main/java/com/indexer/admin/AdminApi.java`
- Modify: `src/main/java/com/indexer/Application.java` (AdminService construction)
- Modify: `src/test/java/com/indexer/admin/AdminServiceTest.java` (constructor arg)
- Test: `src/test/java/com/indexer/admin/AdminApiTest.java`

- [ ] **Step 1: Write failing route tests**

In `AdminApiTest`, add pin/unpin cases (mirrors the existing mock-based style):

```java
    @Test
    void pinRefReturns200() {
        when(adminService.pinRef("cml", "v1.0")).thenReturn(Map.of("repo", "cml", "ref", "v1.0", "pinned", true));
        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/admin/repos/cml/refs/v1.0/pin", builder ->
                    builder.header("Authorization", "Bearer " + TOKEN).post(null));
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("\"pinned\":true");
        });
    }

    @Test
    void unpinRefReturns200() {
        when(adminService.unpinRef("cml", "v1.0")).thenReturn(Map.of("repo", "cml", "ref", "v1.0", "pinned", false));
        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/admin/repos/cml/refs/v1.0/pin", builder ->
                    builder.header("Authorization", "Bearer " + TOKEN).delete());
            assertThat(response.code()).isEqualTo(200);
        });
    }

    @Test
    void pinUnknownRefReturns404() {
        when(adminService.pinRef("cml", "ghost")).thenThrow(new AdminService.NotFoundException("Indexed ref not found: ghost"));
        JavalinTest.test(app, (server, client) -> {
            var response = client.request("/admin/repos/cml/refs/ghost/pin", builder ->
                    builder.header("Authorization", "Bearer " + TOKEN).post(null));
            assertThat(response.code()).isEqualTo(404);
        });
    }

    @Test
    void pinWithoutTokenReturns401() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.post("/admin/repos/cml/refs/v1.0/pin", "");
            assertThat(response.code()).isEqualTo(401);
        });
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests com.indexer.admin.AdminApiTest`
Expected: COMPILE FAILURE — `pinRef`/`unpinRef` don't exist on `AdminService`.

- [ ] **Step 3: Add `BranchIndexDao` to `AdminService` + the two methods**

In `AdminService.java`, add the field, constructor param, and methods. Add import `import com.indexer.db.BranchIndexDao;` (or rely on the existing `import com.indexer.db.*;`). Add the field after `eventDao`:

```java
    private final BranchIndexDao branchIndexDao;
```

Add `BranchIndexDao branchIndexDao` to the constructor signature (after `eventDao`) and assign it:

```java
        this.branchIndexDao = branchIndexDao;
```

Add the methods (next to `triggerReindex`):

```java
    public Map<String, Object> pinRef(String repoName, String ref) {
        return setPinned(repoName, ref, true);
    }

    public Map<String, Object> unpinRef(String repoName, String ref) {
        return setPinned(repoName, ref, false);
    }

    private Map<String, Object> setPinned(String repoName, String ref, boolean pinned) {
        var repo = repositoryDao.findByName(repoName)
                .orElseThrow(() -> new NotFoundException("Repository not found: " + repoName));
        int updated = branchIndexDao.setPinned(repo.id(), ref, pinned);
        if (updated == 0) {
            throw new NotFoundException("Indexed ref not found: " + ref);
        }
        return Map.of("repo", repoName, "ref", ref, "pinned", pinned);
    }
```

- [ ] **Step 4: Register + handle the routes in `AdminApi`**

In `registerRoutes`, add after the reindex route:

```java
        routes.post("/admin/repos/{name}/refs/{ref}/pin", this::pinRef);
        routes.delete("/admin/repos/{name}/refs/{ref}/pin", this::unpinRef);
```

Add the handlers (next to `reindexRepo`):

```java
    private void pinRef(Context ctx) {
        handlePin(ctx, true, "admin:pin");
    }

    private void unpinRef(Context ctx) {
        handlePin(ctx, false, "admin:unpin");
    }

    private void handlePin(Context ctx, boolean pinned, String action) {
        String name = ctx.pathParam("name");
        String ref = ctx.pathParam("ref");
        try {
            var result = pinned ? adminService.pinRef(name, ref) : adminService.unpinRef(name, ref);
            auditAdminBestEffort(ctx, action, true, "success", ref);
            ctx.json(result);
        } catch (AdminService.NotFoundException e) {
            auditAdminBestEffort(ctx, action, true, "error", e.getMessage());
            ctx.status(404).json(Map.of("error", e.getMessage()));
        }
    }
```

> Note: Javalin `{ref}` matches a single path segment, so this endpoint supports tags, SHAs, and slash-free branch names (the common pin targets). Refs containing `/` (e.g. `feature/x`) are not addressable here — acceptable for the immutable-ref use case; revisit with a `<ref>` wildcard route if branch pinning by slashed name is ever needed.

- [ ] **Step 5: Fix the `AdminService` construction sites**

In `Application.java` (around line 199), add `branchIndexDao` after `eventDao`:

```java
            adminService = new AdminService(repoManager, repositoryDao, fileDao, symbolDao,
                    eventDao, branchIndexDao, indexingPipeline, gitOps, queryExecutor, jdbi);
```

In `AdminServiceTest.java`, find the `new AdminService(...)` construction and add a mocked `BranchIndexDao` in the same position (after the `eventDao` mock). If the test mocks dependencies inline, add `mock(BranchIndexDao.class)` as the new argument and an import for `com.indexer.db.BranchIndexDao`.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests com.indexer.admin.AdminApiTest`
Expected: PASS (4 new + existing).
Run: `./gradlew compileTestJava`
Expected: BUILD SUCCESSFUL (AdminServiceTest constructor fixed).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/indexer/admin/AdminService.java src/main/java/com/indexer/admin/AdminApi.java src/main/java/com/indexer/Application.java src/test/java/com/indexer/admin/AdminApiTest.java src/test/java/com/indexer/admin/AdminServiceTest.java
git commit -m "feat(admin): pin/unpin endpoints for branch_index refs"
```

---

## Task FINAL: Docs + full green build

**Files:**
- Modify: `CLAUDE.md` (Branch Support config block + Admin API table)

- [ ] **Step 1: Update CLAUDE.md config + admin docs**

In the Branch Support → Configuration block, add `immutableRefTtlDays` and a `tags` block:

```yaml
branches:
  autoIndex: true
  ttlDays: 14
  immutableRefTtlDays: 90   # tags + SHAs (immutable refs)
  cleanupIntervalHours: 24

tags:
  autoIndex: true           # pre-warm matching tag pushes
  pattern: "v*"             # glob; non-matching tags index lazily on first query
```

In the Admin API endpoints table, add:

```
| `POST`   | `/admin/repos/:name/refs/:ref/pin` | Pin a ref (exempt from TTL cleanup) |
| `DELETE` | `/admin/repos/:name/refs/:ref/pin` | Unpin a ref |
```

Add a sentence under Branch Support: "Tags and SHAs are retained for `immutableRefTtlDays` (default 90, access-based); branches for `ttlDays` (14). Any indexed ref can be pinned via the admin API to exempt it from cleanup. Tag pushes matching `tags.pattern` are indexed automatically; others index lazily on first query."

- [ ] **Step 2: Run the complete suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (unit + integration + admin-ui assumptions per CI). If Docker is unavailable locally, run `./gradlew test` for unit and rely on CI for `integrationTest`.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: tag auto-index, immutable-ref TTL, and pin/unpin admin endpoints"
```

---

## Self-Review

**Spec coverage (Sections A + B):**
- A.1 webhook tag parse + gate + glob → Tasks A3, A4, A5. ✓
- A.2 thread ref_kind through queue (V7), poller reads it → Tasks A1, A2, A6. ✓
- A.3 lazy fault-in verify → existing behavior unchanged; the name-collision regression (A5) + round-trip (A2) cover the persisted-intent path. The "tag faulted-in but no SCIP → clean message" case is a `SemanticQueryTest`-style integration check **not** included here (it needs that harness read first); tracked below as a follow-up rather than bluffed into the plan.
- A.4 config defaults (autoIndex true, pattern v*) → Task A4. ✓
- B.1 V8 pinned column → Task B1. ✓
- B.2 ref-kind-aware + pin-aware findExpired → Task B2. ✓ (branch=ttlDays, tag/sha=immutableRefTtlDays)
- B.3 pin any ref kind → Task B2 (`setPinned` is kind-agnostic) + B5 endpoints. ✓
- B.4 admin POST/DELETE pin routes, bearer auth, audited → Task B5. ✓
- Config keys (`branches.immutableRefTtlDays`, `tags.*`) → Tasks B3, A4 + docs in FINAL. ✓

**Type consistency:** `IndexingEvent.refKind` is a raw DB `String`; converted at the poller via `RefKind.fromDb(...)` (A6) — consistent with `branch`/`status` being raw strings. `EventDao.insert` 7-arg takes a `String` ref kind; webhook passes `kind.dbValue()` (A5). `BranchIndex.pinned` is `boolean`; `setPinned` returns `int` rows-affected (0 → 404). `findExpired(int,int)` signature is updated at its only caller (`BranchCleanupTask`, B4). `BranchConfig` 4-arg form updated at its default-construction site and any test (B3 Step 3).

**Placeholder scan:** none — every code step shows the code; every run step shows the command + expected result.

**Known follow-up (not a gap in A+B):** the "faulted-in tag with no SCIP returns a clean message" verification is deferred to the Section C plan or a small standalone test PR, because it requires the `SemanticQueryTest` Testcontainers harness which this plan does not otherwise touch.
