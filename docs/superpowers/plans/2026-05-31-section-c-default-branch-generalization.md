# Section C — Default-Branch Generalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace every hardcoded literal `"main"` with each repo's configured **base branch** so that repos whose default branch is `develop`/`master`/etc. get correct overlay queries and branch diffs (today they return empty).

**Architecture:** A per-repo *base branch* = the repo's configured `branch` field (already in config + the `repositories` table), with a one-time `origin/HEAD` detection fallback only if the configured branch is empty. This value is threaded into `GitOperations.diffFromBase`, the `IndexingPipeline` branch-delta, the `Application` poller's base-vs-feature decision, and every `'main'` site in `QueryExecutor`. Because the base-branch name is server-controlled (config/DB, never user input), SQL sites inline it as a single-quote-escaped literal — mirroring the existing `searchBranches` quoting pattern — rather than adding a new bind to ~16 queries. This keeps the existing `:branch` bind contract unchanged and confines per-tool edits to (a) computing `baseBranch`, (b) flipping the `"main".equals(effectiveBranch)` sentinel to `effectiveBranch.equals(baseBranch)`.

**Tech Stack:** Java 21, Gradle (Kotlin DSL), PostgreSQL 16, JDBI 3, Testcontainers (`@Tag("integration")`), JUnit 5.

**Backward compatibility:** For a `main`-default repo, `baseBranch == "main"`, so every transformed site resolves to exactly today's behavior. Unit tests using `new QueryExecutor(jdbi)` (null DAOs) resolve `baseBranch` → `"main"` (the fallback), preserving their fixtures that store base files under `branch = 'main'`.

**No schema change.** No new migration (last is V8; this plan adds none).

---

## File Structure

| File | Responsibility | Change |
|------|----------------|--------|
| `src/main/java/com/indexer/repository/GitOperations.java` | Git CLI ops | Rename `diffFromMain` → `diffFromBase(repoDir, baseBranch, branchSha)`; add `detectDefaultBranch(repoDir)` (origin/HEAD) |
| `src/main/java/com/indexer/indexing/IndexingPipeline.java` | Indexing | `branchIndex` gains `baseBranch` param, passes it to `diffFromBase`; fix "from main" log text; overloads default via base |
| `src/main/java/com/indexer/Application.java` | Boot + poller | Generalize the poller base-vs-feature decision (lines 233–251) |
| `src/main/java/com/indexer/mcp/QueryExecutor.java` | MCP query tools | Add `baseBranch(repo)` resolver + cache; thread base branch into `effectiveFilesCte`, `resolveBranch`, `resolveRefSha`, `ensureBranchIndexed`, and all ~13 tool sites; replace every `'main'` literal/sentinel |
| `src/test/java/com/indexer/.../*` | Tests | New integration test for a `develop`-default repo (overlay + diff) and the `origin/HEAD` fallback; update any test asserting `diffFromMain` |

---

## Task 1: GitOperations — base-branch diff + origin/HEAD detection

**Files:**
- Modify: `src/main/java/com/indexer/repository/GitOperations.java:82-96` (rename `diffFromMain`)
- Modify: `src/main/java/com/indexer/repository/GitOperations.java` (add `detectDefaultBranch` near `resolveAnyRef`, ~line 167)
- Test: existing `GitOperations` integration tests if present (locate with `grep -rl "diffFromMain\|class GitOperationsTest" src/test`)

- [ ] **Step 1: Locate callers and existing tests of `diffFromMain`**

Run: `grep -rn "diffFromMain" src/`
Expected: production caller `IndexingPipeline.java:102` (handled in Task 2) plus any test references. Note them.

- [ ] **Step 2: Rename and generalize `diffFromMain` → `diffFromBase`**

Replace `GitOperations.java:82-96`:

```java
    /**
     * List files changed between the repo's base branch and a branch/ref SHA.
     * @param baseBranch the fully-indexed base branch (repo's configured branch)
     */
    public List<String> diffFromBase(Path repoDir, String baseBranch, String branchSha) throws IOException {
        List<String> cmd = List.of("git", "diff", "--name-only", baseBranch + "..." + branchSha);
        String output = runCommandOutput(cmd, repoDir, null);
        List<String> files = new ArrayList<>();
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                files.add(trimmed);
            }
        }
        return files;
    }
```

