# Phase 0 — Fix `source_code: null` for non-`main` refs · Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `get_symbol_detail` return real source code for symbols on any ref (main, branch, tag) by reading from the already-ref-resolved `file_contents` row instead of the on-disk working tree.

**Architecture:** `QueryExecutor.getSymbolDetail` currently reads source via `readSourceLines(clonePath, …)`, which reads the clone's working tree — always checked out to `main` — so any branch/tag query gets `source_code: null`. The overlay CTE (`effective_files`) already resolves the correct `files` row per ref, and `file_contents(file_id → content)` is keyed 1:1 to `files` and is populated for branch/tag deltas by the indexer. The fix: fetch `content` for the overlay-resolved `file_id` and slice the symbol's `[start_line, end_line]` in Java. This removes the disk dependency entirely.

**Tech Stack:** Java 21, JDBI 3, PostgreSQL 16, JUnit 5 + AssertJ + Testcontainers (`@Tag("integration")`).

**Scope:** Phase 0 of `docs/superpowers/specs/2026-05-29-tagged-release-full-indexing-design.md`. Ships independently. Phases 1–4 are out of scope for this plan.

---

### Task 1: Read symbol source from the ref-resolved `file_contents` overlay

**Files:**
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java` (method `getSymbolDetail`, lines ~236-314; helper `readSourceLines`, lines ~1652-1669; imports, lines ~18-24)
- Test: `src/test/java/com/indexer/mcp/tools/BranchQueryTest.java`

> **Note on the test harness:** `BranchQueryTest` constructs `new QueryExecutor(jdbi)` (the single-arg constructor), so `branchIndexDao`/`indexingPipeline` are null and `ensureBranchIndexed` returns early — branch queries run directly against seeded rows with no fault-in. The seeded repo's `clone_path` is the bogus `"/path"`, so the **current** disk-based code returns `source_code: null` for every file. That is exactly the bug, and it makes the new source-returning tests go red before the fix.

- [ ] **Step 1: Add a `jdbi` field and a `file_contents` insert helper to the test class**

In `BranchQueryTest`, add a field next to the existing DAO fields (after line 33, `private SymbolDao symbolDao;`):

```java
    private Jdbi jdbi;
```

In `setUp()`, replace the local `var jdbi = dbManager.getJdbi();` (line 39) with an assignment to the field:

```java
        jdbi = dbManager.getJdbi();
```

Add this helper method to the class (e.g. just before the final closing brace):

```java
    /** Insert (or replace) the stored content for a file. The DB trigger fills search_vector. */
    private void insertContent(int fileId, String content) {
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO file_contents (file_id, content) VALUES (:fid, :c) " +
                                "ON CONFLICT (file_id) DO UPDATE SET content = EXCLUDED.content")
                .bind("fid", fileId)
                .bind("c", content)
                .execute());
    }
