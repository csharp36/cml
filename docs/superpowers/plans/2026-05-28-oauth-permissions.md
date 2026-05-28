# OAuth 2.1 JWT + Per-Session Permissions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add OAuth 2.1 JWT validation as a second auth method (alongside API keys), extract group memberships from JWT claims, and resolve group-to-repo permissions via the git platform API using the server's service account.

**Architecture:** `JwtValidator` validates JWTs via nimbus-jose-jwt with JWKS key fetching. `PermissionResolver` SPI resolves groups to repos via the git platform API. `PermissionCache` caches per-user with TTL. `executeQuery` enforces repo-level authorization for OAuth users only.

**Tech Stack:** Java 21, nimbus-jose-jwt 10.9, MCP SDK 1.1.2, JDK HttpClient for GitHub API

**Spec:** `docs/superpowers/specs/2026-05-28-oauth-permissions-design.md`

---

### Task 1: Add nimbus-jose-jwt Dependency + Extend CallerIdentity with Groups

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/java/com/indexer/auth/CallerIdentity.java`
- Modify: `src/test/java/com/indexer/auth/CallerIdentityTest.java`

- [ ] **Step 1: Add nimbus-jose-jwt dependency**

In `build.gradle.kts`, add after the MCP SDK dependencies:

```kotlin
// JWT validation
implementation("com.nimbusds:nimbus-jose-jwt:10.9")
```

- [ ] **Step 2: Add groups field to CallerIdentity**

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

- [ ] **Step 3: Add test for fromOAuth factory**

Add to `CallerIdentityTest.java`:

```java
@Test
void fromOAuthCarriesGroups() {
    var identity = CallerIdentity.fromOAuth("alice", "Alice Chen",
            List.of("team-payments", "team-platform"), "10.0.0.1");
    assertThat(identity.userId()).isEqualTo("alice");
    assertThat(identity.displayName()).isEqualTo("Alice Chen");
    assertThat(identity.authMethod()).isEqualTo("oauth");
    assertThat(identity.transport()).isEqualTo("streamable-http");
    assertThat(identity.groups()).containsExactly("team-payments", "team-platform");
}

@Test
void fromOAuthWithNullGroupsDefaultsToEmpty() {
    var identity = CallerIdentity.fromOAuth("bob", "Bob", null, "10.0.0.1");
    assertThat(identity.groups()).isEmpty();
}

@Test
void existingFactoriesHaveEmptyGroups() {
    assertThat(CallerIdentity.fromStdio().groups()).isEmpty();
    assertThat(CallerIdentity.anonymous("http").groups()).isEmpty();
    assertThat(CallerIdentity.fromApiKey("id", "name", "ip").groups()).isEmpty();
}
```

- [ ] **Step 4: Fix any compilation errors from the groups field addition**

The `groups` field changes the CallerIdentity constructor signature. Any code constructing CallerIdentity directly (not via factory methods) will need updating. Check:
- `ApiKeyAuthenticator` — uses `CallerIdentity.fromApiKey()` (factory method, OK)
- `McpServerBootstrap.extractIdentity()` — uses `CallerIdentity.fromStdio()` (factory method, OK)
- `Application.java` contextExtractor — uses `CallerIdentity.anonymous()` and `authenticator.authenticate()` (factory methods, OK)
- Tests — use factory methods (OK)

Run: `./gradlew compileJava compileTestJava 2>&1 | tail -5`

- [ ] **Step 5: Run all tests**

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts src/main/java/com/indexer/auth/CallerIdentity.java src/test/java/com/indexer/auth/CallerIdentityTest.java
git commit -m "feat: add groups field to CallerIdentity and nimbus-jose-jwt dependency"
```

---

### Task 2: JwtValidator

**Files:**
- Create: `src/main/java/com/indexer/auth/JwtValidator.java`
- Create: `src/main/java/com/indexer/auth/JwtValidationException.java`
- Create: `src/test/java/com/indexer/auth/JwtValidatorTest.java`

- [ ] **Step 1: Write JwtValidationException**

