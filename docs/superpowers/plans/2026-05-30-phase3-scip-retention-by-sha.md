# Phase 3 — SCIP retention by `upload_sha` · Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retain SCIP data keyed by the upload's git SHA (instead of replacing all SCIP per repo on every upload), make `get_type_hierarchy` / `get_symbol_references` resolve a queried ref to its SHA and read that SHA's SCIP, add a prune policy, and have CI upload SCIP on tag pushes — so a tagged release keeps its type-resolution layer.

**Architecture:** SCIP rows gain real per-SHA identity (migration). Upload deletes only the matching SHA's rows, not the whole repo. A `resolveRefSha` helper maps a ref (main → `repositories.last_indexed_sha`; branch/tag → `branch_index.indexed_sha`) to a SHA; SCIP reads filter `WHERE upload_sha = :sha`. SCIP status becomes **existence-based** (does SCIP exist for the ref's SHA?) rather than comparing the single `repositories.scip_sha` column — necessary because tag uploads would otherwise corrupt main's fresh/stale signal. A periodic `ScipPruneTask` keeps SCIP for the current main SHA + every live `branch_index.indexed_sha` + anything within a grace window, pruning the rest.

**Tech Stack:** Java 21, JDBI 3, PostgreSQL 16 + Flyway, JUnit 5 + AssertJ, Testcontainers (`@Tag("integration")`), GitHub Actions.

**Scope:** Phase 3 of `docs/superpowers/specs/2026-05-29-tagged-release-full-indexing-design.md`. **Correction to the spec:** it assumed "no new schema" because both SCIP tables "already carry `upload_sha`" — but `scip_relationships` has **no** `upload_sha`, and `scip_symbols`' `UNIQUE (repo_id, scip_symbol)` permits only one SHA per symbol. A migration (V6) is required. Out of scope (Phase 4): tag-push *indexing* trigger, tag TTL pinning, retention-policy admin UI. `getSymbolDetail`/`findImplementations` keep their existing repo-global `scip_status` indicator (not made ref-aware here).

---

## File Structure

- **Create** `src/main/resources/db/migration/V6__scip_retention_by_sha.sql` — add `upload_sha` to `scip_relationships`; change `scip_symbols` uniqueness to `(repo_id, upload_sha, scip_symbol)`; backfill.
- **Modify** `src/main/java/com/indexer/scip/ScipService.java` — delete-by-(repo_id, upload_sha); symbol upsert on the new key; relationships carry `upload_sha`.
- **Modify** `src/main/java/com/indexer/mcp/QueryExecutor.java` — existence-based `getScipStatus` + `get_index_health` SCIP status; `resolveRefSha` helper; thread `upload_sha` through `resolveScipSymbol`/`traverseHierarchy`/`getSymbolReferences`; add `branch` param to `getTypeHierarchy`/`getSymbolReferences`.
- **Create** `src/main/java/com/indexer/scip/ScipDao.java` — prune query (+ small helpers as needed).
- **Create** `src/main/java/com/indexer/scip/ScipPruneTask.java` — periodic prune (modeled on `BranchCleanupTask`).
- **Modify** `src/main/java/com/indexer/Application.java` — schedule `ScipPruneTask`.
- **Modify** config (the `Config` record/loader + `config.yaml` docs) — `scip.pruneGraceDays` (default 7).
- **Modify** `src/main/java/com/indexer/mcp/McpServerBootstrap.java` — add `branch` to the two SCIP tool schemas + handlers.
- **Modify** `.github/workflows/scip-upload.yml` — trigger on tag pushes.
- **Modify** `CLAUDE.md` — SCIP retention + the `branch` param on the two tools.
- **Tests:** `ScipServiceRetentionTest` (new), `SemanticQueryTest` (status + ref-aware queries), `ScipPruneTaskTest` (new).

---

### Task 1: Migration V6 — per-SHA SCIP identity

**Files:**
- Create: `src/main/resources/db/migration/V6__scip_retention_by_sha.sql`
- Test: `src/test/java/com/indexer/scip/ScipSchemaMigrationTest.java` (new, small)

