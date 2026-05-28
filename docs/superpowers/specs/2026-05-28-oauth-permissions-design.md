# OAuth 2.1 JWT Validation + Per-Session Permission Resolution Design

## Context

Phase B Part 2 of the enterprise hardening roadmap. Adds OAuth 2.1 JWT validation as a second authentication method (alongside API keys), extracts group memberships from JWT claims, and resolves group-to-repo permissions via the git platform API using the server's own service account.

### Depends On

- Phase B Part 1 (complete): CallerIdentity, ApiKeyAuthenticator, contextExtractor, executeQuery pipeline

### Enables

- Phase C: Audit logging — audit records will include caller identity with groups and authorization decisions
- Phase D: Cross-branch queries — repo-level authorization enforcement is in place

## OAuth 2.1 JWT Validation (B3)

### Auth Flow

The existing `contextExtractor` in `Application.java` is extended to handle both API keys and JWTs:

```
Request arrives with Authorization: Bearer <token>
  ├── Token contains '.' characters (JWT format)? → validate JWT
  └── Token is opaque string? → check API key store (existing)
```

### JWT Validation

Uses **nimbus-jose-jwt** (standard Java library, no Spring dependency) for:
- JWKS endpoint key fetching with automatic caching and rotation
- Signature validation (RS256, ES256)
- Standard claims validation: expiry (`exp`), not-before (`nbf`), issuer (`iss`), audience (`aud`)
- Group extraction from a configurable claim name

### New Dependency

```kotlin
implementation("com.nimbusds:nimbus-jose-jwt:10.3")
```

### JwtValidator Class

New file: `src/main/java/com/indexer/auth/JwtValidator.java`

```java
public class JwtValidator {
    /**
     * Validate a JWT and extract identity + groups.
     * @throws JwtValidationException if token is invalid, expired, or untrusted
     */
    public CallerIdentity validate(String jwt, String sourceIp) { ... }
}
```

Constructor takes JWKS URL, expected issuer, expected audience, and groups claim name. Internally uses nimbus-jose-jwt's `ConfigurableJWTProcessor` with `JWKSKeySelector` for key resolution.

### CallerIdentity Extension

Add `groups` field to CallerIdentity:

```java
public record CallerIdentity(
        String userId,
        String displayName,
        String authMethod,
        String transport,
        String sourceIp,
        String clientName,
        String clientVersion,
        List<String> groups
) {
    public static final String CONTEXT_KEY = "callerIdentity";

    public static CallerIdentity anonymous(String transport) {
        return new CallerIdentity(null, "anonymous", "none", transport, null, null, null, List.of());
    }

    public static CallerIdentity fromStdio() {
        String osUser = System.getProperty("user.name");
        return new CallerIdentity(osUser, osUser, "stdio-os-user", "stdio", null, null, null, List.of());
    }

    public static CallerIdentity fromApiKey(String id, String name, String sourceIp) {
        return new CallerIdentity(id, name, "api-key", "streamable-http", sourceIp, null, null, List.of());
    }

    public static CallerIdentity fromOAuth(String sub, String name, List<String> groups, String sourceIp) {
        return new CallerIdentity(sub, name, "oauth", "streamable-http", sourceIp, null, null,
                groups != null ? List.copyOf(groups) : List.of());
    }
}
```

### Extended contextExtractor

In `Application.java`, the contextExtractor becomes:

```java
.contextExtractor(request -> {
    CallerIdentity identity;
    if (!authenticator.isEnabled() && jwtValidator == null) {
        identity = CallerIdentity.anonymous("streamable-http");
    } else {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("Missing or invalid Authorization header");
        }
        String token = authHeader.substring("Bearer ".length());
        String sourceIp = request.getRemoteAddr();

        if (jwtValidator != null && token.contains(".")) {
            // JWT token — validate and extract groups
            identity = jwtValidator.validate(token, sourceIp);
        } else if (authenticator.isEnabled()) {
            // Opaque token — check API key store
            identity = authenticator.authenticate(token, sourceIp)
                    .orElseThrow(() -> new RuntimeException("Invalid API key"));
        } else {
            throw new RuntimeException("No authentication method available for token");
        }
    }
    return McpTransportContext.create(Map.of(CallerIdentity.CONTEXT_KEY, identity));
})
```

### OAuth Configuration

New fields in `McpAuthConfig`:

```java
public record McpAuthConfig(List<ApiKeyEntry> apiKeys, OAuthConfig oauth, PermissionsConfig permissions) {
    public record ApiKeyEntry(String key, String id, String name) {}
    public record OAuthConfig(String jwksUrl, String issuer, String audience, String groupsClaim) {}
    public record PermissionsConfig(String type, String serviceAccountToken, String org, List<String> openRepos) {}
}
```

YAML config:

