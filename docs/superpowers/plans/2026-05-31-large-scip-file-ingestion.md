# Large SCIP File Ingestion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ingest SCIP indexes that exceed the 50 MB upload cap (hazelcast is 484 MB) by splitting them client-side into valid sub-indexes and uploading them through a staged, all-or-nothing session lifecycle.

**Architecture:** A new CLI sub-command (`scip-split`) does a single streaming wire-level pass over a SCIP `Index`, slicing at `documents` (field 2) boundaries into N valid sub-indexes each ≤ a threshold (protobuf concatenation semantics make each slice a complete `Index`). A new server session API (`/api/scip/{repo}/uploads…`) accepts those parts, writes each into the existing `scip_symbols`/`scip_relationships` tables under a synthetic staging `upload_sha`, and on `complete` atomically promotes the staged rows to the real SHA. The existing single-shot `POST /api/scip/{repo}` stays as the degenerate 1-part path. Memory per request stays bounded by one part (≤50 MB), identical to today.

**Tech Stack:** Java 21, Gradle (Kotlin DSL), `protobuf-java` 4.29.3 (`com.sourcegraph.scip.Scip`), Javalin 7, JDBI 3, Flyway, PostgreSQL 16, JUnit 5 + Testcontainers + AssertJ, Bash (`scip-upload.sh`).

**Spec:** `docs/superpowers/specs/2026-05-31-scip-large-file-ingestion-design.md`

---

## File Structure

**New files**
- `src/main/resources/db/migration/V9__scip_upload_sessions.sql` — `scip_upload_sessions` + `scip_upload_parts` tables.
- `src/main/java/com/indexer/scip/ScipWriter.java` — shared staging/insert helper (delete-by-SHA + batched symbol/relationship inserts), extracted from `ScipService`.
- `src/main/java/com/indexer/scip/ScipSplitter.java` — streaming wire-level splitter (pure, testable).
- `src/main/java/com/indexer/scip/ScipSplitException.java` — thrown when a single item exceeds the part budget.
- `src/main/java/com/indexer/scip/ScipSplitCli.java` — thin CLI wrapper (arg parse, file IO, JSON manifest).
- `src/main/java/com/indexer/scip/ScipUploadSession.java` — session row model (record).
- `src/main/java/com/indexer/scip/ScipSessionDao.java` — session/part DAO.
- `src/main/java/com/indexer/scip/ScipSessionService.java` — init/part/complete/abort orchestration.
- `src/main/java/com/indexer/scip/ScipSessionReaperTask.java` — Runnable that GCs abandoned sessions.
- `src/test/java/com/indexer/scip/ScipSplitterTest.java` — unit (no PG).
- `src/test/java/com/indexer/scip/ScipSessionServiceTest.java` — integration (Testcontainers).
- `src/test/java/com/indexer/scip/ScipSessionReaperTaskTest.java` — integration (Testcontainers).

**Modified files**
- `src/main/java/com/indexer/scip/ScipService.java` — use `ScipWriter`; no behavior change.
- `src/main/java/com/indexer/scip/ScipDao.java` — exclude staging keys from prune.
- `src/main/java/com/indexer/scip/ScipApi.java` — add session routes; new `ScipSessionService` constructor arg.
- `src/main/java/com/indexer/config/IndexerConfig.java` — add `uploadSessionTtlHours` to `ScipConfig`.
- `src/main/java/com/indexer/Application.java` — `scip-split` main dispatch; wire `ScipSessionService` + reaper.
- `scripts/scip-upload.sh` — size detection + split + multipart flow.
- `CLAUDE.md` and `docs/ci-pipeline-guide.md` — document the new flow.

**Conventions to follow** (observed in the codebase):
- Migrations are plain SQL in `src/main/resources/db/migration/`, next free version is **V9** (`V8__branch_index_pinned.sql` exists).
- Integration tests use `@Testcontainers` + `@Tag("integration")` + Flyway `clean()`/`migrate()` (see `ScipServiceRetentionTest`); run via `./gradlew integrationTest`.
- JDBI access via `jdbi.useTransaction` / `jdbi.withHandle` / `prepareBatch`.
- The synthetic staging key format is `__staging__:<uploadId>`; real git SHAs are hex so this never collides. It is ≤48 chars, fitting the `VARCHAR(64)` `upload_sha` columns.

---

## Task 1: V9 migration — session + parts tables

**Files:**
- Create: `src/main/resources/db/migration/V9__scip_upload_sessions.sql`
- Test: `src/test/java/com/indexer/scip/ScipSessionDaoTest.java` (created in Task 5; this task verifies migration applies via the existing suite)

- [ ] **Step 1: Write the migration**

Create `src/main/resources/db/migration/V9__scip_upload_sessions.sql`:

```sql
-- Multi-part SCIP upload sessions. Large SCIP indexes (> 50 MB upload cap) are split
-- client-side into valid sub-indexes and uploaded as parts. Parts are written to
-- scip_symbols/scip_relationships under a synthetic staging upload_sha
-- ('__staging__:<uploadId>') and atomically promoted to the real SHA on completion.

CREATE TABLE scip_upload_sessions (
    id             VARCHAR(64)  PRIMARY KEY,                 -- uploadId (UUID string)
    repo_id        INTEGER      NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    target_sha     VARCHAR(64)  NOT NULL,                    -- the real X-Git-SHA
    staging_sha    VARCHAR(64)  NOT NULL,                    -- '__staging__:<uploadId>'
    status         VARCHAR(16)  NOT NULL DEFAULT 'open',     -- open | completed | aborted
    expected_parts INTEGER,                                  -- nullable; from X-Scip-Parts
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Reaper scans open sessions by age.
CREATE INDEX idx_scip_sessions_status_updated ON scip_upload_sessions (status, updated_at);

-- Idempotency ledger: a part recorded here has already been inserted. Re-uploading
-- a recorded part is a no-op (counts returned from this row).
CREATE TABLE scip_upload_parts (
    session_id   VARCHAR(64) NOT NULL REFERENCES scip_upload_sessions(id) ON DELETE CASCADE,
    part_number  INTEGER     NOT NULL,
    byte_size    BIGINT      NOT NULL,
    symbol_count INTEGER     NOT NULL,
    rel_count    INTEGER     NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (session_id, part_number)
);
```

- [ ] **Step 2: Verify migration applies**

Run: `./gradlew integrationTest --tests com.indexer.scip.ScipServiceRetentionTest`
Expected: PASS — this existing test runs `flyway.migrate()` over all migrations including the new V9, so a malformed migration fails here.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V9__scip_upload_sessions.sql
git commit -m "feat(scip): V9 migration for multi-part upload sessions"
```

---

## Task 2: Extract `ScipWriter` (refactor, no behavior change)

Pull the delete-by-SHA + batched insert logic out of `ScipService` so the session path can reuse it. Existing `ScipServiceRetentionTest` is the regression guard.

**Files:**
- Create: `src/main/java/com/indexer/scip/ScipWriter.java`
- Modify: `src/main/java/com/indexer/scip/ScipService.java:64-132`

- [ ] **Step 1: Write `ScipWriter`**

Create `src/main/java/com/indexer/scip/ScipWriter.java`:

```java
package com.indexer.scip;

import org.jdbi.v3.core.Handle;

/**
 * Shared SCIP row writer used by both the single-shot upload (ScipService) and the
 * multi-part session upload (ScipSessionService). All methods operate within a caller-supplied
 * transaction (Handle). Rows are keyed by the supplied uploadSha — a real git SHA for single-shot,
 * a synthetic staging key ('__staging__:<uploadId>') for in-flight session parts.
 */
public final class ScipWriter {

    private ScipWriter() {}

    /** Delete all SCIP rows for one (repo, uploadSha) from both tables. */
    public static void deleteForSha(Handle handle, int repoId, String uploadSha) {
        handle.createUpdate("DELETE FROM scip_relationships WHERE repo_id = :repoId AND upload_sha = :sha")
                .bind("repoId", repoId).bind("sha", uploadSha).execute();
        handle.createUpdate("DELETE FROM scip_symbols WHERE repo_id = :repoId AND upload_sha = :sha")
                .bind("repoId", repoId).bind("sha", uploadSha).execute();
    }

