# Phase E1: SCIP Receiving + Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an HTTP upload endpoint that receives SCIP protobuf files from CI pipelines, parses type-resolved definitions and relationships into PostgreSQL, and reports SCIP staleness in health checks.

**Architecture:** `ScipApi` REST endpoint at `/api/scip/{repo}` authenticates via API key with `scipUpload: true`, validates the upload, delegates to `ScipService` for parsing and storage. `ScipParser` extracts definitions and relationships from the SCIP protobuf. Flyway V4 migration adds `scip_symbols` and `scip_relationships` tables plus tracking columns on `repositories`.

**Tech Stack:** Java 21, protobuf-java 4.29.3 (Gradle protobuf plugin 0.9.4), JDBI, PostgreSQL 16

**Spec:** `docs/superpowers/specs/2026-05-28-scip-receiving-design.md`

---

### Task 1: Gradle Protobuf Plugin + SCIP Proto

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/main/proto/scip.proto`

- [ ] **Step 1: Update build.gradle.kts**

Add the protobuf plugin and dependency. Replace the plugins block:

```kotlin
plugins {
    java
    application
    id("com.google.protobuf") version "0.9.4"
}
```

Add to dependencies (after the Config section):

```kotlin
// SCIP protobuf
implementation("com.google.protobuf:protobuf-java:4.29.3")
```

Add protobuf configuration at the end of the file (before or after the tasks block):

```kotlin
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.29.3"
    }
}
```

- [ ] **Step 2: Download and place scip.proto**

Download the SCIP protobuf schema from Sourcegraph's repository. The file defines messages: `Index`, `Metadata`, `Document`, `Occurrence`, `SymbolInformation`, `Relationship`, `Descriptor`, and various enums (`SymbolRole`, `SyntaxKind`, `Language`, etc.).

Place at: `src/main/proto/scip.proto`

The proto file's `option java_package` should be `"com.sourcegraph.scip"` and `option java_outer_classname = "Scip"`. If the downloaded proto doesn't have these, add them at the top after `syntax = "proto3"`:

```protobuf
option java_package = "com.sourcegraph.scip";
option java_outer_classname = "Scip";
```

- [ ] **Step 3: Verify protobuf compilation**

Run: `./gradlew generateProto 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL. Generated Java sources should appear in `build/generated/source/proto/main/java/com/sourcegraph/scip/`.

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts src/main/proto/scip.proto
git commit -m "feat: add Gradle protobuf plugin and SCIP proto schema"
```

---

### Task 2: Flyway V4 Migration — SCIP Tables

**Files:**
- Create: `src/main/resources/db/migration/V4__scip_semantic.sql`

- [ ] **Step 1: Write the migration**

```sql
-- Type-resolved symbol definitions from SCIP
CREATE TABLE scip_symbols (
    id              SERIAL PRIMARY KEY,
    repo_id         INT NOT NULL REFERENCES repositories(id),
    scip_symbol     TEXT NOT NULL,
    display_name    VARCHAR(512),
    kind            VARCHAR(32),
    documentation   TEXT,
    file_path       VARCHAR(1024) NOT NULL,
    start_line      INT,
    end_line        INT,
    upload_sha      VARCHAR(64) NOT NULL,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (repo_id, scip_symbol)
);

CREATE INDEX idx_scip_symbols_repo_file ON scip_symbols (repo_id, file_path);
CREATE INDEX idx_scip_symbols_repo_name ON scip_symbols (repo_id, display_name);

-- Type-resolved relationships between SCIP symbols
CREATE TABLE scip_relationships (
    id              SERIAL PRIMARY KEY,
    repo_id         INT NOT NULL REFERENCES repositories(id),
    from_symbol     TEXT NOT NULL,
    to_symbol       TEXT NOT NULL,
    kind            VARCHAR(32) NOT NULL,
    file_path       VARCHAR(1024),
    line            INT
);

CREATE INDEX idx_scip_rel_to ON scip_relationships (repo_id, to_symbol, kind);
CREATE INDEX idx_scip_rel_from ON scip_relationships (repo_id, from_symbol, kind);

