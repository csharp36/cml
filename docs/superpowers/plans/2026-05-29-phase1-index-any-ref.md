# Phase 1 — Index any git ref (branch / tag / SHA) · Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make any git ref — remote branch, tag, or commit SHA — an addressable "build" that the indexer resolves, indexes (copy-on-write vs `main`), and records by ref kind, so the existing `branch` parameter on query tools transparently accepts tags and SHAs.

**Architecture:** Storage and the overlay query are already ref-agnostic (`files.branch` is free-form TEXT; the overlay does `branch IN (:ref,'main')`). The gaps are purely (1) **resolution** — `ensureBranchIndexed` only understands `origin/<branch>`, and (2) **metadata** — `branch_index` doesn't record whether a row is a branch/tag/SHA. We add a `GitOperations.resolveAnyRef` (branch → tag → SHA), thread a `RefKind` through the indexing path into a new `branch_index.ref_kind` column, and rewire `ensureBranchIndexed` to use the resolver. No new MCP tool (per the approved proposal: overload the existing `branch` param).

**Tech Stack:** Java 21, JDBI 3, PostgreSQL 16 + Flyway, JUnit 5 + AssertJ, Testcontainers (`@Tag("integration")`), git invoked via `ProcessBuilder` (no JGit).

**Scope:** Phase 1 of `docs/superpowers/specs/2026-05-29-tagged-release-full-indexing-design.md` (reference: `docs/proposals/2026-05-29-index-by-build-ref.md`). Out of scope (later phases): SCIP retention by SHA (Phase 3), tag pinning / TTL exemption (Phase 4), `diff_builds` tool, default-branch generalization (`diffFromMain` keeps its hardcoded `main`, consistent with the overlay).

---

## File Structure

- **Create** `src/main/java/com/indexer/repository/RefKind.java` — enum `{ BRANCH, TAG, SHA }` with `dbValue()` / `fromDb()`. One responsibility: the ref-kind vocabulary + its DB string form.
- **Modify** `src/main/java/com/indexer/repository/GitOperations.java` — add `resolveAnyRef` + nested `ResolvedRef` record + private `tryRevParse`; make `fetch` also fetch tags.
- **Create** `src/main/resources/db/migration/V5__branch_ref_kind.sql` — add `branch_index.ref_kind`.
- **Modify** `src/main/java/com/indexer/model/BranchIndex.java` — add `refKind` field.
- **Modify** `src/main/java/com/indexer/db/BranchIndexDao.java` — `upsert` takes `refKind`; `find`/`findExpired` read it.
- **Modify** `src/main/java/com/indexer/indexing/IndexingPipeline.java` — `branchIndex` takes a `RefKind` and records it.
- **Modify** `src/main/java/com/indexer/Application.java` — webhook feature-branch call passes `RefKind.BRANCH`.
- **Modify** `src/main/java/com/indexer/mcp/QueryExecutor.java` — `ensureBranchIndexed` resolves via `resolveAnyRef` and passes the resolved kind.
- **Modify** `CLAUDE.md` — document that the `branch` param accepts any ref.
- **Tests:** `GitOperationsTest` (resolveAnyRef), new `BranchIndexDaoTest` (ref_kind round-trip), `IndexingPipelineIntegrationTest` (branchIndex records kind + capstone tag fault-in), and update `CheckSyncToolTest` for the `upsert` signature.

---

### Task 1: `GitOperations.resolveAnyRef` + fetch tags

**Files:**
- Create: `src/main/java/com/indexer/repository/RefKind.java`
- Modify: `src/main/java/com/indexer/repository/GitOperations.java` (add `resolveAnyRef`, `ResolvedRef`, `tryRevParse`; modify `fetch` at lines 27-30)
- Test: `src/test/java/com/indexer/repository/GitOperationsTest.java`

> Context: `GitOperations` shells out to `git` via a private `runCommandOutput(List<String> cmd, Path workDir, GitCredentials creds)` that returns stdout and throws `IOException` on non-zero exit. `getShaForRef` (line 138) runs `git rev-parse <ref>`. `GitOperationsTest` builds real git repos in a `@TempDir` using a `run(Path dir, String... cmd)` helper (lines 104-114). These tests are plain unit tests (no Docker).