- [ ] **Step 3: Add `detectDefaultBranch` (origin/HEAD fallback)**

Insert after `resolveAnyRef` (after `GitOperations.java:167`):

```java
    /**
     * Detect the remote default branch via {@code git symbolic-ref refs/remotes/origin/HEAD},
     * returning the short branch name (e.g. "main", "develop"). Empty if origin/HEAD is not set
     * locally (e.g. a clone made without it). Used only as a fallback when a repo's configured
     * branch is blank.
     */
    public Optional<String> detectDefaultBranch(Path repoDir) {
        try {
            String out = runCommandOutput(
                    List.of("git", "symbolic-ref", "--short", "refs/remotes/origin/HEAD"), repoDir, null).trim();
            if (out.startsWith("origin/")) {
                out = out.substring("origin/".length());
            }
            return out.isBlank() ? Optional.empty() : Optional.of(out);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
```

- [ ] **Step 4: Build to confirm signature change compiles in isolation**

Run: `./gradlew compileJava`
Expected: FAIL — `IndexingPipeline.java:102` still calls `diffFromMain`. This confirms the only production caller; fixed in Task 2.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/repository/GitOperations.java
git commit -m "refactor(git): diffFromMain -> diffFromBase(baseBranch); add detectDefaultBranch (origin/HEAD)"
```

---

## Task 2: IndexingPipeline — diff the delta from the base branch

**Files:**
- Modify: `src/main/java/com/indexer/indexing/IndexingPipeline.java:97-121` (`branchIndex` gains `baseBranch`)
- Modify: `src/main/java/com/indexer/indexing/IndexingPipeline.java:35-37,57-59` (overloads — see step 3)

- [ ] **Step 1: Add `baseBranch` parameter to `branchIndex` and use it for the diff**

Replace `IndexingPipeline.java:97-121` (`branchIndex`):

```java
    /**
     * Index a feature/tag/sha ref by computing its delta from the repo's base branch.
     * Only changed files are indexed; unchanged files fall through to the base via overlay queries.
     */
    public void branchIndex(int repoId, String branch, Path repoDir, String branchSha,
                            RefKind refKind, String baseBranch) throws IOException {
        log.info("Branch indexing repo {} branch {} at SHA {} (base {})", repoId, branch, branchSha, baseBranch);

        String baseSha = gitOps.getCurrentSha(repoDir);

        List<String> changedFiles = gitOps.diffFromBase(repoDir, baseBranch, branchSha);
        log.info("Branch {} has {} files changed from base {}", branch, changedFiles.size(), baseBranch);

        for (String relativePath : changedFiles) {
            try {
                String content = gitOps.showFile(repoDir, branchSha, relativePath);
                fileIndexer.indexFileFromContent(repoId, branch, relativePath, content, branchSha);
            } catch (IOException e) {
                log.debug("Could not read {} from branch {} (may be deleted): {}", relativePath, branch, e.getMessage());
            } catch (Exception e) {
                log.error("Failed to index branch file {} in repo {}: {}", relativePath, repoId, e.getMessage(), e);
            }
        }

        if (branchIndexDao != null) {
            branchIndexDao.upsert(repoId, branch, baseSha, branchSha, refKind.dbValue());
        }

        log.info("Branch index complete for repo {} branch {}", repoId, branch);
    }
```

(Note: `mainSha` was only ever the checked-out base HEAD; renamed `baseSha` for clarity. Behavior identical.)

- [ ] **Step 2: Update the two convenience overloads' default branch**

The no-branch overloads at `:35-37` and `:57-59` default to `"main"`. They are used only by callers that have no branch context. Leave their literal `"main"` default in place ONLY if no caller passes a non-main base; verify first.

Run: `grep -rn "fullIndex(\|incrementalIndex(" src/ | grep -v "String branch"`
Expected: confirm callers. `Application.java:100` already calls `fullIndex(repo.id(), repo.branch(), ...)` (3-arg) and `:245` calls `incrementalIndex(repo.id(), branch, ...)` (5-arg) — both already pass the real branch, so the 2-arg/4-arg overloads are test/legacy only.

If the only callers of the defaulting overloads are tests that assume `main`, leave them unchanged (they keep working for main-default fixtures). If a non-test caller exists, change its call to pass `repo.branch()`. Record the decision in the commit message.

- [ ] **Step 3: Build to confirm compile error now moves to the two `branchIndex` callers**

Run: `./gradlew compileJava`
Expected: FAIL at `Application.java:250` and `QueryExecutor.java:1739` (both call `branchIndex` with the old 5-arg signature). Fixed in Tasks 3 and 5.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/indexer/indexing/IndexingPipeline.java
git commit -m "refactor(indexing): branchIndex diffs from repo base branch, not literal main"
```

