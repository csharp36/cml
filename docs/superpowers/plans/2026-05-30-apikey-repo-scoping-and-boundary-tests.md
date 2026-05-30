# API-Key Repo Scoping + Authorization Boundary & Stack-Trace Diagnosis Tests — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring API-key callers under the same per-repo least-privilege authorization the OAuth path already enforces (fail-closed, with a `["*"]` escape hatch for full-access keys), then add two test suites: a security **authorization-boundary** suite proving a remote caller without entitlement can do nothing meaningful, and a **stack-trace diagnosis** poster-child test exercising the headline "paste a stack trace → diagnose" flow.

**Architecture:** The single authorization choke point is `QueryExecutor.executeQuery()` — all 13 repo-scoped MCP tools funnel through it. Today it gates only `authMethod == "oauth"` callers (via `PermissionCache`); API-key and stdio callers bypass per-repo authorization, so any valid API key can read *every* indexed repo. We add a per-key repo allow-list (`repos:`) carried on `CallerIdentity.allowedRepos`, enforced in the same gate: a key scoped to `["*"]` reads everything (explicit, auditable), a key scoped to a concrete list reads only those repos, and a key with **no** scope is denied everything (fail-closed). stdio (local subprocess, trusted OS user) and `anonymous` (no-auth dev mode) keep their current bypass and are documented as such.

**Tech Stack:** Java 21, JDBI 3, PostgreSQL 16 + Flyway, Jackson (config), JUnit 5 + AssertJ + Mockito, Testcontainers (`@Tag("integration")`), MCP Java SDK (`McpSchema.CallToolResult`).

**Branch/timing note:** Phase 3 PR #11 is open and touches `QueryExecutor` only in the SCIP query methods (`getScipStatus`, `getIndexHealth`, `getTypeHierarchy`, `getSymbolReferences`, `resolveScipSymbol`, `traverseHierarchy`) — far from `executeQuery` (lines ~74-168). Conflict risk is low, but prefer branching from `main` **after #11 merges**; if branching before, expect a trivial rebase. Use a new branch e.g. `feat/apikey-repo-scoping`.