- [ ] **Step 1: Create the `RefKind` enum**

Create `src/main/java/com/indexer/repository/RefKind.java`:

```java
package com.indexer.repository;

import java.util.Locale;

/** The kind of git ref a {@code branch_index} row represents. */
public enum RefKind {
    BRANCH,
    TAG,
    SHA;

    /** Lowercase form stored in branch_index.ref_kind ("branch"/"tag"/"sha"). */
    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parse the DB string form back to a RefKind; defaults to BRANCH if unknown/null. */
    public static RefKind fromDb(String value) {
        if (value == null) return BRANCH;
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BRANCH;
        }
    }
}
```

- [ ] **Step 2: Write the failing tests in `GitOperationsTest`**

Add these tests (the `run(...)` helper and `gitOps`/`repoDir`/`secondSha` fields already exist):

```java
    @Test
    void resolveAnyRefResolvesTagAndSha() throws Exception {
        // A local tag on the existing repoDir (no remote needed for tag/sha paths).
        run(repoDir, "git", "tag", "v1.0");

        var tag = gitOps.resolveAnyRef(repoDir, "v1.0");
        assertThat(tag).isPresent();
        assertThat(tag.get().kind()).isEqualTo(RefKind.TAG);
        assertThat(tag.get().sha()).isEqualTo(secondSha); // tag points at HEAD commit

        var sha = gitOps.resolveAnyRef(repoDir, secondSha);
        assertThat(sha).isPresent();
        assertThat(sha.get().kind()).isEqualTo(RefKind.SHA);
        assertThat(sha.get().sha()).isEqualTo(secondSha);

        assertThat(gitOps.resolveAnyRef(repoDir, "does-not-exist")).isEmpty();
    }

    @Test
    void resolveAnyRefResolvesRemoteBranchAndFetchesTags() throws Exception {
        // origin repo with a commit on the default branch.
        Path origin = tempDir.resolve("origin");
        Files.createDirectories(origin);
        run(origin, "git", "init");
        run(origin, "git", "config", "user.email", "test@example.com");
        run(origin, "git", "config", "user.name", "Test User");
        run(origin, "git", "config", "commit.gpgsign", "false");
        Files.writeString(origin.resolve("R.java"), "public class R {}");
        run(origin, "git", "add", "R.java");
        run(origin, "git", "commit", "-m", "init");

        // Clone it; the clone now has refs/remotes/origin/<default>.
        Path clone = tempDir.resolve("clone");
        run(tempDir, "git", "clone", origin.toString(), clone.toString());
        String defaultBranch = gitOps.getCurrentSha(clone) != null
                ? new String(runDefaultBranch(clone)) : "main";

        // A remote branch resolves to BRANCH.
        var br = gitOps.resolveAnyRef(clone, defaultBranch);
        assertThat(br).isPresent();
        assertThat(br.get().kind()).isEqualTo(RefKind.BRANCH);

        // Create a tag on origin AFTER cloning; only a tag-fetching fetch will see it.
        run(origin, "git", "tag", "v2.0");
        gitOps.fetch(clone, null); // must fetch tags
        var tag = gitOps.resolveAnyRef(clone, "v2.0");
        assertThat(tag).as("fetch must bring remote tags into the clone").isPresent();
        assertThat(tag.get().kind()).isEqualTo(RefKind.TAG);
    }

    /** Returns the clone's current branch name (e.g. "main" or "master"). */
    private byte[] runDefaultBranch(Path dir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD");
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        p.waitFor();
        return new String(out).trim().getBytes();
    }
```

Add the import: `import com.indexer.repository.RefKind;` is unnecessary (same package), but add `import java.nio.file.Files;`/`import java.nio.file.Path;` are already present.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.indexer.repository.GitOperationsTest"`
Expected: the two new tests FAIL to compile/run because `resolveAnyRef`/`ResolvedRef` don't exist yet.