> Context (V4 schema): `scip_symbols(... upload_sha VARCHAR(64) NOT NULL, UNIQUE (repo_id, scip_symbol))`; `scip_relationships(repo_id, from_symbol, to_symbol, kind, file_path, line)` — **no `upload_sha`**. `repositories.scip_sha` holds the latest upload SHA. To retain multiple SHAs, relationships need `upload_sha` and the symbol uniqueness must include `upload_sha`.

- [ ] **Step 1: Write the failing schema test**

Create `src/test/java/com/indexer/scip/ScipSchemaMigrationTest.java`:

```java
package com.indexer.scip;

import com.indexer.db.DatabaseManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class ScipSchemaMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    @Test
    void scipRelationshipsHasUploadShaAndSymbolsAreUniquePerSha() {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        var jdbi = dbManager.getJdbi();

        // scip_relationships now has an upload_sha column.
        Integer relCols = jdbi.withHandle(h -> h.createQuery("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'scip_relationships' AND column_name = 'upload_sha'
                """).mapTo(Integer.class).one());
        assertThat(relCols).isEqualTo(1);

        // scip_symbols uniqueness now includes upload_sha (so two SHAs can hold the same scip_symbol).
        boolean uniqueIncludesSha = jdbi.withHandle(h -> h.createQuery("""
                SELECT count(*) > 0 FROM pg_indexes
                WHERE tablename = 'scip_symbols' AND indexdef ILIKE '%upload_sha%' AND indexdef ILIKE '%UNIQUE%'
                """).mapTo(Boolean.class).one());
        assertThat(uniqueIncludesSha).isTrue();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew integrationTest --tests "com.indexer.scip.ScipSchemaMigrationTest"`
Expected: FAIL (no `upload_sha` on relationships; unique index is on `(repo_id, scip_symbol)`).

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V6__scip_retention_by_sha.sql`:

```sql
-- SCIP retention by upload SHA. Today each upload replaces all SCIP for a repo, so
-- only the latest SHA has type resolution. To retain SCIP per tagged build:
--   1. relationships must be attributable to an upload (add upload_sha),
--   2. a symbol must be storable once PER upload, not once per repo.

-- 1. Add upload_sha to relationships; backfill existing rows from the repo's last upload.
ALTER TABLE scip_relationships ADD COLUMN upload_sha VARCHAR(64);
UPDATE scip_relationships sr
   SET upload_sha = r.scip_sha
  FROM repositories r
 WHERE sr.repo_id = r.id AND sr.upload_sha IS NULL;
-- Any rows with no known upload (repo never had scip_sha) get a sentinel so NOT NULL holds.
UPDATE scip_relationships SET upload_sha = 'unknown' WHERE upload_sha IS NULL;
ALTER TABLE scip_relationships ALTER COLUMN upload_sha SET NOT NULL;
CREATE INDEX idx_scip_rel_repo_sha ON scip_relationships (repo_id, upload_sha);

-- 2. Symbols: uniqueness becomes (repo_id, upload_sha, scip_symbol) so multiple SHAs coexist.
ALTER TABLE scip_symbols DROP CONSTRAINT scip_symbols_repo_id_scip_symbol_key;
ALTER TABLE scip_symbols ADD CONSTRAINT scip_symbols_repo_sha_symbol_key
    UNIQUE (repo_id, upload_sha, scip_symbol);
CREATE INDEX idx_scip_symbols_repo_sha ON scip_symbols (repo_id, upload_sha);
```

> Verify the exact existing constraint name. The default Postgres name for `UNIQUE (repo_id, scip_symbol)` is `scip_symbols_repo_id_scip_symbol_key`; confirm with `\d scip_symbols` semantics or by checking V4. If V4 named it explicitly, use that name in the `DROP CONSTRAINT`.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew integrationTest --tests "com.indexer.scip.ScipSchemaMigrationTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V6__scip_retention_by_sha.sql \
        src/test/java/com/indexer/scip/ScipSchemaMigrationTest.java
git commit -m "feat: V6 schema for per-SHA SCIP retention (relationships.upload_sha + symbol uniqueness)"
```

---