**Out of scope (note, don't build):** SCIP *upload* authorization (`POST /api/scip/{repoName}`, gated by the `scipUpload` flag, not repo scope) is a write path on a different endpoint and is not changed here. `get_index_health` / `query_audit_log` / `verify_audit_chain` are repo-less; under this change a non-wildcard key (like an OAuth caller today) cannot call them — repo-less/system tools require full (`["*"]`) access. This is intentional and consistent with the existing OAuth behavior.

---

## File Structure

- **Modify** `src/main/java/com/indexer/config/IndexerConfig.java` — add `List<String> repos` to `McpAuthConfig.ApiKeyEntry`.
- **Modify** `src/main/java/com/indexer/config/ConfigLoader.java` — parse `repos:` per API key in `parseMcpAuth`.
- **Modify** `src/main/java/com/indexer/auth/CallerIdentity.java` — add `List<String> allowedRepos` field + factory updates.
- **Modify** `src/main/java/com/indexer/auth/ApiKeyAuthenticator.java` — `ApiKeyConfig` carries `repos`; `authenticate` passes it to `fromApiKey`.
- **Modify** `src/main/java/com/indexer/Application.java` — map `ApiKeyEntry.repos()` into `ApiKeyConfig`.
- **Modify** `src/main/java/com/indexer/mcp/QueryExecutor.java` — add an API-key repo-scope branch to `executeQuery` (fail-closed, `["*"]` = all).
- **Modify** `CLAUDE.md` — document per-key `repos:` scoping and the security model.
- **Create** `src/test/java/com/indexer/mcp/PermissionBoundaryTest.java` — boundary suite (OAuth + API-key denial/allow across tools).
- **Create** `src/test/java/com/indexer/mcp/StackTraceDiagnosisTest.java` — poster-child diagnosis flow.
- **Modify** `src/test/java/com/indexer/config/ConfigLoaderTest.java`, `src/test/java/com/indexer/auth/CallerIdentityTest.java`, `src/test/java/com/indexer/auth/ApiKeyAuthenticatorTest.java` — extend for the new field.

---

### Task 1: Config — per-key `repos` allow-list

**Files:**
- Modify: `src/main/java/com/indexer/config/IndexerConfig.java` (the `ApiKeyEntry` record ~line 85)
- Modify: `src/main/java/com/indexer/config/ConfigLoader.java` (`parseMcpAuth`, ~lines 188-201)
- Test: `src/test/java/com/indexer/config/ConfigLoaderTest.java`

> Context: `ApiKeyEntry` is currently `record ApiKeyEntry(String key, String id, String name, boolean auditReader, boolean scipUpload) {}`. `parseMcpAuth` builds each entry from a `JsonNode`. We add a `repos` allow-list (list of repo names; `"*"` means all). Absent → empty list (fail-closed at the gate in Task 3).

- [ ] **Step 1: Write the failing config-parse test**

In `ConfigLoaderTest.java`, read the existing tests first to match the harness (how it loads YAML — likely a temp file or classpath resource string). Add a test that loads an `auth.apiKeys` entry with a `repos:` list and one without, asserting the parsed `ApiKeyEntry.repos()`:

```java
@Test
void parsesApiKeyReposAllowList() {
    String yaml = """
        server:
          cloneBaseDir: /tmp/repos
        database:
          host: localhost
          name: idx
        auth:
          apiKeys:
            - key: scoped-key
              id: ci-a
              name: CI A
              repos: [repo-a, repo-b]
            - key: full-key
              id: admin
              name: Admin
              repos: ["*"]
            - key: bare-key
              id: legacy
              name: Legacy
        """;
    IndexerConfig config = loadFromYaml(yaml); // adapt to ConfigLoaderTest's existing load helper
    var keys = config.mcpAuth().apiKeys();
    assertThat(keys).hasSize(3);
    assertThat(keys.get(0).repos()).containsExactly("repo-a", "repo-b");
    assertThat(keys.get(1).repos()).containsExactly("*");
    assertThat(keys.get(2).repos()).isEmpty(); // absent → empty (fail-closed)
}
```

> Adapt `loadFromYaml(...)` and the minimal required `server`/`database` fields to whatever `ConfigLoaderTest` already does (read it; it may parse from a `Path` or a String via `new ConfigLoader().load(...)`). Keep the three-entry shape and the three `repos()` assertions.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "com.indexer.config.ConfigLoaderTest"`
Expected: FAIL — `ApiKeyEntry` has no `repos()` accessor (compile error) or the field doesn't exist.

- [ ] **Step 3: Add `repos` to `ApiKeyEntry`**

In `IndexerConfig.java`, change the record and default the list:

```java
        public record ApiKeyEntry(String key, String id, String name, boolean auditReader,
                                  boolean scipUpload, java.util.List<String> repos) {
            public ApiKeyEntry {
                repos = repos != null ? java.util.List.copyOf(repos) : java.util.List.of();
            }
        }
```

- [ ] **Step 4: Parse `repos` in `ConfigLoader.parseMcpAuth`**

In `ConfigLoader.java`, inside the `apiKeys` loop (~lines 191-200), parse the array and pass it. Replace the `keys.add(...)` line with:

```java
                    java.util.List<String> repos = new ArrayList<>();
                    JsonNode reposNode = keyNode.get("repos");
                    if (reposNode != null && reposNode.isArray()) {
                        for (JsonNode r : reposNode) {
                            if (r.isTextual() && !r.asText().isBlank()) repos.add(r.asText());
                        }
                    }
                    keys.add(new IndexerConfig.McpAuthConfig.ApiKeyEntry(
                            key, id, name != null ? name : id, auditReader, scipUpload, repos));
```

> Any OTHER construction of `ApiKeyEntry` will now fail to compile (it gained an arg). Search and fix: `grep -rn "new IndexerConfig.McpAuthConfig.ApiKeyEntry(\|new ApiKeyEntry(" src/`. For test/throwaway constructions that don't care about repos, pass `List.of()`.

- [ ] **Step 5: Run it to verify it passes**

Run: `./gradlew test --tests "com.indexer.config.ConfigLoaderTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/indexer/config/IndexerConfig.java \
        src/main/java/com/indexer/config/ConfigLoader.java \
        src/test/java/com/indexer/config/ConfigLoaderTest.java
git commit -m "feat: per-API-key repos allow-list in config"
```

---

### Task 2: Thread the repo scope onto `CallerIdentity` via `ApiKeyAuthenticator`

**Files:**
- Modify: `src/main/java/com/indexer/auth/CallerIdentity.java`
- Modify: `src/main/java/com/indexer/auth/ApiKeyAuthenticator.java`
- Modify: `src/main/java/com/indexer/Application.java` (~lines 118-121, the `ApiKeyEntry` → `ApiKeyConfig` mapping)
- Test: `src/test/java/com/indexer/auth/CallerIdentityTest.java`, `src/test/java/com/indexer/auth/ApiKeyAuthenticatorTest.java`

> Context: `CallerIdentity` is a 10-component record with factories (`anonymous`, `fromStdio`, `fromApiKey` ×3 overloads, `fromOAuth`, `fromAdminToken`). We add `allowedRepos` as the 11th (last) component. Only API-key identities populate it; everyone else gets `List.of()`. `ApiKeyAuthenticator.authenticate` builds the identity from an `ApiKeyConfig`; we add `repos` to that config and pass it through. `Application` converts each `ApiKeyEntry` to an `ApiKeyConfig`.

- [ ] **Step 1: Write the failing identity + authenticator tests**

In `CallerIdentityTest.java`, add:

```java
@Test
void fromApiKeyCarriesAllowedRepos() {
    var id = CallerIdentity.fromApiKey("ci-a", "CI A", "10.0.0.1", false, false, List.of("repo-a", "repo-b"));
    assertThat(id.authMethod()).isEqualTo("api-key");
    assertThat(id.allowedRepos()).containsExactly("repo-a", "repo-b");
}

@Test
void nonApiKeyIdentitiesHaveEmptyAllowedRepos() {
    assertThat(CallerIdentity.fromStdio().allowedRepos()).isEmpty();
    assertThat(CallerIdentity.fromOAuth("u", "U", List.of("g"), "ip").allowedRepos()).isEmpty();
}
```

In `ApiKeyAuthenticatorTest.java`, add (read the existing `authenticatesValidKey` test to match style):

```java
@Test
void authenticatedIdentityCarriesConfiguredRepos() {
    var auth = new ApiKeyAuthenticator(List.of(
            new ApiKeyAuthenticator.ApiKeyConfig("k1", "ci-a", "CI A", false, false, List.of("repo-a"))));
    var id = auth.authenticate("k1", "10.0.0.1").orElseThrow();
    assertThat(id.allowedRepos()).containsExactly("repo-a");
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew test --tests "com.indexer.auth.CallerIdentityTest" --tests "com.indexer.auth.ApiKeyAuthenticatorTest"`
Expected: FAIL (compile) — no `allowedRepos()` accessor, `fromApiKey` 6-arg overload and `ApiKeyConfig` 6-arg constructor don't exist.

- [ ] **Step 3: Add `allowedRepos` to `CallerIdentity`**

In `CallerIdentity.java`:
- Add the field as the last record component:
  ```java
  public record CallerIdentity(
          String userId, String displayName, String authMethod, String transport,
          String sourceIp, String clientName, String clientVersion,
          List<String> groups, boolean auditReader, boolean scipUpload,
          List<String> allowedRepos
  ) {
      public CallerIdentity {
          groups = groups != null ? List.copyOf(groups) : List.of();
          allowedRepos = allowedRepos != null ? List.copyOf(allowedRepos) : List.of();
      }
  ```
- Update every factory to pass an `allowedRepos` argument (append `List.of()` to all existing ones; add a new `fromApiKey` overload that accepts the scope):
  ```java
  public static CallerIdentity anonymous(String transport) {
      return new CallerIdentity(null, "anonymous", "none", transport, null, null, null, List.of(), false, false, List.of());
  }
  public static CallerIdentity fromStdio() {
      String osUser = System.getProperty("user.name");
      return new CallerIdentity(osUser, osUser, "stdio-os-user", "stdio", null, null, null, List.of(), true, false, List.of());
  }
  public static CallerIdentity fromApiKey(String id, String name, String sourceIp) {
      return new CallerIdentity(id, name, "api-key", "streamable-http", sourceIp, null, null, List.of(), false, false, List.of());
  }
  public static CallerIdentity fromApiKey(String id, String name, String sourceIp, boolean auditReader) {
      return new CallerIdentity(id, name, "api-key", "streamable-http", sourceIp, null, null, List.of(), auditReader, false, List.of());
  }
  public static CallerIdentity fromApiKey(String id, String name, String sourceIp, boolean auditReader, boolean scipUpload) {
      return new CallerIdentity(id, name, "api-key", "streamable-http", sourceIp, null, null, List.of(), auditReader, scipUpload, List.of());
  }
  public static CallerIdentity fromApiKey(String id, String name, String sourceIp, boolean auditReader, boolean scipUpload, List<String> allowedRepos) {
      return new CallerIdentity(id, name, "api-key", "streamable-http", sourceIp, null, null, List.of(), auditReader, scipUpload, allowedRepos);
  }
  public static CallerIdentity fromOAuth(String sub, String name, List<String> groups, String sourceIp) {
      return new CallerIdentity(sub, name, "oauth", "streamable-http", sourceIp, null, null, groups, false, false, List.of());
  }
  public static CallerIdentity fromAdminToken(String sourceIp) {
      return new CallerIdentity("admin", "Admin", "admin-token", "streamable-http", sourceIp, null, null, List.of(), false, false, List.of());
  }
  ```

> Search for any DIRECT `new CallerIdentity(...)` outside this file (`grep -rn "new CallerIdentity(" src/`) and append `List.of()` (or the intended scope) to each. Factories cover most call sites.

- [ ] **Step 4: Add `repos` to `ApiKeyConfig` and pass it through `authenticate`**

In `ApiKeyAuthenticator.java`:
- Change the record:
  ```java
  public record ApiKeyConfig(String key, String id, String name, boolean auditReader,
                             boolean scipUpload, java.util.List<String> repos) {}
  ```
- In `authenticate`, pass `keyConfig.repos()`:
  ```java
              if (constantTimeEquals(keyConfig.key(), bearerToken)) {
                  return Optional.of(CallerIdentity.fromApiKey(
                          keyConfig.id(), keyConfig.name(), sourceIp,
                          keyConfig.auditReader(), keyConfig.scipUpload(), keyConfig.repos()));
              }
  ```

- [ ] **Step 5: Map `ApiKeyEntry.repos()` in `Application`**

In `Application.java` (the conversion of config `ApiKeyEntry` → `ApiKeyAuthenticator.ApiKeyConfig`, ~lines 118-121), add `e.repos()` to the constructed `ApiKeyConfig`. Read the exact stream/loop and update it, e.g.:

```java
            var apiKeyConfigs = config.mcpAuth().apiKeys().stream()
                    .map(e -> new ApiKeyAuthenticator.ApiKeyConfig(
                            e.key(), e.id(), e.name(), e.auditReader(), e.scipUpload(), e.repos()))
                    .toList();
```

> Match the actual variable names/shape in Application (it may use a for-loop). Just ensure `repos` is threaded.

- [ ] **Step 6: Run to verify pass + full compile**

Run: `./gradlew test --tests "com.indexer.auth.*"` then `./gradlew compileJava compileTestJava`
Expected: PASS and clean compile (all `CallerIdentity`/`ApiKeyConfig`/`ApiKeyEntry` construction sites updated).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/indexer/auth/CallerIdentity.java \
        src/main/java/com/indexer/auth/ApiKeyAuthenticator.java \
        src/main/java/com/indexer/Application.java \
        src/test/java/com/indexer/auth/CallerIdentityTest.java \
        src/test/java/com/indexer/auth/ApiKeyAuthenticatorTest.java
git commit -m "feat: carry API-key repo scope onto CallerIdentity.allowedRepos"
```

---

### Task 3: Enforce API-key repo scope in `executeQuery` (fail-closed + `["*"]`)

**Files:**
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java` (`executeQuery`, ~lines 74-107)
- Test: `src/test/java/com/indexer/mcp/ApiKeyScopeGateTest.java` (new, pure unit — no DB)

> Context: `executeQuery` currently has one authorization block — `if (permissionCache != null && "oauth".equals(caller.authMethod())) { ... }`. We add a SECOND, independent block for `"api-key"` callers that consults `caller.allowedRepos()` directly (no `PermissionCache` needed). The denied path returns `CallToolResult.isError(true)` and calls `auditBestEffort(...)`; the query lambda never runs. Because the denied path doesn't touch `jdbi`, this is testable with a `QueryExecutor` built from nulls + a capturing `AuditSink`.

- [ ] **Step 1: Write the failing gate unit test**

Create `src/test/java/com/indexer/mcp/ApiKeyScopeGateTest.java`:

```java
package com.indexer.mcp;

import com.indexer.audit.AuditEvent;
import com.indexer.audit.AuditSink;
import com.indexer.auth.CallerIdentity;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyScopeGateTest {

    /** Captures audit events so we can assert denials are recorded. */
    static class CapturingAuditSink implements AuditSink {
        final List<AuditEvent> events = new ArrayList<>();
        @Override public void record(AuditEvent e) { events.add(e); }
    }

    private QueryExecutor newExecutor(CapturingAuditSink sink) {
        // Only the auth gate is exercised; all DB collaborators are null and never touched.
        return new QueryExecutor(null, null, null, null, null, null, sink);
    }

    private static String textOf(McpSchema.CallToolResult r) {
        // Adapt to the MCP SDK Content API used elsewhere (see StreamableHttpTransportIntegrationTest
        // for how tool-result text is extracted). It concatenates the text content blocks.
        var sb = new StringBuilder();
        for (var c : r.content()) {
            if (c instanceof McpSchema.TextContent tc) sb.append(tc.text());
        }
        return sb.toString();
    }

    @Test
    void scopedKeyAllowedRepoRunsQuery() {
        var sink = new CapturingAuditSink();
        var qe = newExecutor(sink);
        var caller = CallerIdentity.fromApiKey("ci-a", "CI A", "ip", false, false, List.of("repo-a"));
        var ran = new AtomicBoolean(false);

        var result = qe.executeQuery(caller, "repo-a", "search_symbols", java.util.Map.of(),
                () -> { ran.set(true); return java.util.Map.of("results", List.of()); });

        assertThat(ran).isTrue();
        assertThat(result.isError()).isFalse();
    }

    @Test
    void scopedKeyForbiddenRepoIsDeniedAndLambdaNeverRuns() {
        var sink = new CapturingAuditSink();
        var qe = newExecutor(sink);
        var caller = CallerIdentity.fromApiKey("ci-a", "CI A", "ip", false, false, List.of("repo-a"));
        var ran = new AtomicBoolean(false);

        var result = qe.executeQuery(caller, "secret-repo", "search_code", java.util.Map.of(),
                () -> { ran.set(true); return java.util.Map.of("leak", "TOP SECRET"); });

        assertThat(ran).isFalse();                         // query never executed
        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).contains("Access denied to repository: secret-repo");
        assertThat(textOf(result)).doesNotContain("TOP SECRET");
        assertThat(sink.events).anyMatch(e -> !e.authorized());  // denial audited
    }

    @Test
    void wildcardKeyReadsAnyRepo() {
        var sink = new CapturingAuditSink();
        var qe = newExecutor(sink);
        var caller = CallerIdentity.fromApiKey("admin", "Admin", "ip", false, false, List.of("*"));

        var result = qe.executeQuery(caller, "any-repo", "search_symbols", java.util.Map.of(),
                () -> java.util.Map.of("results", List.of()));

        assertThat(result.isError()).isFalse();
    }

    @Test
    void unscopedKeyIsDeniedEverything() {
        var sink = new CapturingAuditSink();
        var qe = newExecutor(sink);
        var caller = CallerIdentity.fromApiKey("legacy", "Legacy", "ip"); // no repos → List.of()

        var result = qe.executeQuery(caller, "repo-a", "search_symbols", java.util.Map.of(),
                () -> java.util.Map.of("results", List.of()));

        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).contains("Access denied to repository: repo-a");
    }

    @Test
    void scopedKeyRepoLessToolIsDenied() {
        var sink = new CapturingAuditSink();
        var qe = newExecutor(sink);
        var caller = CallerIdentity.fromApiKey("ci-a", "CI A", "ip", false, false, List.of("repo-a"));

        var result = qe.executeQuery(caller, null, "get_index_health", java.util.Map.of(),
                () -> java.util.Map.of("ok", true));

        assertThat(result.isError()).isTrue();  // repo-less/system tools require ["*"]
    }
}
```

> If the MCP SDK's `TextContent`/`content()` accessors differ, fix `textOf` by modeling on an existing test that reads a `CallToolResult` (e.g. `StreamableHttpTransportIntegrationTest`). The behavioral assertions stay the same.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.indexer.mcp.ApiKeyScopeGateTest"`
Expected: FAIL — no API-key gate exists, so forbidden/unscoped/repo-less calls run the lambda and return success.

- [ ] **Step 3: Add the API-key authorization block to `executeQuery`**

In `QueryExecutor.executeQuery`, immediately AFTER the existing OAuth block (after line ~107, before `// Execute query`), insert:

```java
        // Authorization check — API-key callers are scoped to their configured repos.
        // ["*"] = full access (explicit, auditable); a concrete list restricts to those repos;
        // an empty scope denies everything (fail-closed). stdio/anonymous are intentionally unscoped.
        if ("api-key".equals(caller.authMethod())) {
            List<String> scope = caller.allowedRepos();
            boolean wildcard = scope.contains("*");
            if (!wildcard) {
                if (repo == null) {
                    log.warn("Access denied: api-key {} called {} without repo (requires full access)",
                            caller.displayName(), action);
                    auditBestEffort(caller, action, null, false, "denied", "Repository parameter required");
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("Repository parameter is required for scoped API keys")
                            .isError(true)
                            .build();
                }
                if (!scope.contains(repo)) {
                    log.warn("Access denied: api-key {} not scoped for repo {}", caller.displayName(), repo);
                    auditBestEffort(caller, action, repo, false, "denied", "Access denied to repository: " + repo);
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("Access denied to repository: " + repo)
                            .isError(true)
                            .build();
                }
            }
        }
```

> Confirm `java.util.List` and `java.util.Set` are already imported in QueryExecutor (they are). No new collaborators required — this block reads only `caller`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests "com.indexer.mcp.ApiKeyScopeGateTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/mcp/QueryExecutor.java \
        src/test/java/com/indexer/mcp/ApiKeyScopeGateTest.java
git commit -m "feat: enforce per-API-key repo scope in executeQuery (fail-closed, [*] = all)"
```

---

### Task 4: `PermissionBoundaryTest` — end-to-end "nothing meaningful leaks" suite

**Files:**
- Test: `src/test/java/com/indexer/mcp/PermissionBoundaryTest.java` (new, `@Tag("integration")`)

> Context: This is the security boundary proof. It seeds two repos with real data (so allowed queries return content and denied queries can be shown to leak nothing), wires a `PermissionCache` over a **mock** `PermissionResolver` for the OAuth path, and a capturing `AuditSink`. It calls `executeQuery` exactly as the MCP handlers do — wrapping the real `QueryExecutor` tool methods in the lambda — and asserts denials across MULTIPLE meaningful tools. Model the Testcontainers + Flyway + seeding harness on `src/test/java/com/indexer/mcp/SemanticQueryTest.java` (or `BranchQueryTest`) — read one for the exact `Jdbi.create` + `Flyway.configure().dataSource(...).cleanDisabled(false).load()` + seed pattern. Read `PermissionCache`'s constructor and `PermissionResolver`'s interface to mock them (Mockito is on the classpath).

- [ ] **Step 1: Write the boundary tests**

Create `src/test/java/com/indexer/mcp/PermissionBoundaryTest.java`. Structure:

- `@Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")...`
- `@BeforeEach`: `jdbi = Jdbi.create(...)`; Flyway clean+migrate; seed TWO repos `repo-pub` and `repo-secret`, each with a file + a symbol + file_contents. Example seed (adapt column lists to the real schema — see SemanticQueryTest/BranchQueryTest):
  ```java
  jdbi.useHandle(h -> {
      h.execute("INSERT INTO repositories (name, url, branch, clone_path, auth_type, last_indexed_sha) VALUES ('repo-pub','u','main','/tmp/pub','ssh-key','sha1')");
      h.execute("INSERT INTO repositories (name, url, branch, clone_path, auth_type, last_indexed_sha) VALUES ('repo-secret','u','main','/tmp/sec','ssh-key','sha2')");
      int pub = h.createQuery("SELECT id FROM repositories WHERE name='repo-pub'").mapTo(Integer.class).one();
      int sec = h.createQuery("SELECT id FROM repositories WHERE name='repo-secret'").mapTo(Integer.class).one();
      h.execute("INSERT INTO files (repo_id, path, language) VALUES (?,'src/Pub.java','java')", pub);
      h.execute("INSERT INTO files (repo_id, path, language) VALUES (?,'src/Secret.java','java')", sec);
      int secFile = h.createQuery("SELECT id FROM files WHERE repo_id=? AND path='src/Secret.java'").bind(0, sec).mapTo(Integer.class).one();
      h.execute("INSERT INTO symbols (file_id, name, kind, signature, start_line, end_line, visibility, is_static) VALUES (?,'TopSecretKey','class','class TopSecretKey',1,5,'public',false)", secFile);
      h.createUpdate("INSERT INTO file_contents (file_id, content) VALUES (:f, :c) ON CONFLICT (file_id) DO UPDATE SET content=EXCLUDED.content")
          .bind("f", secFile).bind("c", "class TopSecretKey { String apiSecret = \"sk-LEAK\"; }").execute();
  });
  ```
- Build the executor with a `PermissionCache` (mock resolver) + capturing `AuditSink`:
  ```java
  resolver = org.mockito.Mockito.mock(PermissionResolver.class);
  org.mockito.Mockito.when(resolver.resolveAllowedRepos(java.util.List.of("team-pub")))
          .thenReturn(java.util.Set.of("repo-pub"));
  var cache = new PermissionCache(resolver, java.util.Set.of(), java.time.Duration.ofMinutes(30)); // adapt to real ctor
  sink = new CapturingAuditSink();          // reuse the same fake as Task 3 (copy it in, or extract to a test util)
  qe = new QueryExecutor(jdbi, null, null, null, null, cache, sink);
  ```
  > Read `PermissionCache`'s real constructor signature (param order/types for resolver, openRepos, TTL) and `PermissionResolver`'s method name; adapt the two lines above. Use a small private `textOf(CallToolResult)` helper as in Task 3.

Tests to include:

```java
// --- OAuth path ---
@Test
void oauthUserCannotReadRepoOutsideEntitlement_acrossTools() {
    var caller = CallerIdentity.fromOAuth("alice", "Alice", java.util.List.of("team-pub"), "ip"); // entitled to repo-pub only
    for (String tool : java.util.List.of("search_symbols", "get_symbol_detail", "search_code", "get_file_summary", "get_directory_tree")) {
        var result = qe.executeQuery(caller, "repo-secret", tool, java.util.Map.of(),
                () -> { throw new AssertionError("query lambda must not run when denied"); });
        assertThat(result.isError()).as(tool).isTrue();
        assertThat(textOf(result)).as(tool).contains("Access denied to repository: repo-secret");
        assertThat(textOf(result)).as(tool).doesNotContain("TopSecretKey").doesNotContain("sk-LEAK");
    }
    assertThat(sink.events).allMatch(e -> !e.authorized()); // every attempt audited as denied
}

@Test
void oauthUserCanReadEntitledRepo() {
    var caller = CallerIdentity.fromOAuth("alice", "Alice", java.util.List.of("team-pub"), "ip");
    var result = qe.executeQuery(caller, "repo-pub", "search_symbols", java.util.Map.of(),
            () -> qe.searchSymbols(null, null, null, "repo-pub", null, 20));
    assertThat(result.isError()).isFalse();
}

@Test
void oauthFailClosedWhenResolverThrows() {
    org.mockito.Mockito.when(resolver.resolveAllowedRepos(java.util.List.of("team-broken")))
            .thenThrow(new RuntimeException("github down"));
    var caller = CallerIdentity.fromOAuth("bob", "Bob", java.util.List.of("team-broken"), "ip");
    var result = qe.executeQuery(caller, "repo-pub", "search_symbols", java.util.Map.of(),
            () -> { throw new AssertionError("must not run"); });
    assertThat(result.isError()).isTrue(); // fail-closed
}

// --- API-key path (end-to-end, real tools) ---
@Test
void scopedApiKeyCannotReadUnscopedRepo_acrossTools() {
    var caller = CallerIdentity.fromApiKey("ci", "CI", "ip", false, false, java.util.List.of("repo-pub"));
    for (String tool : java.util.List.of("search_symbols", "get_symbol_detail", "search_code", "get_file_summary")) {
        var result = qe.executeQuery(caller, "repo-secret", tool, java.util.Map.of(),
                () -> { throw new AssertionError("must not run"); });
        assertThat(result.isError()).as(tool).isTrue();
        assertThat(textOf(result)).as(tool).doesNotContain("TopSecretKey").doesNotContain("sk-LEAK");
    }
}

@Test
void scopedApiKeyReadsItsRepo_andWildcardReadsAll() {
    var scoped = CallerIdentity.fromApiKey("ci", "CI", "ip", false, false, java.util.List.of("repo-pub"));
    assertThat(qe.executeQuery(scoped, "repo-pub", "search_symbols", java.util.Map.of(),
            () -> qe.searchSymbols(null, null, null, "repo-pub", null, 20)).isError()).isFalse();

    var admin = CallerIdentity.fromApiKey("admin", "Admin", "ip", false, false, java.util.List.of("*"));
    assertThat(qe.executeQuery(admin, "repo-secret", "search_symbols", java.util.Map.of(),
            () -> qe.searchSymbols(null, null, null, "repo-secret", null, 20)).isError()).isFalse();
}

@Test
void unscopedApiKeyReadsNothing() {
    var caller = CallerIdentity.fromApiKey("legacy", "Legacy", "ip"); // no repos
    var result = qe.executeQuery(caller, "repo-pub", "search_symbols", java.util.Map.of(),
            () -> { throw new AssertionError("must not run"); });
    assertThat(result.isError()).isTrue();
}
```

> The `CapturingAuditSink` and `textOf` helper are duplicated from Task 3 — either copy them in, or (cleaner) extract them to `src/test/java/com/indexer/mcp/TestAuthSupport.java` in this task and have both test classes use it. Prefer extraction.

- [ ] **Step 2: Run to verify pass**

Run: `./gradlew integrationTest --tests "com.indexer.mcp.PermissionBoundaryTest"`
Expected: PASS (requires Docker/Testcontainers). If a control test (allowed query) fails, the seed column lists likely need adjusting to the real schema — read `SemanticQueryTest`/`BranchQueryTest` seeding.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/indexer/mcp/PermissionBoundaryTest.java \
        src/test/java/com/indexer/mcp/TestAuthSupport.java
git commit -m "test: authorization boundary — remote caller without entitlement leaks nothing (OAuth + API key)"
```

---

### Task 5: `StackTraceDiagnosisTest` — poster-child diagnosis flow

**Files:**
- Test: `src/test/java/com/indexer/mcp/StackTraceDiagnosisTest.java` (new, `@Tag("integration")`)

> Context: There is no stack-trace tool; diagnosis is a composition of existing tools. This test takes a representative Java frame — `at com.example.payment.PaymentProcessor.charge(PaymentProcessor.java:42)` — and walks the realistic diagnosis path against seeded structural data (no SCIP needed; `findImplementations` uses `type_relationships`). It proves the headline workflow end-to-end at the `QueryExecutor` level. Model the harness on `BranchQueryTest` (DatabaseManager/Jdbi + `RepositoryDao`/`FileDao`/`SymbolDao` + an `insertContent` helper) — read it for exact DAO method names (`fileDao.upsert(SourceFile)`, `symbolDao.insertSymbol(Symbol)`, `symbolDao.insertTypeRelationship(...)` or the real equivalents) and record shapes.

- [ ] **Step 1: Write the diagnosis-flow test**

Create `src/test/java/com/indexer/mcp/StackTraceDiagnosisTest.java`:

- `@Container` Postgres; `@BeforeEach`: init DB, seed repo `payment-app` with two files:
  - `src/main/java/com/example/payment/PaymentProcessor.java` — class `PaymentProcessor` (implements `PaymentGateway` via `type_relationships`), method `charge` at lines 40-50; `file_contents` includes a line near 42 like `throw new IllegalStateException("charge failed: invalid state");`.
  - `src/main/java/com/example/PaymentService.java` — class `PaymentService`, method `processTransaction`; `file_contents` `import com.example.payment.PaymentProcessor;` and a call `new PaymentProcessor().charge(amount);`.
  - Also seed a second implementer `StripeGateway` (implements `PaymentGateway`) so `findImplementations` returns >1.
- Build `queryExecutor = new QueryExecutor(jdbi);` (single-arg ctor — no auth gate, no fault-in; matches BranchQueryTest).

```java
@Test
void diagnoseFromStackFrame_PaymentProcessor_charge_line42() {
    // FRAME: at com.example.payment.PaymentProcessor.charge(PaymentProcessor.java:42)
    String repo = "payment-app";
    String file = "src/main/java/com/example/payment/PaymentProcessor.java";

    // 1) Jump to the throwing method's source (file + method + line disambiguates overloads)
    var detail = queryExecutor.getSymbolDetail(repo, file, "charge", 42, null);
    assertThat(detail.get("name")).isEqualTo("charge");
    assertThat(detail.get("source_code")).asString().contains("IllegalStateException");

    // 2) Who references the throwing class? (import-based callers)
    var refs = queryExecutor.findReferences("PaymentProcessor", repo, null, 20);
    assertThat(refs.toString()).contains("PaymentService.java");

    // 3) Polymorphism: which concrete types implement the same interface?
    var impls = queryExecutor.findImplementations("PaymentGateway", repo, null);
    assertThat(impls.toString()).contains("PaymentProcessor").contains("StripeGateway");

    // 4) Widen to file context: sibling symbols + imports
    var summary = queryExecutor.getFileSummary(repo, file, null);
    assertThat(summary.get("symbols").toString()).contains("charge");

    // 5) Hunt the error string across the codebase
    var hits = queryExecutor.searchCode("IllegalStateException", null, repo, null, 10);
    assertThat(hits.toString()).contains(file);
}
```

> Adapt the return-shape assertions to the actual structures (read each method's return map keys in QueryExecutor: `getSymbolDetail` → `name`/`source_code`; `findReferences`/`findImplementations` → `{results: [...]}` wrappers when repo is set; `getFileSummary` → `{symbols, imports}`; `searchCode` → `List<Map>` with `file_path`). Use `.toString().contains(...)` only as a robust fallback; prefer extracting the specific field where the shape is clear (model on `BranchQueryTest` assertions). The five steps must each assert a real value, not just non-null.

- [ ] **Step 2: Run to verify pass**

Run: `./gradlew integrationTest --tests "com.indexer.mcp.StackTraceDiagnosisTest"`
Expected: PASS. If `getSymbolDetail` returns `source_code: null`, the `file_contents` seed didn't take (check the `ON CONFLICT` insert and that the file_id matches). If `findImplementations` is empty, the `type_relationships` seed (symbol_id + related_name + kind='implements') is missing or mis-shaped — read the real schema/DAO.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/indexer/mcp/StackTraceDiagnosisTest.java
git commit -m "test: poster-child stack-trace diagnosis flow (frame -> source -> callers -> impls -> context)"
```

---

### Task 6: Document the model in CLAUDE.md

**Files:**
- Modify: `CLAUDE.md` (SCIP Upload API / Supported Auth Types / a new note under the auth `apiKeys` config block)

- [ ] **Step 1: Document per-key `repos` scoping**

Find the `auth.apiKeys` config example in CLAUDE.md (the SCIP Upload API section shows `apiKeys: - key: ... scipUpload: true`). Add a `repos:` field to the example and a short subsection explaining the authorization model:
- API keys are **scoped per key** via `repos:`. `repos: ["*"]` grants full read access to all indexed repos (explicit, auditable); `repos: [repo-a, repo-b]` restricts the key to those repos; **omitting `repos` denies all queries** (fail-closed) — a key must declare its scope.
- This mirrors the OAuth per-repo entitlement (groups → allowed repos), so the indexer never exposes a repo's code to a remote caller who isn't entitled to it, regardless of auth method.
- Repo-less/system tools (`get_index_health`, `query_audit_log`, `verify_audit_chain`) require a `["*"]` key (same as OAuth, which cannot call them).
- stdio (local subprocess, OS user) is trusted and unscoped.

Add `repos: ["*"]` to the SCIP-upload example key so the documented example still works after this change. Note the migration: existing keys need `repos:` added or they will be denied.

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: document per-API-key repo scoping (fail-closed, [*] for full access)"
```

---

## Self-Review

**Spec coverage:**
- "API keys get per-repo scope, fail-closed, `["*"]` escape hatch" → Task 1 (config), Task 2 (identity threading), Task 3 (enforcement). The fail-closed default (absent `repos` → `List.of()` → denied) is set in Task 1's compact constructor and enforced in Task 3.
- "Prove a remote caller without access can't do anything meaningful" → Task 4 (OAuth + API-key denial across 5/4 tools, asserting no `TopSecretKey`/`sk-LEAK` leak), plus controls, fail-closed-on-resolver-error, and audit-of-denials.
- "Stack-trace diagnose poster-child" → Task 5 (frame → source → callers → implementations → file context → error-string search).
- Documentation → Task 6.

**Placeholder scan:** All code blocks are concrete. Three spots explicitly say "adapt to the real signature/shape" — `ConfigLoaderTest`'s load helper (Task 1), `PermissionCache` ctor + `PermissionResolver` method (Task 4), the `textOf(CallToolResult)` content accessor (Tasks 3-4), and DAO method/record shapes (Task 5) — because those are existing APIs the implementer must read rather than ones this plan defines. Each names the exact file to model on. The behavioral assertions are fully specified.

**Type/name consistency:** `ApiKeyEntry(... , List<String> repos)` (Task 1) → `ApiKeyConfig(... , List<String> repos)` (Task 2) → `CallerIdentity.fromApiKey(..., List<String> allowedRepos)` → `caller.allowedRepos()` read in `executeQuery` (Task 3). `CapturingAuditSink` + `textOf` defined in Task 3 and reused (extracted to `TestAuthSupport`) in Task 4. The `["*"]` wildcard and "absent → empty → deny" semantics are consistent across Tasks 1/3/6.

**Atomicity:** Task 1 (config) and Task 2 (identity) each compile + pass independently. Task 3 adds the gate (unit-tested, no DB). Tasks 4-5 add integration tests over the now-complete feature. Each task commits green.

## Out of scope (later)
- SCIP upload (`POST /api/scip/{repoName}`) write-path authorization by repo scope (currently `scipUpload` flag only).
- Per-ref `get_index_health` (repo-global today).
- Rotating the `dev-*` API tokens (operational follow-up; they'll be denied until given a `repos:` scope).