- [ ] **Step 4: Implement `fetch` (tags) + `resolveAnyRef` + `tryRevParse` + `ResolvedRef`**

In `GitOperations.java`, change `fetch` (lines 27-30) to also fetch tags:

```java
    public void fetch(Path repoDir, GitCredentials creds) throws IOException {
        List<String> cmd = List.of("git", "fetch", "--prune", "--tags");
        runCommand(cmd, repoDir, creds);
    }
```

Add these members (place near `getShaForRef`, ~line 138):

```java
    /** Resolved git ref: the commit SHA it points to, plus what kind of ref it was. */
    public record ResolvedRef(String sha, RefKind kind) {}

    /**
     * Resolve an arbitrary ref to a commit SHA, trying remote branch, then tag, then
     * raw commit-ish (full or abbreviated SHA), in that order. Returns empty if the ref
     * is not resolvable locally (callers degrade to main-only results).
     */
    public Optional<ResolvedRef> resolveAnyRef(Path repoDir, String ref) {
        Optional<String> sha = tryRevParse(repoDir, "refs/remotes/origin/" + ref);
        if (sha.isPresent()) return Optional.of(new ResolvedRef(sha.get(), RefKind.BRANCH));

        sha = tryRevParse(repoDir, "refs/tags/" + ref + "^{commit}"); // deref annotated tags
        if (sha.isPresent()) return Optional.of(new ResolvedRef(sha.get(), RefKind.TAG));

        sha = tryRevParse(repoDir, ref + "^{commit}");
        if (sha.isPresent()) return Optional.of(new ResolvedRef(sha.get(), RefKind.SHA));

        return Optional.empty();
    }

    /** {@code git rev-parse --verify --quiet <spec>}; empty when the spec doesn't resolve. */
    private Optional<String> tryRevParse(Path repoDir, String spec) {
        try {
            String out = runCommandOutput(List.of("git", "rev-parse", "--verify", "--quiet", spec), repoDir, null).trim();
            return out.isEmpty() ? Optional.empty() : Optional.of(out);
        } catch (IOException e) {
            return Optional.empty(); // --quiet exits non-zero when the spec is unknown
        }
    }
```