### Task 2: Upload retains by SHA (no full wipe)

**Files:**
- Modify: `src/main/java/com/indexer/scip/ScipService.java` (the upload transaction, ~lines 65-127)
- Test: `src/test/java/com/indexer/scip/ScipServiceRetentionTest.java` (new)

> Context: `ScipService` currently does `DELETE FROM scip_relationships WHERE repo_id=:repoId` + `DELETE FROM scip_symbols WHERE repo_id=:repoId` (wipe all), then inserts symbols `ON CONFLICT (repo_id, scip_symbol) DO UPDATE`, inserts relationships without `upload_sha`, and `UPDATE repositories SET scip_sha=:sha`. Read the current method to get the exact surrounding code and the `parseResult`/`gitSha`/`repo` variable names.

- [ ] **Step 1: Write failing retention tests**

Create `src/test/java/com/indexer/scip/ScipServiceRetentionTest.java`. It uploads two different SHAs and asserts BOTH remain, then re-uploads one SHA and asserts only that SHA's rows were replaced. Use the SCIP parsing path the existing `ScipService`/`ScipParser` expects — model the protobuf fixture on the existing `ScipParserTest` (read it for how it builds a SCIP `Index`/bytes). If building protobuf fixtures is heavy, call the lower-level persistence path `ScipService` uses after parsing (confirm whether `ScipService` exposes an ingest method that takes parsed symbols/relationships + sha; if only the byte-level `processUpload` exists, build a minimal protobuf via the same builders `ScipParserTest` uses).

Assertions (pseudocode to realize against the real API):
```java
// upload SCIP for sha "AAA" (1 symbol S, 1 relationship S->T)
// upload SCIP for sha "BBB" (1 symbol S, 1 relationship S->T)
// both SHAs present:
assertCount("SELECT count(*) FROM scip_symbols WHERE repo_id=? AND upload_sha='AAA'", 1);
assertCount("SELECT count(*) FROM scip_symbols WHERE repo_id=? AND upload_sha='BBB'", 1);
assertCount("SELECT count(*) FROM scip_relationships WHERE repo_id=? AND upload_sha='AAA'", 1);
assertCount("SELECT count(*) FROM scip_relationships WHERE repo_id=? AND upload_sha='BBB'", 1);
// re-upload "AAA" with a different symbol set (symbol U) → AAA replaced, BBB untouched:
assertCount("SELECT count(*) FROM scip_symbols WHERE repo_id=? AND upload_sha='AAA' AND scip_symbol like '%U%'", 1);
assertCount("SELECT count(*) FROM scip_symbols WHERE repo_id=? AND upload_sha='BBB'", 1);
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew integrationTest --tests "com.indexer.scip.ScipServiceRetentionTest"`
Expected: FAIL — the second upload wipes the first (DELETE by repo_id), so only one SHA survives.

- [ ] **Step 3: Change the upload to retain by SHA**

In `ScipService.java`, inside the upload transaction:

