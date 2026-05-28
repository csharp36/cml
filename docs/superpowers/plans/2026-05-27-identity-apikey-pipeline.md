# Identity + API Key Auth + Query Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add CallerIdentity to the system end-to-end: authenticate via API keys, route every query through a unified pipeline wrapper that handles auth, result formatting, and error handling.

**Architecture:** `contextExtractor` on the SDK transport extracts identity from Bearer tokens. Identity is stored in `McpTransportContext` and retrieved by tool handlers via `exchange.transportContext()`. All tool calls flow through `QueryExecutor.executeQuery()` which centralizes auth checks, result formatting, and error handling. When auth is not configured, everything works as before (anonymous identity).

**Tech Stack:** Java 21, MCP SDK 1.1.2, Javalin 7.2.2

**Spec:** `docs/superpowers/specs/2026-05-27-identity-apikey-pipeline-design.md`

---

### Task 1: CallerIdentity Record

**Files:**
- Create: `src/main/java/com/indexer/auth/CallerIdentity.java`
- Create: `src/test/java/com/indexer/auth/CallerIdentityTest.java`

- [ ] **Step 1: Write tests for CallerIdentity factory methods**

```java
package com.indexer.auth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CallerIdentityTest {

    @Test
    void fromStdioUsesOsUsername() {
        var identity = CallerIdentity.fromStdio();
        assertThat(identity.userId()).isEqualTo(System.getProperty("user.name"));
        assertThat(identity.authMethod()).isEqualTo("stdio-os-user");
        assertThat(identity.transport()).isEqualTo("stdio");
        assertThat(identity.sourceIp()).isNull();
    }

    @Test
    void anonymousHasNoAuth() {
        var identity = CallerIdentity.anonymous("streamable-http");
        assertThat(identity.userId()).isNull();
        assertThat(identity.displayName()).isEqualTo("anonymous");
        assertThat(identity.authMethod()).isEqualTo("none");
        assertThat(identity.transport()).isEqualTo("streamable-http");
    }

    @Test
    void fromApiKeyCarriesIdentity() {
        var identity = CallerIdentity.fromApiKey("team-payments", "Payments Team", "10.0.0.1");
        assertThat(identity.userId()).isEqualTo("team-payments");
        assertThat(identity.displayName()).isEqualTo("Payments Team");
        assertThat(identity.authMethod()).isEqualTo("api-key");
        assertThat(identity.transport()).isEqualTo("streamable-http");
        assertThat(identity.sourceIp()).isEqualTo("10.0.0.1");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.indexer.auth.CallerIdentityTest" --rerun 2>&1 | tail -5`
Expected: Compilation failure — `CallerIdentity` class doesn't exist yet.

- [ ] **Step 3: Implement CallerIdentity**

```java
package com.indexer.auth;

public record CallerIdentity(
        String userId,
        String displayName,
        String authMethod,
        String transport,
        String sourceIp,
        String clientName,
        String clientVersion
) {
    public static final String CONTEXT_KEY = "callerIdentity";

    public static CallerIdentity anonymous(String transport) {
        return new CallerIdentity(null, "anonymous", "none", transport, null, null, null);
    }

    public static CallerIdentity fromStdio() {
        String osUser = System.getProperty("user.name");
        return new CallerIdentity(osUser, osUser, "stdio-os-user", "stdio", null, null, null);
    }

    public static CallerIdentity fromApiKey(String id, String name, String sourceIp) {
        return new CallerIdentity(id, name, "api-key", "streamable-http", sourceIp, null, null);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.indexer.auth.CallerIdentityTest" --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/auth/CallerIdentity.java src/test/java/com/indexer/auth/CallerIdentityTest.java
git commit -m "feat: add CallerIdentity record with factory methods for stdio, anonymous, and API key"
```

---

### Task 2: ApiKeyAuthenticator

**Files:**
- Create: `src/main/java/com/indexer/auth/ApiKeyAuthenticator.java`
- Create: `src/test/java/com/indexer/auth/ApiKeyAuthenticatorTest.java`

- [ ] **Step 1: Write tests for ApiKeyAuthenticator**