Add imports at the top of `GitOperations.java` if missing: `import java.util.Optional;`. (`java.util.List`, `java.nio.file.Path`, `java.io.IOException` are already imported.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.indexer.repository.GitOperationsTest"`
Expected: all `GitOperationsTest` tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/indexer/repository/RefKind.java \
        src/main/java/com/indexer/repository/GitOperations.java \
        src/test/java/com/indexer/repository/GitOperationsTest.java
git commit -m "feat: resolve any git ref (branch/tag/sha) + fetch tags"
```

---

### Task 2: Record ref kind through the indexing path

**Files:**
- Create: `src/main/resources/db/migration/V5__branch_ref_kind.sql`
- Modify: `src/main/java/com/indexer/model/BranchIndex.java`
- Modify: `src/main/java/com/indexer/db/BranchIndexDao.java`
- Modify: `src/main/java/com/indexer/indexing/IndexingPipeline.java` (`branchIndex`, line 96; upsert call line 116)
- Modify: `src/main/java/com/indexer/Application.java` (webhook call site ~line 247)
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java` (`ensureBranchIndexed` branchIndex call ~line 1650 — pass `RefKind.BRANCH`; resolution stays `origin/<branch>` until Task 3)
- Modify: `src/test/java/com/indexer/mcp/tools/CheckSyncToolTest.java:48` (upsert signature)
- Test: create `src/test/java/com/indexer/db/BranchIndexDaoTest.java`; modify `src/test/java/com/indexer/indexing/IndexingPipelineIntegrationTest.java`

> Context: `branch_index` (V2) has columns `id, repo_id, branch, base_sha, indexed_sha, indexed_at, last_accessed_at` and `UNIQUE(repo_id, branch)`. `BranchIndexDao` uses manual `(rs, ctx) -> new BranchIndex(...)` mappers. `IndexingPipeline.branchIndex` (lines 96-120) reads changed files via `git show <sha>:<path>` and calls `branchIndexDao.upsert(repoId, branch, mainSha, branchSha)`. Today `branchIndex` is only used for feature branches (kind = BRANCH).

- [ ] **Step 1: Write the failing DAO round-trip test**

Create `src/test/java/com/indexer/db/BranchIndexDaoTest.java`:

```java
package com.indexer.db;

import com.indexer.model.Repository;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class BranchIndexDaoTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private BranchIndexDao dao;
    private int repoId;

    @BeforeEach
    void setUp() {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        Jdbi jdbi = dbManager.getJdbi();
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM branch_index");
            h.execute("DELETE FROM repositories");
        });
        repoId = new RepositoryDao(jdbi).insert(
                new Repository(0, "repo", "url", "main", "/path", "ssh-key", "abc", Instant.now()));
        dao = new BranchIndexDao(jdbi);
    }

    @Test
    void upsertPersistsRefKind() {
        dao.upsert(repoId, "v1.0", "mainsha", "tagsha", "tag");

        var found = dao.find(repoId, "v1.0");
        assertThat(found).isPresent();
        assertThat(found.get().refKind()).isEqualTo("tag");
        assertThat(found.get().indexedSha()).isEqualTo("tagsha");
    }

    @Test
    void upsertUpdatesRefKindOnConflict() {
        dao.upsert(repoId, "x", "m1", "s1", "branch");
        dao.upsert(repoId, "x", "m2", "s2", "sha");

        assertThat(dao.find(repoId, "x").orElseThrow().refKind()).isEqualTo("sha");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew integrationTest --tests "com.indexer.db.BranchIndexDaoTest"`
Expected: FAIL — `upsert(...)` has no 5-arg form and `BranchIndex` has no `refKind()`.

- [ ] **Step 3: Add the migration**

Create `src/main/resources/db/migration/V5__branch_ref_kind.sql`:

```sql
-- Record the kind of git ref each branch_index row represents: a moving branch
-- HEAD, an immutable tag, or a bare commit SHA. Enables ref-aware resolution
-- (Phase 1) and future tag pinning / retention (Phase 4). Existing rows are
-- feature branches, so the default backfills them correctly.
ALTER TABLE branch_index
    ADD COLUMN ref_kind TEXT NOT NULL DEFAULT 'branch';
```

- [ ] **Step 4: Add `refKind` to the `BranchIndex` record**

In `src/main/java/com/indexer/model/BranchIndex.java`, add `refKind` as the final field:

```java
public record BranchIndex(int id, int repoId, String branch, String baseSha, String indexedSha,
                          Instant indexedAt, Instant lastAccessedAt, String refKind) {}
```

- [ ] **Step 5: Update `BranchIndexDao` (upsert + both mappers)**

In `BranchIndexDao.java`, change `upsert` to accept and persist `refKind`:

```java
    public void upsert(int repoId, String branch, String baseSha, String indexedSha, String refKind) {
        jdbi.useHandle(handle ->
                handle.createUpdate("""
                        INSERT INTO branch_index (repo_id, branch, base_sha, indexed_sha, ref_kind, indexed_at, last_accessed_at)
                        VALUES (:repoId, :branch, :baseSha, :indexedSha, :refKind, NOW(), NOW())
                        ON CONFLICT (repo_id, branch) DO UPDATE
                            SET base_sha = EXCLUDED.base_sha,
                                indexed_sha = EXCLUDED.indexed_sha,
                                ref_kind = EXCLUDED.ref_kind,
                                indexed_at = NOW(),
                                last_accessed_at = NOW()
                        """)
                        .bind("repoId", repoId)
                        .bind("branch", branch)
                        .bind("baseSha", baseSha)
                        .bind("indexedSha", indexedSha)
                        .bind("refKind", refKind)
                        .execute()
        );
    }
```

In BOTH `find` (lines 41-49) and `findExpired` (lines 70-78), extend the `new BranchIndex(...)` mapper with the new column as the final arg:

```java
                        .map((rs, ctx) -> new BranchIndex(
                                rs.getInt("id"),
                                rs.getInt("repo_id"),
                                rs.getString("branch"),
                                rs.getString("base_sha"),
                                rs.getString("indexed_sha"),
                                rs.getTimestamp("indexed_at").toInstant(),
                                rs.getTimestamp("last_accessed_at").toInstant(),
                                rs.getString("ref_kind")
                        ))
```

- [ ] **Step 6: Thread `RefKind` through `IndexingPipeline.branchIndex`**

In `IndexingPipeline.java`, change the `branchIndex` signature (line 96) to take a `RefKind`, and update the upsert (line 116):

```java
    public void branchIndex(int repoId, String branch, Path repoDir, String branchSha, RefKind refKind) throws IOException {
```
…and:
```java
        if (branchIndexDao != null) {
            branchIndexDao.upsert(repoId, branch, mainSha, branchSha, refKind.dbValue());
        }
```

Add the import: `import com.indexer.repository.RefKind;`.

- [ ] **Step 7: Update the two `branchIndex` call sites**

In `Application.java` (~line 247), the feature-branch webhook path is always a branch:
```java
                        indexingPipeline.branchIndex(repo.id(), branch, Path.of(repo.clonePath()), event.currentSha(), RefKind.BRANCH);
```
Add `import com.indexer.repository.RefKind;` to `Application.java`.

In `QueryExecutor.java` `ensureBranchIndexed` (~line 1650), keep the existing `origin/<branch>` resolution for now but pass the kind:
```java
            indexingPipeline.branchIndex(repoObj.id(), effectiveBranch, repoDir, branchSha, RefKind.BRANCH);
```
Add `import com.indexer.repository.RefKind;` to `QueryExecutor.java` if not already present.

- [ ] **Step 8: Fix the test caller**

In `CheckSyncToolTest.java:48`, add the ref-kind argument:
```java
        branchIndexDao.upsert(repo.id(), "feature/auth", "abc1234def5678901234567890abcdef12345678", "fff0000111222333444555666777888999aaabbb", "branch");
```

- [ ] **Step 9: Add the pipeline test that asserts ref kind is recorded**

In `IndexingPipelineIntegrationTest.java`, add a test that branch-indexes a tag and checks the recorded kind. Follow the file's existing setup for building a real git repo + Testcontainers DB and constructing the `IndexingPipeline` and DAOs (mirror its `fullIndex` test). Use a `BranchIndexDao` against the same `Jdbi`:

```java
    @Test
    void branchIndexRecordsRefKind() throws Exception {
        // Assumes the test's existing harness exposes: the repo dir (a real git repo with a
        // committed file on main), the repoId, the constructed `pipeline`, and `jdbi`.
        // Create a tag whose commit adds a file not on main.
        run(repoDir, "git", "checkout", "-b", "tagsrc");
        java.nio.file.Files.writeString(repoDir.resolve("Tagged.java"), "public class Tagged {}");
        run(repoDir, "git", "add", "Tagged.java");
        run(repoDir, "git", "commit", "-m", "tagged change");
        run(repoDir, "git", "tag", "v1.0");
        run(repoDir, "git", "checkout", "main");
        String tagSha = gitOps.getShaForRef(repoDir, "refs/tags/v1.0^{commit}");

        pipeline.branchIndex(repoId, "v1.0", repoDir, tagSha, RefKind.TAG);

        var dao = new BranchIndexDao(jdbi);
        var bi = dao.find(repoId, "v1.0");
        assertThat(bi).isPresent();
        assertThat(bi.get().refKind()).isEqualTo("tag");
        assertThat(bi.get().indexedSha()).isEqualTo(tagSha);
    }
```

If the existing test class does not already expose `repoDir`, `repoId`, `pipeline`, `gitOps`, `jdbi`, and a `run(...)` git helper, reuse/extract them from its `fullIndex` test setup rather than duplicating. Add `import com.indexer.repository.RefKind;` and `import com.indexer.db.BranchIndexDao;`.

- [ ] **Step 10: Run the tests to verify they pass**

Run:
```bash
./gradlew integrationTest --tests "com.indexer.db.BranchIndexDaoTest" \
        --tests "com.indexer.indexing.IndexingPipelineIntegrationTest" \
        --tests "com.indexer.mcp.tools.CheckSyncToolTest"
```
Expected: all PASS.

- [ ] **Step 11: Commit**

```bash
git add src/main/resources/db/migration/V5__branch_ref_kind.sql \
        src/main/java/com/indexer/model/BranchIndex.java \
        src/main/java/com/indexer/db/BranchIndexDao.java \
        src/main/java/com/indexer/indexing/IndexingPipeline.java \
        src/main/java/com/indexer/Application.java \
        src/main/java/com/indexer/mcp/QueryExecutor.java \
        src/test/java/com/indexer/db/BranchIndexDaoTest.java \
        src/test/java/com/indexer/indexing/IndexingPipelineIntegrationTest.java \
        src/test/java/com/indexer/mcp/tools/CheckSyncToolTest.java
git commit -m "feat: record ref kind (branch/tag/sha) in branch_index"
```

---

### Task 3: Generalize `ensureBranchIndexed` to fault in any ref

**Files:**
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java` (`ensureBranchIndexed`, ~lines 1621-1656)
- Test: `src/test/java/com/indexer/mcp/tools/RefFaultInIntegrationTest.java` (new)

> Context: `ensureBranchIndexed` is called by every branch-aware query before running. It currently fetches, checks `remoteBranchExists`, resolves `origin/<branch>`, and calls `branchIndex`. Tags/SHAs fail `remoteBranchExists` and silently fall back to main. This task swaps that for `resolveAnyRef`. The QueryExecutor's wired constructor is `QueryExecutor(Jdbi, BranchIndexDao, IndexingPipeline, RepositoryDao, GitOperations)` (line 44).

- [ ] **Step 1: Write the failing capstone test**

Create `src/test/java/com/indexer/mcp/tools/RefFaultInIntegrationTest.java`. It builds a real git repo, registers it (clonePath = the repo dir), wires a full `QueryExecutor`, then queries by a **tag** name and asserts the tag was faulted in (a tag-only symbol becomes visible and `branch_index` records `ref_kind='tag'`):

```java
package com.indexer.mcp.tools;

import com.indexer.db.*;
import com.indexer.indexing.FileIndexer;
import com.indexer.indexing.IndexingPipeline;
import com.indexer.mcp.QueryExecutor;
import com.indexer.model.Repository;
import com.indexer.repository.GitOperations;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class RefFaultInIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    @TempDir Path tempDir;

    private QueryExecutor queryExecutor;
    private BranchIndexDao branchIndexDao;
    private int repoId;
    private Path repoDir;

    @BeforeEach
    void setUp() throws Exception {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        Jdbi jdbi = dbManager.getJdbi();
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM symbols");
            h.execute("DELETE FROM file_contents");
            h.execute("DELETE FROM files");
            h.execute("DELETE FROM branch_index");
            h.execute("DELETE FROM repositories");
        });

        // Real git repo: main has App.java; tag v1.0 adds Tagged.java with class Tagged.
        repoDir = tempDir.resolve("repo");
        Files.createDirectories(repoDir);
        git("init"); git("config", "user.email", "t@e.com"); git("config", "user.name", "T");
        git("config", "commit.gpgsign", "false");
        Files.writeString(repoDir.resolve("App.java"), "public class App {}");
        git("add", "App.java"); git("commit", "-m", "main");
        Files.writeString(repoDir.resolve("Tagged.java"), "public class Tagged {}");
        git("add", "Tagged.java"); git("commit", "-m", "tagged");
        git("tag", "v1.0");
        git("reset", "--hard", "HEAD~1"); // working tree back on main; v1.0 retains Tagged.java

        var repoDao = new RepositoryDao(jdbi);
        repoId = repoDao.insert(new Repository(0, "repo", "url", "main", repoDir.toString(), "none", null, Instant.now()));

        var gitOps = new GitOperations();
        var fileDao = new FileDao(jdbi);
        var symbolDao = new SymbolDao(jdbi);
        var importDao = new ImportDao(jdbi);
        branchIndexDao = new BranchIndexDao(jdbi);
        var fileIndexer = new FileIndexer(fileDao, symbolDao, importDao, 1_048_576);
        var pipeline = new IndexingPipeline(repoDao, fileDao, fileIndexer, gitOps, branchIndexDao);

        // Index main first so the overlay has a baseline.
        pipeline.fullIndex(repoId, repoDir);

        queryExecutor = new QueryExecutor(jdbi, branchIndexDao, pipeline, repoDao, gitOps);
    }

    @Test
    void queryingATagFaultsItInAndRecordsTagKind() {
        // Tagged.java exists only at v1.0, not on main → only resolvable if the tag is indexed.
        @SuppressWarnings("unchecked")
        var results = (List<Map<String, Object>>) queryExecutor.searchSymbols(
                "Tagged", null, "repo", null, "v1.0", 20);

        assertThat(results).as("tag was faulted in and Tagged class is visible").hasSize(1);
        assertThat(results.get(0).get("name")).isEqualTo("Tagged");

        var bi = branchIndexDao.find(repoId, "v1.0");
        assertThat(bi).isPresent();
        assertThat(bi.get().refKind()).isEqualTo("tag");
    }

    private void git(String... args) throws Exception {
        var cmd = new java.util.ArrayList<String>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) throw new IllegalStateException("git " + List.of(args) + " failed");
    }
}
```

> The exact constructors of `IndexingPipeline`, `FileIndexer`, and the `searchSymbols(...)` parameter order must match the current code — confirm them against `IndexingPipelineIntegrationTest` (for the pipeline/indexer wiring) and `BranchQueryTest` (for `searchSymbols` argument order: `searchSymbols(name, kind, repo, pattern, branch, limit)`). Adjust the wiring lines to the real signatures if they differ; the test's intent (query a tag → it faults in → tag-only symbol visible, ref_kind='tag') is what matters.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew integrationTest --tests "com.indexer.mcp.tools.RefFaultInIntegrationTest"`
Expected: FAIL — with the current `origin/<branch>`-only logic, `remoteBranchExists("v1.0")` is false, so the tag is never indexed and `results` is empty.