---

## Task 3: Application poller — generalize the base-vs-feature decision

**Files:**
- Modify: `src/main/java/com/indexer/Application.java:233-251`

- [ ] **Step 1: Replace the poller's base-branch branch resolution and `branchIndex` call**

Replace `Application.java:233-251`:

```java
                    String branch = event.branch() != null ? event.branch() : repo.branch();
                    if (branch.equals(repo.branch())) {
                        // Base branch: fetch, fast-forward, incremental index
                        var repoDir = Path.of(repo.clonePath());
                        gitOps.fetch(repoDir, null);
                        try {
                            gitOps.fastForward(repoDir, repo.branch());
                        } catch (IOException e) {
                            log.warn("Fast-forward failed for {} (likely force-push), resetting to origin/{}: {}",
                                    repo.name(), repo.branch(), e.getMessage());
                            gitOps.resetToRemote(repoDir, repo.branch());
                        }
                        indexingPipeline.incrementalIndex(repo.id(), branch, repoDir,
                                event.previousSha(), event.currentSha());
                    } else {
                        // Feature branch / tag / sha: fetch, then delta-index from the base branch using the recorded kind.
                        gitOps.fetch(Path.of(repo.clonePath()), null);
                        indexingPipeline.branchIndex(repo.id(), branch, Path.of(repo.clonePath()),
                                event.currentSha(), RefKind.fromDb(event.refKind()), repo.branch());
                    }
```

Rationale: a null-branch event (generic `/webhook`) now defaults to the repo's base branch instead of `"main"`, so it routes to the base path for any default branch. The base-path test is `branch.equals(repo.branch())`.