```java
package com.indexer.auth;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthenticatorTest {

    @Test
    void authenticatesValidKey() {
        var keys = List.of(
                new ApiKeyAuthenticator.ApiKeyConfig("secret-key-123", "team-payments", "Payments Team")
        );
        var authenticator = new ApiKeyAuthenticator(keys);

        var result = authenticator.authenticate("secret-key-123", "10.0.0.1");

        assertThat(result).isPresent();
        assertThat(result.get().userId()).isEqualTo("team-payments");
        assertThat(result.get().displayName()).isEqualTo("Payments Team");
        assertThat(result.get().authMethod()).isEqualTo("api-key");
        assertThat(result.get().sourceIp()).isEqualTo("10.0.0.1");
    }

    @Test
    void rejectsInvalidKey() {
        var keys = List.of(
                new ApiKeyAuthenticator.ApiKeyConfig("secret-key-123", "team-payments", "Payments Team")
        );
        var authenticator = new ApiKeyAuthenticator(keys);

        var result = authenticator.authenticate("wrong-key", "10.0.0.1");

        assertThat(result).isEmpty();
    }

    @Test
    void rejectsNullKey() {
        var keys = List.of(
                new ApiKeyAuthenticator.ApiKeyConfig("secret-key-123", "team-payments", "Payments Team")
        );
        var authenticator = new ApiKeyAuthenticator(keys);

        assertThat(authenticator.authenticate(null, "10.0.0.1")).isEmpty();
    }

    @Test
    void supportsMultipleKeys() {
        var keys = List.of(
                new ApiKeyAuthenticator.ApiKeyConfig("key-alpha", "alice", "Alice Chen"),
                new ApiKeyAuthenticator.ApiKeyConfig("key-beta", "bob", "Bob Smith")
        );
        var authenticator = new ApiKeyAuthenticator(keys);

        assertThat(authenticator.authenticate("key-alpha", "1.1.1.1").get().userId()).isEqualTo("alice");
        assertThat(authenticator.authenticate("key-beta", "2.2.2.2").get().userId()).isEqualTo("bob");
    }

    @Test
    void isEnabledWithKeys() {
        var authenticator = new ApiKeyAuthenticator(
                List.of(new ApiKeyAuthenticator.ApiKeyConfig("key", "id", "name")));
        assertThat(authenticator.isEnabled()).isTrue();
    }

    @Test
    void isDisabledWithoutKeys() {
        var authenticator = new ApiKeyAuthenticator(List.of());
        assertThat(authenticator.isEnabled()).isFalse();
    }

    @Test
    void isDisabledWithNull() {
        var authenticator = new ApiKeyAuthenticator(null);
        assertThat(authenticator.isEnabled()).isFalse();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.indexer.auth.ApiKeyAuthenticatorTest" --rerun 2>&1 | tail -5`
Expected: Compilation failure.

- [ ] **Step 3: Implement ApiKeyAuthenticator**

```java
package com.indexer.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

public class ApiKeyAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticator.class);

    private final List<ApiKeyConfig> apiKeys;

    public record ApiKeyConfig(String key, String id, String name) {}

    public ApiKeyAuthenticator(List<ApiKeyConfig> apiKeys) {
        this.apiKeys = apiKeys != null ? apiKeys : List.of();
        if (isEnabled()) {
            log.info("API key authentication enabled with {} configured key(s)", this.apiKeys.size());
        }
    }

    public Optional<CallerIdentity> authenticate(String bearerToken, String sourceIp) {
        if (bearerToken == null) {
            return Optional.empty();
        }
        for (var keyConfig : apiKeys) {
            if (constantTimeEquals(keyConfig.key(), bearerToken)) {
                return Optional.of(CallerIdentity.fromApiKey(keyConfig.id(), keyConfig.name(), sourceIp));
            }
        }
        return Optional.empty();
    }

    public boolean isEnabled() {
        return !apiKeys.isEmpty();
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.indexer.auth.ApiKeyAuthenticatorTest" --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/auth/ApiKeyAuthenticator.java src/test/java/com/indexer/auth/ApiKeyAuthenticatorTest.java
git commit -m "feat: add ApiKeyAuthenticator with constant-time key validation"
```