- [ ] **Step 3: Rewrite `ensureBranchIndexed` to use `resolveAnyRef`**

Replace the fault-in body in `QueryExecutor.java` (`ensureBranchIndexed`, lines ~1636-1652) — keep the `"main"` skip and the null-DAO guard and the `existing`/`touchLastAccessed` early-return unchanged. Replace the `try { ... }` block:

```java
        log.info("Ref '{}' not indexed for repo '{}', triggering synchronous fault-in", effectiveBranch, repo);
        try {
            Path repoDir = Path.of(repoObj.clonePath());
            gitOps.fetch(repoDir, null); // also fetches tags (Task 1)

            var resolved = gitOps.resolveAnyRef(repoDir, effectiveBranch);
            if (resolved.isEmpty()) {
                log.debug("Ref '{}' not resolvable for repo '{}', falling back to main", effectiveBranch, repo);
                return;
            }
            indexingPipeline.branchIndex(repoObj.id(), effectiveBranch, repoDir,
                    resolved.get().sha(), resolved.get().kind());
            log.info("Fault-in complete for ref '{}' ({}) repo '{}'",
                    effectiveBranch, resolved.get().kind(), repo);
        } catch (Exception e) {
            log.warn("Fault-in failed for ref '{}' repo '{}': {}", effectiveBranch, repo, e.getMessage());
        }
```

