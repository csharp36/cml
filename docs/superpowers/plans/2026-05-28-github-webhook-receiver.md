# GitHub Webhook Receiver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a signed GitHub `push` webhook receiver so the index stays in sync when changes merge to a repo's configured branch.

**Architecture:** A new `GitHubWebhookApi` endpoint class (mirroring the existing `ScipApi`) is registered on the shared Javalin server at `POST /webhook/github/{repoName}`. It verifies a per-repo HMAC-SHA256 signature, and on a valid push to the configured branch enqueues an event into the existing PostgreSQL queue. The existing poller does the fetch + incremental index — no new indexing path.

**Tech Stack:** Java 21, Javalin, Jackson, `javax.crypto.Mac` (HMAC-SHA256), PostgreSQL, JUnit 5 + AssertJ + Testcontainers.

**Spec:** `docs/superpowers/specs/2026-05-28-github-webhook-receiver-design.md`

---

## File Structure

- `src/main/java/com/indexer/webhook/GitHubWebhookVerifier.java` (new) — pure HMAC-SHA256 verification; no I/O.
- `src/main/java/com/indexer/webhook/GitHubPushPayload.java` (new) — Jackson DTO + branch/deletion helpers.
- `src/main/java/com/indexer/webhook/GitHubWebhookApi.java` (new) — endpoint: route, verify, parse, enqueue (mirrors `ScipApi`).
- `src/main/java/com/indexer/config/IndexerConfig.java` (modify) — add `webhookSecret` to `RepositoryConfig`.
- `src/main/java/com/indexer/config/ConfigLoader.java` (modify) — parse `webhookSecret`.
- `src/main/java/com/indexer/repository/RepositoryManager.java` (modify) — update `RepositoryConfig` constructor call.
- `src/main/java/com/indexer/Application.java` (modify) — build `repoName → secret` map; construct + register `GitHubWebhookApi`.
- `src/test/java/com/indexer/webhook/GitHubWebhookVerifierTest.java` (new).
- `src/test/java/com/indexer/webhook/GitHubPushPayloadTest.java` (new).
- `src/test/java/com/indexer/config/ConfigLoaderTest.java` (modify) — webhookSecret parse test.
- `src/test/java/com/indexer/repository/RepositoryManagerTest.java` (modify) — update constructor call.
- `src/test/java/com/indexer/webhook/GitHubWebhookApiIntegrationTest.java` (new) — Testcontainers end-to-end.
- `README.md`, `CLAUDE.md` (modify) — docs.

---

### Task 1: GitHubWebhookVerifier (pure HMAC verification)

**Files:**
- Create: `src/main/java/com/indexer/webhook/GitHubWebhookVerifier.java`
- Test: `src/test/java/com/indexer/webhook/GitHubWebhookVerifierTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/indexer/webhook/GitHubWebhookVerifierTest.java`:

```java
package com.indexer.webhook;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubWebhookVerifierTest {

    private static final String SECRET = "It's a Secret to Everybody";
    private static final byte[] BODY = "Hello, World!".getBytes(StandardCharsets.UTF_8);

    private static String sign(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void validSignaturePasses() throws Exception {
        assertThat(GitHubWebhookVerifier.isValid(BODY, SECRET, sign(BODY, SECRET))).isTrue();
    }

    @Test
    void tamperedBodyFails() throws Exception {
        String sig = sign(BODY, SECRET);
        byte[] tampered = "Hello, World?".getBytes(StandardCharsets.UTF_8);
        assertThat(GitHubWebhookVerifier.isValid(tampered, SECRET, sig)).isFalse();
    }

    @Test
    void wrongSecretFails() throws Exception {
        assertThat(GitHubWebhookVerifier.isValid(BODY, "wrong-secret", sign(BODY, SECRET))).isFalse();
    }

    @Test
    void missingHeaderFails() {
        assertThat(GitHubWebhookVerifier.isValid(BODY, SECRET, null)).isFalse();
    }

    @Test
    void malformedHeaderWithoutPrefixFails() {
        assertThat(GitHubWebhookVerifier.isValid(BODY, SECRET, "abc123")).isFalse();
    }

    @Test
    void blankSecretFails() throws Exception {
        assertThat(GitHubWebhookVerifier.isValid(BODY, "", sign(BODY, SECRET))).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.indexer.webhook.GitHubWebhookVerifierTest"`