---

### Task 3: Auth Config Parsing

**Files:**
- Modify: `src/main/java/com/indexer/config/IndexerConfig.java`
- Modify: `src/main/java/com/indexer/config/ConfigLoader.java`
- Modify: `src/test/java/com/indexer/config/ConfigLoaderTest.java` (if it exists, otherwise create)

- [ ] **Step 1: Add McpAuthConfig to IndexerConfig**

In `IndexerConfig.java`, add a new record and field. Note: the existing `AuthConfig` is for repository auth (type + properties). The new config is for MCP endpoint auth. Name it `McpAuthConfig` to avoid confusion.

Add inside `IndexerConfig`:

```java
public record McpAuthConfig(List<ApiKeyEntry> apiKeys) {
    public McpAuthConfig {
        if (apiKeys == null) apiKeys = List.of();
    }
    public record ApiKeyEntry(String key, String id, String name) {}
}
```

Add `mcpAuth` field to the `IndexerConfig` record parameter list (after `branches`):

```java
public record IndexerConfig(
        ServerConfig server,
        DatabaseConfig database,
        List<RepositoryConfig> repositories,
        LanguagesConfig languages,
        AdminConfig admin,
        BranchConfig branches,
        McpAuthConfig mcpAuth
) {
```

Update the compact constructor to default `mcpAuth`:
```java
if (mcpAuth == null) mcpAuth = new McpAuthConfig(List.of());
```

Add `@JsonProperty("auth")` annotation to the `mcpAuth` parameter so it maps from the YAML `auth:` key:
```java
@JsonProperty("auth") McpAuthConfig mcpAuth
```

- [ ] **Step 2: Add parsing in ConfigLoader**

In `ConfigLoader.parseConfig()`, add:

```java
IndexerConfig.McpAuthConfig mcpAuth = parseMcpAuth(root.get("auth"));
```

And update the return:
```java
return new IndexerConfig(server, database, repositories, languages, admin, branches, mcpAuth);
```

Add the parse method:

```java
private IndexerConfig.McpAuthConfig parseMcpAuth(JsonNode node) {
    if (node == null) return null;
    JsonNode apiKeysNode = node.get("apiKeys");
    if (apiKeysNode == null || !apiKeysNode.isArray()) return new IndexerConfig.McpAuthConfig(List.of());
    List<IndexerConfig.McpAuthConfig.ApiKeyEntry> keys = new ArrayList<>();
    for (JsonNode keyNode : apiKeysNode) {
        String key = textOrNull(keyNode, "key");
        String id = textOrNull(keyNode, "id");
        String name = textOrNull(keyNode, "name");
        if (key != null && id != null) {
            keys.add(new IndexerConfig.McpAuthConfig.ApiKeyEntry(key, id, name != null ? name : id));
        }
    }
    return new IndexerConfig.McpAuthConfig(keys);
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew compileJava compileTestJava 2>&1 | tail -5`

Fix any compilation errors — the `IndexerConfig` constructor changed (added a parameter), so any test code that constructs `IndexerConfig` directly may need updating. Most tests use `ConfigLoader.load()` which handles the new field via null-defaulting.

- [ ] **Step 4: Run all tests**

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL — existing tests pass because `mcpAuth` defaults to empty when absent from config.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/config/IndexerConfig.java src/main/java/com/indexer/config/ConfigLoader.java
git commit -m "feat: add MCP auth config section for API key authentication"
```

---

### Task 4: Wire contextExtractor and Identity into Application

**Files:**
- Modify: `src/main/java/com/indexer/Application.java`

- [ ] **Step 1: Create the ApiKeyAuthenticator from config**

After loading config and before building the streamable transport, create the authenticator:

```java
// 5b. Set up API key authenticator
var apiKeyConfigs = config.mcpAuth().apiKeys().stream()
        .map(e -> new ApiKeyAuthenticator.ApiKeyConfig(e.key(), e.id(), e.name()))
        .toList();
