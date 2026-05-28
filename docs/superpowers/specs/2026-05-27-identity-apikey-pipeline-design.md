# Identity + API Key Auth + Query Pipeline Design

## Context

Phase B (Part 1) of the enterprise hardening roadmap. Adds identity to the system end-to-end: who is calling, are they authenticated, and routes every query through a unified pipeline wrapper.

This covers B1 (CallerIdentity), B2 (API key auth), and B5 (QueryExecutor pipeline wrapper). B3 (OAuth 2.1) and B4 (per-session permissions) come in Part 2.

### Depends On

- Phase A1: Streamable HTTP migration (complete) — provides `contextExtractor` hook on the SDK transport

### Enables

- Phase C: Audit logging — the pipeline wrapper is where audit writes will be inserted
- Phase B Part 2: OAuth + per-session permissions — CallerIdentity carries groups, pipeline wrapper enforces repo access

## CallerIdentity Record

New file: `src/main/java/com/indexer/auth/CallerIdentity.java`

```java
public record CallerIdentity(
    String userId,          // opaque identifier (for GDPR, this can be a UUID mapped separately)
    String displayName,     // human-readable (for logging, not for auth decisions)
    String authMethod,      // "api-key", "oauth", "stdio-os-user", "none"
    String transport,       // "stdio", "streamable-http"
    String sourceIp,        // null for stdio
    String clientName,      // from MCP clientInfo (e.g., "Claude Code")
    String clientVersion    // from MCP clientInfo
) {}
```

**Factory methods:**

- `CallerIdentity.anonymous(String transport)` — unauthenticated caller (when auth is not configured)
- `CallerIdentity.fromStdio()` — reads `System.getProperty("user.name")` as the identity
- `CallerIdentity.fromApiKey(String id, String name, String sourceIp)` — authenticated via API key

**Stdio transport:** Identity is the OS user. Trusted by design — the server is a subprocess spawned by Claude Code. No auth check needed.

**HTTP transport without auth configured:** Anonymous identity. All requests allowed. Backward compatible with existing deployments.

**HTTP transport with auth configured but no/invalid key:** 401 Unauthorized.

## API Key Configuration

New config section in `config.yaml`:

```yaml
auth:
  apiKeys:
    - key: ${API_KEY_TEAM_PAYMENTS}
      id: "team-payments"
      name: "Payments Team Service Account"
    - key: ${API_KEY_DEV_ALICE}
      id: "alice"
      name: "Alice Chen"
```

Keys support `${ENV_VAR}` substitution (existing config pattern). When `auth` or `auth.apiKeys` is absent or empty, authentication is disabled — all HTTP requests proceed with anonymous identity.

### Config Model

New record in `IndexerConfig`:

```java
public record AuthConfig(List<ApiKeyConfig> apiKeys) {
    public record ApiKeyConfig(String key, String id, String name) {}
}
```

Add `auth` field to `IndexerConfig`:
```java
public record IndexerConfig(ServerConfig server, DatabaseConfig database,
                            List<RepositoryConfig> repositories, AdminConfig admin,
                            LanguageConfig languages, BranchConfig branches,
                            AuthConfig auth) {}
```

## API Key Authenticator

New file: `src/main/java/com/indexer/auth/ApiKeyAuthenticator.java`

Responsible for validating Bearer tokens against the configured key store.

```java
public class ApiKeyAuthenticator {
    private final Map<String, AuthConfig.ApiKeyConfig> keyMap;  // key value -> config

    public ApiKeyAuthenticator(List<AuthConfig.ApiKeyConfig> apiKeys) { ... }

    /** Returns the matching identity, or empty if key is invalid. */
    public Optional<CallerIdentity> authenticate(String bearerToken, String sourceIp) { ... }

    public boolean isEnabled() { return !keyMap.isEmpty(); }
}
```

**Key validation uses constant-time comparison** (`MessageDigest.isEqual()`) to prevent timing attacks. Same pattern as `AdminApi.constantTimeEquals()`.

## Transport Integration — contextExtractor

The SDK's `HttpServletStreamableServerTransportProvider` has a `contextExtractor` builder option that runs on every HTTP request. This is where API key validation happens:

```java
var authenticator = new ApiKeyAuthenticator(config.auth().apiKeys());

var streamableTransport = HttpServletStreamableServerTransportProvider.builder()
        .mcpEndpoint("/mcp")
        .contextExtractor(request -> {
            if (!authenticator.isEnabled()) {
                return CallerIdentity.anonymous("streamable-http");
            }
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw new UnauthorizedException("Missing or invalid Authorization header");
            }
            String token = authHeader.substring("Bearer ".length());
            return authenticator.authenticate(token, request.getRemoteAddr())
                    .orElseThrow(() -> new UnauthorizedException("Invalid API key"));
        })
        .build();
```

**Note:** The `contextExtractor` returns an `Object` that is attached to the `McpTransportContext`. The tool handlers retrieve it via the `McpSyncServerExchange`. The exact retrieval API depends on the SDK — we need to verify how `contextExtractor` results are accessed from `exchange` during implementation. If the SDK doesn't provide a clean accessor, we fall back to a `ThreadLocal<CallerIdentity>` set by the extractor and read by the handlers.