This removes the `remoteBranchExists` check and the `getShaForRef("origin/" + ...)` call. `RefKind` is already imported (Task 2, Step 7); `var resolved` is `Optional<GitOperations.ResolvedRef>`.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew integrationTest --tests "com.indexer.mcp.tools.RefFaultInIntegrationTest"`
Expected: PASS — the tag is faulted in, `Tagged` is visible, `ref_kind='tag'`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/mcp/QueryExecutor.java \
        src/test/java/com/indexer/mcp/tools/RefFaultInIntegrationTest.java
git commit -m "feat: fault in any git ref (branch/tag/sha) on query"
```

---

### Task 4: Document that the `branch` param accepts any ref

**Files:**
- Modify: `CLAUDE.md` (the "Branch Support" section)

- [ ] **Step 1: Update the Branch Support docs**

In `CLAUDE.md`, under "## Branch Support", add a subsection after the bullet list describing the copy-on-write model:

```markdown
### Any ref: branches, tags, and commit SHAs

The `branch` parameter on every branch-aware tool accepts **any git ref** — a
remote branch name, a tag (e.g. `v2.3.1`), or a commit SHA. On first query the
indexer resolves it (remote branch → tag → raw commit) and faults it in as a
copy-on-write overlay vs `main`, exactly like a feature branch. The ref kind
(branch / tag / SHA) is recorded in `branch_index.ref_kind`. This powers the
"debug a production release by tag or SHA, with no local checkout" workflow:
pass the release tag as `branch` to any query or to `diff_branches`.

Tags and SHAs are immutable, so once indexed they never need re-indexing.
(Retention/pinning of tag indexes is handled separately — see the tagged-release
design.)
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: branch param accepts any git ref (branch/tag/sha)"
```