var authenticator = new ApiKeyAuthenticator(apiKeyConfigs);
```

Add import:
```java
import com.indexer.auth.ApiKeyAuthenticator;
import com.indexer.auth.CallerIdentity;
import io.modelcontextprotocol.common.McpTransportContext;
```

- [ ] **Step 2: Wire contextExtractor into the transport builder**

Replace the current transport builder:

```java
var streamableTransport = HttpServletStreamableServerTransportProvider.builder()
        .mcpEndpoint("/mcp")
        .build();
```

With:

```java
var streamableTransport = HttpServletStreamableServerTransportProvider.builder()
        .mcpEndpoint("/mcp")
        .contextExtractor(request -> {
            CallerIdentity identity;
            if (!authenticator.isEnabled()) {
                identity = CallerIdentity.anonymous("streamable-http");
            } else {
                String authHeader = request.getHeader("Authorization");
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    throw new RuntimeException("Missing or invalid Authorization header");
                }
                String token = authHeader.substring("Bearer ".length());
                identity = authenticator.authenticate(token, request.getRemoteAddr())
                        .orElseThrow(() -> new RuntimeException("Invalid API key"));
            }
            return McpTransportContext.create(Map.of(CallerIdentity.CONTEXT_KEY, identity));
        })
        .build();
```

Note: The `contextExtractor` throws `RuntimeException` on auth failure. The SDK catches this and returns an appropriate HTTP error to the client. We use `RuntimeException` because the SDK's `McpTransportContextExtractor` interface doesn't declare checked exceptions.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run all tests**

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL — existing tests don't configure auth, so `authenticator.isEnabled()` is false and anonymous identity is used.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/Application.java
git commit -m "feat: wire API key authenticator into Streamable HTTP contextExtractor"
```

---

### Task 5: QueryExecutor Pipeline Wrapper

**Files:**
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java`

- [ ] **Step 1: Add the executeQuery method**

Add imports:
```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indexer.auth.CallerIdentity;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.function.Supplier;
```

Add a static ObjectMapper field:
```java
private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
```

Add the pipeline method:

```java
/**
 * Unified query pipeline. All tool calls flow through this method.
 * Handles: authentication validation, result formatting, error handling.
 * Future: authorization (repo access), audit logging, precision tagging.
 */