```yaml
auth:
  apiKeys: [...]                    # existing, optional

  oauth:                             # optional — enables JWT validation
    jwksUrl: "https://idp.example.com/.well-known/jwks.json"
    issuer: "https://idp.example.com"
    audience: "source-code-indexer"
    groupsClaim: "groups"            # defaults to "groups"

  permissions:                       # optional — enables repo-level authorization
    type: github                     # "github" or "none" (default)
    serviceAccountToken: ${GH_TOKEN} # server's own token, NOT user's
    org: "your-org"
    overrides:
      openRepos: ["shared-libs"]     # accessible to all authenticated users
```

**When `oauth` is absent:** JWT validation is disabled. Only API keys work (or anonymous if no auth at all).

**When `permissions` is absent:** No repo-level authorization. All authenticated users can query all repos.

## PermissionResolver SPI (B4)

### Interface

New file: `src/main/java/com/indexer/auth/PermissionResolver.java`

```java
public interface PermissionResolver {
    /**
     * Resolve which indexed repos the given groups can access.
     * Returns repo names that intersect with the set of indexed repos.
     * O(groups) API calls — one "repos for team" call per group.
     *
     * @throws PermissionResolutionException if the platform API is unreachable (fail-closed)
     */
    Set<String> resolveAllowedRepos(List<String> groups);
}
```

### GitHubPermissionResolver

New file: `src/main/java/com/indexer/auth/GitHubPermissionResolver.java`

Calls `GET /orgs/{org}/teams/{team_slug}/repos` per group using the server's service account token. Paginates automatically. Intersects results with indexed repos (from `RepositoryDao.findAll()`).

```java
public class GitHubPermissionResolver implements PermissionResolver {
    private final String org;
    private final String serviceAccountToken;
    private final RepositoryDao repositoryDao;
    private final HttpClient httpClient;

    @Override
    public Set<String> resolveAllowedRepos(List<String> groups) {
        Set<String> indexedRepos = repositoryDao.findAll().stream()
                .map(Repository::name)
                .collect(Collectors.toSet());

        Set<String> allowed = new HashSet<>();
        for (String group : groups) {
            Set<String> teamRepos = fetchTeamRepos(group);
            for (String repo : teamRepos) {
                if (indexedRepos.contains(repo)) {
                    allowed.add(repo);
                }
            }
        }
        return Set.copyOf(allowed);
    }
}
```

Uses JDK `HttpClient` (no additional dependency). Calls the GitHub API with `Authorization: Bearer <serviceAccountToken>` header. Handles pagination via `Link` header.

### NoOpPermissionResolver

New file: `src/main/java/com/indexer/auth/NoOpPermissionResolver.java`

Returns all indexed repos. Used when `permissions.type` is `"none"` or absent.

```java
public class NoOpPermissionResolver implements PermissionResolver {
    private final RepositoryDao repositoryDao;

    @Override
    public Set<String> resolveAllowedRepos(List<String> groups) {
        return repositoryDao.findAll().stream()
                .map(Repository::name)
                .collect(Collectors.toSet());
    }
}
```

## Per-Session Permission Cache

New file: `src/main/java/com/indexer/auth/PermissionCache.java`

Caches resolved permissions per user with a configurable TTL, approximating per-session behavior.

```java
public class PermissionCache {
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final PermissionResolver resolver;
    private final Set<String> openRepos;
    private final Duration ttl;

    record CacheEntry(Set<String> allowedRepos, Instant resolvedAt) {}

    public Set<String> getAllowedRepos(CallerIdentity caller) {
        if (caller.groups().isEmpty()) {
            return Set.copyOf(openRepos);  // no groups = open repos only
        }

        String cacheKey = caller.userId();
        var entry = cache.get(cacheKey);
        if (entry != null && entry.resolvedAt().plus(ttl).isAfter(Instant.now())) {
            return entry.allowedRepos();
        }

        // Cache miss or expired — resolve via platform API (fail-closed: exception propagates)
        Set<String> resolved = resolver.resolveAllowedRepos(caller.groups());
        Set<String> combined = new HashSet<>(resolved);
        combined.addAll(openRepos);
        Set<String> result = Set.copyOf(combined);
        cache.put(cacheKey, new CacheEntry(result, Instant.now()));
        return result;
    }
}
```

TTL defaults to 30 minutes. Open repos are always included regardless of group membership.

## Authorization Enforcement in executeQuery

In `QueryExecutor.executeQuery()`:

```java
public McpSchema.CallToolResult executeQuery(
        CallerIdentity caller, String repo, String action,
        Map<String, Object> params, Supplier<Object> query) {

    log.info("Tool call: {} by {} ({})", action, caller.displayName(), caller.authMethod());

    // Authorization check — only for OAuth users (API key and stdio users have full access)
    if (permissionCache != null && repo != null && "oauth".equals(caller.authMethod())) {
        Set<String> allowed = permissionCache.getAllowedRepos(caller);
        if (!allowed.contains(repo)) {
            log.warn("Access denied: {} attempted to query repo {}", caller.displayName(), repo);
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Access denied to repository: " + repo)
                    .isError(true)
                    .build();
        }
    }

    // Execute query
    try {
        Object result = query.get();
        // ... existing result formatting
    } catch (Exception e) {
        // ... existing error handling
    }
}
```