## Query Pipeline Wrapper

Modified file: `src/main/java/com/indexer/mcp/QueryExecutor.java`

New method:

```java
public McpSchema.CallToolResult executeQuery(
        CallerIdentity caller, String repo, String action,
        Map<String, Object> params, Supplier<Object> query) {

    // 1. Authorization check — Phase B Part 2 will add per-session repo filtering
    //    For now: if caller is present, proceed (authentication already happened at transport layer)

    // 2. Future: audit write (Phase C) — AuditSink.recordAccess(event) goes here

    // 3. Execute the query
    try {
        Object result = query.get();
        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .isError(false)
                .build();
    } catch (Exception e) {
        // Future: AuditSink.recordDenial(event) for auth failures
        log.error("Tool execution error in {}: {}", action, e.getMessage(), e);
        return McpSchema.CallToolResult.builder()
                .addTextContent("Error: " + e.getMessage())
                .isError(true)
                .build();
    }
}
```

This consolidates `jsonResult()` and `errorResult()` from `McpServerBootstrap` into the pipeline — single place for result formatting, error handling, and (future) audit + authorization.

## McpServerBootstrap Changes

Each tool handler simplifies to extract identity and delegate to the pipeline:

```java
private McpSchema.CallToolResult handleSearchSymbols(
        McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
    var args = request.arguments();
    CallerIdentity caller = extractIdentity(exchange);
    String repo = stringArg(args, "repo");
    return queryExecutor.executeQuery(caller, repo, "search_symbols",
            args, () -> queryExecutor.searchSymbols(
                    stringArg(args, "query"), stringArg(args, "kind"),
                    stringArg(args, "language"), repo,
                    stringArg(args, "branch"), intArg(args, "limit", 20)));
}
```

The `extractIdentity(exchange)` method reads the `CallerIdentity` from the transport context. For stdio, it returns `CallerIdentity.fromStdio()`.

The `jsonResult()` and `errorResult()` helper methods in `McpServerBootstrap` are removed — their logic moves into `QueryExecutor.executeQuery()`.

## Error Handling

**Authentication failure (invalid/missing API key):**
The `contextExtractor` throws `UnauthorizedException` before the MCP session is established. The SDK handles this at the HTTP layer — the client receives a 401 response, never reaches a tool handler.

**Authorization failure (future — wrong repo):**
The `executeQuery` wrapper returns an error `CallToolResult` with `isError(true)` and a message like "Access denied to repository X". The MCP session stays alive — the client can retry with a different repo.

## Config Validation

At startup, if `auth.apiKeys` is configured:
- Validate each key is non-empty after env var substitution
- Validate each id is non-empty and unique
- Warn if any key appears to be an un-substituted env var reference (e.g., still contains `${`)
- Log the number of configured keys (NOT the key values)

## Files Changed

### New Files
- `src/main/java/com/indexer/auth/CallerIdentity.java` — identity record with factory methods
- `src/main/java/com/indexer/auth/ApiKeyAuthenticator.java` — key validation with constant-time comparison
- `src/main/java/com/indexer/auth/UnauthorizedException.java` — thrown by contextExtractor on auth failure
- `src/test/java/com/indexer/auth/ApiKeyAuthenticatorTest.java` — unit tests

### Modified Files
- `src/main/java/com/indexer/config/IndexerConfig.java` — add `AuthConfig` record
- `src/main/java/com/indexer/config/ConfigLoader.java` — parse `auth` section
- `src/main/java/com/indexer/Application.java` — create `ApiKeyAuthenticator`, pass to transport builder `contextExtractor`
- `src/main/java/com/indexer/mcp/QueryExecutor.java` — add `executeQuery` pipeline method
- `src/main/java/com/indexer/mcp/McpServerBootstrap.java` — simplify handlers to use `executeQuery`, add `extractIdentity()`, remove `jsonResult()`/`errorResult()`
- `src/main/java/com/indexer/server/HttpServer.java` — no changes needed (auth is at transport layer, not HTTP layer)

## Testing Strategy

1. **Unit tests** for `ApiKeyAuthenticator`:
   - Valid key returns correct identity
   - Invalid key returns empty
   - Empty key store means `isEnabled()` is false
   - Constant-time comparison (not directly testable, but implementation uses `MessageDigest.isEqual`)

2. **Unit tests** for `CallerIdentity` factory methods:
   - `fromStdio()` returns OS username
   - `anonymous()` returns authMethod "none"
   - `fromApiKey()` returns authMethod "api-key"

3. **Integration test** — extend `StreamableHttpTransportIntegrationTest`:
   - Configure API keys, verify authenticated requests succeed
   - Verify unauthenticated requests return 401
   - Verify invalid key returns 401

4. **Backward compatibility** — existing tests pass without any auth config (anonymous mode)

## Success Criteria

- `CallerIdentity` flows from transport layer through tool handlers to `QueryExecutor`
- API key auth validates Bearer tokens with constant-time comparison
- `executeQuery` wrapper handles all tool calls (auth check + result formatting + error handling)
- Existing tests pass without auth config (backward compatible)
- New tests verify API key auth works end-to-end
- No auth check on stdio transport (trusted subprocess model)
- Config validation fails fast on invalid key configuration