    /** Batch-insert parsed symbols and relationships under the given uploadSha. */
    public static void insert(Handle handle, int repoId, String uploadSha, ScipParseResult parseResult) {
        var symbolBatch = handle.prepareBatch("""
                INSERT INTO scip_symbols (repo_id, scip_symbol, display_name, kind, documentation,
                                          file_path, start_line, end_line, upload_sha, uploaded_at)
                VALUES (:repoId, :scipSymbol, :displayName, :kind, :documentation,
                        :filePath, :startLine, :endLine, :uploadSha, NOW())
                ON CONFLICT (repo_id, upload_sha, scip_symbol) DO UPDATE SET
                    display_name = EXCLUDED.display_name, kind = EXCLUDED.kind,
                    documentation = EXCLUDED.documentation, file_path = EXCLUDED.file_path,
                    start_line = EXCLUDED.start_line, end_line = EXCLUDED.end_line,
                    uploaded_at = NOW()
                """);
        for (var sym : parseResult.symbols()) {
            symbolBatch
                    .bind("repoId", repoId)
                    .bind("scipSymbol", sym.scipSymbol())
                    .bind("displayName", sym.displayName())
                    .bind("kind", sym.kind())
                    .bind("documentation", sym.documentation())
                    .bind("filePath", sym.filePath())
                    .bind("startLine", sym.startLine())
                    .bind("endLine", sym.endLine())
                    .bind("uploadSha", uploadSha)
                    .add();
        }
        if (!parseResult.symbols().isEmpty()) {
            symbolBatch.execute();
        }

        var relBatch = handle.prepareBatch("""
                INSERT INTO scip_relationships (repo_id, from_symbol, to_symbol, kind, file_path, line, upload_sha)
                VALUES (:repoId, :fromSymbol, :toSymbol, :kind, :filePath, :line, :uploadSha)
                """);
        for (var rel : parseResult.relationships()) {
            relBatch
                    .bind("repoId", repoId)
                    .bind("fromSymbol", rel.fromSymbol())
                    .bind("toSymbol", rel.toSymbol())
                    .bind("kind", rel.kind())
                    .bind("filePath", rel.filePath())
                    .bind("line", rel.line())
                    .bind("uploadSha", uploadSha)
                    .add();
        }
        if (!parseResult.relationships().isEmpty()) {
            relBatch.execute();
        }
    }
}
```

- [ ] **Step 2: Rewrite `ScipService` transaction body to call `ScipWriter`**

In `src/main/java/com/indexer/scip/ScipService.java`, replace the whole `jdbi.useTransaction(...)` block (lines 64-132) with:

```java
        // Store in transaction: delete old, insert new, update repo
        jdbi.useTransaction(handle -> {
            // Delete only this SHA's prior rows — other SHAs are retained
            ScipWriter.deleteForSha(handle, repo.id(), gitSha);
            ScipWriter.insert(handle, repo.id(), gitSha, parseResult);

            // Update repo SCIP tracking. scip_sha/scip_uploaded_at are informational only
            // ("most recent upload"); SCIP freshness is computed existence-based, not from this column.
            handle.createUpdate(
                    "UPDATE repositories SET scip_sha = :sha, scip_uploaded_at = NOW() WHERE id = :id")
                    .bind("sha", gitSha)
                    .bind("id", repo.id())
                    .execute();
        });
```

- [ ] **Step 3: Run the regression suite**

Run: `./gradlew integrationTest --tests com.indexer.scip.ScipServiceRetentionTest`
Expected: PASS — all three existing tests (`twoShasCoexistAfterSuccessiveUploads`, `reUploadingShaReplacesOnlyThatSha`, `reUploadingSameShaIsIdempotent`) still pass, proving the refactor preserved behavior.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/indexer/scip/ScipWriter.java src/main/java/com/indexer/scip/ScipService.java
git commit -m "refactor(scip): extract ScipWriter for shared row inserts"
```

---

## Task 3: `ScipSplitter` — streaming wire-level splitter

The core algorithm. Reads an `Index` field-by-field with `CodedInputStream`, captures `metadata` (field 1), and packs `documents` (field 2) and `external_symbols` (field 3) — both repeated, length-delimited — into buckets, each emitted as a valid sub-index. Assumes the standard SCIP layout where `metadata` precedes documents (true for `scip-java` output); metadata is replicated into every emitted part.

**Files:**
- Create: `src/main/java/com/indexer/scip/ScipSplitException.java`
- Create: `src/main/java/com/indexer/scip/ScipSplitter.java`
- Test: `src/test/java/com/indexer/scip/ScipSplitterTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/indexer/scip/ScipSplitterTest.java`:

```java
package com.indexer.scip;

import com.sourcegraph.scip.Scip;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScipSplitterTest {

    /** Build an Index with `count` documents, each carrying `padBytes` of filler to control size. */
    private byte[] buildIndex(int count, int padBytes) {
        var index = Scip.Index.newBuilder()
                .setMetadata(Scip.Metadata.newBuilder().setProjectRoot("file:///repo").build());
        String pad = "x".repeat(padBytes);
        for (int i = 0; i < count; i++) {
            index.addDocuments(Scip.Document.newBuilder()
                    .setRelativePath("src/File" + i + ".java")
                    .setLanguage("java")
                    .setText(pad)   // text field inflates the document size deterministically
                    .build());
        }
        return index.build().toByteArray();
    }

    private List<byte[]> split(byte[] input, long maxBytes) {
        List<byte[]> parts = new ArrayList<>();
        ScipSplitter.split(new ByteArrayInputStream(input), maxBytes,
                (n, bytes) -> parts.add(bytes));
        return parts;
    }

    @Test
    void splitsMultiDocumentIndexIntoMultipleValidParts() throws Exception {
        // 10 documents of ~10 KB each => ~100 KB; cap at 30 KB forces ~4 parts.
        byte[] input = buildIndex(10, 10_000);
        List<byte[]> parts = split(input, 30_000);

        assertThat(parts).hasSizeGreaterThan(1);

        // Every part is a valid Index and is within the cap.
        Set<String> allPaths = new java.util.HashSet<>();
        for (byte[] part : parts) {
            assertThat((long) part.length).isLessThanOrEqualTo(30_000L);
            Scip.Index parsed = Scip.Index.parseFrom(part);
            parsed.getDocumentsList().forEach(d -> allPaths.add(d.getRelativePath()));
        }

        // Union of documents across parts == original set.
        Set<String> expected = Scip.Index.parseFrom(input).getDocumentsList().stream()
                .map(Scip.Document::getRelativePath).collect(Collectors.toSet());
        assertThat(allPaths).isEqualTo(expected);
    }

    @Test
    void concatenationOfPartsReconstructsOriginalDocumentCount() throws Exception {
        byte[] input = buildIndex(10, 10_000);
        List<byte[]> parts = split(input, 30_000);

        // Protobuf concatenation property: concatenated parts parse as the merged Index.
        ByteArrayOutputStream cat = new ByteArrayOutputStream();
        for (byte[] p : parts) cat.write(p);
        Scip.Index merged = Scip.Index.parseFrom(cat.toByteArray());

        assertThat(merged.getDocumentsCount())
                .isEqualTo(Scip.Index.parseFrom(input).getDocumentsCount());
    }

    @Test
    void singleDocumentLargerThanBudgetFailsLoudly() {
        // One 50 KB document, cap at 20 KB => cannot fit under budget.
        byte[] input = buildIndex(1, 50_000);
        assertThatThrownBy(() -> split(input, 20_000))
                .isInstanceOf(ScipSplitException.class)
                .hasMessageContaining("exceeds max part size");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.indexer.scip.ScipSplitterTest`
Expected: FAIL — `ScipSplitter` / `ScipSplitException` do not exist (compilation error).

- [ ] **Step 3: Write `ScipSplitException`**

Create `src/main/java/com/indexer/scip/ScipSplitException.java`:

```java
package com.indexer.scip;

public class ScipSplitException extends RuntimeException {
    public ScipSplitException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Write `ScipSplitter`**

Create `src/main/java/com/indexer/scip/ScipSplitter.java`:

```java
package com.indexer.scip;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a SCIP protobuf Index into N smaller valid sub-indexes, slicing at the
 * repeated `documents` (field 2) and `external_symbols` (field 3) boundaries. Because protobuf
 * merges repeated fields on concatenation, every emitted part — metadata (field 1) plus a subset
 * of items — is itself a fully-valid Index.
 *
 * Single streaming pass; peak memory is bounded by one part (≤ maxBytesPerPart) plus the
 * (tiny) metadata bytes. Assumes the standard SCIP layout where metadata precedes documents.
 */
public final class ScipSplitter {

    // Index field numbers (see src/main/proto/scip.proto).
    private static final int FIELD_METADATA = 1;
    private static final int FIELD_DOCUMENTS = 2;
    private static final int FIELD_EXTERNAL_SYMBOLS = 3;

    // Per-item wire overhead allowance (tag + length varint); generous upper bound.
    private static final int ITEM_FRAMING_SLACK = 16;

    /** Receives each emitted part. partNumber is 1-based. */
    @FunctionalInterface
    public interface PartSink {
        void accept(int partNumber, byte[] partBytes) throws IOException;
    }

    private ScipSplitter() {}

    /**
     * @return the number of parts emitted (always ≥ 1).
     * @throws ScipSplitException if a single item cannot fit under maxBytesPerPart.
     */
    public static int split(InputStream in, long maxBytesPerPart, PartSink sink) {
        try {
            CodedInputStream cis = CodedInputStream.newInstance(in);
            cis.setSizeLimit(Integer.MAX_VALUE); // default cap is too small for large indexes

            byte[] metadata = new byte[0];

            // Current bucket state.
            List<byte[]> bucketItems = new ArrayList<>();
            List<Integer> bucketFields = new ArrayList<>();
            long bucketSize = 0;
            int partNumber = 0;

            while (true) {
                int tag = cis.readTag();
                if (tag == 0) break; // EOF
                int field = WireFormat.getTagFieldNumber(tag);

                if (field == FIELD_METADATA) {
                    metadata = cis.readByteArray();
                    continue;
                }
                if (field != FIELD_DOCUMENTS && field != FIELD_EXTERNAL_SYMBOLS) {
                    cis.skipField(tag);
                    continue;
                }

                byte[] item = cis.readByteArray();
                long itemCost = item.length + ITEM_FRAMING_SLACK;
                long metaCost = metadata.length + ITEM_FRAMING_SLACK;

                if (metaCost + itemCost > maxBytesPerPart) {
                    throw new ScipSplitException(
                            "SCIP item (field " + field + ", " + item.length
                            + " bytes) exceeds max part size of " + maxBytesPerPart
                            + " bytes; raise --max-bytes");
                }

                // Flush current bucket if adding this item would overflow.
                if (!bucketItems.isEmpty() && bucketSize + itemCost > maxBytesPerPart) {
                    emit(sink, ++partNumber, metadata, bucketItems, bucketFields);
                    bucketItems = new ArrayList<>();
                    bucketFields = new ArrayList<>();
                    bucketSize = 0;
                }
                if (bucketItems.isEmpty()) {
                    bucketSize = metaCost; // metadata is replicated into every part
                }
                bucketItems.add(item);
                bucketFields.add(field);
                bucketSize += itemCost;
            }

            // Emit the trailing bucket (or an empty metadata-only part if the index had no items).
            if (!bucketItems.isEmpty() || partNumber == 0) {
                emit(sink, ++partNumber, metadata, bucketItems, bucketFields);
            }
            return partNumber;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to split SCIP index", e);
        }
    }