(a) Replace the two repo-wide DELETEs with SHA-scoped deletes (replace only this upload's prior data):
```java
            handle.createUpdate("DELETE FROM scip_relationships WHERE repo_id = :repoId AND upload_sha = :sha")
                    .bind("repoId", repo.id()).bind("sha", gitSha).execute();
            handle.createUpdate("DELETE FROM scip_symbols WHERE repo_id = :repoId AND upload_sha = :sha")
                    .bind("repoId", repo.id()).bind("sha", gitSha).execute();
```

(b) Change the symbol insert's conflict target to the new unique key:
```java
                ON CONFLICT (repo_id, upload_sha, scip_symbol) DO UPDATE SET
```
(the rest of the `DO UPDATE SET` list is unchanged).

(c) Add `upload_sha` to the relationships insert:
```java
        var relBatch = handle.prepareBatch("""
                INSERT INTO scip_relationships (repo_id, from_symbol, to_symbol, kind, file_path, line, upload_sha)
                VALUES (:repoId, :fromSymbol, :toSymbol, :kind, :filePath, :line, :uploadSha)
                """);
```
…and bind `.bind("uploadSha", gitSha)` on each relationship row (alongside the existing binds).

(d) Leave the `UPDATE repositories SET scip_sha=:sha, scip_uploaded_at=NOW()` as-is — it now means "most recent upload" (informational); status no longer depends on it (Task 3). Since deletes are SHA-scoped, re-uploading the same SHA is idempotent.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew integrationTest --tests "com.indexer.scip.ScipServiceRetentionTest"`
Expected: PASS — both SHAs retained; re-upload replaces only its own SHA.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/scip/ScipService.java \
        src/test/java/com/indexer/scip/ScipServiceRetentionTest.java
git commit -m "feat: SCIP upload retains data per upload_sha instead of replacing all"
```

---

### Task 3: Existence-based SCIP status (tag-safe)

**Files:**
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java` — `getScipStatus` (~774-791) and the `get_index_health` SCIP CASE (~725-740)
- Test: `src/test/java/com/indexer/mcp/SemanticQueryTest.java` (update the three status tests + add one)

> Why: with tag uploads, `repositories.scip_sha` = "latest upload" could be a tag's SHA, which would wrongly mark main `stale`. Compute status from whether SCIP rows EXIST for the relevant SHA instead.

- [ ] **Step 1: Update the status tests**

In `SemanticQueryTest`, the existing `getScipStatusFresh/Stale/Unavailable` tests seed `repositories.scip_sha`/`last_indexed_sha`. Change them to seed `scip_symbols` rows (with `upload_sha`) instead, matching the new existence-based contract:
- **fresh:** a `scip_symbols` row exists with `upload_sha = repositories.last_indexed_sha`.
- **stale:** `scip_symbols` rows exist for the repo, but none with `upload_sha = last_indexed_sha`.
- **unavailable:** no `scip_symbols` rows for the repo.

Read the current three tests and adapt their seeding accordingly (keep using the existing Testcontainers harness; you can still set `last_indexed_sha` via the repositories insert/update). Keep the assertions (`isEqualTo("fresh"/"stale"/"unavailable")`).

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew integrationTest --tests "com.indexer.mcp.SemanticQueryTest"`
Expected: the three status tests FAIL under the old `scip_sha`-comparison logic given the new seeding.

- [ ] **Step 3: Rewrite `getScipStatus`**

Replace the body of `getScipStatus` (~774-791) with existence-based logic:

```java
    public String getScipStatus(String repoName) {
        if (repoName == null || repoName.isBlank()) return null;
        return jdbi.withHandle(handle -> {
            var repo = handle.createQuery("SELECT id, last_indexed_sha FROM repositories WHERE name = :name")
                    .bind("name", repoName).mapToMap().findOne();
            if (repo.isEmpty()) return null;
            int repoId = ((Number) repo.get().get("id")).intValue();
            String indexedSha = (String) repo.get().get("last_indexed_sha");

            boolean anyScip = handle.createQuery("SELECT EXISTS(SELECT 1 FROM scip_symbols WHERE repo_id = :id)")
                    .bind("id", repoId).mapTo(Boolean.class).one();
            if (!anyScip) return "unavailable";

            boolean freshScip = indexedSha != null && handle.createQuery(
                            "SELECT EXISTS(SELECT 1 FROM scip_symbols WHERE repo_id = :id AND upload_sha = :sha)")
                    .bind("id", repoId).bind("sha", indexedSha).mapTo(Boolean.class).one();
            return freshScip ? "fresh" : "stale";
        });
    }
```

- [ ] **Step 4: Update the `get_index_health` SCIP status**

In the `get_index_health` query (~725-740), replace the `CASE WHEN r.scip_sha ...` expression with existence-based logic. Change the SELECT to compute status via correlated EXISTS subqueries:

```sql
                CASE
                    WHEN NOT EXISTS (SELECT 1 FROM scip_symbols s WHERE s.repo_id = r.id) THEN 'unavailable'
                    WHEN EXISTS (SELECT 1 FROM scip_symbols s WHERE s.repo_id = r.id AND s.upload_sha = r.last_indexed_sha) THEN 'fresh'
                    ELSE 'stale'
                END AS scip_status,
```
Keep `r.scip_uploaded_at` in the SELECT (still informational). Remove `r.scip_sha` from the SELECT/GROUP BY only if it's no longer referenced; otherwise leave it. (Read the full query and adjust GROUP BY to stay valid.)

- [ ] **Step 5: Run to verify they pass**

Run: `./gradlew integrationTest --tests "com.indexer.mcp.SemanticQueryTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/indexer/mcp/QueryExecutor.java \
        src/test/java/com/indexer/mcp/SemanticQueryTest.java
git commit -m "feat: existence-based SCIP status (tag uploads no longer skew main fresh/stale)"
```

---

### Task 4: Ref-aware SCIP queries (`branch` on the two tools)

**Files:**
- Modify: `src/main/java/com/indexer/mcp/QueryExecutor.java` — add `resolveRefSha`; add `branch` param to `getTypeHierarchy`/`getSymbolReferences`; thread `upload_sha` through `resolveScipSymbol`, `traverseHierarchy`, and the `getSymbolReferences` inbound/outbound queries.
- Modify: `src/main/java/com/indexer/mcp/McpServerBootstrap.java` — `branch` on the two tool schemas + handlers.
- Test: `src/test/java/com/indexer/mcp/SemanticQueryTest.java`

> Threading rule: every SCIP read filters by the resolved ref SHA. `resolveScipSymbol` (queries `scip_symbols`) and `traverseHierarchy` (queries `scip_relationships`) and the two `getSymbolReferences` queries each gain `AND upload_sha = :uploadSha`. Pass the resolved SHA down as a new parameter.

- [ ] **Step 1: Add the ref→SHA resolver**

Add to `QueryExecutor`:
```java
    /** Resolve a ref (branch/tag/sha) to the indexed commit SHA whose SCIP we should read. */
    private String resolveRefSha(org.jdbi.v3.core.Handle handle, int repoId, String branch) {
        String effective = resolveBranch(branch);
        if ("main".equals(effective)) {
            return handle.createQuery("SELECT last_indexed_sha FROM repositories WHERE id = :id")
                    .bind("id", repoId).mapTo(String.class).findOne().orElse(null);
        }
        return handle.createQuery("SELECT indexed_sha FROM branch_index WHERE repo_id = :id AND branch = :b")
                .bind("id", repoId).bind("b", effective).mapTo(String.class).findOne().orElse(null);
    }
```

- [ ] **Step 2: Write failing ref-aware query tests**

In `SemanticQueryTest`, add a test that seeds the SAME `scip_symbol` for two SHAs (`mainSha`, `tagSha`) with DIFFERENT relationships, registers a `branch_index` row mapping a tag name → `tagSha` and `repositories.last_indexed_sha = mainSha`, then asserts:
- `getTypeHierarchy(repo, symbol, ..., branch=null/main)` reflects `mainSha`'s relationships,
- `getTypeHierarchy(repo, symbol, ..., branch="v1.0")` reflects `tagSha`'s relationships,
- a ref with no SCIP for its SHA yields an empty/`unavailable` hierarchy.
Model the seeding on the existing `getTypeHierarchyBothDirections` test (read it). Add an equivalent pair for `getSymbolReferences`.

Run: `./gradlew integrationTest --tests "com.indexer.mcp.SemanticQueryTest"` → the new ref-aware tests FAIL (queries ignore `upload_sha`, so both refs return the union/first match).

- [ ] **Step 3: Thread `upload_sha` through the SCIP reads**

(a) `getTypeHierarchy` and `getSymbolReferences`: add a trailing `String branch` parameter. At the top of each (inside `withHandle`, after resolving `repoId`), call `String uploadSha = resolveRefSha(handle, repoId, branch);` and also call `ensureBranchIndexed(repo, resolveBranch(branch))` before the handle block (so the ref is faulted in, consistent with other branch-aware tools). If `uploadSha == null`, short-circuit to the existing "not found / unavailable" return shape.

(b) `resolveScipSymbol`: add a `String uploadSha` parameter and `AND upload_sha = :uploadSha` to its query (bind it). Update its single caller chain.

(c) `traverseHierarchy`: add a `String uploadSha` parameter; add `AND upload_sha = :uploadSha` to BOTH the up and down queries; pass `uploadSha` through the recursive call.

(d) `getSymbolReferences` inbound (~990-1003) and outbound (~1034-1047) queries: add `AND sr.upload_sha = :uploadSha` and bind it.

Read each method body and apply consistently. Keep the public return shapes identical.

- [ ] **Step 4: Add `branch` to the MCP tool surface**

In `McpServerBootstrap.java`:
- `getTypeHierarchyTool()` and `getSymbolReferencesTool()`: add to each `props` map:
  `props.put("branch", Map.of("type", "string", "description", "Ref to resolve SCIP at: branch, tag, or commit SHA (default: main)"));`
- `handleGetTypeHierarchy` / `handleGetSymbolReferences`: pass `stringArg(args, "branch")` as the new trailing argument to the query calls.

- [ ] **Step 5: Run to verify they pass**

Run: `./gradlew integrationTest --tests "com.indexer.mcp.SemanticQueryTest"`
Expected: PASS — type hierarchy / references differ correctly between `main` and the tag SHA.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/indexer/mcp/QueryExecutor.java \
        src/main/java/com/indexer/mcp/McpServerBootstrap.java \
        src/test/java/com/indexer/mcp/SemanticQueryTest.java
git commit -m "feat: get_type_hierarchy / get_symbol_references resolve SCIP by ref SHA"
```

---

### Task 5: SCIP prune task + retention policy

**Files:**
- Create: `src/main/java/com/indexer/scip/ScipDao.java`
- Create: `src/main/java/com/indexer/scip/ScipPruneTask.java`
- Modify: config (`Config` record/loader) + `src/main/java/com/indexer/Application.java` (schedule it)
- Test: `src/test/java/com/indexer/scip/ScipPruneTaskTest.java` (new)

> Retained per repo = {`repositories.last_indexed_sha`} ∪ {every `branch_index.indexed_sha`} ∪ {anything uploaded within `pruneGraceDays`}. Prune everything else.

- [ ] **Step 1: Write the failing prune test**

Create `src/test/java/com/indexer/scip/ScipPruneTaskTest.java` (Testcontainers). Seed for one repo:
- `last_indexed_sha = 'MAIN'`; a `branch_index` row with `indexed_sha = 'TAGSHA'`.
- `scip_symbols` for SHAs: `'MAIN'` (keep), `'TAGSHA'` (keep), `'OLD'` with `uploaded_at = NOW() - 30 days` (prune), `'RECENT'` with `uploaded_at = NOW()` (keep, within grace). Mirror the same SHAs into `scip_relationships`.
Run `new ScipPruneTask(scipDao, repositoryDao, /*graceDays*/7).run()`, then assert: `MAIN`, `TAGSHA`, `RECENT` rows remain; `OLD` rows (symbols AND relationships) are gone.

```java
        new ScipPruneTask(scipDao, repositoryDao, 7).run();
        assertThat(countSymbols(repoId, "OLD")).isZero();
        assertThat(countSymbols(repoId, "MAIN")).isPositive();
        assertThat(countSymbols(repoId, "TAGSHA")).isPositive();
        assertThat(countSymbols(repoId, "RECENT")).isPositive();
        assertThat(countRels(repoId, "OLD")).isZero();
```

Run: `./gradlew integrationTest --tests "com.indexer.scip.ScipPruneTaskTest"` → FAIL (classes don't exist).

- [ ] **Step 2: Implement `ScipDao.prune`**

Create `src/main/java/com/indexer/scip/ScipDao.java`:
```java
package com.indexer.scip;

import org.jdbi.v3.core.Jdbi;

public class ScipDao {
    private final Jdbi jdbi;
    public ScipDao(Jdbi jdbi) { this.jdbi = jdbi; }

    /**
     * Delete SCIP rows for a repo whose upload_sha is neither the current main SHA nor any
     * live branch_index ref, and which are older than the grace window. Returns rows removed.
     */
    public int prune(int repoId, int graceDays) {
        return jdbi.inTransaction(handle -> {
            String retainedFilter = """
                     AND uploaded_at < NOW() - CAST(:graceDays || ' days' AS INTERVAL)
                     AND upload_sha IS DISTINCT FROM (SELECT last_indexed_sha FROM repositories WHERE id = :repoId)
                     AND NOT EXISTS (SELECT 1 FROM branch_index bi WHERE bi.repo_id = :repoId AND bi.indexed_sha = scip_t.upload_sha)
                    """;
            int rels = handle.createUpdate(
                            "DELETE FROM scip_relationships scip_t WHERE repo_id = :repoId" + retainedFilter)
                    .bind("repoId", repoId).bind("graceDays", graceDays).execute();
            int syms = handle.createUpdate(
                            "DELETE FROM scip_symbols scip_t WHERE repo_id = :repoId" + retainedFilter)
                    .bind("repoId", repoId).bind("graceDays", graceDays).execute();
            return rels + syms;
        });
    }
}
```
> Confirm Postgres accepts the `scip_t` alias in `DELETE FROM <table> scip_t WHERE ...`; if the alias form is rejected, drop the alias and reference `upload_sha`/`uploaded_at` unqualified (column names are unambiguous in a single-table DELETE), keeping the correlated `branch_index` subquery referencing the table's `upload_sha` directly.

- [ ] **Step 3: Implement `ScipPruneTask`**

Create `src/main/java/com/indexer/scip/ScipPruneTask.java` (model on `BranchCleanupTask`):
```java
package com.indexer.scip;

import com.indexer.db.RepositoryDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScipPruneTask implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ScipPruneTask.class);
    private final ScipDao scipDao;
    private final RepositoryDao repositoryDao;
    private final int graceDays;

    public ScipPruneTask(ScipDao scipDao, RepositoryDao repositoryDao, int graceDays) {
        this.scipDao = scipDao;
        this.repositoryDao = repositoryDao;
        this.graceDays = graceDays;
    }

    @Override
    public void run() {
        try {
            for (var repo : repositoryDao.findAll()) {
                try {
                    int removed = scipDao.prune(repo.id(), graceDays);
                    if (removed > 0) log.info("SCIP prune: removed {} rows for repo {}", removed, repo.name());
                } catch (Exception e) {
                    log.error("SCIP prune failed for repo {}: {}", repo.name(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("SCIP prune task failed: {}", e.getMessage(), e);
        }
    }
}
```
> Confirm `RepositoryDao.findAll()` exists and returns records with `.id()`/`.name()`. If the method has a different name (e.g. `listAll`/`findAllRepos`), use that. If none exists, add a minimal `findAll()` to `RepositoryDao` returning all repos.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew integrationTest --tests "com.indexer.scip.ScipPruneTaskTest"`
Expected: PASS.

- [ ] **Step 5: Wire config + schedule it**

Add `pruneGraceDays` (default 7) to the SCIP/branches config. Read how `config.branches().ttlDays()` / `cleanupIntervalHours()` are defined (the `Config` record + YAML loader) and add a parallel `scip` section or a `scipPruneGraceDays` field with a default. Then in `Application.java`, alongside the existing `BranchCleanupTask` scheduling, schedule the prune on the same scheduler/cadence:
```java
            var scipPruneTask = new ScipPruneTask(new ScipDao(jdbi), repositoryDao, config.scip().pruneGraceDays());
            scheduler.scheduleAtFixedRate(scipPruneTask,
                    config.branches().cleanupIntervalHours(),
                    config.branches().cleanupIntervalHours(),
                    TimeUnit.HOURS);
            log.info("SCIP prune task scheduled every {}h (grace={}d)",
                    config.branches().cleanupIntervalHours(), config.scip().pruneGraceDays());
```
Adapt `config.scip().pruneGraceDays()` to however config is actually structured (read `Config`/the loader first; if adding a nested record is heavy, a single `config.scipPruneGraceDays()` with a sane default is fine). Ensure a missing config value defaults to 7.

- [ ] **Step 6: Run the full SCIP test set**

Run: `./gradlew integrationTest --tests "com.indexer.scip.*" --tests "com.indexer.mcp.SemanticQueryTest"` → all pass. Also `./gradlew compileJava` to confirm `Application` wiring compiles.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/indexer/scip/ScipDao.java \
        src/main/java/com/indexer/scip/ScipPruneTask.java \
        src/main/java/com/indexer/Application.java \
        src/test/java/com/indexer/scip/ScipPruneTaskTest.java
# plus the config file(s) you modified
git commit -m "feat: periodic SCIP prune keeping main + live refs + grace window"
```

---

### Task 6: CI uploads SCIP on tags + docs

**Files:**
- Modify: `.github/workflows/scip-upload.yml`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Trigger SCIP upload on tag pushes**

In `.github/workflows/scip-upload.yml`, extend the `on.push` trigger to include tags:
```yaml
on:
  push:
    branches: [main]
    tags: ['*']
  workflow_dispatch: {}
```
The job already derives `X-Git-SHA` from `git rev-parse HEAD`, which on a tag push is the tag's commit — so the upload is keyed by the tag's SHA automatically. (No script change needed.) Note in a YAML comment that full type-resolved tag queries also require the tag to be *indexed* — handled by Phase 1 fault-in / Phase 4 trigger.

- [ ] **Step 2: Document retention + the new params**

In `CLAUDE.md`:
- Under the SCIP Upload API section, add that uploads are now **retained per `X-Git-SHA`** (not replace-all), pruned by a retention policy (current main + live refs + a grace window, default 7 days), and that CI uploads SCIP on tag pushes.
- In the MCP Tools Reference, note that `get_type_hierarchy` and `get_symbol_references` now accept an optional `branch` parameter (any ref) and resolve SCIP at that ref's SHA. Update the "All tools except …" sentence in the Branch Support section to remove these two from the exception list (they now accept `branch`; `get_index_health` remains the exception).

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/scip-upload.yml CLAUDE.md
git commit -m "ci+docs: upload SCIP on tags; document per-SHA retention and ref-aware SCIP tools"
```

---

## Self-Review

**Spec coverage:**
- "Retain SCIP keyed by `upload_sha`" → Task 1 (schema) + Task 2 (upload).
- "Query for ref R resolves R's SHA and reads that SHA's SCIP, falling back to unavailable" → Task 4 (`resolveRefSha` + `upload_sha` threading + `branch` param) and Task 3 (existence-based status).
- "Retention policy (keep retained tags + current main; prune superseded main uploads)" → Task 5 (referenced + grace window, per the chosen option).
- "CI uploads SCIP on tag" → Task 6.
- **Spec correction:** "no new schema" was wrong → Task 1 migration (relationships `upload_sha`; symbol uniqueness). Documented in Scope.

**Atomicity:** Task 1 (schema) precedes everything that needs `upload_sha`. Task 2 (upload) and Task 3 (status) and Task 4 (queries) each compile/pass independently after Task 1. Task 5 adds the prune task without touching the others. Each task commits green.

**Type/name consistency:** `resolveRefSha(handle, repoId, branch)` defined in Task 4 Step 1, used in Task 4 Step 3. `upload_sha` threaded consistently through `resolveScipSymbol`/`traverseHierarchy`/`getSymbolReferences`. `ScipDao.prune(repoId, graceDays)` defined Task 5 Step 2, used by `ScipPruneTask` (Step 3) and the test (Step 1). Config accessor (`pruneGraceDays`) defined Step 5, used in Application wiring.

**Placeholder scan:** The tests in Tasks 2 and 4 reference the existing protobuf-fixture/seeding style and tell the implementer which existing test to model against (`ScipParserTest`, `getTypeHierarchyBothDirections`) rather than inventing an API — necessary because the exact SCIP ingest entrypoint and fixture builders must be read from the codebase. All SQL, migration, prune logic, config wiring, and CI YAML are concrete.

## Out of scope (later phases)
- Phase 4: tag-push *indexing* trigger (so a CI-uploaded tag SCIP has a `branch_index` ref and survives pruning beyond the grace window), tag TTL pinning, retention-policy admin UI.
- Making `getSymbolDetail`/`findImplementations` `scip_status` ref-aware (kept repo-global here).