```java
package com.indexer.auth;

public class JwtValidationException extends RuntimeException {
    public JwtValidationException(String message) {
        super(message);
    }

    public JwtValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 2: Write JwtValidator tests**

```java
package com.indexer.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtValidatorTest {

    private static final String ISSUER = "https://idp.example.com";
    private static final String AUDIENCE = "source-code-indexer";

    private static RSAKey testKey;
    private static JWKSource<SecurityContext> testKeySource;

    @BeforeAll
    static void generateKey() throws Exception {
        testKey = new RSAKeyGenerator(2048)
                .keyUse(KeyUse.SIGNATURE)
                .keyID(UUID.randomUUID().toString())
                .algorithm(JWSAlgorithm.RS256)
                .generate();
        testKeySource = new ImmutableJWKSet<>(new JWKSet(testKey.toPublicJWK()));
    }

    @Test
    void validTokenExtractsIdentityAndGroups() {
        String token = mintToken("alice", "Alice Chen",
                List.of("team-payments", "team-platform"), ISSUER, AUDIENCE, 300);

        var validator = new JwtValidator(testKeySource, ISSUER, AUDIENCE, "groups");
        CallerIdentity identity = validator.validate(token, "10.0.0.1");

        assertThat(identity.userId()).isEqualTo("alice");
        assertThat(identity.displayName()).isEqualTo("Alice Chen");
        assertThat(identity.authMethod()).isEqualTo("oauth");
        assertThat(identity.groups()).containsExactly("team-payments", "team-platform");
        assertThat(identity.sourceIp()).isEqualTo("10.0.0.1");
    }

    @Test
    void missingGroupsClaimReturnsEmptyList() {
        String token = mintToken("bob", "Bob", null, ISSUER, AUDIENCE, 300);

        var validator = new JwtValidator(testKeySource, ISSUER, AUDIENCE, "groups");
        CallerIdentity identity = validator.validate(token, "10.0.0.1");

        assertThat(identity.groups()).isEmpty();
    }

    @Test
    void expiredTokenThrows() {
        String token = mintToken("alice", "Alice", List.of(), ISSUER, AUDIENCE, -10);

        var validator = new JwtValidator(testKeySource, ISSUER, AUDIENCE, "groups");

        assertThatThrownBy(() -> validator.validate(token, "10.0.0.1"))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void wrongIssuerThrows() {
        String token = mintToken("alice", "Alice", List.of(), "https://wrong-issuer.com", AUDIENCE, 300);

        var validator = new JwtValidator(testKeySource, ISSUER, AUDIENCE, "groups");

        assertThatThrownBy(() -> validator.validate(token, "10.0.0.1"))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void wrongAudienceThrows() {
        String token = mintToken("alice", "Alice", List.of(), ISSUER, "wrong-audience", 300);

        var validator = new JwtValidator(testKeySource, ISSUER, AUDIENCE, "groups");

        assertThatThrownBy(() -> validator.validate(token, "10.0.0.1"))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void malformedTokenThrows() {
        var validator = new JwtValidator(testKeySource, ISSUER, AUDIENCE, "groups");

        assertThatThrownBy(() -> validator.validate("not.a.jwt", "10.0.0.1"))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void customGroupsClaimName() {
        // Use "roles" instead of "groups"
        String token = mintTokenWithClaim("alice", "Alice", "roles",
                List.of("admin", "reader"), ISSUER, AUDIENCE, 300);

        var validator = new JwtValidator(testKeySource, ISSUER, AUDIENCE, "roles");
        CallerIdentity identity = validator.validate(token, "10.0.0.1");

        assertThat(identity.groups()).containsExactly("admin", "reader");
    }

    // --- Test helpers ---

    private static String mintToken(String sub, String name, List<String> groups,
                                    String issuer, String audience, int ttlSeconds) {
        return mintTokenWithClaim(sub, name, "groups", groups, issuer, audience, ttlSeconds);
    }

    private static String mintTokenWithClaim(String sub, String name, String groupsClaimName,
                                             List<String> groups, String issuer, String audience,
                                             int ttlSeconds) {
        try {
            var builder = new JWTClaimsSet.Builder()
                    .subject(sub)
                    .claim("name", name)
                    .issuer(issuer)
                    .audience(audience)
                    .expirationTime(new Date(System.currentTimeMillis() + ttlSeconds * 1000L))
                    .issueTime(new Date());

            if (groups != null) {
                builder.claim(groupsClaimName, groups);
            }

            var jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(testKey.getKeyID())
                            .build(),
                    builder.build());
            jwt.sign(new RSASSASigner(testKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to mint test token", e);
        }
    }
}
```

- [ ] **Step 3: Implement JwtValidator**

```java
package com.indexer.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimNames;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public class JwtValidator {

    private static final Logger log = LoggerFactory.getLogger(JwtValidator.class);

    private final ConfigurableJWTProcessor<SecurityContext> processor;
    private final String groupsClaim;

    /** Production constructor — fetches keys from JWKS URL. */
    public JwtValidator(String jwksUrl, String issuer, String audience, String groupsClaim) {
        try {
            JWKSource<SecurityContext> keySource = JWKSourceBuilder
                    .create(new URL(jwksUrl))
                    .retrying(true)
                    .build();
            this.processor = buildProcessor(keySource, issuer, audience);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize JWT validator with JWKS URL: " + jwksUrl, e);
        }
        this.groupsClaim = groupsClaim;
        log.info("JWT validator initialized (issuer: {}, audience: {})", issuer, audience);
    }

    /** Test constructor — uses a provided JWKSource (e.g., ImmutableJWKSet). */
    public JwtValidator(JWKSource<SecurityContext> keySource, String issuer, String audience, String groupsClaim) {
        this.processor = buildProcessor(keySource, issuer, audience);
        this.groupsClaim = groupsClaim;
    }

    public CallerIdentity validate(String jwt, String sourceIp) {
        try {
            JWTClaimsSet claims = processor.process(jwt, null);

            String sub = claims.getSubject();
            String name = claims.getStringClaim("name");
            if (name == null) name = sub;

            List<String> groups = claims.getStringListClaim(groupsClaim);
            if (groups == null) groups = List.of();

            return CallerIdentity.fromOAuth(sub, name, groups, sourceIp);
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null) message = e.getClass().getSimpleName();
            throw new JwtValidationException("JWT validation failed: " + message, e);
        }
    }

    private static ConfigurableJWTProcessor<SecurityContext> buildProcessor(
            JWKSource<SecurityContext> keySource, String issuer, String audience) {
        var keySelector = new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);

        var processor = new DefaultJWTProcessor<SecurityContext>();
        processor.setJWSKeySelector(keySelector);
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                audience,
                new JWTClaimsSet.Builder().issuer(issuer).build(),
                new HashSet<>(Arrays.asList(
                        JWTClaimNames.SUBJECT,
                        JWTClaimNames.EXPIRATION_TIME,
                        JWTClaimNames.ISSUED_AT
                ))
        ));
        return processor;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "com.indexer.auth.JwtValidatorTest" --rerun 2>&1 | tail -10`
Expected: All 7 tests pass.

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL — no regressions.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/auth/JwtValidator.java src/main/java/com/indexer/auth/JwtValidationException.java src/test/java/com/indexer/auth/JwtValidatorTest.java
git commit -m "feat: add JwtValidator for OAuth 2.1 JWT validation via nimbus-jose-jwt"
```

---

### Task 3: PermissionResolver SPI + Implementations

**Files:**
- Create: `src/main/java/com/indexer/auth/PermissionResolver.java`
- Create: `src/main/java/com/indexer/auth/PermissionResolutionException.java`
- Create: `src/main/java/com/indexer/auth/NoOpPermissionResolver.java`
- Create: `src/main/java/com/indexer/auth/GitHubPermissionResolver.java`
- Create: `src/test/java/com/indexer/auth/GitHubPermissionResolverTest.java`

- [ ] **Step 1: Create the interface and exception**

```java
package com.indexer.auth;

import java.util.List;
import java.util.Set;

public interface PermissionResolver {
    /**
     * Resolve which indexed repos the given groups can access.
     * O(groups) API calls — one "repos for team" call per group.
     *
     * @throws PermissionResolutionException if the platform API is unreachable (fail-closed)
     */
    Set<String> resolveAllowedRepos(List<String> groups);
}
```

```java
package com.indexer.auth;

public class PermissionResolutionException extends RuntimeException {
    public PermissionResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 2: Create NoOpPermissionResolver**

```java
package com.indexer.auth;

import com.indexer.db.RepositoryDao;
import com.indexer.model.Repository;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class NoOpPermissionResolver implements PermissionResolver {
    private final RepositoryDao repositoryDao;

    public NoOpPermissionResolver(RepositoryDao repositoryDao) {
        this.repositoryDao = repositoryDao;
    }

    @Override
    public Set<String> resolveAllowedRepos(List<String> groups) {
        return repositoryDao.findAll().stream()
                .map(Repository::name)
                .collect(Collectors.toUnmodifiableSet());
    }
}
```

- [ ] **Step 3: Write GitHubPermissionResolver tests**

```java
package com.indexer.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indexer.db.RepositoryDao;
import com.indexer.model.Repository;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GitHubPermissionResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void resolvesReposForSingleGroup() throws Exception {
        var repositoryDao = mock(RepositoryDao.class);
        when(repositoryDao.findAll()).thenReturn(List.of(
                repo("payments-api"), repo("platform-lib"), repo("other-repo")));

        var httpClient = mock(HttpClient.class);
        var httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(MAPPER.writeValueAsString(List.of(
                java.util.Map.of("name", "payments-api"),
                java.util.Map.of("name", "platform-lib"),
                java.util.Map.of("name", "unindexed-repo")  // not indexed — should be filtered
        )));
        when(httpResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        var resolver = new GitHubPermissionResolver("my-org", "ghp_token", repositoryDao, httpClient);
        Set<String> repos = resolver.resolveAllowedRepos(List.of("team-payments"));

        assertThat(repos).containsExactlyInAnyOrder("payments-api", "platform-lib");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mergesReposFromMultipleGroups() throws Exception {
        var repositoryDao = mock(RepositoryDao.class);
        when(repositoryDao.findAll()).thenReturn(List.of(
                repo("payments-api"), repo("platform-lib"), repo("infra-tools")));

        var httpClient = mock(HttpClient.class);
        var httpResponse1 = mock(HttpResponse.class);
        when(httpResponse1.statusCode()).thenReturn(200);
        when(httpResponse1.body()).thenReturn(MAPPER.writeValueAsString(
                List.of(java.util.Map.of("name", "payments-api"))));
        when(httpResponse1.headers()).thenReturn(java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true));

        var httpResponse2 = mock(HttpResponse.class);
        when(httpResponse2.statusCode()).thenReturn(200);
        when(httpResponse2.body()).thenReturn(MAPPER.writeValueAsString(
                List.of(java.util.Map.of("name", "infra-tools"))));
        when(httpResponse2.headers()).thenReturn(java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse1, httpResponse2);

        var resolver = new GitHubPermissionResolver("my-org", "ghp_token", repositoryDao, httpClient);
        Set<String> repos = resolver.resolveAllowedRepos(List.of("team-payments", "team-infra"));

        assertThat(repos).containsExactlyInAnyOrder("payments-api", "infra-tools");
    }

    @Test
    @SuppressWarnings("unchecked")
    void apiErrorThrowsPermissionResolutionException() throws Exception {
        var repositoryDao = mock(RepositoryDao.class);
        when(repositoryDao.findAll()).thenReturn(List.of(repo("payments-api")));

        var httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("Connection refused"));

        var resolver = new GitHubPermissionResolver("my-org", "ghp_token", repositoryDao, httpClient);

        assertThatThrownBy(() -> resolver.resolveAllowedRepos(List.of("team-payments")))
                .isInstanceOf(PermissionResolutionException.class)
                .hasMessageContaining("Connection refused");
    }

    private Repository repo(String name) {
        return new Repository(0, name, "https://github.com/org/" + name, "main", "/repos/" + name, "ssh-key", null, null);
    }
}
```

- [ ] **Step 4: Implement GitHubPermissionResolver**

```java
package com.indexer.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indexer.db.RepositoryDao;
import com.indexer.model.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;

public class GitHubPermissionResolver implements PermissionResolver {

    private static final Logger log = LoggerFactory.getLogger(GitHubPermissionResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String org;
    private final String serviceAccountToken;
    private final RepositoryDao repositoryDao;
    private final HttpClient httpClient;

    public GitHubPermissionResolver(String org, String serviceAccountToken,
                                    RepositoryDao repositoryDao, HttpClient httpClient) {
        this.org = org;
        this.serviceAccountToken = serviceAccountToken;
        this.repositoryDao = repositoryDao;
        this.httpClient = httpClient;
    }

    /** Production convenience constructor — creates its own HttpClient. */
    public GitHubPermissionResolver(String org, String serviceAccountToken,
                                    RepositoryDao repositoryDao) {
        this(org, serviceAccountToken, repositoryDao, HttpClient.newHttpClient());
    }

    @Override
    public Set<String> resolveAllowedRepos(List<String> groups) {
        Set<String> indexedRepos = repositoryDao.findAll().stream()
                .map(Repository::name)
                .collect(Collectors.toSet());

        Set<String> allowed = new HashSet<>();
        for (String group : groups) {
            try {
                Set<String> teamRepos = fetchTeamRepos(group);
                for (String repo : teamRepos) {
                    if (indexedRepos.contains(repo)) {
                        allowed.add(repo);
                    }
                }
            } catch (Exception e) {
                throw new PermissionResolutionException(
                        "Failed to resolve repos for group '" + group + "': " + e.getMessage(), e);
            }
        }
        log.info("Resolved {} allowed repos for {} groups", allowed.size(), groups.size());
        return Set.copyOf(allowed);
    }

    private Set<String> fetchTeamRepos(String teamSlug) throws Exception {
        Set<String> repos = new HashSet<>();
        String url = "https://api.github.com/orgs/" + org + "/teams/" + teamSlug + "/repos?per_page=100";

        while (url != null) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + serviceAccountToken)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("GitHub API returned {} for team {}", response.statusCode(), teamSlug);
                break;
            }

            List<Map<String, Object>> repoList = MAPPER.readValue(
                    response.body(), new TypeReference<>() {});
            for (var repo : repoList) {
                repos.add((String) repo.get("name"));
            }

            url = parseLinkNext(response.headers().firstValue("Link").orElse(null));
        }
        return repos;
    }

    /** Parse GitHub pagination Link header for the "next" URL. */
    private String parseLinkNext(String linkHeader) {
        if (linkHeader == null) return null;
        for (String part : linkHeader.split(",")) {
            part = part.trim();
            if (part.endsWith("rel=\"next\"")) {
                int start = part.indexOf('<');
                int end = part.indexOf('>');
                if (start >= 0 && end > start) {
                    return part.substring(start + 1, end);
                }
            }
        }
        return null;
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests "com.indexer.auth.GitHubPermissionResolverTest" --rerun 2>&1 | tail -10`
Expected: All 3 tests pass.

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/indexer/auth/PermissionResolver.java src/main/java/com/indexer/auth/PermissionResolutionException.java src/main/java/com/indexer/auth/NoOpPermissionResolver.java src/main/java/com/indexer/auth/GitHubPermissionResolver.java src/test/java/com/indexer/auth/GitHubPermissionResolverTest.java
git commit -m "feat: add PermissionResolver SPI with GitHub and NoOp implementations"
```

---

### Task 4: PermissionCache

**Files:**
- Create: `src/main/java/com/indexer/auth/PermissionCache.java`
- Create: `src/test/java/com/indexer/auth/PermissionCacheTest.java`

- [ ] **Step 1: Write PermissionCache tests**

```java
package com.indexer.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PermissionCacheTest {

    @Test
    void cacheMissTriggersResolution() {
        var resolver = mock(PermissionResolver.class);
        when(resolver.resolveAllowedRepos(List.of("team-a"))).thenReturn(Set.of("repo-1", "repo-2"));

        var cache = new PermissionCache(resolver, Set.of(), Duration.ofMinutes(30));
        var caller = CallerIdentity.fromOAuth("alice", "Alice", List.of("team-a"), "10.0.0.1");

        Set<String> result = cache.getAllowedRepos(caller);

        assertThat(result).containsExactlyInAnyOrder("repo-1", "repo-2");
        verify(resolver, times(1)).resolveAllowedRepos(List.of("team-a"));
    }

    @Test
    void cacheHitDoesNotReResolve() {
        var resolver = mock(PermissionResolver.class);
        when(resolver.resolveAllowedRepos(List.of("team-a"))).thenReturn(Set.of("repo-1"));

        var cache = new PermissionCache(resolver, Set.of(), Duration.ofMinutes(30));
        var caller = CallerIdentity.fromOAuth("alice", "Alice", List.of("team-a"), "10.0.0.1");

        cache.getAllowedRepos(caller);
        cache.getAllowedRepos(caller);

        verify(resolver, times(1)).resolveAllowedRepos(any());
    }

    @Test
    void openReposAlwaysIncluded() {
        var resolver = mock(PermissionResolver.class);
        when(resolver.resolveAllowedRepos(List.of("team-a"))).thenReturn(Set.of("repo-1"));

        var cache = new PermissionCache(resolver, Set.of("shared-lib"), Duration.ofMinutes(30));
        var caller = CallerIdentity.fromOAuth("alice", "Alice", List.of("team-a"), "10.0.0.1");

        Set<String> result = cache.getAllowedRepos(caller);

        assertThat(result).containsExactlyInAnyOrder("repo-1", "shared-lib");
    }

    @Test
    void emptyGroupsReturnsOpenReposOnly() {
        var resolver = mock(PermissionResolver.class);

        var cache = new PermissionCache(resolver, Set.of("shared-lib"), Duration.ofMinutes(30));
        var caller = CallerIdentity.fromOAuth("alice", "Alice", List.of(), "10.0.0.1");

        Set<String> result = cache.getAllowedRepos(caller);

        assertThat(result).containsExactly("shared-lib");
        verify(resolver, never()).resolveAllowedRepos(any());
    }

    @Test
    void resolverExceptionPropagatesFailClosed() {
        var resolver = mock(PermissionResolver.class);
        when(resolver.resolveAllowedRepos(any())).thenThrow(
                new PermissionResolutionException("GitHub API unreachable", null));

        var cache = new PermissionCache(resolver, Set.of(), Duration.ofMinutes(30));
        var caller = CallerIdentity.fromOAuth("alice", "Alice", List.of("team-a"), "10.0.0.1");

        assertThatThrownBy(() -> cache.getAllowedRepos(caller))
                .isInstanceOf(PermissionResolutionException.class);
    }

    @Test
    void expiredCacheEntryReResolves() throws InterruptedException {
        var resolver = mock(PermissionResolver.class);
        when(resolver.resolveAllowedRepos(List.of("team-a")))
                .thenReturn(Set.of("repo-1"))
                .thenReturn(Set.of("repo-1", "repo-2"));

        // 100ms TTL for testing
        var cache = new PermissionCache(resolver, Set.of(), Duration.ofMillis(100));
        var caller = CallerIdentity.fromOAuth("alice", "Alice", List.of("team-a"), "10.0.0.1");

        cache.getAllowedRepos(caller);
        Thread.sleep(150);
        Set<String> result = cache.getAllowedRepos(caller);

        assertThat(result).containsExactlyInAnyOrder("repo-1", "repo-2");
        verify(resolver, times(2)).resolveAllowedRepos(any());
    }
}
```

- [ ] **Step 2: Implement PermissionCache**

```java
package com.indexer.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PermissionCache {

    private static final Logger log = LoggerFactory.getLogger(PermissionCache.class);

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final PermissionResolver resolver;
    private final Set<String> openRepos;
    private final Duration ttl;

    private record CacheEntry(Set<String> allowedRepos, Instant resolvedAt) {}

    public PermissionCache(PermissionResolver resolver, Set<String> openRepos, Duration ttl) {
        this.resolver = resolver;
        this.openRepos = openRepos != null ? Set.copyOf(openRepos) : Set.of();
        this.ttl = ttl;
    }

    public Set<String> getAllowedRepos(CallerIdentity caller) {
        if (caller.groups().isEmpty()) {
            return Set.copyOf(openRepos);
        }

        String cacheKey = caller.userId();
        var entry = cache.get(cacheKey);
        if (entry != null && entry.resolvedAt().plus(ttl).isAfter(Instant.now())) {
            return entry.allowedRepos();
        }

        // Cache miss or expired — resolve via platform API (fail-closed: exception propagates)
        log.info("Resolving permissions for user {} ({} groups)", caller.userId(), caller.groups().size());
        Set<String> resolved = resolver.resolveAllowedRepos(caller.groups());
        Set<String> combined = new HashSet<>(resolved);
        combined.addAll(openRepos);
        Set<String> result = Set.copyOf(combined);
        cache.put(cacheKey, new CacheEntry(result, Instant.now()));
        return result;
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests "com.indexer.auth.PermissionCacheTest" --rerun 2>&1 | tail -10`
Expected: All 6 tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/indexer/auth/PermissionCache.java src/test/java/com/indexer/auth/PermissionCacheTest.java
git commit -m "feat: add PermissionCache with per-user TTL and open repos support"
```

---

### Task 5: Config Extension + Wiring + Authorization Enforcement

This task is tightly coupled — config parsing, Application wiring, and executeQuery enforcement all depend on each other.

**Files:**
- Modify: `src/main/java/com/indexer/config/IndexerConfig.java`
- Modify: `src/main/java/com/indexer/config/ConfigLoader.java`
- Modify: `src/main/java/com/indexer/Application.java`
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java`

- [ ] **Step 1: Extend McpAuthConfig with OAuthConfig and PermissionsConfig**

In `IndexerConfig.java`, replace the `McpAuthConfig` record:

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpAuthConfig(List<ApiKeyEntry> apiKeys, OAuthConfig oauth, PermissionsConfig permissions) {
    public McpAuthConfig {
        if (apiKeys == null) apiKeys = List.of();
    }
    public record ApiKeyEntry(String key, String id, String name) {}
    public record OAuthConfig(String jwksUrl, String issuer, String audience, String groupsClaim) {
        public OAuthConfig {
            if (groupsClaim == null) groupsClaim = "groups";
        }
    }
    public record PermissionsConfig(String type, String serviceAccountToken, String org, List<String> openRepos) {
        public PermissionsConfig {
            if (type == null) type = "none";
            if (openRepos == null) openRepos = List.of();
        }
    }
}
```

- [ ] **Step 2: Extend ConfigLoader.parseMcpAuth**

Replace the `parseMcpAuth` method in `ConfigLoader.java`:

```java
private IndexerConfig.McpAuthConfig parseMcpAuth(JsonNode node) {
    if (node == null) return null;

    // API keys
    List<IndexerConfig.McpAuthConfig.ApiKeyEntry> keys = new ArrayList<>();
    JsonNode apiKeysNode = node.get("apiKeys");
    if (apiKeysNode != null && apiKeysNode.isArray()) {
        for (JsonNode keyNode : apiKeysNode) {
            String key = textOrNull(keyNode, "key");
            String id = textOrNull(keyNode, "id");
            String name = textOrNull(keyNode, "name");
            if (key != null && id != null) {
                keys.add(new IndexerConfig.McpAuthConfig.ApiKeyEntry(key, id, name != null ? name : id));
            }
        }
    }

    // OAuth
    IndexerConfig.McpAuthConfig.OAuthConfig oauth = null;
    JsonNode oauthNode = node.get("oauth");
    if (oauthNode != null) {
        oauth = new IndexerConfig.McpAuthConfig.OAuthConfig(
                textOrNull(oauthNode, "jwksUrl"),
                textOrNull(oauthNode, "issuer"),
                textOrNull(oauthNode, "audience"),
                textOrNull(oauthNode, "groupsClaim")
        );
    }

    // Permissions
    IndexerConfig.McpAuthConfig.PermissionsConfig permissions = null;
    JsonNode permNode = node.get("permissions");
    if (permNode != null) {
        List<String> openRepos = new ArrayList<>();
        JsonNode overrides = permNode.get("overrides");
        if (overrides != null) {
            JsonNode openReposNode = overrides.get("openRepos");
            if (openReposNode != null && openReposNode.isArray()) {
                openReposNode.forEach(n -> { if (n.isTextual()) openRepos.add(n.textValue()); });
            }
        }
        permissions = new IndexerConfig.McpAuthConfig.PermissionsConfig(
                textOrNull(permNode, "type"),
                textOrNull(permNode, "serviceAccountToken"),
                textOrNull(permNode, "org"),
                openRepos
        );
    }

    return new IndexerConfig.McpAuthConfig(keys, oauth, permissions);
}
```

- [ ] **Step 3: Wire JwtValidator, PermissionResolver, and PermissionCache in Application.java**

In `Application.java`, after creating the `ApiKeyAuthenticator` (around line 116), add:

```java
// 5c. Set up JWT validator (if OAuth configured)
JwtValidator jwtValidator = null;
if (config.mcpAuth().oauth() != null && config.mcpAuth().oauth().jwksUrl() != null) {
    var oauth = config.mcpAuth().oauth();
    jwtValidator = new JwtValidator(oauth.jwksUrl(), oauth.issuer(), oauth.audience(), oauth.groupsClaim());
}

// 5d. Set up permission resolver and cache
PermissionCache permissionCache = null;
if (config.mcpAuth().permissions() != null && !"none".equals(config.mcpAuth().permissions().type())) {
    var permConfig = config.mcpAuth().permissions();
    PermissionResolver resolver;
    if ("github".equals(permConfig.type())) {
        resolver = new GitHubPermissionResolver(permConfig.org(), permConfig.serviceAccountToken(), repositoryDao);
    } else {
        resolver = new NoOpPermissionResolver(repositoryDao);
    }
    Set<String> openRepos = new HashSet<>(permConfig.openRepos());
    permissionCache = new PermissionCache(resolver, openRepos, Duration.ofMinutes(30));
}
```

Add imports:
```java
import com.indexer.auth.*;
import java.time.Duration;
import java.util.Set;
import java.util.HashSet;
```

Update the contextExtractor to handle JWTs (capture `jwtValidator` as effectively final):

```java
final JwtValidator finalJwtValidator = jwtValidator;

var streamableTransport = HttpServletStreamableServerTransportProvider.builder()
        .mcpEndpoint("/mcp")
        .contextExtractor(request -> {
            CallerIdentity identity;
            if (!authenticator.isEnabled() && finalJwtValidator == null) {
                identity = CallerIdentity.anonymous("streamable-http");
            } else {
                String authHeader = request.getHeader("Authorization");
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    throw new RuntimeException("Missing or invalid Authorization header");
                }
                String token = authHeader.substring("Bearer ".length());
                String sourceIp = request.getRemoteAddr();

                if (finalJwtValidator != null && token.contains(".")) {
                    identity = finalJwtValidator.validate(token, sourceIp);
                } else if (authenticator.isEnabled()) {
                    identity = authenticator.authenticate(token, sourceIp)
                            .orElseThrow(() -> new RuntimeException("Invalid API key"));
                } else {
                    throw new RuntimeException("No authentication method available for token");
                }
            }
            return McpTransportContext.create(Map.of(CallerIdentity.CONTEXT_KEY, identity));
        })
        .build();
```

Pass `permissionCache` to `QueryExecutor`:

```java
var queryExecutor = new com.indexer.mcp.QueryExecutor(jdbi, branchIndexDao, indexingPipeline, repositoryDao, gitOps, permissionCache);
```

- [ ] **Step 4: Add permissionCache to QueryExecutor**

In `QueryExecutor.java`, add a field and update constructors:

```java
private final PermissionCache permissionCache;

// Backward-compatible constructor (used in tests)
public QueryExecutor(Jdbi jdbi) {
    this(jdbi, null, null, null, null, null);
}

public QueryExecutor(Jdbi jdbi, BranchIndexDao branchIndexDao, IndexingPipeline indexingPipeline,
                     RepositoryDao repositoryDao, GitOperations gitOps) {
    this(jdbi, branchIndexDao, indexingPipeline, repositoryDao, gitOps, null);
}

public QueryExecutor(Jdbi jdbi, BranchIndexDao branchIndexDao, IndexingPipeline indexingPipeline,
                     RepositoryDao repositoryDao, GitOperations gitOps, PermissionCache permissionCache) {
    this.jdbi = jdbi;
    this.branchIndexDao = branchIndexDao;
    this.indexingPipeline = indexingPipeline;
    this.repositoryDao = repositoryDao;
    this.gitOps = gitOps;
    this.permissionCache = permissionCache;
}
```

Add import:
```java
import com.indexer.auth.PermissionCache;
```

- [ ] **Step 5: Add authorization check to executeQuery**

Update the `executeQuery` method to check permissions before executing:

```java
public McpSchema.CallToolResult executeQuery(
        CallerIdentity caller, String repo, String action,
        Map<String, Object> params, Supplier<Object> query) {
    log.info("Tool call: {} by {} ({})", action, caller.displayName(), caller.authMethod());

    // Authorization check — only for OAuth users with configured permissions
    if (permissionCache != null && repo != null && "oauth".equals(caller.authMethod())) {
        try {
            Set<String> allowed = permissionCache.getAllowedRepos(caller);
            if (!allowed.contains(repo)) {
                log.warn("Access denied: {} attempted to query repo {}", caller.displayName(), repo);
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Access denied to repository: " + repo)
                        .isError(true)
                        .build();
            }
        } catch (Exception e) {
            log.error("Permission resolution failed for {}: {}", caller.displayName(), e.getMessage());
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Authorization failed: unable to verify permissions")
                    .isError(true)
                    .build();
        }
    }

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

Add import:
```java
import java.util.Set;
```

- [ ] **Step 6: Verify compilation and tests**

Run: `./gradlew compileJava compileTestJava 2>&1 | tail -5`

Fix any compilation errors — the `QueryExecutor` constructor changed. Check:
- `Application.java` — already updated
- `AdminService` — may construct QueryExecutor (check and update if needed)
- Test files that construct QueryExecutor directly — the backward-compatible constructor `QueryExecutor(Jdbi)` still works

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL — all existing tests pass (no OAuth or permissions configured → no enforcement)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/indexer/config/IndexerConfig.java src/main/java/com/indexer/config/ConfigLoader.java src/main/java/com/indexer/Application.java src/main/java/com/indexer/mcp/QueryExecutor.java
git commit -m "feat: wire OAuth JWT validation and permission resolution into identity pipeline

Extend contextExtractor for JWT + API key dual auth. Add PermissionCache
to QueryExecutor. Enforce repo-level authorization for OAuth users.
API key and stdio users have unrestricted access. Fail-closed on
permission resolution failure."
```

---

### Implementation Notes

**JWT detection heuristic:** A token is treated as a JWT if it contains `.` characters (JWTs have `header.payload.signature` format with two dots). Opaque API keys don't contain dots. This is a simple, reliable heuristic used widely in practice.

**Config backward compatibility:** When `oauth` and `permissions` sections are absent from config, `McpAuthConfig` constructors default them to null. `JwtValidator` and `PermissionCache` are only created when configured. Existing tests with no auth config pass unchanged.

**QueryExecutor constructor chain:** Three constructors maintain backward compatibility: `(Jdbi)` for simple tests, `(Jdbi, ..., GitOperations)` for existing production code, `(Jdbi, ..., GitOperations, PermissionCache)` for new code with permissions.

**GitHub API pagination:** The resolver follows `Link: <url>; rel="next"` headers for teams with many repos. Most teams have < 100 repos, so pagination is rarely exercised but handled correctly.

**Fail-closed semantics:** `PermissionCache.getAllowedRepos()` lets `PermissionResolutionException` propagate. In `executeQuery`, this is caught and returns an error `CallToolResult`. The query never executes — no data leaks on auth failure.