**Who gets checked:**
- `authMethod = "oauth"` → permission check enforced
- `authMethod = "api-key"` → no check (admin-assigned, trusted)
- `authMethod = "stdio-os-user"` → no check (trusted subprocess)
- `authMethod = "none"` → no check (anonymous, only when auth is disabled)

**Fail-closed:** If `PermissionResolver.resolveAllowedRepos()` throws (platform API unreachable), the exception propagates through `PermissionCache.getAllowedRepos()` and `executeQuery()` returns an error result. No fallback to "allow all."

**Redaction (future):** When cross-repo queries are added (Phase D), results from unauthorized repos will be filtered out with a count of excluded repos but no repo names.

## Config Validation at Startup

In `Application.java`, after loading config:

**OAuth validation:**
- If `oauth` is configured, `jwksUrl` is required (fail-fast)
- `issuer` and `audience` are required
- `groupsClaim` defaults to `"groups"` if absent
- Log: "OAuth JWT validation enabled (issuer: X, audience: Y)"

**Permissions validation:**
- If `permissions.type` is `"github"`, `serviceAccountToken` and `org` are required
- Token must not contain `${` after env var substitution (un-substituted reference)
- Log: "GitHub permission resolution enabled (org: X)"

**Incompatible config:**
- If `permissions` is configured but `oauth` is not → warn: "Permission resolution configured but OAuth is disabled — permissions will not be enforced (API key and stdio users have full access)"

## Files Changed

### New Files
- `src/main/java/com/indexer/auth/JwtValidator.java` — JWT validation via nimbus-jose-jwt
- `src/main/java/com/indexer/auth/JwtValidationException.java` — typed exception for validation failures
- `src/main/java/com/indexer/auth/PermissionResolver.java` — SPI interface
- `src/main/java/com/indexer/auth/GitHubPermissionResolver.java` — GitHub implementation
- `src/main/java/com/indexer/auth/NoOpPermissionResolver.java` — passthrough implementation
- `src/main/java/com/indexer/auth/PermissionCache.java` — TTL-based per-user cache
- `src/test/java/com/indexer/auth/JwtValidatorTest.java` — unit tests
- `src/test/java/com/indexer/auth/PermissionCacheTest.java` — unit tests
- `src/test/java/com/indexer/auth/GitHubPermissionResolverTest.java` — unit tests with mocked HTTP

### Modified Files
- `build.gradle.kts` — add nimbus-jose-jwt dependency
- `src/main/java/com/indexer/auth/CallerIdentity.java` — add `groups` field, `fromOAuth()` factory
- `src/main/java/com/indexer/config/IndexerConfig.java` — add `OAuthConfig` and `PermissionsConfig` records to `McpAuthConfig`
- `src/main/java/com/indexer/config/ConfigLoader.java` — parse `oauth` and `permissions` sections
- `src/main/java/com/indexer/Application.java` — create JwtValidator, PermissionResolver, PermissionCache; extend contextExtractor
- `src/main/java/com/indexer/mcp/QueryExecutor.java` — accept PermissionCache, add authorization check in executeQuery
- `src/test/java/com/indexer/auth/CallerIdentityTest.java` — add test for fromOAuth
- `src/test/java/com/indexer/auth/ApiKeyAuthenticatorTest.java` — update CallerIdentity construction if needed

## Testing Strategy

1. **JwtValidator unit tests** — use a local in-memory JWK set (no real IdP). Test: valid JWT returns identity with groups, expired JWT rejected, wrong issuer rejected, missing groups claim returns empty list.

2. **PermissionCache unit tests** — mock PermissionResolver. Test: cache miss triggers resolution, cache hit returns cached result, expired entry re-resolves, open repos always included, empty groups returns open repos only.

3. **GitHubPermissionResolver unit tests** — mock HTTP responses. Test: single group resolution, multi-group resolution, pagination handling, API error throws PermissionResolutionException (fail-closed), intersection with indexed repos.

4. **Integration test** — extend `StreamableHttpTransportIntegrationTest` with a test that uses a JWT (signed with a test key) and verifies the full flow: JWT validation → identity extraction → permission check → query execution.

5. **Backward compatibility** — all existing tests pass without any OAuth or permissions config.

## Success Criteria

- JWT Bearer tokens validated via nimbus-jose-jwt with JWKS key resolution
- Groups extracted from configurable JWT claim
- CallerIdentity carries groups through the entire pipeline
- PermissionResolver SPI with GitHub implementation
- Per-session permission cache with TTL
- OAuth users can only query repos their groups have access to
- API key and stdio users have unrestricted access
- Fail-closed when platform API is unreachable
- Open repos accessible to all authenticated users
- All existing tests pass without config changes (backward compatible)