Expected: FAIL — `GitHubWebhookVerifier` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/indexer/webhook/GitHubWebhookVerifier.java`:

```java
package com.indexer.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies GitHub webhook payloads using the per-repo HMAC-SHA256 shared secret.
 * GitHub sends the signature in the {@code X-Hub-Signature-256} header as
 * {@code sha256=<hex>} over the raw request body.
 */
public final class GitHubWebhookVerifier {

    private GitHubWebhookVerifier() {
    }

    /**
     * @param body            the raw request body bytes (must be the exact bytes received)
     * @param secret          the repo's configured webhook secret
     * @param signatureHeader the value of the X-Hub-Signature-256 header
     * @return true iff the signature header matches the HMAC of the body under the secret
     */
    public static boolean isValid(byte[] body, String secret, String signatureHeader) {
        if (body == null || secret == null || secret.isBlank()) {
            return false;
        }
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.indexer.webhook.GitHubWebhookVerifierTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/webhook/GitHubWebhookVerifier.java \
        src/test/java/com/indexer/webhook/GitHubWebhookVerifierTest.java
git commit -m "feat: add GitHub webhook HMAC-SHA256 verifier"
```

---

### Task 2: GitHubPushPayload DTO

**Files:**
- Create: `src/main/java/com/indexer/webhook/GitHubPushPayload.java`
- Test: `src/test/java/com/indexer/webhook/GitHubPushPayloadTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/indexer/webhook/GitHubPushPayloadTest.java`:

```java
package com.indexer.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubPushPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesPushPayloadIgnoringUnknownFields() throws Exception {
        String json = """
                {
                  "ref": "refs/heads/main",
                  "before": "aaaa1111",
                  "after": "bbbb2222",
                  "repository": { "name": "cml", "full_name": "csharp36/cml" },
                  "pusher": { "name": "someone" }
                }
                """;
        GitHubPushPayload p = mapper.readValue(json, GitHubPushPayload.class);
        assertThat(p.ref()).isEqualTo("refs/heads/main");
        assertThat(p.before()).isEqualTo("aaaa1111");
        assertThat(p.after()).isEqualTo("bbbb2222");
        assertThat(p.repository().name()).isEqualTo("cml");
    }

    @Test
    void branchExtractsHeadName() {
        var p = new GitHubPushPayload("refs/heads/feature/x", "a", "b", null);
        assertThat(p.branch()).isEqualTo("feature/x");
    }

    @Test
    void branchIsNullForNonBranchRef() {
        var p = new GitHubPushPayload("refs/tags/v1.0", "a", "b", null);
        assertThat(p.branch()).isNull();
    }

    @Test
    void detectsBranchDeletion() {
        var deleted = new GitHubPushPayload("refs/heads/main",
                "abc", "0000000000000000000000000000000000000000", null);
        assertThat(deleted.isBranchDeletion()).isTrue();

        var normal = new GitHubPushPayload("refs/heads/main", "abc", "def123", null);
        assertThat(normal.isBranchDeletion()).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.indexer.webhook.GitHubPushPayloadTest"`
Expected: FAIL — `GitHubPushPayload` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/indexer/webhook/GitHubPushPayload.java`:

```java
package com.indexer.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Subset of GitHub's push event payload that CML needs. Unknown fields are ignored.
 * See https://docs.github.com/en/webhooks/webhook-events-and-payloads#push
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubPushPayload(String ref, String before, String after, Repository repository) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(String name) {
    }

    private static final String HEADS_PREFIX = "refs/heads/";

    /** The branch name from {@code ref}, or null if the ref is not a branch. */
    public String branch() {
        return (ref != null && ref.startsWith(HEADS_PREFIX))
                ? ref.substring(HEADS_PREFIX.length())
                : null;
    }

    /** True when this push deletes a branch ({@code after} is the all-zero SHA). */
    public boolean isBranchDeletion() {
        return after != null && !after.isEmpty() && after.chars().allMatch(c -> c == '0');
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.indexer.webhook.GitHubPushPayloadTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/webhook/GitHubPushPayload.java \
        src/test/java/com/indexer/webhook/GitHubPushPayloadTest.java
git commit -m "feat: add GitHubPushPayload DTO with branch helpers"
```

---

### Task 3: Add `webhookSecret` to repository config

**Files:**
- Modify: `src/main/java/com/indexer/config/IndexerConfig.java` (the `RepositoryConfig` record, ~line 45-51)
- Modify: `src/main/java/com/indexer/config/ConfigLoader.java` (`parseRepository`, ~line 122-127)
- Modify: `src/main/java/com/indexer/repository/RepositoryManager.java:138`
- Modify: `src/test/java/com/indexer/repository/RepositoryManagerTest.java:34`
- Test: `src/test/java/com/indexer/config/ConfigLoaderTest.java`

- [ ] **Step 1: Write the failing test**

Add this test method to `src/test/java/com/indexer/config/ConfigLoaderTest.java` (above the `toStream` helper):

```java
    @Test
    void parsesPerRepoWebhookSecret() throws IOException {
        String yaml = """
                server:
                  cloneBaseDir: /tmp/repos

                database:
                  host: localhost
                  name: indexer_db

                repositories:
                  - url: git@github.com:org/myrepo.git
                    branch: main
                    auth:
                      type: ssh-key
                      keyPath: /k
                    webhookSecret: ${HOOK_SECRET}
                """;
        ConfigLoader loader = new ConfigLoader(v -> "HOOK_SECRET".equals(v) ? "s3cr3t" : null);
        IndexerConfig config = loader.load(toStream(yaml));

        assertThat(config.repositories().get(0).webhookSecret()).isEqualTo("s3cr3t");
    }

    @Test
    void webhookSecretIsNullWhenOmitted() throws IOException {
        String yaml = """
                server:
                  cloneBaseDir: /tmp/repos

                database:
                  host: localhost
                  name: indexer_db

                repositories:
                  - url: git@github.com:org/myrepo.git
                    branch: main
                    auth:
                      type: token
                      token: t
                """;
        ConfigLoader loader = new ConfigLoader();
        IndexerConfig config = loader.load(toStream(yaml));

        assertThat(config.repositories().get(0).webhookSecret()).isNull();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.indexer.config.ConfigLoaderTest"`
Expected: FAIL — `webhookSecret()` method does not exist on `RepositoryConfig` (compilation error).

- [ ] **Step 3a: Add the field to the record**

In `src/main/java/com/indexer/config/IndexerConfig.java`, replace the `RepositoryConfig` record:

```java
    public record RepositoryConfig(String url, String branch, AuthConfig auth) {
        public RepositoryConfig {
            if (url == null) throw new ConfigValidationException("repository.url is required");
            if (branch == null) branch = "main";
            if (auth == null) throw new ConfigValidationException("repository.auth is required");
        }
    }
```

with:

```java
    public record RepositoryConfig(String url, String branch, AuthConfig auth, String webhookSecret) {
        public RepositoryConfig {
            if (url == null) throw new ConfigValidationException("repository.url is required");
            if (branch == null) branch = "main";
            if (auth == null) throw new ConfigValidationException("repository.auth is required");
            // webhookSecret is optional (null when no GitHub webhook is configured)
        }
    }
```

- [ ] **Step 3b: Parse it in ConfigLoader**

In `src/main/java/com/indexer/config/ConfigLoader.java`, replace `parseRepository`:

```java
    private IndexerConfig.RepositoryConfig parseRepository(JsonNode node) {
        String url = textOrNull(node, "url");
        String branch = textOrNull(node, "branch");
        IndexerConfig.AuthConfig auth = parseAuth(node.get("auth"));
        return new IndexerConfig.RepositoryConfig(url, branch, auth);
    }
```

with:

```java
    private IndexerConfig.RepositoryConfig parseRepository(JsonNode node) {
        String url = textOrNull(node, "url");
        String branch = textOrNull(node, "branch");
        IndexerConfig.AuthConfig auth = parseAuth(node.get("auth"));
        String webhookSecret = textOrNull(node, "webhookSecret");
        return new IndexerConfig.RepositoryConfig(url, branch, auth, webhookSecret);
    }
```

- [ ] **Step 3c: Fix the `addRepository` call site**

In `src/main/java/com/indexer/repository/RepositoryManager.java` (~line 138), replace:

```java
        var repoConfig = new IndexerConfig.RepositoryConfig(url, branch, authConfig);
```

with:

```java
        var repoConfig = new IndexerConfig.RepositoryConfig(url, branch, authConfig, null);
```

- [ ] **Step 3d: Fix the RepositoryManagerTest call site**

In `src/test/java/com/indexer/repository/RepositoryManagerTest.java` (~line 34), replace:

```java
        var repoConfig = new IndexerConfig.RepositoryConfig(
                "https://github.com/org/myrepo.git", "main", auth);
```

with:

```java
        var repoConfig = new IndexerConfig.RepositoryConfig(
                "https://github.com/org/myrepo.git", "main", auth, null);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.indexer.config.ConfigLoaderTest" --tests "com.indexer.repository.RepositoryManagerTest"`
Expected: PASS (all ConfigLoaderTest + RepositoryManagerTest tests, including the two new webhookSecret tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/config/IndexerConfig.java \
        src/main/java/com/indexer/config/ConfigLoader.java \
        src/main/java/com/indexer/repository/RepositoryManager.java \
        src/test/java/com/indexer/config/ConfigLoaderTest.java \
        src/test/java/com/indexer/repository/RepositoryManagerTest.java
git commit -m "feat: add optional per-repo webhookSecret to config"
```

---

### Task 4: GitHubWebhookApi endpoint + wiring

**Files:**
- Create: `src/main/java/com/indexer/webhook/GitHubWebhookApi.java`
- Modify: `src/main/java/com/indexer/Application.java` (~line 191-201)
- Test: `src/test/java/com/indexer/webhook/GitHubWebhookApiIntegrationTest.java`

- [ ] **Step 1: Write the failing integration test**

Create `src/test/java/com/indexer/webhook/GitHubWebhookApiIntegrationTest.java`:

```java
package com.indexer.webhook;

import com.indexer.audit.AuditSink;
import com.indexer.db.DatabaseManager;
import com.indexer.db.EventDao;
import com.indexer.db.RepositoryDao;
import com.indexer.model.Repository;
import com.indexer.server.HttpServer;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Testcontainers
@Tag("integration")
class GitHubWebhookApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private static final String SECRET = "test-webhook-secret";

    private HttpServer httpServer;
    private EventDao eventDao;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        var jdbi = dbManager.getJdbi();
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM indexing_events");
            h.execute("DELETE FROM repositories");
        });

        var repositoryDao = new RepositoryDao(jdbi);
        eventDao = new EventDao(jdbi);
        // Seed an indexed repo named "cml" on branch "main".
        repositoryDao.insert(new Repository(0, "cml", "git@github.com:org/cml.git",
                "main", "/tmp/cml", "SSH_KEY", "abc", null));

        var api = new GitHubWebhookApi(Map.of("cml", SECRET), repositoryDao, eventDao, mock(AuditSink.class));

        int port;
        try (var ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        httpServer = new HttpServer(eventDao, repositoryDao, null);
        httpServer.addRoutes(api::registerRoutes);
        httpServer.start(port);

        client = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + port;
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) httpServer.stop();
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private HttpResponse<String> post(String path, String body, String event, String signature) throws Exception {
        var b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (event != null) b.header("X-GitHub-Event", event);
        if (signature != null) b.header("X-Hub-Signature-256", signature);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void validPushToMainEnqueuesEvent() throws Exception {
        String body = "{\"ref\":\"refs/heads/main\",\"before\":\"old\",\"after\":\"new\",\"repository\":{\"name\":\"cml\"}}";
        var resp = post("/webhook/github/cml", body, "push", sign(body));
        assertThat(resp.statusCode()).isEqualTo(202);
        assertThat(eventDao.countByStatus("pending")).isEqualTo(1);
    }

    @Test
    void pingEventReturns200AndEnqueuesNothing() throws Exception {
        String body = "{\"zen\":\"Keep it simple\"}";
        var resp = post("/webhook/github/cml", body, "ping", sign(body));
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(eventDao.countByStatus("pending")).isEqualTo(0);
    }

    @Test
    void pushToNonConfiguredBranchIsNoOp() throws Exception {
        String body = "{\"ref\":\"refs/heads/feature/x\",\"before\":\"old\",\"after\":\"new\",\"repository\":{\"name\":\"cml\"}}";
        var resp = post("/webhook/github/cml", body, "push", sign(body));
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(eventDao.countByStatus("pending")).isEqualTo(0);
    }

    @Test
    void badSignatureReturns401() throws Exception {
        String body = "{\"ref\":\"refs/heads/main\",\"before\":\"old\",\"after\":\"new\",\"repository\":{\"name\":\"cml\"}}";
        var resp = post("/webhook/github/cml", body, "push", "sha256=deadbeef");
        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(eventDao.countByStatus("pending")).isEqualTo(0);
    }

    @Test
    void unknownRepoReturns404() throws Exception {
        String body = "{\"ref\":\"refs/heads/main\",\"before\":\"old\",\"after\":\"new\"}";
        var resp = post("/webhook/github/unknown", body, "push", sign(body));
        assertThat(resp.statusCode()).isEqualTo(404);
    }
}
```

> Note: `eventDao.countByStatus("pending")` is the same method used elsewhere (e.g. `Application` boot uses `countByStatus("failed")`). New events are inserted with status `pending`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew integrationTest --tests "com.indexer.webhook.GitHubWebhookApiIntegrationTest"`
Expected: FAIL — `GitHubWebhookApi` does not exist (compilation error).

- [ ] **Step 3a: Implement GitHubWebhookApi**

Create `src/main/java/com/indexer/webhook/GitHubWebhookApi.java`:

```java
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
        log.info("GitHub webhook event #{} for repo {} ({} -> {})",
                eventId, repoName, payload.before(), payload.after());
        ctx.status(202).json(Map.of("eventId", eventId));
    }

    private void auditAuthFailure(Context ctx, String repo, String message) {
        if (auditSink == null) return;
        try {
            var caller = CallerIdentity.anonymous(ctx.ip());
            auditSink.record(AuditEvent.from(caller, "githubWebhook:authFailure", repo, false, "denied", message));
        } catch (Exception e) {
            log.warn("Audit write failed for GitHub webhook auth failure: {}", e.getMessage());
        }
    }
}
```

> Note: confirm the `CallerIdentity.anonymous(...)` signature matches usage in `ScipApi` (it calls `CallerIdentity.anonymous("streamable-http")`). Use `ctx.ip()` as the source argument; if `anonymous` takes no arg in this codebase, call `CallerIdentity.anonymous()` instead.

- [ ] **Step 3b: Wire it into Application**

In `src/main/java/com/indexer/Application.java`, find the block (~line 200-201):

```java
            httpServer.addRoutes(adminApi::registerRoutes);
            httpServer.addRoutes(scipApi::registerRoutes);
```

Insert immediately after it:

```java
            // GitHub webhook receiver — map repoName -> per-repo webhook secret
            var webhookSecrets = new java.util.HashMap<String, String>();
            for (var rc : config.repositories()) {
                if (rc.webhookSecret() != null && !rc.webhookSecret().isBlank()) {
                    webhookSecrets.put(repoManager.extractRepoName(rc.url()), rc.webhookSecret());
                }
            }
            var githubWebhookApi = new com.indexer.webhook.GitHubWebhookApi(
                    webhookSecrets, repositoryDao, eventDao, auditSink);
            httpServer.addRoutes(githubWebhookApi::registerRoutes);
            log.info("GitHub webhook receiver enabled for {} repo(s)", webhookSecrets.size());
```

> Note: `repoManager`, `config`, `repositoryDao`, `eventDao`, and `auditSink` are all already in scope at this point in `Application.start()`. `extractRepoName` is a public method on `RepositoryManager`.

- [ ] **Step 4: Run the integration test to verify it passes**

Run: `./gradlew integrationTest --tests "com.indexer.webhook.GitHubWebhookApiIntegrationTest"`
Expected: PASS (5 tests). If `CallerIdentity.anonymous(ctx.ip())` fails to compile, switch to the no-arg form per the Step 3a note and re-run.

- [ ] **Step 5: Verify the whole build is green**

Run: `./gradlew compileJava compileTestJava test`
Expected: BUILD SUCCESSFUL (all unit tests pass).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/indexer/webhook/GitHubWebhookApi.java \
        src/main/java/com/indexer/Application.java \
        src/test/java/com/indexer/webhook/GitHubWebhookApiIntegrationTest.java
git commit -m "feat: add GitHub push webhook receiver endpoint"
```

---

### Task 5: Documentation

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add a GitHub webhook subsection to README.md**

In `README.md`, immediately after the "Semantic Indexing (SCIP)" section (before "## Authentication & Audit"), insert:

```markdown
## GitHub Webhook (live main-branch sync)

Keep a repo's index in sync automatically when changes merge to its configured branch.
Add a per-repo signing secret to config:

​```yaml
repositories:
  - url: git@github.com:your-org/your-repo.git
    branch: main
    auth:
      type: ssh-key
      keyPath: ~/.ssh/id_ed25519
    webhookSecret: ${REPO_WEBHOOK_SECRET}   # HMAC-SHA256 shared secret
​```

Then in the repo on GitHub: **Settings → Webhooks → Add webhook**
- **Payload URL:** `https://<cml-host>/webhook/github/<repoName>`
- **Content type:** `application/json`
- **Secret:** the same value as `webhookSecret`
- **Events:** "Just the push event"

On a verified push to the configured branch, CML returns `202` and enqueues an indexing
event; the index updates asynchronously via the event queue. Pushes to other branches and
non-push events (e.g. `ping`) are accepted but ignored. Repos without a `webhookSecret`
reject webhook deliveries (fail-closed).
```

> Note: the `​` characters before each ```` ``` ```` inside the snippet above are zero-width spaces to escape the nested fence — when editing the real README, use plain triple backticks for the inner YAML block.

- [ ] **Step 2: Add the webhookSecret field and endpoint to CLAUDE.md**

In `CLAUDE.md`, in the webhook/architecture area, add a short note that CML exposes
`POST /webhook/github/{repoName}` for GitHub push webhooks, verified with the per-repo
`webhookSecret` config field, and that it enqueues an event processed by the existing
poller (main-branch only). Place it near the existing `/webhook` description.

- [ ] **Step 3: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "docs: document GitHub webhook receiver and webhookSecret config"
```

---

## Self-Review

**Spec coverage:**
- Per-repo `webhookSecret` config → Task 3. ✓
- Endpoint `POST /webhook/github/{repoName}`, per-repo path, match by name → Task 4. ✓
- HMAC-SHA256 verification of raw body, constant-time → Task 1. ✓
- Main-branch only; 202 async via existing queue/poller → Task 4 (branch check + `eventDao.insert`). ✓
- Error matrix (404 unknown, 401 no-secret/bad-sig, 200 ping/non-main/deletion, 400 bad JSON, 202 valid) → Task 4 handler + integration tests. ✓
- Fail-closed (no secret → 401) → Task 4. ✓
- Audit on auth failure (AuditSink, like ScipApi) → Task 4 `auditAuthFailure`. ✓
- Docs (README + CLAUDE.md, GitHub setup) → Task 5. ✓

**Placeholder scan:** No TBD/TODO; every code step shows complete code. The two `> Note:` callouts flag a signature to confirm against the codebase (`CallerIdentity.anonymous`) and a markdown-escaping detail — both are guidance, not missing content.

**Type consistency:** `GitHubWebhookVerifier.isValid(byte[], String, String)`, `GitHubPushPayload(ref, before, after, repository)` with `.branch()`/`.isBranchDeletion()`, `GitHubWebhookApi(Map<String,String>, RepositoryDao, EventDao, AuditSink)`, `eventDao.insert(repoName, repoPath, eventType, previousSha, currentSha, branch)`, and `RepositoryConfig(url, branch, auth, webhookSecret)` are used consistently across tasks.

**Deviation from spec note:** The spec described the handler as a method on `HttpServer`; this plan instead uses a dedicated `GitHubWebhookApi` class registered via `httpServer.addRoutes(...)`, matching the established `ScipApi`/`AdminApi` pattern. Behavior is identical; isolation is better.