-- SCIP staleness tracking on repositories
ALTER TABLE repositories ADD COLUMN scip_sha VARCHAR(64);
ALTER TABLE repositories ADD COLUMN scip_uploaded_at TIMESTAMPTZ;
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V4__scip_semantic.sql
git commit -m "feat: add V4 Flyway migration for SCIP semantic tables"
```

---

### Task 3: Config Extension — scipUpload on API Keys + CallerIdentity

**Files:**
- Modify: `src/main/java/com/indexer/config/IndexerConfig.java`
- Modify: `src/main/java/com/indexer/config/ConfigLoader.java`
- Modify: `src/main/java/com/indexer/auth/ApiKeyAuthenticator.java`
- Modify: `src/main/java/com/indexer/auth/CallerIdentity.java`
- Modify: `src/test/java/com/indexer/auth/CallerIdentityTest.java`
- Modify: `src/main/java/com/indexer/Application.java`

- [ ] **Step 1: Add scipUpload to ApiKeyEntry**

In `IndexerConfig.java`, replace the `ApiKeyEntry` record (line 84):

From: `public record ApiKeyEntry(String key, String id, String name, boolean auditReader) {}`
To: `public record ApiKeyEntry(String key, String id, String name, boolean auditReader, boolean scipUpload) {}`

- [ ] **Step 2: Update ConfigLoader**

In `ConfigLoader.java`, in the API key parsing (around line 187-188), update:

From:
```java
boolean auditReader = keyNode.has("auditReader") && keyNode.get("auditReader").asBoolean(false);
keys.add(new IndexerConfig.McpAuthConfig.ApiKeyEntry(key, id, name != null ? name : id, auditReader));
```

To:
```java
boolean auditReader = keyNode.has("auditReader") && keyNode.get("auditReader").asBoolean(false);
boolean scipUpload = keyNode.has("scipUpload") && keyNode.get("scipUpload").asBoolean(false);
keys.add(new IndexerConfig.McpAuthConfig.ApiKeyEntry(key, id, name != null ? name : id, auditReader, scipUpload));
```

- [ ] **Step 3: Update ApiKeyAuthenticator.ApiKeyConfig**

In `ApiKeyAuthenticator.java`, replace the `ApiKeyConfig` record (line 19):

From: `public record ApiKeyConfig(String key, String id, String name, boolean auditReader) {}`
To: `public record ApiKeyConfig(String key, String id, String name, boolean auditReader, boolean scipUpload) {}`

Update `authenticate()` (line 53) to pass `scipUpload`:

From:
```java
return Optional.of(CallerIdentity.fromApiKey(keyConfig.id(), keyConfig.name(), sourceIp, keyConfig.auditReader()));
```

To:
```java
return Optional.of(CallerIdentity.fromApiKey(keyConfig.id(), keyConfig.name(), sourceIp, keyConfig.auditReader(), keyConfig.scipUpload()));
```

- [ ] **Step 4: Add scipUpload to CallerIdentity**

Replace `CallerIdentity.java` entirely:

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
        boolean auditReader,
        boolean scipUpload
) {
    public CallerIdentity {
        groups = groups != null ? List.copyOf(groups) : List.of();
    }

    public static final String CONTEXT_KEY = "callerIdentity";

    public static CallerIdentity anonymous(String transport) {
        return new CallerIdentity(null, "anonymous", "none", transport, null, null, null, List.of(), false, false);
    }

    public static CallerIdentity fromStdio() {
        String osUser = System.getProperty("user.name");
        return new CallerIdentity(osUser, osUser, "stdio-os-user", "stdio", null, null, null, List.of(), true, false);
    }

    public static CallerIdentity fromApiKey(String id, String name, String sourceIp) {
        return new CallerIdentity(id, name, "api-key", "streamable-http", sourceIp, null, null, List.of(), false, false);
    }

    public static CallerIdentity fromApiKey(String id, String name, String sourceIp, boolean auditReader) {
        return new CallerIdentity(id, name, "api-key", "streamable-http", sourceIp, null, null, List.of(), auditReader, false);
    }

    public static CallerIdentity fromApiKey(String id, String name, String sourceIp, boolean auditReader, boolean scipUpload) {
        return new CallerIdentity(id, name, "api-key", "streamable-http", sourceIp, null, null, List.of(), auditReader, scipUpload);
    }

    public static CallerIdentity fromOAuth(String sub, String name, List<String> groups, String sourceIp) {
        return new CallerIdentity(sub, name, "oauth", "streamable-http", sourceIp, null, null, groups, false, false);
    }

    public static CallerIdentity fromAdminToken(String sourceIp) {
        return new CallerIdentity("admin", "Admin", "admin-token", "streamable-http", sourceIp, null, null, List.of(), false, false);
    }
}
```

- [ ] **Step 5: Update Application.java wiring**

In `Application.java`, update the ApiKeyConfig construction (line 116):