```

(`Jdbi` is already imported at line 8: `import org.jdbi.v3.core.Jdbi;`.)

- [ ] **Step 2: Write the failing tests**

Add these four tests to `BranchQueryTest` (self-contained — each seeds its own file/symbol/content, like the existing `diffBranches*` tests):

```java
    @SuppressWarnings("unchecked")
    @Test
    void getSymbolDetailReturnsSourceForMainFile() {
        int fileId = fileDao.upsert(new SourceFile(0, repoId, "main", "src/Detail.java", "java", 100, "abc", Instant.now()));
        symbolDao.insertSymbol(new Symbol(0, fileId, "Detail", "class", "public class Detail", 2, 4, null, "public", false));
        insertContent(fileId, "package x;\npublic class Detail {\n  int f;\n}\n");

        var detail = queryExecutor.getSymbolDetail("test-repo", "src/Detail.java", "Detail", null, "main");

        assertThat((String) detail.get("source_code")).isEqualTo("public class Detail {\n  int f;\n}");
    }

    @SuppressWarnings("unchecked")
    @Test
    void getSymbolDetailReturnsSourceForBranchOnlyFile() {
        // File exists ONLY on the feature branch — the on-disk working tree (main) has no such file,
        // so the old disk read returned null. This is the core bug.
        int fileId = fileDao.upsert(new SourceFile(0, repoId, "feature/detail", "src/Only.java", "java", 100, "def", Instant.now()));
        symbolDao.insertSymbol(new Symbol(0, fileId, "doThing", "method", "void doThing()", 1, 2, null, "public", false));
        insertContent(fileId, "void doThing() {\n  return;\n}\n");

        var detail = queryExecutor.getSymbolDetail("test-repo", "src/Only.java", "doThing", null, "feature/detail");

        assertThat((String) detail.get("source_code")).isEqualTo("void doThing() {\n  return;");
    }

    @SuppressWarnings("unchecked")
    @Test
    void getSymbolDetailReturnsNullSourceForMetadataOnlyFile() {
        // A file row + symbol but NO file_contents row (binary/oversized → content never stored).
        int fileId = fileDao.upsert(new SourceFile(0, repoId, "main", "bin/Blob.bin", "binary", 999999, "abc", Instant.now()));
        symbolDao.insertSymbol(new Symbol(0, fileId, "Blob", "class", "n/a", 1, 1, null, "public", false));
        // intentionally no insertContent(...)

        var detail = queryExecutor.getSymbolDetail("test-repo", "bin/Blob.bin", "Blob", null, "main");

        assertThat(detail.get("source_code")).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void getSymbolDetailSlicesCorrectLineRange() {
        int fileId = fileDao.upsert(new SourceFile(0, repoId, "main", "src/Slice.java", "java", 100, "abc", Instant.now()));
        symbolDao.insertSymbol(new Symbol(0, fileId, "mid", "method", "void mid()", 3, 5, null, "public", false));
        insertContent(fileId, "L1\nL2\nL3\nL4\nL5\nL6\n");

        var detail = queryExecutor.getSymbolDetail("test-repo", "src/Slice.java", "mid", null, "main");

        assertThat((String) detail.get("source_code")).isEqualTo("L3\nL4\nL5");
    }
```

- [ ] **Step 3: Run the new tests to verify they fail**

Run:
```bash
./gradlew integrationTest --tests "com.indexer.mcp.tools.BranchQueryTest"
```
Expected: `getSymbolDetailReturnsSourceForMainFile`, `getSymbolDetailReturnsSourceForBranchOnlyFile`, and `getSymbolDetailSlicesCorrectLineRange` **FAIL** (actual `source_code` is `null` because the disk read against the bogus `/path` clone returns null). `getSymbolDetailReturnsNullSourceForMetadataOnlyFile` may already pass (the buggy code also returns null) — it stands as a regression guard that the fix never fabricates content.

- [ ] **Step 4: Add the `sliceLines` helper**

In `QueryExecutor.java`, add this private helper next to where `readSourceLines` lives (it will replace `readSourceLines` in Step 5):

```java
    /**
     * Slice lines [startLine, endLine] (1-based, inclusive) out of stored file content.
     * Mirrors the old disk-based readSourceLines semantics but reads from file_contents
     * (ref-aware via the effective_files overlay) instead of the working tree.
     * Returns null when content is null (binary/oversized/metadata-only files).
     */
    private static String sliceLines(String content, int startLine, int endLine) {
        if (content == null) {
            return null;
        }
        // String.lines() splits on \n, \r, and \r\n and drops a trailing terminator,
        // matching Files.readAllLines — so symbol line numbers line up.
        List<String> lines = content.lines().toList();
        int from = Math.max(0, startLine - 1);
        int to = Math.min(lines.size(), endLine);
        if (from >= to) {
            return "";
        }
        return String.join("\n", lines.subList(from, to));
    }
```

(`List` is already imported. `content.lines()` and `Stream.toList()` are standard on Java 21.)

- [ ] **Step 5: Rewrite the source read in `getSymbolDetail` and delete `readSourceLines`**

(a) In `getSymbolDetail`, change the symbol SELECT to expose the overlay-resolved `file_id` and drop the now-unneeded `clone_path`. Replace lines ~242-244:

```java
                    SELECT s.id, s.name, s.kind, s.signature, s.start_line, s.end_line,
                           s.parent_id, s.visibility, s.is_static,
                           ef.path AS file_path, r.name AS repo_name, r.clone_path
```
with:
```java
                    SELECT s.id, s.name, s.kind, s.signature, s.start_line, s.end_line,
                           s.parent_id, s.visibility, s.is_static,
                           ef.id AS file_id,
                           ef.path AS file_path, r.name AS repo_name
```

(b) Replace the source-reading block (lines ~277-283):

```java
            int symbolId = ((Number) symbol.get("id")).intValue();
            int startLine = ((Number) symbol.get("start_line")).intValue();
            int endLine = ((Number) symbol.get("end_line")).intValue();
            String clonePath = (String) symbol.get("clone_path");

            // Read source lines from disk
            symbol.put("source_code", readSourceLines(clonePath, filePath, startLine, endLine));
```
with:
```java
            int symbolId = ((Number) symbol.get("id")).intValue();
            int startLine = ((Number) symbol.get("start_line")).intValue();
            int endLine = ((Number) symbol.get("end_line")).intValue();
            long fileId = ((Number) symbol.get("file_id")).longValue();

            // Read source from the overlay-resolved file_contents row (ref-aware),
            // not the on-disk working tree (which is always checked out to main).
            String content = handle.createQuery(
                            "SELECT content FROM file_contents WHERE file_id = :fileId")
                    .bind("fileId", fileId)
                    .mapTo(String.class)
                    .findOne()
                    .orElse(null);
            symbol.put("source_code", sliceLines(content, startLine, endLine));
            symbol.remove("file_id"); // internal id — not part of the response contract
```

(c) Delete the entire `readSourceLines` method (lines ~1652-1669):

```java
    private String readSourceLines(String clonePath, String filePath, int startLine, int endLine) {
        if (clonePath == null || clonePath.isBlank()) {
            return null;
        }
        try {
            Path fullPath = Path.of(clonePath).resolve(filePath);
            if (!Files.exists(fullPath)) {
                return null;
            }
            List<String> lines = Files.readAllLines(fullPath);
            int from = Math.max(0, startLine - 1);
            int to = Math.min(lines.size(), endLine);
            return lines.subList(from, to).stream().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            log.warn("Could not read source lines from {}/{}: {}", clonePath, filePath, e.getMessage());
            return null;
        }
    }
```

(d) Remove the three imports that `readSourceLines` was the sole user of. Delete these lines:

```java
import java.io.IOException;          // line ~18
import java.nio.file.Files;          // line ~19
import java.util.stream.Collectors;  // line ~24
```

Leave `import java.nio.file.Path;` — it is still used elsewhere (`ensureBranchIndexed`). `Collectors` is still referenced once at the fully-qualified `java.util.stream.Collectors.joining(...)` (≈ line 1509), which needs no import.

- [ ] **Step 6: Run the tests to verify they pass**

Run:
```bash
./gradlew integrationTest --tests "com.indexer.mcp.tools.BranchQueryTest"
```
Expected: all `BranchQueryTest` tests **PASS**, including the four new ones.

- [ ] **Step 7: Full build (compile + unit + integration)**

Run:
```bash
./gradlew build
```
Expected: BUILD SUCCESSFUL — confirms the removed imports left no compile error and no other caller referenced `readSourceLines`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/indexer/mcp/QueryExecutor.java \
        src/test/java/com/indexer/mcp/tools/BranchQueryTest.java
git commit -m "fix: get_symbol_detail returns source for branch/tag refs (Phase 0)

Read symbol source from the overlay-resolved file_contents row instead of
the on-disk working tree (always on main), so non-main refs no longer return
source_code: null. Removes the disk dependency; metadata-only files still
return null source."
```

---

## Self-Review

**Spec coverage (Phase 0 section of the design):**
- "Read source from `file_contents.content` sliced to `start_line..end_line` via the `effective_files` row" → Task 1 Steps 4-5.
- "Works uniformly for main, branches, tags" → tests for main (Step 2a) and branch-only (Step 2b); tag is the same code path (ref-agnostic overlay) and is exercised end-to-end once Phase 1 lands.
- "Removes the disk dependency entirely" → `readSourceLines` deleted, `Files`/`IOException` imports removed (Step 5c-d).
- "Metadata-only files correctly return null" → Step 2 `getSymbolDetailReturnsNullSourceForMetadataOnlyFile` + `sliceLines(null)` guard.
- Spec's four named TDD cases (branch-only source, main source, metadata-only null, correct line slice) → the four tests in Step 2.

**Placeholder scan:** No TBD/TODO/"add error handling"/"similar to" — every code and command step is concrete.

**Type consistency:** `sliceLines(String, int, int)` is defined (Step 4) and called (Step 5b) with matching arg types; `file_id` is added to the SELECT (5a), read as `long` and removed from the map (5b). `insertContent(int, String)` defined in Step 1 and called in Step 2. No dangling references.

## Out of scope (later phases of the spec)
- Phase 1 (index any ref: branch/tag/SHA resolution), Phase 2 (`search_code` overlay verification), Phase 3 (SCIP retention by SHA), Phase 4 (tag lifecycle/retention).