public McpSchema.CallToolResult executeQuery(
        CallerIdentity caller, String repo, String action,
        Map<String, Object> params, Supplier<Object> query) {
    // 1. Log the query with caller context
    log.info("Tool call: {} by {} ({})", action, caller.displayName(), caller.authMethod());

    // 2. Future: authorization check (Phase B Part 2 — per-session repo filtering)
    // 3. Future: audit write (Phase C — AuditSink.recordAccess)

    // 4. Execute the query
    try {
        Object result = query.get();
        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .isError(false)
                .build();
    } catch (Exception e) {
        log.error("Tool execution error in {}: {}", action, e.getMessage(), e);
        return McpSchema.CallToolResult.builder()
                .addTextContent("Error: " + e.getMessage())
                .isError(true)
                .build();
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/indexer/mcp/QueryExecutor.java
git commit -m "feat: add executeQuery pipeline wrapper to QueryExecutor"
```

---

### Task 6: Refactor McpServerBootstrap to Use Pipeline

**Files:**
- Modify: `src/main/java/com/indexer/mcp/McpServerBootstrap.java`

This is the largest task. Every tool handler is refactored to extract identity and delegate to `executeQuery`. The `jsonResult()` and `errorResult()` helper methods are removed.

- [ ] **Step 1: Add extractIdentity helper**

Add import:
```java
import com.indexer.auth.CallerIdentity;
import io.modelcontextprotocol.common.McpTransportContext;
```

Add helper method:

```java
private CallerIdentity extractIdentity(McpSyncServerExchange exchange) {
    McpTransportContext ctx = exchange.transportContext();
    if (ctx == null) {
        return CallerIdentity.fromStdio();
    }
    Object identity = ctx.get(CallerIdentity.CONTEXT_KEY);
    if (identity instanceof CallerIdentity ci) {
        return ci;
    }
    // Stdio transport has no contextExtractor — default to OS user
    return CallerIdentity.fromStdio();
}
```

- [ ] **Step 2: Refactor all 11 tool handlers**

Replace each handler to use `executeQuery`. The pattern for each handler is:

1. Extract args from `request.arguments()`
2. Extract identity from `exchange`
3. Extract `repo` (or `repo_name`) arg for the pipeline
4. Call `queryExecutor.executeQuery(caller, repo, actionName, args, () -> queryExecutor.specificMethod(...))`

Here are all 11 handlers — replace the entire tool handlers section:

```java
private McpSchema.CallToolResult handleSearchSymbols(
        McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
    var args = request.arguments();
    var caller = extractIdentity(exchange);
    String repo = stringArg(args, "repo");
    return queryExecutor.executeQuery(caller, repo, "search_symbols", args,
            () -> queryExecutor.searchSymbols(
                    stringArg(args, "query"), stringArg(args, "kind"),
                    stringArg(args, "language"), repo,
                    stringArg(args, "branch"), intArg(args, "limit", 20)));
}

private McpSchema.CallToolResult handleGetSymbolDetail(
        McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
    var args = request.arguments();
    var caller = extractIdentity(exchange);
    String repo = stringArg(args, "repo");
    return queryExecutor.executeQuery(caller, repo, "get_symbol_detail", args,
            () -> queryExecutor.getSymbolDetail(repo, stringArg(args, "file_path"),
                    stringArg(args, "symbol_name"),
                    args.containsKey("line") ? intArg(args, "line", 0) : null,
                    stringArg(args, "branch")));
}

private McpSchema.CallToolResult handleFindImplementations(
        McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
    var args = request.arguments();
    var caller = extractIdentity(exchange);
    String repo = stringArg(args, "repo");
    return queryExecutor.executeQuery(caller, repo, "find_implementations", args,
            () -> queryExecutor.findImplementations(
                    stringArg(args, "type_name"), repo, stringArg(args, "branch")));
}

private McpSchema.CallToolResult handleFindReferences(
        McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
    var args = request.arguments();
    var caller = extractIdentity(exchange);
    String repo = stringArg(args, "repo");
    return queryExecutor.executeQuery(caller, repo, "find_references", args,
            () -> queryExecutor.findReferences(
                    stringArg(args, "symbol_name"), repo,
                    stringArg(args, "branch"), intArg(args, "limit", 20)));
}

private McpSchema.CallToolResult handleSearchCode(
        McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
    var args = request.arguments();
    var caller = extractIdentity(exchange);
    String repo = stringArg(args, "repo");
    return queryExecutor.executeQuery(caller, repo, "search_code", args,
            () -> queryExecutor.searchCode(
                    stringArg(args, "query"), stringArg(args, "language"),
                    repo, stringArg(args, "branch"), intArg(args, "limit", 20)));
}

private McpSchema.CallToolResult handleSearchFiles(
        McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
    var args = request.arguments();
    var caller = extractIdentity(exchange);
    String repo = stringArg(args, "repo");
    return queryExecutor.executeQuery(caller, repo, "search_files", args,
            () -> queryExecutor.searchFiles(
                    stringArg(args, "pattern"), stringArg(args, "language"),
                    repo, stringArg(args, "branch"), intArg(args, "limit", 50)));
}

private McpSchema.CallToolResult handleGetRepoSummary(
        McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
    var args = request.arguments();
    var caller = extractIdentity(exchange);
    String repo = stringArg(args, "repo_name");
    return queryExecutor.executeQuery(caller, repo, "get_repo_summary", args,
            () -> queryExecutor.getRepoSummary(repo, stringArg(args, "branch")));
}

private McpSchema.CallToolResult handleGetFileSummary(
        McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
    var args = request.arguments();
    var caller = extractIdentity(exchange);
    String repo = stringArg(args, "repo_name");
    return queryExecutor.executeQuery(caller, repo, "get_file_summary", args,
            () -> queryExecutor.getFileSummary(repo, stringArg(args, "file_path"),
                    stringArg(args, "branch")));
}

private McpSchema.CallToolResult handleGetDirectoryTree(
        McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
    var args = request.arguments();
    var caller = extractIdentity(exchange);
    String repo = stringArg(args, "repo_name");
    return queryExecutor.executeQuery(caller, repo, "get_directory_tree", args,
            () -> queryExecutor.getDirectoryTree(repo, stringArg(args, "path"),
                    intArg(args, "depth", 3), stringArg(args, "branch")));
}

private McpSchema.CallToolResult handleGetIndexHealth(
        McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
    var caller = extractIdentity(exchange);
    return queryExecutor.executeQuery(caller, null, "get_index_health", Map.of(),
            () -> queryExecutor.getIndexHealth());
}

private McpSchema.CallToolResult handleCheckSync(
        McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
    var args = request.arguments();
    var caller = extractIdentity(exchange);
    String repo = stringArg(args, "repo_name");
    return queryExecutor.executeQuery(caller, repo, "check_sync", args,
            () -> queryExecutor.checkSync(repo, stringArg(args, "local_sha"),
                    stringArg(args, "branch")));
}
```

- [ ] **Step 3: Remove jsonResult and errorResult helpers**

Delete the `jsonResult()` and `errorResult()` methods from `McpServerBootstrap` — their logic is now in `QueryExecutor.executeQuery()`.

Also remove the `OBJECT_MAPPER` field from `McpServerBootstrap` — it's now in `QueryExecutor`.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run all tests**

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL — all 108 tests pass. The pipeline wrapper is transparent to existing tests (anonymous identity, no auth configured).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/indexer/mcp/McpServerBootstrap.java
git commit -m "refactor: route all tool calls through executeQuery pipeline

Extract identity from McpSyncServerExchange, delegate to
QueryExecutor.executeQuery() for unified auth/result/error handling.
Remove jsonResult() and errorResult() helpers from McpServerBootstrap."
```

---

### Task 7: API Key Integration Test

**Files:**
- Modify: `src/test/java/com/indexer/mcp/transport/StreamableHttpTransportIntegrationTest.java`

- [ ] **Step 1: Add test for authenticated access**

Add a new test method that configures API keys and verifies authentication works:

```java
@Test
void authenticatedRequestSucceeds() throws Exception {
    // This test needs its own server setup with API keys configured.
    // Stop the default (no-auth) server from @BeforeEach
    if (mcpBootstrap != null) mcpBootstrap.stop();
    if (httpServer != null) httpServer.stop();

    // Set up with API key auth
    var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dbManager.initialize();
    var jdbi = dbManager.getJdbi();
    var eventDao = new EventDao(jdbi);
    var repositoryDao = new RepositoryDao(jdbi);
    var queryExecutor = new QueryExecutor(jdbi);

    var authenticator = new ApiKeyAuthenticator(List.of(
            new ApiKeyAuthenticator.ApiKeyConfig("test-secret-key", "test-user", "Test User")
    ));

    var transport = HttpServletStreamableServerTransportProvider.builder()
            .mcpEndpoint("/mcp")
            .contextExtractor(request -> {
                String authHeader = request.getHeader("Authorization");
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    throw new RuntimeException("Missing Authorization header");
                }
                String token = authHeader.substring("Bearer ".length());
                CallerIdentity identity = authenticator.authenticate(token, request.getRemoteAddr())
                        .orElseThrow(() -> new RuntimeException("Invalid API key"));
                return McpTransportContext.create(Map.of(CallerIdentity.CONTEXT_KEY, identity));
            })
            .build();

    try (var ss = new ServerSocket(0)) { port = ss.getLocalPort(); }
    httpServer = new HttpServer(eventDao, repositoryDao, transport);
    mcpBootstrap = new McpServerBootstrap(queryExecutor);
    mcpBootstrap.startHttp(transport);
    httpServer.start(port);
    baseUrl = "http://localhost:" + port;

    // Authenticated request should succeed
    String initRequest = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}
            """;

    var response = client.send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream, application/json")
            .header("Authorization", "Bearer test-secret-key")
            .POST(HttpRequest.BodyPublishers.ofString(initRequest))
            .build(), HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    String sessionId = response.headers().firstValue("Mcp-Session-Id")
            .or(() -> response.headers().firstValue("mcp-session-id")).orElse(null);
    assertThat(sessionId).isNotNull();
}

@Test
void unauthenticatedRequestRejectedWhenAuthConfigured() throws Exception {
    // Stop the default server
    if (mcpBootstrap != null) mcpBootstrap.stop();
    if (httpServer != null) httpServer.stop();

    var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dbManager.initialize();
    var jdbi = dbManager.getJdbi();
    var eventDao = new EventDao(jdbi);
    var repositoryDao = new RepositoryDao(jdbi);
    var queryExecutor = new QueryExecutor(jdbi);

    var authenticator = new ApiKeyAuthenticator(List.of(
            new ApiKeyAuthenticator.ApiKeyConfig("test-secret-key", "test-user", "Test User")
    ));

    var transport = HttpServletStreamableServerTransportProvider.builder()
            .mcpEndpoint("/mcp")
            .contextExtractor(request -> {
                String authHeader = request.getHeader("Authorization");
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    throw new RuntimeException("Missing Authorization header");
                }
                String token = authHeader.substring("Bearer ".length());
                CallerIdentity identity = authenticator.authenticate(token, request.getRemoteAddr())
                        .orElseThrow(() -> new RuntimeException("Invalid API key"));
                return McpTransportContext.create(Map.of(CallerIdentity.CONTEXT_KEY, identity));
            })
            .build();

    try (var ss = new ServerSocket(0)) { port = ss.getLocalPort(); }
    httpServer = new HttpServer(eventDao, repositoryDao, transport);
    mcpBootstrap = new McpServerBootstrap(queryExecutor);
    mcpBootstrap.startHttp(transport);
    httpServer.start(port);
    baseUrl = "http://localhost:" + port;

    // Request without auth header should be rejected
    String initRequest = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}
            """;

    var response = client.send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream, application/json")
            .POST(HttpRequest.BodyPublishers.ofString(initRequest))
            .build(), HttpResponse.BodyHandlers.ofString());

    // Should return an error status (401 or 500 depending on SDK's exception handling)
    assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
}
```

Add required imports at top of test file:
```java
import com.indexer.auth.ApiKeyAuthenticator;
import com.indexer.auth.CallerIdentity;
import io.modelcontextprotocol.common.McpTransportContext;
import java.util.Map;
```

- [ ] **Step 2: Run the new tests**

Run: `./gradlew test --tests "com.indexer.mcp.transport.StreamableHttpTransportIntegrationTest" --rerun 2>&1 | tail -10`
Expected: All 5 tests pass (3 existing + 2 new).

- [ ] **Step 3: Run full test suite**

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL — all tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/indexer/mcp/transport/StreamableHttpTransportIntegrationTest.java
git commit -m "test: add API key authentication integration tests

Verify: authenticated requests succeed with valid Bearer token,
unauthenticated requests are rejected when auth is configured."
```

---

### Implementation Notes

**SDK `contextExtractor` exception handling:** When the `contextExtractor` throws, the SDK catches it at the HTTP layer and returns a 500 (or similar error status) to the client. The exact status depends on the SDK's internal error handling. The integration test asserts `>= 400` to handle either 401 or 500.

**Stdio identity for `contextExtractor`-less transports:** The stdio transport has no `contextExtractor`. When `exchange.transportContext()` returns null or doesn't contain a `CallerIdentity`, the `extractIdentity()` helper defaults to `CallerIdentity.fromStdio()`. This is the correct behavior — stdio is a trusted subprocess.

**Config naming:** The YAML config uses `auth:` at the top level, but the Java record is `McpAuthConfig` to avoid collision with the existing `AuthConfig` (which is for repository clone auth). The `@JsonProperty("auth")` annotation maps between them.

**Backward compatibility:** When no `auth` section is in config, `mcpAuth` defaults to an empty `McpAuthConfig` with no API keys. `ApiKeyAuthenticator.isEnabled()` returns false. The `contextExtractor` returns anonymous identity. Everything works exactly as before.