    private static void emit(PartSink sink, int partNumber, byte[] metadata,
                             List<byte[]> items, List<Integer> fields) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(baos);
        if (metadata.length > 0) {
            out.writeByteArray(FIELD_METADATA, metadata);
        }
        for (int i = 0; i < items.size(); i++) {
            out.writeByteArray(fields.get(i), items.get(i));
        }
        out.flush();
        sink.accept(partNumber, baos.toByteArray());
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests com.indexer.scip.ScipSplitterTest`
Expected: PASS — all three tests green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/indexer/scip/ScipSplitter.java src/main/java/com/indexer/scip/ScipSplitException.java src/test/java/com/indexer/scip/ScipSplitterTest.java
git commit -m "feat(scip): streaming wire-level SCIP index splitter"
```

---

## Task 4: `ScipSplitCli` + `scip-split` main dispatch

**Files:**
- Create: `src/main/java/com/indexer/scip/ScipSplitCli.java`
- Modify: `src/main/java/com/indexer/Application.java:53-56`
- Test: `src/test/java/com/indexer/scip/ScipSplitCliTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/indexer/scip/ScipSplitCliTest.java`:

```java
package com.indexer.scip;

import com.sourcegraph.scip.Scip;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScipSplitCliTest {

    @Test
    void writesNumberedPartsToOutputDir(@TempDir Path tmp) throws Exception {
        var index = Scip.Index.newBuilder()
                .setMetadata(Scip.Metadata.newBuilder().setProjectRoot("file:///repo").build());
        for (int i = 0; i < 6; i++) {
            index.addDocuments(Scip.Document.newBuilder()
                    .setRelativePath("src/F" + i + ".java").setText("y".repeat(8_000)).build());
        }
        Path input = tmp.resolve("index.scip");
        Files.write(input, index.build().toByteArray());
        Path outDir = tmp.resolve("parts");

        ScipSplitCli.main(new String[]{
                input.toString(), "--max-bytes", "20000", "--out", outDir.toString()});

        List<Path> parts = Files.list(outDir).sorted().toList();
        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(parts.get(0).getFileName().toString()).isEqualTo("part-0001.scip");
        for (Path p : parts) {
            assertThat(Files.size(p)).isLessThanOrEqualTo(20_000L);
            Scip.Index.parseFrom(Files.readAllBytes(p)); // parses without throwing
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.indexer.scip.ScipSplitCliTest`
Expected: FAIL — `ScipSplitCli` does not exist.

- [ ] **Step 3: Write `ScipSplitCli`**

Create `src/main/java/com/indexer/scip/ScipSplitCli.java`:

```java
package com.indexer.scip;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI entry point for splitting a SCIP index into ≤ N-byte parts.
 *
 * Usage: scip-split &lt;input.scip&gt; --max-bytes &lt;N&gt; --out &lt;dir&gt;
 * Writes part-0001.scip … part-NNNN.scip and prints a JSON manifest to stdout.
 */
public final class ScipSplitCli {

    private static final long DEFAULT_MAX_BYTES = 47_185_920L; // 45 MiB (headroom under 50 MB server cap)

    private ScipSplitCli() {}

    public static void main(String[] args) {
        String input = null;
        long maxBytes = DEFAULT_MAX_BYTES;
        Path outDir = Path.of(".");

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--max-bytes" -> maxBytes = Long.parseLong(args[++i]);
                case "--out" -> outDir = Path.of(args[++i]);
                case "-h", "--help" -> { usage(); return; }
                default -> {
                    if (args[i].startsWith("--")) { usage(); throw new IllegalArgumentException("Unknown flag: " + args[i]); }
                    input = args[i];
                }
            }
        }
        if (input == null) { usage(); throw new IllegalArgumentException("Missing input .scip file"); }

        Path inputPath = Path.of(input);
        try {
            Files.createDirectories(outDir);
            List<Long> sizes = new ArrayList<>();
            final Path dir = outDir;
            int count;
            try (InputStream in = new BufferedInputStream(Files.newInputStream(inputPath))) {
                count = ScipSplitter.split(in, maxBytes, (n, bytes) -> {
                    Path part = dir.resolve(String.format("part-%04d.scip", n));
                    Files.write(part, bytes);
                    sizes.add((long) bytes.length);
                });
            }
            System.out.println(manifestJson(count, sizes));
        } catch (IOException e) {
            throw new RuntimeException("scip-split failed: " + e.getMessage(), e);
        }
    }

    private static String manifestJson(int parts, List<Long> sizes) {
        StringBuilder sb = new StringBuilder("{\"parts\":").append(parts).append(",\"sizes\":[");
        for (int i = 0; i < sizes.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(sizes.get(i));
        }
        return sb.append("]}").toString();
    }

    private static void usage() {
        System.err.println("Usage: scip-split <input.scip> --max-bytes <N> --out <dir>");
    }
}
```

- [ ] **Step 4: Add `scip-split` dispatch to `Application.main`**

In `src/main/java/com/indexer/Application.java`, add the import near the other `java.util` imports (after line 35, `import java.util.Map;`):

```java
import java.util.Arrays;
```

Replace the `main` method (lines 53-56) with:

```java
    public static void main(String[] args) {
        // CLI sub-command: split a large SCIP index into uploadable parts.
        if (args.length > 0 && args[0].equals("scip-split")) {
            com.indexer.scip.ScipSplitCli.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        String configPath = args.length > 0 ? args[0] : System.getProperty("user.home") + "/.source-code-indexer/config.yaml";
        new Application().start(Path.of(configPath));
    }
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests com.indexer.scip.ScipSplitCliTest`
Expected: PASS

- [ ] **Step 6: Verify the jar sub-command end-to-end (manual smoke)**

Run:
```bash
./gradlew installDist
build/install/SourceCodeIndexerMCP/bin/SourceCodeIndexerMCP scip-split /Users/csharpl/Projects/hazelcast/index.scip --max-bytes 47185920 --out /tmp/hz-parts
ls -la /tmp/hz-parts | head
```
Expected: a JSON manifest line like `{"parts":11,"sizes":[...]}` and `/tmp/hz-parts/part-0001.scip …` each ≤ 45 MB. (The exact module/script name under `build/install/.../bin/` matches the Gradle `application` project name; use whatever `installDist` produces.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/indexer/scip/ScipSplitCli.java src/main/java/com/indexer/Application.java src/test/java/com/indexer/scip/ScipSplitCliTest.java
git commit -m "feat(scip): scip-split CLI sub-command"
```

---

## Task 5: `ScipUploadSession` model + `ScipSessionDao`

**Files:**
- Create: `src/main/java/com/indexer/scip/ScipUploadSession.java`
- Create: `src/main/java/com/indexer/scip/ScipSessionDao.java`
- Test: `src/test/java/com/indexer/scip/ScipSessionDaoTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/indexer/scip/ScipSessionDaoTest.java`:

```java
package com.indexer.scip;

import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class ScipSessionDaoTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private Jdbi jdbi;
    private ScipSessionDao dao;
    private int repoId;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var flyway = Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO repositories (name, url, branch, clone_path, auth_type, last_indexed_sha) "
                    + "VALUES ('r', 'git@x:r.git', 'main', '/tmp', 'ssh-key', 'AAA')");
            repoId = h.createQuery("SELECT id FROM repositories WHERE name='r'").mapTo(Integer.class).one();
        });
        dao = new ScipSessionDao(jdbi);
    }

    @Test
    void createAndFetchSession() {
        ScipUploadSession s = dao.create(repoId, "SHA1");
        assertThat(s.id()).isNotBlank();
        assertThat(s.stagingSha()).isEqualTo("__staging__:" + s.id());
        assertThat(s.status()).isEqualTo("open");

        Optional<ScipUploadSession> fetched = dao.find(s.id());
        assertThat(fetched).isPresent();
        assertThat(fetched.get().targetSha()).isEqualTo("SHA1");
    }

    @Test
    void recordPartIsTrackedAndIdempotent() {
        ScipUploadSession s = dao.create(repoId, "SHA1");
        assertThat(dao.partExists(s.id(), 1)).isFalse();

        dao.recordPart(s.id(), 1, 1000L, 5, 3);
        assertThat(dao.partExists(s.id(), 1)).isTrue();

        Optional<int[]> counts = dao.partCounts(s.id(), 1);
        assertThat(counts).isPresent();
        assertThat(counts.get()).containsExactly(5, 3); // [symbolCount, relCount]
    }

    @Test
    void markCompletedUpdatesStatus() {
        ScipUploadSession s = dao.create(repoId, "SHA1");
        dao.markStatus(s.id(), "completed");
        assertThat(dao.find(s.id()).orElseThrow().status()).isEqualTo("completed");
    }

    @Test
    void findOpenOlderThanReturnsStaleSessions() {
        ScipUploadSession s = dao.create(repoId, "SHA1");
        // Backdate updated_at by 48h.
        jdbi.useHandle(h -> h.execute(
                "UPDATE scip_upload_sessions SET updated_at = NOW() - INTERVAL '48 hours' WHERE id = ?", s.id()));
        assertThat(dao.findOpenOlderThan(24)).extracting(ScipUploadSession::id).contains(s.id());
        assertThat(dao.findOpenOlderThan(72)).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew integrationTest --tests com.indexer.scip.ScipSessionDaoTest`
Expected: FAIL — `ScipUploadSession` / `ScipSessionDao` do not exist.

- [ ] **Step 3: Write `ScipUploadSession`**

Create `src/main/java/com/indexer/scip/ScipUploadSession.java`:

```java
package com.indexer.scip;

public record ScipUploadSession(
        String id,
        int repoId,
        String targetSha,
        String stagingSha,
        String status,
        Integer expectedParts
) {
    public static String stagingKeyFor(String uploadId) {
        return "__staging__:" + uploadId;
    }
}
```

- [ ] **Step 4: Write `ScipSessionDao`**

Create `src/main/java/com/indexer/scip/ScipSessionDao.java`:

```java
package com.indexer.scip;

import org.jdbi.v3.core.Jdbi;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Data-access for multi-part SCIP upload sessions and their part ledger. */
public class ScipSessionDao {

    private final Jdbi jdbi;

    public ScipSessionDao(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public ScipUploadSession create(int repoId, String targetSha) {
        String id = UUID.randomUUID().toString();
        String stagingSha = ScipUploadSession.stagingKeyFor(id);
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO scip_upload_sessions (id, repo_id, target_sha, staging_sha, status)
                VALUES (:id, :repoId, :targetSha, :stagingSha, 'open')
                """)
                .bind("id", id).bind("repoId", repoId)
                .bind("targetSha", targetSha).bind("stagingSha", stagingSha)
                .execute());
        return new ScipUploadSession(id, repoId, targetSha, stagingSha, "open", null);
    }

    public Optional<ScipUploadSession> find(String id) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT id, repo_id, target_sha, staging_sha, status, expected_parts
                FROM scip_upload_sessions WHERE id = :id
                """)
                .bind("id", id)
                .map((rs, ctx) -> new ScipUploadSession(
                        rs.getString("id"), rs.getInt("repo_id"), rs.getString("target_sha"),
                        rs.getString("staging_sha"), rs.getString("status"),
                        (Integer) rs.getObject("expected_parts")))
                .findOne());
    }

    public boolean partExists(String sessionId, int partNumber) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT count(*) > 0 FROM scip_upload_parts WHERE session_id = :s AND part_number = :p")
                .bind("s", sessionId).bind("p", partNumber)
                .mapTo(Boolean.class).one());
    }

    /** @return [symbolCount, relCount] for a recorded part, or empty if not recorded. */
    public Optional<int[]> partCounts(String sessionId, int partNumber) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT symbol_count, rel_count FROM scip_upload_parts WHERE session_id = :s AND part_number = :p")
                .bind("s", sessionId).bind("p", partNumber)
                .map((rs, ctx) -> new int[]{rs.getInt("symbol_count"), rs.getInt("rel_count")})
                .findOne());
    }

    public void recordPart(String sessionId, int partNumber, long byteSize, int symbolCount, int relCount) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO scip_upload_parts (session_id, part_number, byte_size, symbol_count, rel_count)
                VALUES (:s, :p, :bytes, :syms, :rels)
                """)
                .bind("s", sessionId).bind("p", partNumber).bind("bytes", byteSize)
                .bind("syms", symbolCount).bind("rels", relCount)
                .execute());
    }

    public void markStatus(String sessionId, String status) {
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE scip_upload_sessions SET status = :st, updated_at = NOW() WHERE id = :id")
                .bind("st", status).bind("id", sessionId).execute());
    }

    public List<ScipUploadSession> findOpenOlderThan(int ttlHours) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT id, repo_id, target_sha, staging_sha, status, expected_parts
                FROM scip_upload_sessions
                WHERE status = 'open' AND updated_at < NOW() - CAST(:hours || ' hours' AS INTERVAL)
                """)
                .bind("hours", ttlHours)
                .map((rs, ctx) -> new ScipUploadSession(
                        rs.getString("id"), rs.getInt("repo_id"), rs.getString("target_sha"),
                        rs.getString("staging_sha"), rs.getString("status"),
                        (Integer) rs.getObject("expected_parts")))
                .list());
    }

    /** Delete a session, its part ledger (FK cascade), and any staging rows it left behind. */
    public void deleteSessionAndStaging(ScipUploadSession session) {
        jdbi.useTransaction(h -> {
            h.createUpdate("DELETE FROM scip_relationships WHERE repo_id = :r AND upload_sha = :sha")
                    .bind("r", session.repoId()).bind("sha", session.stagingSha()).execute();
            h.createUpdate("DELETE FROM scip_symbols WHERE repo_id = :r AND upload_sha = :sha")
                    .bind("r", session.repoId()).bind("sha", session.stagingSha()).execute();
            h.createUpdate("DELETE FROM scip_upload_sessions WHERE id = :id")
                    .bind("id", session.id()).execute();
        });
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew integrationTest --tests com.indexer.scip.ScipSessionDaoTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/indexer/scip/ScipUploadSession.java src/main/java/com/indexer/scip/ScipSessionDao.java src/test/java/com/indexer/scip/ScipSessionDaoTest.java
git commit -m "feat(scip): session model + ScipSessionDao"
```

---

## Task 6: `ScipSessionService` — init/part/complete/abort

The orchestration tier. `part` parses a sub-index with the existing `ScipParser`, writes staging rows via `ScipWriter`, and records the part — idempotently. `complete` runs the file-overlap check, then atomically promotes staging rows to the real SHA.

**Files:**
- Create: `src/main/java/com/indexer/scip/ScipSessionService.java`
- Test: `src/test/java/com/indexer/scip/ScipSessionServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/indexer/scip/ScipSessionServiceTest.java`:

```java
package com.indexer.scip;

import com.indexer.db.FileDao;
import com.indexer.db.RepositoryDao;
import com.indexer.model.Repository;
import com.sourcegraph.scip.Scip;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Tag("integration")
class ScipSessionServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private Jdbi jdbi;
    private ScipSessionService service;
    private RepositoryDao repositoryDao;
    private int repoId;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var flyway = Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO repositories (name, url, branch, clone_path, auth_type, last_indexed_sha) "
                    + "VALUES ('r', 'git@x:r.git', 'main', '/tmp', 'ssh-key', 'AAA')");
            repoId = h.createQuery("SELECT id FROM repositories WHERE name='r'").mapTo(Integer.class).one();
            h.execute("INSERT INTO files (repo_id, branch, path, language) VALUES (?, 'main', 'src/A.java', 'java')", repoId);
            h.execute("INSERT INTO files (repo_id, branch, path, language) VALUES (?, 'main', 'src/B.java', 'java')", repoId);
        });
        repositoryDao = new RepositoryDao(jdbi);
        service = new ScipSessionService(repositoryDao, new FileDao(jdbi), new ScipSessionDao(jdbi), jdbi);
    }

    private Repository repo() { return repositoryDao.findByName("r").orElseThrow(); }

    private byte[] indexFor(String path, String symbol) {
        var occ = Scip.Occurrence.newBuilder().setSymbol(symbol)
                .addAllRange(List.of(1, 0, 5, 0)).setSymbolRoles(Scip.SymbolRole.Definition_VALUE).build();
        var info = Scip.SymbolInformation.newBuilder().setSymbol(symbol)
                .setKind(Scip.SymbolInformation.Kind.Class).setDisplayName(symbol).build();
        var doc = Scip.Document.newBuilder().setRelativePath(path).setLanguage("java")
                .addOccurrences(occ).addSymbols(info).build();
        return Scip.Index.newBuilder().addDocuments(doc).build().toByteArray();
    }

    private int liveSymbols(String sha) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT count(*) FROM scip_symbols WHERE repo_id = ? AND upload_sha = ?")
                .bind(0, repoId).bind(1, sha).mapTo(Integer.class).one());
    }

    @Test
    void multiPartUploadPromotesAllRowsOnComplete() {
        var session = service.init(repo(), "SHA1", null);
        service.part(session.id(), 1, indexFor("src/A.java", "java . A#"));
        service.part(session.id(), 2, indexFor("src/B.java", "java . B#"));

        // Before complete: nothing visible under the real SHA.
        assertThat(liveSymbols("SHA1")).isZero();

        var result = service.complete(repo(), session.id());
        assertThat(result.symbols()).isEqualTo(2);
        assertThat(liveSymbols("SHA1")).isEqualTo(2);
        // Staging rows are gone (promoted, not copied).
        assertThat(liveSymbols(session.stagingSha())).isZero();
    }

    @Test
    void interruptedSessionIsInvisibleUntilComplete() {
        var session = service.init(repo(), "SHA1", null);
        service.part(session.id(), 1, indexFor("src/A.java", "java . A#"));
        // No complete call: a query for SHA1 sees nothing.
        assertThat(liveSymbols("SHA1")).isZero();
    }

    @Test
    void rePostingSamePartIsIdempotent() {
        var session = service.init(repo(), "SHA1", null);
        service.part(session.id(), 1, indexFor("src/A.java", "java . A#"));
        service.part(session.id(), 1, indexFor("src/A.java", "java . A#")); // retry
        service.complete(repo(), session.id());
        assertThat(liveSymbols("SHA1")).isEqualTo(1);
    }

    @Test
    void completeWithNoMatchingFilesThrows422() {
        var session = service.init(repo(), "SHA1", null);
        service.part(session.id(), 1, indexFor("src/UNKNOWN.java", "java . X#"));
        assertThatThrownBy(() -> service.complete(repo(), session.id()))
                .isInstanceOf(ScipUploadException.class)
                .hasMessageStartingWith("No SCIP document");
    }

    @Test
    void completePromotesOverOldShaData() {
        // Seed an existing SHA1 row via a first session, then re-upload SHA1 via a second session.
        var s1 = service.init(repo(), "SHA1", null);
        service.part(s1.id(), 1, indexFor("src/A.java", "java . Old#"));
        service.complete(repo(), s1.id());
        assertThat(liveSymbols("SHA1")).isEqualTo(1);

        var s2 = service.init(repo(), "SHA1", null);
        service.part(s2.id(), 1, indexFor("src/A.java", "java . New#"));
        service.complete(repo(), s2.id());

        // Still exactly 1 row for SHA1, and it is the new symbol.
        assertThat(liveSymbols("SHA1")).isEqualTo(1);
        boolean isNew = jdbi.withHandle(h -> h.createQuery(
                "SELECT count(*) > 0 FROM scip_symbols WHERE repo_id = ? AND upload_sha = 'SHA1' AND scip_symbol LIKE '%New%'")
                .bind(0, repoId).mapTo(Boolean.class).one());
        assertThat(isNew).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew integrationTest --tests com.indexer.scip.ScipSessionServiceTest`
Expected: FAIL — `ScipSessionService` does not exist.

- [ ] **Step 3: Write `ScipSessionService`**

Create `src/main/java/com/indexer/scip/ScipSessionService.java`:

```java
package com.indexer.scip;

import com.indexer.db.FileDao;
import com.indexer.db.RepositoryDao;
import com.indexer.model.Repository;
import com.sourcegraph.scip.Scip;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates multi-part SCIP uploads. Parts are parsed with the existing ScipParser and
 * written to scip_symbols/scip_relationships under the session's synthetic staging upload_sha;
 * complete() atomically promotes those rows to the real target SHA (all-or-nothing).
 */
public class ScipSessionService {

    private static final Logger log = LoggerFactory.getLogger(ScipSessionService.class);

    private final RepositoryDao repositoryDao;
    private final FileDao fileDao;
    private final ScipSessionDao sessionDao;
    private final Jdbi jdbi;

    public ScipSessionService(RepositoryDao repositoryDao, FileDao fileDao,
                              ScipSessionDao sessionDao, Jdbi jdbi) {
        this.repositoryDao = repositoryDao;
        this.fileDao = fileDao;
        this.sessionDao = sessionDao;
        this.jdbi = jdbi;
    }

    public ScipUploadSession init(Repository repo, String targetSha, Integer expectedParts) {
        ScipUploadSession session = sessionDao.create(repo.id(), targetSha);
        log.info("SCIP upload session {} opened for {} (sha {})", session.id(), repo.name(), targetSha);
        return session;
    }

    /** Parse and stage one part. Idempotent: a re-posted part returns its recorded counts. */
    public ScipUploadResult part(String uploadId, int partNumber, byte[] partBytes) {
        ScipUploadSession session = sessionDao.find(uploadId)
                .orElseThrow(() -> new ScipUploadException("Invalid SCIP upload session: " + uploadId));
        if (!"open".equals(session.status())) {
            throw new ScipUploadException("Invalid SCIP session state: session " + uploadId + " is " + session.status());
        }

        // Idempotent replay: already recorded → return stored counts, do not re-insert.
        var existing = sessionDao.partCounts(uploadId, partNumber);
        if (existing.isPresent()) {
            return new ScipUploadResult(String.valueOf(session.repoId()), session.targetSha(),
                    existing.get()[0], existing.get()[1], 0);
        }

        Scip.Index index;
        try {
            index = Scip.Index.parseFrom(partBytes);
        } catch (Exception e) {
            throw new ScipUploadException("Invalid SCIP protobuf in part " + partNumber + ": " + e.getMessage());
        }
        ScipParseResult parsed = ScipParser.parse(index);

        // Insert staging rows + record the part in ONE transaction so a failure leaves no
        // half-applied part (and no ledger row → safe retry).
        jdbi.useTransaction(handle -> {
            ScipWriter.insert(handle, session.repoId(), session.stagingSha(), parsed);
            handle.createUpdate("""
                    INSERT INTO scip_upload_parts (session_id, part_number, byte_size, symbol_count, rel_count)
                    VALUES (:s, :p, :bytes, :syms, :rels)
                    """)
                    .bind("s", uploadId).bind("p", partNumber).bind("bytes", (long) partBytes.length)
                    .bind("syms", parsed.symbols().size()).bind("rels", parsed.relationships().size())
                    .execute();
            handle.createUpdate("UPDATE scip_upload_sessions SET updated_at = NOW() WHERE id = :id")
                    .bind("id", uploadId).execute();
        });

        return new ScipUploadResult(String.valueOf(session.repoId()), session.targetSha(),
                parsed.symbols().size(), parsed.relationships().size(), index.getDocumentsCount());
    }

    /** Atomically promote staged rows to the real SHA. Idempotent if already completed. */
    public ScipUploadResult complete(Repository repo, String uploadId) {
        ScipUploadSession session = sessionDao.find(uploadId)
                .orElseThrow(() -> new ScipUploadException("Invalid SCIP upload session: " + uploadId));

        if ("completed".equals(session.status())) {
            int syms = liveCount(repo.id(), session.targetSha(), "scip_symbols");
            int rels = liveCount(repo.id(), session.targetSha(), "scip_relationships");
            return new ScipUploadResult(repo.name(), session.targetSha(), syms, rels, 0);
        }

        return jdbi.inTransaction(handle -> {
            // Serialize concurrent completes for this session.
            handle.createQuery("SELECT id FROM scip_upload_sessions WHERE id = :id FOR UPDATE")
                    .bind("id", uploadId).mapTo(String.class).one();

            // File-overlap guard (mirrors single-shot 422): at least one staged document
            // path must match an indexed file.
            boolean overlap = handle.createQuery("""
                    SELECT EXISTS (
                        SELECT 1 FROM scip_symbols ss
                        JOIN files f ON f.repo_id = ss.repo_id AND f.path = ss.file_path
                        WHERE ss.repo_id = :repoId AND ss.upload_sha = :stagingSha
                    )
                    """)
                    .bind("repoId", repo.id()).bind("stagingSha", session.stagingSha())
                    .mapTo(Boolean.class).one();
            if (!overlap) {
                throw new ScipUploadException(
                        "No SCIP document paths match indexed files for repo '" + repo.name()
                        + "'. Ensure the SCIP data was produced from the correct repository.");
            }

            // Delete old live rows for the target SHA, then promote staging → target.
            ScipWriter.deleteForSha(handle, repo.id(), session.targetSha());
            handle.createUpdate("UPDATE scip_symbols SET upload_sha = :target WHERE repo_id = :r AND upload_sha = :staging")
                    .bind("target", session.targetSha()).bind("r", repo.id()).bind("staging", session.stagingSha())
                    .execute();
            handle.createUpdate("UPDATE scip_relationships SET upload_sha = :target WHERE repo_id = :r AND upload_sha = :staging")
                    .bind("target", session.targetSha()).bind("r", repo.id()).bind("staging", session.stagingSha())
                    .execute();

            handle.createUpdate("UPDATE repositories SET scip_sha = :sha, scip_uploaded_at = NOW() WHERE id = :id")
                    .bind("sha", session.targetSha()).bind("id", repo.id()).execute();
            handle.createUpdate("UPDATE scip_upload_sessions SET status = 'completed', updated_at = NOW() WHERE id = :id")
                    .bind("id", uploadId).execute();

            int syms = handle.createQuery("SELECT count(*) FROM scip_symbols WHERE repo_id = :r AND upload_sha = :sha")
                    .bind("r", repo.id()).bind("sha", session.targetSha()).mapTo(Integer.class).one();
            int rels = handle.createQuery("SELECT count(*) FROM scip_relationships WHERE repo_id = :r AND upload_sha = :sha")
                    .bind("r", repo.id()).bind("sha", session.targetSha()).mapTo(Integer.class).one();

            log.info("SCIP upload session {} completed for {} (sha {}): {} symbols, {} relationships",
                    uploadId, repo.name(), session.targetSha(), syms, rels);
            return new ScipUploadResult(repo.name(), session.targetSha(), syms, rels, 0);
        });
    }

    /** Abort an in-flight session, discarding staged rows. */
    public void abort(String uploadId) {
        sessionDao.find(uploadId).ifPresent(sessionDao::deleteSessionAndStaging);
    }

    private int liveCount(int repoId, String sha, String table) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT count(*) FROM " + table + " WHERE repo_id = :r AND upload_sha = :sha")
                .bind("r", repoId).bind("sha", sha).mapTo(Integer.class).one());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew integrationTest --tests com.indexer.scip.ScipSessionServiceTest`
Expected: PASS — all five tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/scip/ScipSessionService.java src/test/java/com/indexer/scip/ScipSessionServiceTest.java
git commit -m "feat(scip): ScipSessionService init/part/complete/abort with atomic promote"
```

---

## Task 7: Session HTTP routes on `ScipApi`

**Files:**
- Modify: `src/main/java/com/indexer/scip/ScipApi.java` (constructor + `registerRoutes` + 4 handlers)
- Modify: `src/main/java/com/indexer/Application.java:155-157`

- [ ] **Step 1: Add `ScipSessionService` to the `ScipApi` constructor**

In `src/main/java/com/indexer/scip/ScipApi.java`, change the field block + constructor (lines 18-26) to:

```java
    private final ApiKeyAuthenticator authenticator;
    private final ScipService scipService;
    private final ScipSessionService sessionService;
    private final AuditSink auditSink;

    public ScipApi(ApiKeyAuthenticator authenticator, ScipService scipService,
                   ScipSessionService sessionService, AuditSink auditSink) {
        this.authenticator = authenticator;
        this.scipService = scipService;
        this.sessionService = sessionService;
        this.auditSink = auditSink;
    }
```

- [ ] **Step 2: Register the session routes**

Replace `registerRoutes` (lines 28-30) with:

```java
    public void registerRoutes(RoutesConfig routes) {
        routes.post("/api/scip/{repoName}", this::handleUpload);
        routes.post("/api/scip/{repoName}/uploads", this::handleInit);
        routes.post("/api/scip/{repoName}/uploads/{uploadId}/parts/{partNumber}", this::handlePart);
        routes.post("/api/scip/{repoName}/uploads/{uploadId}/complete", this::handleComplete);
        routes.delete("/api/scip/{repoName}/uploads/{uploadId}", this::handleAbort);
    }
```

- [ ] **Step 3: Add the four handlers**

Insert these methods into `ScipApi` after `handleUpload` (before `authenticate`, around line 88). They reuse the existing `authenticate` / permission / repo-existence pattern and the same `ScipUploadException` → status mapping:

```java
    private void handleInit(Context ctx) {
        String repoName = ctx.pathParam("repoName");
        CallerIdentity caller = authorizeUpload(ctx, repoName);
        if (caller == null) return;
        String gitSha = ctx.header("X-Git-SHA");
        if (gitSha == null || gitSha.isBlank()) {
            ctx.status(400).json(Map.of("error", "X-Git-SHA header is required"));
            return;
        }
        var optRepo = scipService.findRepo(repoName);
        if (optRepo.isEmpty()) {
            ctx.status(404).json(Map.of("error", "Repository '" + repoName + "' not found"));
            return;
        }
        Integer expectedParts = parseIntHeader(ctx.header("X-Scip-Parts"));
        var session = sessionService.init(optRepo.get(), gitSha, expectedParts);
        auditBestEffort(caller, repoName, true, "success", "session init " + session.id());
        ctx.status(201).json(Map.of("uploadId", session.id(), "stagingKey", session.stagingSha()));
    }

    private void handlePart(Context ctx) {
        String repoName = ctx.pathParam("repoName");
        CallerIdentity caller = authorizeUpload(ctx, repoName);
        if (caller == null) return;
        String uploadId = ctx.pathParam("uploadId");
        int partNumber;
        try {
            partNumber = Integer.parseInt(ctx.pathParam("partNumber"));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "Invalid part number"));
            return;
        }
        try {
            var result = sessionService.part(uploadId, partNumber, ctx.bodyAsBytes());
            ctx.json(Map.of("part", partNumber, "symbols", result.symbols(), "relationships", result.relationships()));
        } catch (ScipUploadException e) {
            ctx.status(statusFor(e)).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("SCIP part upload failed for {} session {}: {}", repoName, uploadId, e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error processing SCIP part"));
        }
    }

    private void handleComplete(Context ctx) {
        String repoName = ctx.pathParam("repoName");
        CallerIdentity caller = authorizeUpload(ctx, repoName);
        if (caller == null) return;
        String uploadId = ctx.pathParam("uploadId");
        var optRepo = scipService.findRepo(repoName);
        if (optRepo.isEmpty()) {
            ctx.status(404).json(Map.of("error", "Repository '" + repoName + "' not found"));
            return;
        }
        try {
            var result = sessionService.complete(optRepo.get(), uploadId);
            auditBestEffort(caller, repoName, true, "success", "session complete " + uploadId);
            ctx.json(Map.of("repo", result.repo(), "sha", result.sha(),
                    "symbols", result.symbols(), "relationships", result.relationships()));
        } catch (ScipUploadException e) {
            auditBestEffort(caller, repoName, true, "error", e.getMessage());
            ctx.status(statusFor(e)).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("SCIP complete failed for {} session {}: {}", repoName, uploadId, e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error completing SCIP upload"));
        }
    }

    private void handleAbort(Context ctx) {
        String repoName = ctx.pathParam("repoName");
        CallerIdentity caller = authorizeUpload(ctx, repoName);
        if (caller == null) return;
        sessionService.abort(ctx.pathParam("uploadId"));
        ctx.status(204);
    }

    /** Authenticate + scipUpload permission, shared by all session handlers. Returns null if a response was sent. */
    private CallerIdentity authorizeUpload(Context ctx, String repoName) {
        CallerIdentity caller = authenticate(ctx);
        if (caller == null) return null;
        if (!caller.scipUpload()) {
            auditBestEffort(caller, repoName, false, "denied", "Missing scipUpload permission");
            ctx.status(403).json(Map.of("error", "API key does not have scipUpload permission"));
            return null;
        }
        return caller;
    }

    private static int statusFor(ScipUploadException e) {
        return switch (e.getMessage()) {
            case String msg when msg.startsWith("Upload exceeds") -> 413;
            case String msg when msg.startsWith("Invalid SCIP") -> 400;
            case String msg when msg.startsWith("No SCIP document") -> 422;
            case String msg when msg.startsWith("Invalid SCIP upload session") -> 404;
            case String msg when msg.startsWith("Invalid SCIP session state") -> 409;
            default -> 400;
        };
    }

    private static Integer parseIntHeader(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return null; }
    }
```

- [ ] **Step 4: Wire `ScipSessionService` in `Application`**

In `src/main/java/com/indexer/Application.java`, replace lines 155-157 with:

```java
            // 5f. Set up SCIP upload endpoint (single-shot + multi-part sessions)
            var scipService = new com.indexer.scip.ScipService(repositoryDao, fileDao, jdbi);
            var scipSessionService = new com.indexer.scip.ScipSessionService(
                    repositoryDao, fileDao, new com.indexer.scip.ScipSessionDao(jdbi), jdbi);
            var scipApi = new com.indexer.scip.ScipApi(authenticator, scipService, scipSessionService, auditSink);
```

- [ ] **Step 5: Compile + run the full SCIP test set**

Run: `./gradlew compileJava && ./gradlew test integrationTest --tests "com.indexer.scip.*"`
Expected: PASS — everything compiles and all SCIP unit + integration tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/indexer/scip/ScipApi.java src/main/java/com/indexer/Application.java
git commit -m "feat(scip): multi-part upload session HTTP routes"
```

---

## Task 8: Prune safety, session reaper, and config

**Files:**
- Modify: `src/main/java/com/indexer/scip/ScipDao.java:40-57` (exclude staging keys)
- Modify: `src/main/java/com/indexer/config/IndexerConfig.java:109-113` (add `uploadSessionTtlHours`)
- Create: `src/main/java/com/indexer/scip/ScipSessionReaperTask.java`
- Modify: `src/main/java/com/indexer/Application.java:273-279` (schedule reaper)
- Test: `src/test/java/com/indexer/scip/ScipSessionReaperTaskTest.java`

- [ ] **Step 1: Exclude staging keys from prune**

In `src/main/java/com/indexer/scip/ScipDao.java`, in the prunable-SHA query (the `createQuery` starting at line 40), add a staging-key exclusion. Change the `WHERE repo_id = :repoId` line to include the extra predicate:

```java
            List<String> prunable = handle.createQuery("""
                    SELECT upload_sha
                    FROM scip_symbols
                    WHERE repo_id = :repoId
                      AND upload_sha NOT LIKE '\\_\\_staging\\_\\_:%'
                      AND upload_sha IS DISTINCT FROM
                          (SELECT last_indexed_sha FROM repositories WHERE id = :repoId)
                      AND NOT EXISTS (
                          SELECT 1 FROM branch_index bi
                          WHERE bi.repo_id = :repoId
                            AND bi.indexed_sha = scip_symbols.upload_sha
                      )
                    GROUP BY upload_sha
                    HAVING MAX(uploaded_at) < NOW() - CAST(:graceDays || ' days' AS INTERVAL)
                    """)
```

> The `\\_` escapes are required because `_` is a single-char wildcard in SQL `LIKE`; PostgreSQL's default escape char is backslash, and the Java text block needs `\\` to emit one backslash. This ensures only the literal `__staging__:` prefix is excluded.

- [ ] **Step 2: Add `uploadSessionTtlHours` to `ScipConfig`**

In `src/main/java/com/indexer/config/IndexerConfig.java`, replace the `ScipConfig` record (lines 109-113) with:

```java
    public record ScipConfig(int pruneGraceDays, int uploadSessionTtlHours) {
        public ScipConfig {
            if (pruneGraceDays <= 0) pruneGraceDays = 7;
            if (uploadSessionTtlHours <= 0) uploadSessionTtlHours = 24;
        }
    }
```

> If any code constructs `ScipConfig` directly (e.g. a default in a config-loader), update those call sites to pass `0` for the new field so the compact-constructor default (24) applies. Search: `grep -rn "new ScipConfig\|ScipConfig(" src/main`.

- [ ] **Step 3: Write the failing reaper test**

Create `src/test/java/com/indexer/scip/ScipSessionReaperTaskTest.java`:

```java
package com.indexer.scip;

import com.indexer.db.FileDao;
import com.indexer.db.RepositoryDao;
import com.indexer.model.Repository;
import com.sourcegraph.scip.Scip;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class ScipSessionReaperTaskTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private Jdbi jdbi;
    private ScipSessionService service;
    private ScipSessionDao sessionDao;
    private RepositoryDao repositoryDao;
    private int repoId;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var flyway = Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO repositories (name, url, branch, clone_path, auth_type, last_indexed_sha) "
                    + "VALUES ('r', 'git@x:r.git', 'main', '/tmp', 'ssh-key', 'AAA')");
            repoId = h.createQuery("SELECT id FROM repositories WHERE name='r'").mapTo(Integer.class).one();
            h.execute("INSERT INTO files (repo_id, branch, path, language) VALUES (?, 'main', 'src/A.java', 'java')", repoId);
        });
        repositoryDao = new RepositoryDao(jdbi);
        sessionDao = new ScipSessionDao(jdbi);
        service = new ScipSessionService(repositoryDao, new FileDao(jdbi), sessionDao, jdbi);
    }

    private byte[] index() {
        var occ = Scip.Occurrence.newBuilder().setSymbol("java . A#")
                .addAllRange(List.of(1, 0, 5, 0)).setSymbolRoles(Scip.SymbolRole.Definition_VALUE).build();
        var doc = Scip.Document.newBuilder().setRelativePath("src/A.java").addOccurrences(occ).build();
        return Scip.Index.newBuilder().addDocuments(doc).build().toByteArray();
    }

    private int stagingRows(String stagingSha) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT count(*) FROM scip_symbols WHERE upload_sha = ?")
                .bind(0, stagingSha).mapTo(Integer.class).one());
    }

    @Test
    void reaperDeletesAbandonedSessionAndStagingRows() {
        Repository repo = repositoryDao.findByName("r").orElseThrow();
        var session = service.init(repo, "SHA1", null);
        service.part(session.id(), 1, index());
        assertThat(stagingRows(session.stagingSha())).isEqualTo(1);

        // Age the session past the 24h TTL.
        jdbi.useHandle(h -> h.execute(
                "UPDATE scip_upload_sessions SET updated_at = NOW() - INTERVAL '48 hours' WHERE id = ?", session.id()));

        new ScipSessionReaperTask(sessionDao, 24).run();

        assertThat(sessionDao.find(session.id())).isEmpty();
        assertThat(stagingRows(session.stagingSha())).isZero();
    }

    @Test
    void reaperLeavesFreshSessionsAlone() {
        Repository repo = repositoryDao.findByName("r").orElseThrow();
        var session = service.init(repo, "SHA1", null);
        service.part(session.id(), 1, index());

        new ScipSessionReaperTask(sessionDao, 24).run();

        assertThat(sessionDao.find(session.id())).isPresent();
        assertThat(stagingRows(session.stagingSha())).isEqualTo(1);
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew integrationTest --tests com.indexer.scip.ScipSessionReaperTaskTest`
Expected: FAIL — `ScipSessionReaperTask` does not exist.

- [ ] **Step 5: Write `ScipSessionReaperTask`**

Create `src/main/java/com/indexer/scip/ScipSessionReaperTask.java`:

```java
package com.indexer.scip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodic task that deletes abandoned (open, stale) SCIP upload sessions and their staging rows.
 * Modelled after ScipPruneTask: implements Runnable, per-session try/catch so one failure does
 * not abort the rest.
 */
public class ScipSessionReaperTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ScipSessionReaperTask.class);

    private final ScipSessionDao sessionDao;
    private final int ttlHours;

    public ScipSessionReaperTask(ScipSessionDao sessionDao, int ttlHours) {
        this.sessionDao = sessionDao;
        this.ttlHours = ttlHours;
    }

    @Override
    public void run() {
        try {
            var stale = sessionDao.findOpenOlderThan(ttlHours);
            for (var session : stale) {
                try {
                    sessionDao.deleteSessionAndStaging(session);
                    log.info("Reaped abandoned SCIP upload session {} (repo_id={}, sha={})",
                            session.id(), session.repoId(), session.targetSha());
                } catch (Exception e) {
                    log.error("Failed to reap SCIP session {}: {}", session.id(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("SCIP session reaper failed: {}", e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 6: Schedule the reaper in `Application`**

In `src/main/java/com/indexer/Application.java`, immediately after the existing `scipPruneTask` scheduling block (after line 279), add:

```java
            // Schedule the SCIP upload-session reaper (GC abandoned multi-part uploads).
            var scipSessionReaper = new ScipSessionReaperTask(
                    new com.indexer.scip.ScipSessionDao(jdbi), config.scip().uploadSessionTtlHours());
            scheduler.scheduleAtFixedRate(scipSessionReaper,
                    config.branches().cleanupIntervalHours(),
                    config.branches().cleanupIntervalHours(), TimeUnit.HOURS);
```

Add the import if not already present (near line 12):

```java
import com.indexer.scip.ScipSessionReaperTask;
```

- [ ] **Step 7: Run the affected tests**

Run: `./gradlew integrationTest --tests "com.indexer.scip.*"`
Expected: PASS — reaper tests green, and `ScipServiceRetentionTest` / `ScipPruneTaskTest` still pass (prune change is backward-compatible: no real SHA matches the staging pattern).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/indexer/scip/ScipDao.java src/main/java/com/indexer/config/IndexerConfig.java src/main/java/com/indexer/scip/ScipSessionReaperTask.java src/main/java/com/indexer/Application.java src/test/java/com/indexer/scip/ScipSessionReaperTaskTest.java
git commit -m "feat(scip): prune excludes staging keys + abandoned-session reaper + TTL config"
```

---

## Task 9: `scip-upload.sh` — size detection + multipart flow

**Files:**
- Modify: `scripts/scip-upload.sh`

- [ ] **Step 1: Add new flags + defaults**

In `scripts/scip-upload.sh`, after the existing variable defaults (after line 26, `GENERATED_FILE=""`), add:

```bash
SPLITTER_JAR="${SCIP_SPLITTER_JAR:-}"      # path to the indexer jar/dist providing `scip-split`
MAX_PART_BYTES="${SCIP_MAX_PART_BYTES:-47185920}"  # 45 MiB — must stay under the 50 MB server cap
```

In the argument-parsing `case` (around lines 52-61), add two cases before the `-h|--help` line:

```bash
        --splitter-jar) SPLITTER_JAR="$2"; shift 2 ;;
        --max-part-bytes) MAX_PART_BYTES="$2"; shift 2 ;;
```

- [ ] **Step 2: Replace the single Upload block with size-aware upload**

In `scripts/scip-upload.sh`, replace the entire `# Upload` section (lines 152-182, from `echo "Uploading to ...` through the final `fi`) with:

```bash
# ------------------------------------------------------------------
# Helper: POST one request, echo "HTTP_CODE\nBODY"
# ------------------------------------------------------------------
post_file() {
    local url="$1" file="$2"
    local rf; rf=$(mktemp)
    local code
    code=$(curl -s -w "%{http_code}" -o "$rf" \
        -X POST "$url" \
        -H "Authorization: Bearer ${API_KEY}" \
        -H "X-Git-SHA: ${SHA}" \
        -H "Content-Type: application/x-protobuf" \
        --data-binary "@${file}" \
        --max-time 300)
    echo "$code"
    cat "$rf"
    rm -f "$rf"
}

# Extract a JSON string field via python3 (already a dependency below).
json_field() { python3 -c "import sys,json; print(json.load(sys.stdin).get('$1',''))"; }

FILE_BYTES=$(wc -c < "$SCIP_FILE" | tr -d ' ')

if [[ "$FILE_BYTES" -le "$MAX_PART_BYTES" ]]; then
    # ---- Single-shot path (unchanged behavior) ----
    echo "Uploading to $SERVER/api/scip/$REPO (sha: $SHA, ${FILE_BYTES} bytes)..."
    RESPONSE_FILE=$(mktemp)
    HTTP_CODE=$(curl -s -w "%{http_code}" -o "$RESPONSE_FILE" \
        -X POST "${SERVER}/api/scip/${REPO}" \
        -H "Authorization: Bearer ${API_KEY}" \
        -H "X-Git-SHA: ${SHA}" \
        -H "Content-Type: application/x-protobuf" \
        --data-binary "@${SCIP_FILE}" \
        --max-time 300)
    RESPONSE_BODY=$(cat "$RESPONSE_FILE"); rm -f "$RESPONSE_FILE"
    if [[ "$HTTP_CODE" == "200" ]]; then
        echo "Upload successful!"
        echo "$RESPONSE_BODY" | python3 -m json.tool 2>/dev/null || echo "$RESPONSE_BODY"
        exit 0
    fi
    echo "Upload failed (HTTP $HTTP_CODE)"
    echo "$RESPONSE_BODY" | python3 -m json.tool 2>/dev/null || echo "$RESPONSE_BODY"
    [[ "$FAIL_ON_ERROR" == "true" ]] && exit 1
    echo "Warning: SCIP upload failed but --fail-on-error is not set. Continuing."
    exit 0
fi

# ---- Multi-part path: file exceeds the part cap ----
echo "SCIP file is ${FILE_BYTES} bytes (> ${MAX_PART_BYTES}); splitting into parts..."

if [[ -z "$SPLITTER_JAR" ]]; then
    echo "Error: file exceeds --max-part-bytes and no --splitter-jar / SCIP_SPLITTER_JAR provided."
    echo "Provide the indexer jar so the file can be split (java -jar <jar> scip-split ...)."
    [[ "$FAIL_ON_ERROR" == "true" ]] && exit 1
    exit 0
fi

PARTS_DIR=$(mktemp -d)
trap 'rm -rf "$PARTS_DIR"' EXIT
if ! java -jar "$SPLITTER_JAR" scip-split "$SCIP_FILE" --max-bytes "$MAX_PART_BYTES" --out "$PARTS_DIR"; then
    echo "Error: scip-split failed"
    [[ "$FAIL_ON_ERROR" == "true" ]] && exit 1
    exit 0
fi

PART_FILES=( "$PARTS_DIR"/part-*.scip )
NUM_PARTS=${#PART_FILES[@]}
echo "Split into ${NUM_PARTS} parts. Initializing upload session..."

INIT_OUT=$(curl -s -X POST "${SERVER}/api/scip/${REPO}/uploads" \
    -H "Authorization: Bearer ${API_KEY}" \
    -H "X-Git-SHA: ${SHA}" \
    -H "X-Scip-Parts: ${NUM_PARTS}" --max-time 60)
UPLOAD_ID=$(echo "$INIT_OUT" | json_field uploadId)
if [[ -z "$UPLOAD_ID" ]]; then
    echo "Error: failed to initialize upload session: $INIT_OUT"
    [[ "$FAIL_ON_ERROR" == "true" ]] && exit 1
    exit 0
fi
echo "Session ${UPLOAD_ID} open. Uploading ${NUM_PARTS} parts..."

PART_NUM=0
for pf in "${PART_FILES[@]}"; do
    PART_NUM=$((PART_NUM + 1))
    OUT=$(post_file "${SERVER}/api/scip/${REPO}/uploads/${UPLOAD_ID}/parts/${PART_NUM}" "$pf")
    CODE=$(echo "$OUT" | head -n1)
    if [[ "$CODE" != "200" ]]; then
        echo "Error: part ${PART_NUM}/${NUM_PARTS} failed (HTTP ${CODE}): $(echo "$OUT" | tail -n +2)"
        curl -s -X DELETE "${SERVER}/api/scip/${REPO}/uploads/${UPLOAD_ID}" \
            -H "Authorization: Bearer ${API_KEY}" --max-time 60 >/dev/null || true
        [[ "$FAIL_ON_ERROR" == "true" ]] && exit 1
        exit 0
    fi
    echo "  part ${PART_NUM}/${NUM_PARTS} ok"
done

echo "Finalizing session ${UPLOAD_ID}..."
COMPLETE_FILE=$(mktemp)
COMPLETE_CODE=$(curl -s -w "%{http_code}" -o "$COMPLETE_FILE" \
    -X POST "${SERVER}/api/scip/${REPO}/uploads/${UPLOAD_ID}/complete" \
    -H "Authorization: Bearer ${API_KEY}" --max-time 120)
COMPLETE_BODY=$(cat "$COMPLETE_FILE"); rm -f "$COMPLETE_FILE"
if [[ "$COMPLETE_CODE" == "200" ]]; then
    echo "Multi-part upload successful!"
    echo "$COMPLETE_BODY" | python3 -m json.tool 2>/dev/null || echo "$COMPLETE_BODY"
    exit 0
fi
echo "Complete failed (HTTP $COMPLETE_CODE): $COMPLETE_BODY"
[[ "$FAIL_ON_ERROR" == "true" ]] && exit 1
echo "Warning: SCIP upload failed but --fail-on-error is not set. Continuing."
exit 0
```

> Note: the new `trap 'rm -rf "$PARTS_DIR"' EXIT` replaces the earlier `trap cleanup EXIT` only along the multipart path. To preserve the generated-file cleanup, change `cleanup()` (lines 41-46) to also remove `$PARTS_DIR` if set, OR combine both traps. Simplest: add `PARTS_DIR=""` to the top defaults and extend `cleanup()` to `rm -rf "$PARTS_DIR"` when non-empty, then keep the single `trap cleanup EXIT` and drop the inline trap above.

Apply that simplification: add `PARTS_DIR=""` near the top defaults, extend `cleanup()`:

```bash
cleanup() {
    if [[ -n "$GENERATED_FILE" && -f "$GENERATED_FILE" ]]; then
        rm -f "$GENERATED_FILE"
    fi
    if [[ -n "$PARTS_DIR" && -d "$PARTS_DIR" ]]; then
        rm -rf "$PARTS_DIR"
    fi
}
```

and remove the inline `trap 'rm -rf "$PARTS_DIR"' EXIT` line from the multipart block (the existing `trap cleanup EXIT` at line 46 now covers it).

- [ ] **Step 3: Syntax-check the script**

Run: `bash -n scripts/scip-upload.sh`
Expected: no output (valid syntax). If `shellcheck` is installed: `shellcheck scripts/scip-upload.sh` (warnings acceptable; no errors).

- [ ] **Step 4: Manual end-to-end smoke (optional, requires running server)**

Run (against a local server with the repo registered + an API key with `scipUpload`):
```bash
./gradlew installDist
SCIP_SPLITTER_JAR=$(ls build/install/*/lib/*.jar | head -1) \
./scripts/scip-upload.sh --server http://localhost:8080 --repo hazelcast \
  --scip-file /Users/csharpl/Projects/hazelcast/index.scip --api-key "$KEY" --fail-on-error
```
Expected: splits into ~11 parts, uploads each, completes, prints symbol/relationship counts.
> The jar passed as `--splitter-jar` must be the application jar whose `Main-Class` is `com.indexer.Application` (so `scip-split` dispatch works). `installDist` produces a launcher under `build/install/<name>/bin/`; you may pass that launcher instead by adapting the `java -jar` call to invoke the launcher with `scip-split`.

- [ ] **Step 5: Commit**

```bash
git add scripts/scip-upload.sh
git commit -m "feat(scip): scip-upload.sh splits oversize indexes and uploads via session API"
```

---

## Task 10: Documentation

**Files:**
- Modify: `CLAUDE.md` (SCIP Upload API section + CLI wrapper section)
- Modify: `docs/ci-pipeline-guide.md`

- [ ] **Step 1: Document the session API in `CLAUDE.md`**

In `CLAUDE.md`, in the **SCIP Upload API** section (after the `### Upload Endpoint` block describing `POST /api/scip/{repoName}`), add:

```markdown
### Large uploads — multi-part session API

SCIP indexes larger than the 50 MB request cap (e.g. a 484 MB index for a large monorepo)
are uploaded in parts. The CLI wrapper (`scripts/scip-upload.sh`) does this automatically; the
underlying endpoints are:

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/scip/{repo}/uploads` | Open a session (`X-Git-SHA`, optional `X-Scip-Parts`). Returns `{uploadId, stagingKey}`. |
| `POST` | `/api/scip/{repo}/uploads/{id}/parts/{n}` | Upload one valid sub-index part (≤50 MB). Idempotent per part number. |
| `POST` | `/api/scip/{repo}/uploads/{id}/complete` | Atomically promote staged data to the SHA. |
| `DELETE`| `/api/scip/{repo}/uploads/{id}` | Abort a session, discarding staged data. |

Parts are produced by splitting the index at SCIP `Document` boundaries — each part is itself a
fully-valid SCIP `Index` (protobuf concatenation semantics), so the server parses each part with the
same code path as a single-shot upload and never holds the whole index in memory. Staged parts are
invisible to queries until `complete` runs; an interrupted session leaves no visible data and is
garbage-collected after `scip.uploadSessionTtlHours` (default 24 h).

Splitting is done by the `scip-split` sub-command of the indexer jar:

    java -jar indexer.jar scip-split index.scip --max-bytes 47185920 --out parts/
```

Also add the new config key to the SCIP config block (the `scip:` YAML example):

```markdown
    scip:
      pruneGraceDays: 7         # SCIP uploads within this window are kept even if the ref is no longer live
      uploadSessionTtlHours: 24 # Abandoned multi-part upload sessions are reaped after this many hours
```

- [ ] **Step 2: Document the CLI flags in `CLAUDE.md`**

In the **SCIP CLI Wrapper** section, under Usage, add:

```markdown
For indexes larger than the 50 MB server cap, pass the indexer jar so the script can split:

    ./scripts/scip-upload.sh --server http://indexer:8080 --repo my-repo --api-key "$KEY" \
        --scip-file build/index.scip --splitter-jar /opt/indexer/indexer.jar

Flags: `--splitter-jar PATH` (or `SCIP_SPLITTER_JAR`) and `--max-part-bytes N`
(or `SCIP_MAX_PART_BYTES`, default 47185920 = 45 MiB). Files at or under the cap upload
single-shot exactly as before.
```

- [ ] **Step 3: Document the large-file path in `docs/ci-pipeline-guide.md`**

Add a short subsection to `docs/ci-pipeline-guide.md` explaining that CI should make the indexer jar available (release asset or container) and pass `--splitter-jar`, and that oversize indexes are split + uploaded via the session API automatically. (Match the surrounding doc's heading style; mirror the wording from Step 1-2.)

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md docs/ci-pipeline-guide.md
git commit -m "docs(scip): document multi-part upload session API and splitter CLI"
```

---

## Final Verification

- [ ] **Step 1: Full build + test sweep**

Run:
```bash
./gradlew build
./gradlew integrationTest
```
Expected: BUILD SUCCESSFUL; all unit + integration tests pass.

- [ ] **Step 2: Real-file end-to-end against hazelcast**

With a local server running and `hazelcast` registered, run the Task 9 Step 4 smoke command against `/Users/csharpl/Projects/hazelcast/index.scip` and confirm a successful multi-part completion with non-zero symbol/relationship counts. Then verify `get_index_health` reports the repo's SCIP as `fresh` for that SHA.

---

## Notes / Known Limitations (from spec)

- A single SCIP `Document` larger than `--max-bytes` cannot be split further; `scip-split` fails loudly directing the user to raise `--max-bytes`. Extraordinarily unlikely at the ~1 GB ceiling with 45 MB parts.
- The splitter assumes the standard SCIP layout where `metadata` precedes `documents` (true for `scip-java`/`scip-python`/`scip-typescript` output); metadata is replicated into every part.
- Design ceiling is ~1 GB. Multi-GB / unbounded indexes and heavy resumable-multipart infrastructure are explicitly out of scope and would be a future revision.
```