From:
```java
.map(e -> new ApiKeyAuthenticator.ApiKeyConfig(e.key(), e.id(), e.name(), e.auditReader()))
```

To:
```java
.map(e -> new ApiKeyAuthenticator.ApiKeyConfig(e.key(), e.id(), e.name(), e.auditReader(), e.scipUpload()))
```

- [ ] **Step 6: Add CallerIdentity test**

Add to `CallerIdentityTest.java`:

```java
@Test
void fromApiKeyWithScipUpload() {
    var identity = CallerIdentity.fromApiKey("ci", "CI Pipeline", "10.0.0.1", false, true);
    assertThat(identity.scipUpload()).isTrue();
    assertThat(identity.auditReader()).isFalse();
}
```

- [ ] **Step 7: Fix compilation errors and run tests**

Run: `./gradlew compileJava compileTestJava 2>&1 | tail -10`
Fix any compilation errors from the constructor signature changes in test files (add `false` for `scipUpload` where `ApiKeyConfig` is constructed in tests).

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/indexer/config/IndexerConfig.java src/main/java/com/indexer/config/ConfigLoader.java src/main/java/com/indexer/auth/ApiKeyAuthenticator.java src/main/java/com/indexer/auth/CallerIdentity.java src/test/java/com/indexer/auth/CallerIdentityTest.java src/main/java/com/indexer/Application.java
git commit -m "feat: add scipUpload config flag to API keys and wire through to CallerIdentity"
```

---

### Task 4: ScipParser — Extract Definitions and Relationships

**Files:**
- Create: `src/main/java/com/indexer/scip/ScipParser.java`
- Create: `src/main/java/com/indexer/scip/ScipSymbolRow.java`
- Create: `src/main/java/com/indexer/scip/ScipRelationshipRow.java`
- Create: `src/main/java/com/indexer/scip/ScipParseResult.java`
- Create: `src/test/java/com/indexer/scip/ScipParserTest.java`

- [ ] **Step 1: Create data records**

```java
// ScipSymbolRow.java
package com.indexer.scip;

public record ScipSymbolRow(
        String scipSymbol,
        String displayName,
        String kind,
        String documentation,
        String filePath,
        int startLine,
        int endLine
) {}
```

```java
// ScipRelationshipRow.java
package com.indexer.scip;

public record ScipRelationshipRow(
        String fromSymbol,
        String toSymbol,
        String kind,
        String filePath,
        int line
) {}
```

```java
// ScipParseResult.java
package com.indexer.scip;

import java.util.List;

public record ScipParseResult(
        List<ScipSymbolRow> symbols,
        List<ScipRelationshipRow> relationships,
        int documentsProcessed
) {}
```

- [ ] **Step 2: Write ScipParser tests**

```java
package com.indexer.scip;