- [ ] **Step 2: Commit (compile still red until Task 5; that's expected for the branch)**

```bash
git add src/main/java/com/indexer/Application.java
git commit -m "refactor(poller): base-vs-feature decision keys off repo.branch(), not literal main"
```

---

## Task 4: QueryExecutor — base-branch resolver + generalized helpers

**Files:**
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java` (fields near other private fields; helpers at `1653-1745`)

- [ ] **Step 1: Add the cached base-branch resolver**

Add a field alongside the other private fields (near the top of the class, after the `gitOps`/`repositoryDao` fields) and a helper method. Place the method just above `effectiveFilesCte` (before line 1653):

```java
    private final java.util.concurrent.ConcurrentHashMap<String, String> baseBranchCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The fully-indexed base branch for a repo: its configured {@code branch}, with a one-time
     * {@code origin/HEAD} detection fallback, defaulting to "main" if neither is available
     * (e.g. unit tests constructed with null DAOs). Cached — resolution is effectively static per repo.
     */
    private String baseBranch(String repo) {
        return baseBranchCache.computeIfAbsent(repo, r -> {
            if (repositoryDao != null) {
                var rec = repositoryDao.findByName(r);
                if (rec.isPresent()) {
                    String configured = rec.get().branch();
                    if (configured != null && !configured.isBlank()) {
                        return configured;
                    }
                    if (gitOps != null) {
                        var detected = gitOps.detectDefaultBranch(java.nio.file.Path.of(rec.get().clonePath()));
                        if (detected.isPresent()) {
                            return detected.get();
                        }
                    }
                }
            }
            return "main";
        });
    }

    /** Single-quote-escape a server-controlled identifier for safe inlining as a SQL string literal. */
    private static String sqlLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
```

- [ ] **Step 2: Generalize `effectiveFilesCte` to take the base branch**

Replace `QueryExecutor.java:1658-1681`:

```java
    private String effectiveFilesCte(String branch, String baseBranch) {
        String base = sqlLiteral(baseBranch);
        String effectiveBranch = (branch == null || branch.isBlank()) ? baseBranch : branch;
        if (effectiveBranch.equals(baseBranch)) {
            return """
                    WITH effective_files AS (
                        SELECT f.id, f.repo_id, f.path, f.language, f.size_bytes,
                               f.last_commit_sha, f.last_modified_at, f.branch
                        FROM files f
                        WHERE f.branch = """ + base + """
                    )
                    """;
        }
        return """
                WITH effective_files AS (
                    SELECT DISTINCT ON (f.repo_id, f.path)
                           f.id, f.repo_id, f.path, f.language, f.size_bytes,
                           f.last_commit_sha, f.last_modified_at, f.branch
                    FROM files f
                    WHERE f.branch IN (:branch, """ + base + """)
                    ORDER BY f.repo_id, f.path,
                             CASE WHEN f.branch = :branch THEN 0 ELSE 1 END
                )
                """;
    }
```

(The `:branch` bind contract is unchanged — callers still bind `:branch` only when `effectiveBranch != baseBranch`.)

- [ ] **Step 3: Generalize `resolveBranch` and `resolveRefSha`**

Replace `QueryExecutor.java:1686-1688`:

```java
    private String resolveBranch(String branch, String baseBranch) {
        return (branch == null || branch.isBlank()) ? baseBranch : branch;
    }
```

Replace `QueryExecutor.java:1694-1702`:

```java
    private String resolveRefSha(org.jdbi.v3.core.Handle handle, int repoId, String branch, String baseBranch) {
        String effective = resolveBranch(branch, baseBranch);
        if (effective.equals(baseBranch)) {
            return handle.createQuery("SELECT last_indexed_sha FROM repositories WHERE id = :id")
                    .bind("id", repoId).mapTo(String.class).findOne().orElse(null);
        }
        return handle.createQuery("SELECT indexed_sha FROM branch_index WHERE repo_id = :id AND branch = :b")
                .bind("id", repoId).bind("b", effective).mapTo(String.class).findOne().orElse(null);
    }
```

- [ ] **Step 4: Generalize `ensureBranchIndexed` (base early-return + `branchIndex` call)**

Replace `QueryExecutor.java:1710-1745`. Key changes: the early-return compares against the repo's base branch; the `branchIndex` call passes the base branch.

```java
    private void ensureBranchIndexed(String repo, String effectiveBranch) {
        if (branchIndexDao == null || indexingPipeline == null || repositoryDao == null || gitOps == null) return;

        var repoRecord = repositoryDao.findByName(repo);
        if (repoRecord.isEmpty()) return;
        var repoObj = repoRecord.get();

        String base = baseBranch(repo);
        if (effectiveBranch.equals(base)) return;   // base layer needs no fault-in

        var existing = branchIndexDao.find(repoObj.id(), effectiveBranch);
        if (existing.isPresent()) {
            branchIndexDao.touchLastAccessed(repoObj.id(), effectiveBranch);
            return;
        }

        log.info("Ref '{}' not indexed for repo '{}', triggering synchronous fault-in", effectiveBranch, repo);
        try {
            Path repoDir = Path.of(repoObj.clonePath());
            gitOps.fetch(repoDir, null); // also fetches tags

            var resolved = gitOps.resolveAnyRef(repoDir, effectiveBranch);
            if (resolved.isEmpty()) {
                log.debug("Ref '{}' not resolvable for repo '{}', falling back to base", effectiveBranch, repo);
                return;
            }
            var ref = resolved.get();
            indexingPipeline.branchIndex(repoObj.id(), effectiveBranch, repoDir, ref.sha(), ref.kind(), base);
            log.info("Fault-in complete for ref '{}' ({}) repo '{}'", effectiveBranch, ref.kind(), repo);
        } catch (Exception e) {
            log.warn("Fault-in failed for ref '{}' repo '{}': {}", effectiveBranch, repo, e.getMessage());
        }
    }
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/mcp/QueryExecutor.java
git commit -m "refactor(query): add base-branch resolver; generalize CTE/resolve/fault-in helpers"
```

---

## Task 5: QueryExecutor — thread base branch through all tool sites

Each tool method currently computes `effectiveBranch` defaulting to `"main"` and gates the overlay with `"main".equals(effectiveBranch)`. Apply this uniform transformation **per method**:

1. Near the top of the method (after `repo` is known), add: `String baseBranch = baseBranch(repo);`
2. Wherever `resolveBranch(branch)` is called → `resolveBranch(branch, baseBranch)`.
3. Wherever a bare ternary `(branch == null || branch.isBlank()) ? "main" : branch` builds `effectiveBranch` → replace `"main"` with `baseBranch`.
4. Replace `"main".equals(effectiveBranch)` → `effectiveBranch.equals(baseBranch)` and `!"main".equals(effectiveBranch)` → `!effectiveBranch.equals(baseBranch)`.
5. Wherever `effectiveFilesCte(effectiveBranch)` is called → `effectiveFilesCte(effectiveBranch, baseBranch)`.
6. Replace inline SQL literal `'main'` with the escaped base literal built in-method: add `String safeBase = sqlLiteral(baseBranch);` and substitute (`branch = 'main'` → `branch = " + safeBase + "`, `branch IN (:branch, 'main')` → `branch IN (:branch, " + safeBase + ")`).
7. Wherever `resolveRefSha(handle, repoId, branch)` is called → `resolveRefSha(handle, repoId, branch, baseBranch)`.

**Exact site list (verify line numbers with `grep -an '"main"\|'"'"'main'"'"'' src/main/java/com/indexer/mcp/QueryExecutor.java` before editing — they shift as edits land):**

| Line(s) | Current | Becomes |
|--------|---------|---------|
| 218, 279, 371, 417, 468, 514, 666, 731 | `if (!"main".equals(effectiveBranch)) {` | `if (!effectiveBranch.equals(baseBranch)) {` |
| 561, 1187 | `if ("main".equals(effectiveBranch)) {` | `if (effectiveBranch.equals(baseBranch)) {` |
| 564 | `... AND branch = 'main')` | `... AND branch = " + safeBase + ")` |
| 573 | `FROM files WHERE repo_id = :repoId AND branch = 'main' AND language IS NOT NULL` | replace `'main'` with `" + safeBase + "` |
| 584 | `FROM files WHERE repo_id = :repoId AND branch = 'main' AND path LIKE '%/%'` | replace `'main'` with `" + safeBase + "` |
| 597, 612, 628 | `... branch IN (:branch, 'main') ...` | replace `'main'` with `" + safeBase + "` |
| 1407 | `... branch IN (:branchA, 'main')` | replace `'main'` with `" + safeBase + "` (add `String safeBase = sqlLiteral(baseBranch);` in `diffFiles`) |
| 1412 | `... branch IN (:branchB, 'main')` | replace `'main'` with `" + safeBase + "` |
| 1519 | `... f.branch IN (:branch, 'main')` | replace `'main'` with `" + safeBase + "` (add `safeBase` in `fetchEffectiveSymbols`) |
| 1583 | `allBranches.add("main");` | `allBranches.add(baseBranch);` |

**Threading base branch into private helpers that don't take `repo`:** `diffFiles`, `diffSymbols`, `fetchEffectiveSymbols` receive only `(handle, repoId, ...)`. Add a `String baseBranch` parameter to each and pass it from their caller (`diffBranches` / `searchBranches`), which has the `repo` name. For text-search inline SQL built with `StringBuilder` in `searchBranches` (line ~1591+), the base is already handled by `allBranches.add(baseBranch)` (line 1583), so no `'main'` literal remains there — verify with the grep after editing.

- [ ] **Step 1: List every remaining `main` site (authoritative pre-edit map)**

Run: `grep -an '"main"'"'"'\|'"'"'main'"'"'' src/main/java/com/indexer/mcp/QueryExecutor.java`
(If shell quoting is awkward, use: `grep -an "main" src/main/java/com/indexer/mcp/QueryExecutor.java | grep -E "\"main\"|'main'"`.)
Expected: the lines in the table above (minus any already changed in Task 4: 1659, 1660, 1666, 1676, 1687, 1696, 1711).

- [ ] **Step 2: Apply the transformation per tool method**

Work method-by-method (each is a self-contained edit): `getRepoSummary` (555–638), `searchSymbols`, `getSymbolDetail`, `findImplementations`, `findReferences`, `searchCode`, `searchFiles`, `getFileSummary`, `getDirectoryTree`, `getTypeHierarchy`/`getSymbolReferences` (the `resolveRefSha` callers near 1187/1696), `diffBranches`→`diffFiles`/`diffSymbols` (1402–1528), `searchBranches` (1552+). For each: add `String baseBranch = baseBranch(repo);` (and `String safeBase = sqlLiteral(baseBranch);` where inline SQL `'main'` exists), then apply rules 2–7.

- [ ] **Step 3: Verify no `main` literal remains**

Run: `grep -an "\"main\"\|'main'" src/main/java/com/indexer/mcp/QueryExecutor.java`
Expected: **no matches** (the docstring at old line 1656/1684/1692 mentioning "main" may be reworded; ensure no executable `"main"` / `'main'` remains).

- [ ] **Step 4: Full build**

Run: `./gradlew compileJava compileTestJava`
Expected: PASS — all `branchIndex`/`diffFromBase`/`resolveBranch`/`resolveRefSha`/`effectiveFilesCte` call sites now match new signatures.

- [ ] **Step 5: Run existing unit suite (no Docker) to confirm main-default parity**

Run: `./gradlew test`
Expected: PASS — `BranchQueryTest` and friends are unaffected because `baseBranch` resolves to `"main"` for their null-DAO `QueryExecutor`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/indexer/mcp/QueryExecutor.java
git commit -m "refactor(query): thread per-repo base branch through all tool sites; drop literal main"
```

---

## Task 6: Integration test — a `develop`-default repo

**Files:**
- Create: `src/test/java/com/indexer/integration/DefaultBranchGeneralizationTest.java`
- Reference patterns: existing Testcontainers integration tests (`grep -rln "@Tag(\"integration\")" src/test` and the `git init -b main` setup in branch-related tests).

- [ ] **Step 1: Write the failing integration test**

This test creates a repo whose default branch is `develop` (NOT `main`), indexes it as the base layer, adds a feature branch, and asserts that overlay queries and `diff_branches` return the develop base content. It also exercises the `origin/HEAD` fallback by leaving the configured branch blank in one case.

```java
package com.indexer.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class DefaultBranchGeneralizationTest {

    // Use the project's existing Testcontainers Postgres + temp-dir git harness.
    // Mirror the setup helpers used by the existing branch integration tests
    // (locate via: grep -rln "git init -b main" src/test).

    @Test
    void overlayAndDiffWorkForDevelopDefaultRepo() throws Exception {
        // 1. git init -b develop  in a temp dir; commit files on develop.
        //    Register the repo with configured branch = "develop".
        // 2. fullIndex(repoId, "develop", repoDir)  -> base files stored under branch='develop'.
        // 3. Create feature branch 'feature/x' off develop, change one file, commit.
        //    branchIndex(repoId, "feature/x", repoDir, featureSha, RefKind.BRANCH, "develop").
        // 4. QueryExecutor.getRepoSummary(repo, /*branch*/ null)  -> fileCount > 0
        //    (regression: with hardcoded 'main' this returned 0).
        assertTrue(repoSummaryFileCount(repo, null) > 0,
                "base-branch query must see develop-indexed files");

        // 5. search_code / search_symbols with branch=null resolve against develop base.
        assertFalse(searchSymbols(repo, "SomeSymbolOnDevelop", null).isEmpty());

        // 6. diff_branches(repo, "feature/x", "develop") reports the one changed file.
        var diff = diffBranches(repo, "feature/x", "develop", "files");
        assertEquals(1, changedCount(diff));
    }

    @Test
    void baseBranchFallsBackToOriginHead() throws Exception {
        // Register a repo with a BLANK configured branch; set origin/HEAD -> develop:
        //   git symbolic-ref refs/remotes/origin/HEAD refs/remotes/origin/develop
        // Assert GitOperations.detectDefaultBranch(repoDir) == Optional.of("develop")
        // and that baseBranch resolution (via a query) uses 'develop'.
        assertEquals(java.util.Optional.of("develop"), gitOps.detectDefaultBranch(repoDir));
    }
}
```

Fill the `// ...` setup using the exact harness the existing integration tests use (Testcontainers `JdbcDatabaseContainer`, Flyway migrate, temp git repo). Replace the pseudo-helper calls (`repoSummaryFileCount`, `searchSymbols`, etc.) with real `QueryExecutor` invocations constructed with the live DAOs (so `baseBranch` resolves from the `repositories` row).

- [ ] **Step 2: Run it to confirm it FAILS on the pre-refactor code path**

Run: `./gradlew integrationTest --tests "com.indexer.integration.DefaultBranchGeneralizationTest"`
Expected: the new assertions would have FAILED before Tasks 1–5 (fileCount 0). After Tasks 1–5 they PASS. (If authoring tests before the refactor, stash Tasks 1–5 to observe the red; otherwise document that the test encodes the regression.)

- [ ] **Step 3: Run it to confirm it PASSES on the refactored code**

Run: `./gradlew integrationTest --tests "com.indexer.integration.DefaultBranchGeneralizationTest"`
Expected: PASS.

- [ ] **Step 4: Update any test referencing `diffFromMain`**

Run: `grep -rn "diffFromMain" src/test`
For each hit, rename to `diffFromBase(repoDir, "main", sha)` (preserving main-default behavior).

- [ ] **Step 5: Full suite**

Run: `./gradlew build`
Expected: PASS (unit + integration; requires Docker for Testcontainers).

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/indexer/integration/DefaultBranchGeneralizationTest.java
git commit -m "test(query): default-branch generalization — develop-default overlay/diff + origin/HEAD fallback"
```

---

## Task 7: Docs reconciliation

**Files:**
- Modify: `CLAUDE.md` (Branch Support section), `README.md` if it repeats the "main branch" framing.

- [ ] **Step 1: Update branch-model wording from "main" to "configured base branch"**

In `CLAUDE.md` "Branch Support" and "Any ref" sections, change phrasing like "differ from main" / "merge branch-specific files with main" to "differ from the repo's **base branch** (its configured `branch`, e.g. `main`/`develop`)". Keep examples using `main` as the common case.

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "docs: branch model is keyed off each repo's configured base branch, not literal main"
```

---

## Self-Review (run before execution)

**Spec coverage (Section C, design doc lines 123–139, 157–159):**
- C.1 base-branch resolution (configured `branch` → `origin/HEAD` fallback) → Task 4 Step 1 (`baseBranch`) + Task 1 Step 3 (`detectDefaultBranch`). ✓
- C.2 `diffFromMain` → `diffFromBase(baseBranch)` → Task 1 Step 2 + Task 2. ✓
- C.2 `effective_files` overlay + "any other `'main'` literal in `QueryExecutor`" keyed off base → Task 4 + Task 5 (exhaustive site table). ✓
- C.2 resolution where repo record is loaded; fallback one-time not per-query → `baseBranchCache` (Task 4 Step 1). ✓
- Testing strategy: develop-default overlay+diff; configured-branch path AND origin/HEAD fallback both exercised → Task 6 (two tests). ✓

**Placeholder scan:** Task 6 test body intentionally references the existing integration harness (located via grep) rather than reinventing container setup — the executor must wire real `QueryExecutor`/DAO calls. This is the one spot requiring the engineer to read sibling tests; flagged explicitly, not a silent TODO.

**Type consistency:** `diffFromBase(Path, String baseBranch, String branchSha)`, `branchIndex(int, String, Path, String, RefKind, String baseBranch)`, `effectiveFilesCte(String branch, String baseBranch)`, `resolveBranch(String, String baseBranch)`, `resolveRefSha(Handle, int, String, String baseBranch)`, `baseBranch(String repo)`, `detectDefaultBranch(Path)` — names/arities consistent across Tasks 1–6.

**Out of scope (per spec):** no admin index-ref endpoint; `scip_status` stays repo-global; no query-count retention. None touched here.