---

## Self-Review

**Spec/proposal coverage:**
- "Generalize ref resolution (origin/<ref> → refs/tags/<ref> → raw SHA)" → Task 1 `resolveAnyRef`; wired into fault-in in Task 3.
- "fetch should fetch tags (`--tags`)" → Task 1 Step 4.
- "Record the ref kind in `branch_index`" → Task 2 (migration + record + DAO + pipeline).
- "Storage/overlay already ref-agnostic" → no overlay changes; verified by reusing existing `searchSymbols` overlay in the Task 3 test.
- "API surface — option (a): overload existing `branch` param, just docs" → Task 4 (no new tool).
- Immutability/retention (skip stale-check, pin tags) → explicitly deferred to Phase 4; the `ref_kind` column this phase adds is the hook Phase 4 will use.

**Placeholder scan:** No TBD/"add error handling"/"similar to". The only soft spots are the two tests that must match existing wiring (Task 2 Step 9, Task 3 Step 1) — both call out the exact reference files/signatures to confirm against, with concrete assertions provided.

**Type consistency:** `RefKind` (Task 1) → `dbValue()`/`fromDb()` used by DAO/pipeline (Task 2) and resolution (Task 3). `ResolvedRef(String sha, RefKind kind)` returned by `resolveAnyRef` and consumed in Task 3 (`.sha()`, `.kind()`). `branchIndex(..., RefKind)` defined in Task 2 Step 6 and called in Task 2 Step 7 + Task 3 Step 3. `upsert(..., String refKind)` defined Task 2 Step 5, called Task 2 Steps 6/8 and BranchIndexDaoTest. `BranchIndex.refKind()` added Task 2 Step 4, read in Tasks 2/3 tests.

**Atomicity:** Each task compiles and its tests pass on its own. Task 2 changes `upsert`/`branchIndex` signatures and updates *all* callers in the same commit (Application, ensureBranchIndexed-as-BRANCH, IndexingPipeline, CheckSyncToolTest), so nothing is left non-compiling; Task 3 only changes resolution logic inside `ensureBranchIndexed`.

## Out of scope (later phases)
- Phase 3: SCIP retention by `upload_sha` (type-resolved multi-build diff).
- Phase 4: tag lifecycle — pin tags / exempt from the 14-day TTL using `ref_kind`; CI tag-push trigger.
- `diff_builds` tool alias; `git fetch origin <sha>` for SHAs unreachable from any ref; default-branch generalization of `diffFromMain`.