import com.sourcegraph.scip.Scip;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScipParserTest {

    @Test
    void extractsDefinitionFromOccurrence() {
        // Build a minimal SCIP Index with one document containing one definition occurrence
        var occurrence = Scip.Occurrence.newBuilder()
                .setSymbol("java maven . com/example/Payment#charge().")
                .addAllRange(List.of(10, 4, 10, 10)) // line 10, chars 4-10 (single-line)
                .setSymbolRoles(Scip.SymbolRole.Definition_VALUE)
                .build();

        var symbolInfo = Scip.SymbolInformation.newBuilder()
                .setSymbol("java maven . com/example/Payment#charge().")
                .addDocumentation("Charges the payment method")
                .setKind(Scip.SymbolInformation.Kind.Method)
                .setDisplayName("charge")
                .build();

        var document = Scip.Document.newBuilder()
                .setRelativePath("src/main/java/com/example/Payment.java")
                .setLanguage("java")
                .addOccurrences(occurrence)
                .addSymbols(symbolInfo)
                .build();

        var index = Scip.Index.newBuilder()
                .addDocuments(document)
                .build();

        var result = ScipParser.parse(index);

        assertThat(result.documentsProcessed()).isEqualTo(1);
        assertThat(result.symbols()).hasSize(1);
        var sym = result.symbols().get(0);
        assertThat(sym.scipSymbol()).isEqualTo("java maven . com/example/Payment#charge().");
        assertThat(sym.displayName()).isEqualTo("charge");
        assertThat(sym.kind()).isEqualTo("Method");
        assertThat(sym.documentation()).isEqualTo("Charges the payment method");
        assertThat(sym.filePath()).isEqualTo("src/main/java/com/example/Payment.java");
        assertThat(sym.startLine()).isEqualTo(11); // SCIP uses 0-based lines, we store 1-based
    }

    @Test
    void extractsRelationships() {
        var relationship = Scip.Relationship.newBuilder()
                .setSymbol("java maven . com/example/PaymentProcessor#.")
                .setIsImplementation(true)
                .build();

        var symbolInfo = Scip.SymbolInformation.newBuilder()
                .setSymbol("java maven . com/example/StripeProcessor#.")
                .setKind(Scip.SymbolInformation.Kind.Class)
                .setDisplayName("StripeProcessor")
                .addRelationships(relationship)
                .build();

        var occurrence = Scip.Occurrence.newBuilder()
                .setSymbol("java maven . com/example/StripeProcessor#.")
                .addAllRange(List.of(5, 0, 5, 20))
                .setSymbolRoles(Scip.SymbolRole.Definition_VALUE)
                .build();

        var document = Scip.Document.newBuilder()
                .setRelativePath("src/main/java/com/example/StripeProcessor.java")
                .setLanguage("java")
                .addOccurrences(occurrence)
                .addSymbols(symbolInfo)
                .build();

        var index = Scip.Index.newBuilder().addDocuments(document).build();

        var result = ScipParser.parse(index);

        assertThat(result.relationships()).hasSize(1);
        var rel = result.relationships().get(0);
        assertThat(rel.fromSymbol()).isEqualTo("java maven . com/example/StripeProcessor#.");
        assertThat(rel.toSymbol()).isEqualTo("java maven . com/example/PaymentProcessor#.");
        assertThat(rel.kind()).isEqualTo("implements");
    }

    @Test
    void skipsReferenceOccurrences() {
        // Reference occurrence (no Definition role) should not produce a symbol row
        var refOccurrence = Scip.Occurrence.newBuilder()
                .setSymbol("java maven . com/example/Logger#info().")
                .addAllRange(List.of(20, 8, 20, 12))
                .setSymbolRoles(0) // no roles = reference
                .build();

        var defOccurrence = Scip.Occurrence.newBuilder()
                .setSymbol("java maven . com/example/Payment#charge().")
                .addAllRange(List.of(10, 4, 10, 10))
                .setSymbolRoles(Scip.SymbolRole.Definition_VALUE)
                .build();

        var symbolInfo = Scip.SymbolInformation.newBuilder()
                .setSymbol("java maven . com/example/Payment#charge().")
                .setKind(Scip.SymbolInformation.Kind.Method)
                .setDisplayName("charge")
                .build();

        var document = Scip.Document.newBuilder()
                .setRelativePath("src/Payment.java")
                .addOccurrences(refOccurrence)
                .addOccurrences(defOccurrence)
                .addSymbols(symbolInfo)
                .build();

        var index = Scip.Index.newBuilder().addDocuments(document).build();

        var result = ScipParser.parse(index);

        // Only the definition occurrence should produce a symbol
        assertThat(result.symbols()).hasSize(1);
        assertThat(result.symbols().get(0).scipSymbol()).isEqualTo("java maven . com/example/Payment#charge().");
    }

    @Test
    void handlesEmptyIndex() {
        var index = Scip.Index.newBuilder().build();
        var result = ScipParser.parse(index);

        assertThat(result.symbols()).isEmpty();
        assertThat(result.relationships()).isEmpty();
        assertThat(result.documentsProcessed()).isEqualTo(0);
    }
}
```

- [ ] **Step 3: Implement ScipParser**

```java
package com.indexer.scip;

import com.sourcegraph.scip.Scip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ScipParser {

    private static final Logger log = LoggerFactory.getLogger(ScipParser.class);

    public static ScipParseResult parse(Scip.Index index) {
        var symbols = new ArrayList<ScipSymbolRow>();
        var relationships = new ArrayList<ScipRelationshipRow>();

        for (var document : index.getDocumentsList()) {
            String filePath = document.getRelativePath();

            // Build a lookup from symbol string to SymbolInformation
            var symbolInfoMap = new HashMap<String, Scip.SymbolInformation>();
            for (var si : document.getSymbolsList()) {
                symbolInfoMap.put(si.getSymbol(), si);
            }

            // Find definition occurrences
            for (var occ : document.getOccurrencesList()) {
                if ((occ.getSymbolRoles() & Scip.SymbolRole.Definition_VALUE) == 0) {
                    continue; // skip references
                }

                String scipSymbol = occ.getSymbol();
                var range = occ.getRangeList();
                int startLine = range.size() >= 1 ? range.get(0) + 1 : 0; // 0-based to 1-based
                int endLine = range.size() >= 3 ? range.get(2) + 1 : startLine;
                // SCIP range: [startLine, startChar, endLine, endChar] or [line, startChar, endChar] for single-line
                if (range.size() == 3) {
                    endLine = startLine; // single-line occurrence
                }

                Scip.SymbolInformation info = symbolInfoMap.get(scipSymbol);
                String displayName = info != null && !info.getDisplayName().isEmpty() ? info.getDisplayName() : extractSimpleName(scipSymbol);
                String kind = info != null ? info.getKind().name() : "Unknown";
                String documentation = info != null && info.getDocumentationCount() > 0 ? info.getDocumentation(0) : null;

                symbols.add(new ScipSymbolRow(scipSymbol, displayName, kind, documentation, filePath, startLine, endLine));

                // Extract relationships from this symbol's SymbolInformation
                if (info != null) {
                    for (var rel : info.getRelationshipsList()) {
                        String relKind;
                        if (rel.getIsImplementation()) relKind = "implements";
                        else if (rel.getIsTypeDefinition()) relKind = "extends";
                        else if (rel.getIsReference()) relKind = "references";
                        else relKind = "references";

                        relationships.add(new ScipRelationshipRow(
                                scipSymbol, rel.getSymbol(), relKind, filePath, startLine));
                    }
                }
            }
        }

        log.info("Parsed SCIP: {} symbols, {} relationships from {} documents",
                symbols.size(), relationships.size(), index.getDocumentsCount());
        return new ScipParseResult(symbols, relationships, index.getDocumentsCount());
    }

    /** Extract a simple name from a SCIP symbol string (last segment after # or .). */
    private static String extractSimpleName(String scipSymbol) {
        if (scipSymbol == null || scipSymbol.isEmpty()) return "unknown";
        // SCIP symbols look like: java maven . com/example/Payment#charge().
        int hash = scipSymbol.lastIndexOf('#');
        if (hash >= 0 && hash < scipSymbol.length() - 1) {
            String after = scipSymbol.substring(hash + 1);
            // Remove trailing punctuation (., (), etc.)
            return after.replaceAll("[.()]+$", "");
        }
        return scipSymbol;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "com.indexer.scip.ScipParserTest" --rerun 2>&1 | tail -15`
Expected: All 4 tests pass.

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/scip/ScipParser.java src/main/java/com/indexer/scip/ScipSymbolRow.java src/main/java/com/indexer/scip/ScipRelationshipRow.java src/main/java/com/indexer/scip/ScipParseResult.java src/test/java/com/indexer/scip/ScipParserTest.java
git commit -m "feat: add ScipParser for extracting definitions and relationships from SCIP protobuf"
```

---

### Task 5: ScipService — Upload Processing + Storage

**Files:**
- Create: `src/main/java/com/indexer/scip/ScipService.java`
- Create: `src/main/java/com/indexer/scip/ScipUploadResult.java`

- [ ] **Step 1: Create ScipUploadResult**

```java
package com.indexer.scip;

public record ScipUploadResult(
        String repo,
        String sha,
        int symbols,
        int relationships,
        int documentsProcessed
) {}
```

- [ ] **Step 2: Implement ScipService**

```java
package com.indexer.scip;

import com.indexer.db.FileDao;
import com.indexer.db.RepositoryDao;
import com.indexer.model.Repository;
import com.sourcegraph.scip.Scip;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class ScipService {

    private static final Logger log = LoggerFactory.getLogger(ScipService.class);
    private static final long MAX_UPLOAD_BYTES = 50 * 1024 * 1024; // 50 MB

    private final RepositoryDao repositoryDao;
    private final FileDao fileDao;
    private final Jdbi jdbi;

    public ScipService(RepositoryDao repositoryDao, FileDao fileDao, Jdbi jdbi) {
        this.repositoryDao = repositoryDao;
        this.fileDao = fileDao;
        this.jdbi = jdbi;
    }

    public Optional<Repository> findRepo(String repoName) {
        return repositoryDao.findByName(repoName);
    }

    public ScipUploadResult processUpload(Repository repo, String gitSha, byte[] scipBytes) {
        // Size check
        if (scipBytes.length > MAX_UPLOAD_BYTES) {
            throw new ScipUploadException("Upload exceeds maximum size of " + MAX_UPLOAD_BYTES + " bytes");
        }

        // Parse protobuf
        Scip.Index index;
        try {
            index = Scip.Index.parseFrom(scipBytes);
        } catch (Exception e) {
            throw new ScipUploadException("Invalid SCIP protobuf: " + e.getMessage());
        }

        // Cross-ref check: at least one document path must match an indexed file
        Set<String> indexedPaths = fileDao.findByRepo(repo.id()).stream()
                .map(f -> f.path())
                .collect(Collectors.toSet());

        boolean hasOverlap = index.getDocumentsList().stream()
                .anyMatch(doc -> indexedPaths.contains(doc.getRelativePath()));
        if (!hasOverlap) {
            throw new ScipUploadException(
                    "No SCIP document paths match indexed files for repo '" + repo.name()
                    + "'. Ensure the SCIP data was produced from the correct repository.");
        }

        // Parse
        ScipParseResult parseResult = ScipParser.parse(index);

        // Store in transaction: delete old, insert new, update repo
        jdbi.useTransaction(handle -> {
            // Delete existing SCIP data
            handle.createUpdate("DELETE FROM scip_relationships WHERE repo_id = :repoId")
                    .bind("repoId", repo.id())
                    .execute();
            handle.createUpdate("DELETE FROM scip_symbols WHERE repo_id = :repoId")
                    .bind("repoId", repo.id())
                    .execute();

            // Bulk insert symbols
            var symbolBatch = handle.prepareBatch("""
                    INSERT INTO scip_symbols (repo_id, scip_symbol, display_name, kind, documentation,
                                              file_path, start_line, end_line, upload_sha, uploaded_at)
                    VALUES (:repoId, :scipSymbol, :displayName, :kind, :documentation,
                            :filePath, :startLine, :endLine, :uploadSha, NOW())
                    ON CONFLICT (repo_id, scip_symbol) DO UPDATE SET
                        display_name = EXCLUDED.display_name, kind = EXCLUDED.kind,
                        documentation = EXCLUDED.documentation, file_path = EXCLUDED.file_path,
                        start_line = EXCLUDED.start_line, end_line = EXCLUDED.end_line,
                        upload_sha = EXCLUDED.upload_sha, uploaded_at = NOW()
                    """);
            for (var sym : parseResult.symbols()) {
                symbolBatch
                        .bind("repoId", repo.id())
                        .bind("scipSymbol", sym.scipSymbol())
                        .bind("displayName", sym.displayName())
                        .bind("kind", sym.kind())
                        .bind("documentation", sym.documentation())
                        .bind("filePath", sym.filePath())
                        .bind("startLine", sym.startLine())
                        .bind("endLine", sym.endLine())
                        .bind("uploadSha", gitSha)
                        .add();
            }
            if (!parseResult.symbols().isEmpty()) {
                symbolBatch.execute();
            }

            // Bulk insert relationships
            var relBatch = handle.prepareBatch("""
                    INSERT INTO scip_relationships (repo_id, from_symbol, to_symbol, kind, file_path, line)
                    VALUES (:repoId, :fromSymbol, :toSymbol, :kind, :filePath, :line)
                    """);
            for (var rel : parseResult.relationships()) {
                relBatch
                        .bind("repoId", repo.id())
                        .bind("fromSymbol", rel.fromSymbol())
                        .bind("toSymbol", rel.toSymbol())
                        .bind("kind", rel.kind())
                        .bind("filePath", rel.filePath())
                        .bind("line", rel.line())
                        .add();
            }
            if (!parseResult.relationships().isEmpty()) {
                relBatch.execute();
            }

            // Update repo SCIP tracking
            handle.createUpdate(
                    "UPDATE repositories SET scip_sha = :sha, scip_uploaded_at = NOW() WHERE id = :id")
                    .bind("sha", gitSha)
                    .bind("id", repo.id())
                    .execute();
        });

        log.info("SCIP upload for {}: {} symbols, {} relationships from {} documents (sha: {})",
                repo.name(), parseResult.symbols().size(), parseResult.relationships().size(),
                parseResult.documentsProcessed(), gitSha);

        return new ScipUploadResult(repo.name(), gitSha,
                parseResult.symbols().size(), parseResult.relationships().size(),
                parseResult.documentsProcessed());
    }
}
```

- [ ] **Step 3: Create ScipUploadException**

```java
package com.indexer.scip;

public class ScipUploadException extends RuntimeException {
    public ScipUploadException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/scip/ScipService.java src/main/java/com/indexer/scip/ScipUploadResult.java src/main/java/com/indexer/scip/ScipUploadException.java
git commit -m "feat: add ScipService for SCIP upload processing and storage"
```

---

### Task 6: ScipApi — Upload Endpoint with Auth + Audit

**Files:**
- Create: `src/main/java/com/indexer/scip/ScipApi.java`
- Modify: `src/main/java/com/indexer/Application.java`

- [ ] **Step 1: Implement ScipApi**

```java
package com.indexer.scip;

import com.indexer.audit.AuditEvent;
import com.indexer.audit.AuditSink;
import com.indexer.auth.ApiKeyAuthenticator;
import com.indexer.auth.CallerIdentity;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class ScipApi {

    private static final Logger log = LoggerFactory.getLogger(ScipApi.class);

    private final ApiKeyAuthenticator authenticator;
    private final ScipService scipService;
    private final AuditSink auditSink;

    public ScipApi(ApiKeyAuthenticator authenticator, ScipService scipService, AuditSink auditSink) {
        this.authenticator = authenticator;
        this.scipService = scipService;
        this.auditSink = auditSink;
    }

    public void registerRoutes(RoutesConfig routes) {
        routes.post("/api/scip/{repoName}", this::handleUpload);
    }

    private void handleUpload(Context ctx) {
        String repoName = ctx.pathParam("repoName");

        // Authenticate
        CallerIdentity caller = authenticate(ctx);
        if (caller == null) return; // response already sent

        // Check scipUpload permission
        if (!caller.scipUpload()) {
            auditBestEffort(caller, repoName, false, "denied", "Missing scipUpload permission");
            ctx.status(403).json(Map.of("error", "API key does not have scipUpload permission"));
            return;
        }

        // Validate X-Git-SHA header
        String gitSha = ctx.header("X-Git-SHA");
        if (gitSha == null || gitSha.isBlank()) {
            auditBestEffort(caller, repoName, true, "error", "Missing X-Git-SHA header");
            ctx.status(400).json(Map.of("error", "X-Git-SHA header is required"));
            return;
        }

        // Validate repo exists
        var optRepo = scipService.findRepo(repoName);
        if (optRepo.isEmpty()) {
            auditBestEffort(caller, repoName, true, "error", "Repository not found");
            ctx.status(404).json(Map.of("error", "Repository '" + repoName + "' not found"));
            return;
        }

        // Process upload
        try {
            byte[] body = ctx.bodyAsBytes();
            var result = scipService.processUpload(optRepo.get(), gitSha, body);
            auditBestEffort(caller, repoName, true, "success", null);
            ctx.json(Map.of(
                    "repo", result.repo(),
                    "sha", result.sha(),
                    "symbols", result.symbols(),
                    "relationships", result.relationships(),
                    "documents_processed", result.documentsProcessed()
            ));
        } catch (ScipUploadException e) {
            int status = switch (e.getMessage()) {
                case String msg when msg.startsWith("Upload exceeds") -> 413;
                case String msg when msg.startsWith("Invalid SCIP") -> 400;
                case String msg when msg.startsWith("No SCIP document") -> 422;
                default -> 400;
            };
            auditBestEffort(caller, repoName, true, "error", e.getMessage());
            ctx.status(status).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("SCIP upload failed for {}: {}", repoName, e.getMessage(), e);
            auditBestEffort(caller, repoName, true, "error", e.getMessage());
            ctx.status(500).json(Map.of("error", "Internal error processing SCIP upload"));
        }
    }

    private CallerIdentity authenticate(Context ctx) {
        String authHeader = ctx.header("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            auditAuthFailure(ctx, "Missing Authorization header");
            ctx.status(401).json(Map.of("error", "Missing or invalid Authorization header"));
            return null;
        }
        String token = authHeader.substring("Bearer ".length());
        var optIdentity = authenticator.authenticate(token, ctx.ip());
        if (optIdentity.isEmpty()) {
            auditAuthFailure(ctx, "Invalid API key");
            ctx.status(401).json(Map.of("error", "Invalid API key"));
            return null;
        }
        return optIdentity.get();
    }

    private void auditAuthFailure(Context ctx, String message) {
        if (auditSink == null) return;
        try {
            var caller = CallerIdentity.anonymous("streamable-http");
            auditSink.record(AuditEvent.from(caller, "scip:authFailure", null, false, "denied", message));
        } catch (Exception e) {
            log.warn("Audit write failed for SCIP auth failure: {}", e.getMessage());
        }
    }

    private void auditBestEffort(CallerIdentity caller, String repo,
                                  boolean authorized, String resultStatus, String errorMessage) {
        if (auditSink == null) return;
        try {
            auditSink.record(AuditEvent.from(caller, "scip:upload", repo, authorized, resultStatus, errorMessage));
        } catch (Exception e) {
            log.warn("Audit write failed for SCIP upload: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Wire ScipApi in Application.java**

After the audit sink setup (around line 150, after `var auditSink = ...`) and before the Streamable HTTP transport builder, add:

```java
// 5f. Set up SCIP upload endpoint
var scipService = new com.indexer.scip.ScipService(repositoryDao, fileDao, jdbi);
var scipApi = new com.indexer.scip.ScipApi(authenticator, scipService, auditSink);
```

After the AdminApi route registration (after `httpServer.addRoutes(adminApi::registerRoutes)`), add:

```java
httpServer.addRoutes(scipApi::registerRoutes);
```

- [ ] **Step 3: Verify compilation and tests**

Run: `./gradlew compileJava compileTestJava 2>&1 | tail -10`
Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/indexer/scip/ScipApi.java src/main/java/com/indexer/Application.java
git commit -m "feat: add SCIP upload endpoint with API key auth and audit logging"
```

---

### Task 7: SCIP Staleness in get_index_health

**Files:**
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java`

- [ ] **Step 1: Update getIndexHealth to include SCIP status**

In `QueryExecutor.java`, update the `getIndexHealth` method. Replace the per-repo stats query (around line 682-692):

From:
```java
var repos = handle.createQuery("""
        SELECT r.name AS repo_name, r.last_indexed_sha,
               COUNT(CASE WHEN ie.status = 'pending' THEN 1 END) AS pending_events,
               COUNT(CASE WHEN ie.status = 'failed' THEN 1 END) AS failed_events
        FROM repositories r
        LEFT JOIN indexing_events ie ON ie.repo_name = r.name
        GROUP BY r.name, r.last_indexed_sha
        ORDER BY r.name
        """)
        .mapToMap()
        .list();
```

To:
```java
var repos = handle.createQuery("""
        SELECT r.name AS repo_name, r.last_indexed_sha, r.scip_sha, r.scip_uploaded_at,
               CASE
                   WHEN r.scip_sha IS NULL THEN 'unavailable'
                   WHEN r.scip_sha = r.last_indexed_sha THEN 'fresh'
                   ELSE 'stale'
               END AS scip_status,
               COUNT(CASE WHEN ie.status = 'pending' THEN 1 END) AS pending_events,
               COUNT(CASE WHEN ie.status = 'failed' THEN 1 END) AS failed_events
        FROM repositories r
        LEFT JOIN indexing_events ie ON ie.repo_name = r.name
        GROUP BY r.name, r.last_indexed_sha, r.scip_sha, r.scip_uploaded_at
        ORDER BY r.name
        """)
        .mapToMap()
        .list();
```

- [ ] **Step 2: Verify compilation and tests**

Run: `./gradlew test --rerun 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/indexer/mcp/QueryExecutor.java
git commit -m "feat: add SCIP staleness status to get_index_health"
```

---

### Implementation Notes

**Task dependency chain:** Task 1 (protobuf plugin) must come first — Tasks 4+ depend on generated SCIP classes. Task 2 (migration) is independent. Task 3 (config) is independent. Task 4 (ScipParser) depends on Task 1. Task 5 (ScipService) depends on Tasks 2 and 4. Task 6 (ScipApi) depends on Tasks 3 and 5. Task 7 (health) depends on Task 2.

**Proto file sourcing:** The SCIP proto is available at `https://raw.githubusercontent.com/sourcegraph/scip/main/scip.proto`. The implementer should download it and verify it compiles. If the proto doesn't include Java options, add `option java_package = "com.sourcegraph.scip";` and `option java_outer_classname = "Scip";`.

**SCIP range format:** SCIP uses 0-based line numbers. Our database uses 1-based (matching Tree-sitter). `ScipParser` adds 1 to all line numbers during extraction.

**SCIP symbol roles:** `SymbolRole.Definition_VALUE` is a bitmask. The check `(occ.getSymbolRoles() & Scip.SymbolRole.Definition_VALUE) != 0` correctly identifies definitions.

**SCIP relationship kinds:** The SCIP `Relationship` message uses boolean flags (`isImplementation`, `isTypeDefinition`, `isReference`) rather than an enum. `ScipParser` maps these to string kinds.

**Bulk insert pattern:** `ScipService` uses JDBI's `prepareBatch()` for efficient bulk insertion. The `ON CONFLICT` clause on `scip_symbols` handles the case where the same upload is retried.

**CLAUDE.md update needed after implementation:** Add `/api/scip/{repo}` to the API reference, document the `scipUpload` config flag, add SCIP staleness to the health check description.
