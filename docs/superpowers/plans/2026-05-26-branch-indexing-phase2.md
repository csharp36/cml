# Branch-Aware Indexing Phase 2: Branch Indexing Pipeline

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the webhook→event→indexing pipeline branch-aware so that feature branches are automatically indexed on push, storing only their delta from main.

**Architecture:** `WebhookPayload` gains a `branch` field. The branch flows through `EventDao`→`IndexingEvent`→`EventQueuePoller`→`IndexingPipeline`. For non-main branches, `IndexingPipeline.branchIndex()` runs `git diff --name-only main...<sha>`, reads changed files via `git show <sha>:<path>`, and indexes them with `branch='feature/x'`. A `BranchIndexDao` tracks which branches have been indexed.

**Tech Stack:** Java 21, JDBI, PostgreSQL 16, Git CLI, JUnit 5 + Testcontainers

**Spec:** `docs/superpowers/specs/2026-05-26-branch-indexing-design.md` (section 6)

---

## File Structure

```
src/main/java/com/indexer/webhook/WebhookPayload.java      (modify — add branch field)
src/main/java/com/indexer/model/IndexingEvent.java          (modify — add branch field)
src/main/java/com/indexer/db/EventDao.java                  (modify — branch in insert + all mappers)
src/main/java/com/indexer/queue/EventQueuePoller.java       (modify — branch in ProcessableEvent)
src/main/java/com/indexer/server/HttpServer.java            (modify — pass branch to EventDao.insert)
src/main/java/com/indexer/repository/GitOperations.java     (modify — add diffFromMain + showFile methods)
src/main/java/com/indexer/model/BranchIndex.java            (create — record)
src/main/java/com/indexer/db/BranchIndexDao.java            (create — DAO)
src/main/java/com/indexer/indexing/FileIndexer.java         (modify — add indexFileFromContent method)
src/main/java/com/indexer/indexing/IndexingPipeline.java    (modify — add branchIndex method)
src/main/java/com/indexer/Application.java                  (modify — branch-aware event processor)
src/main/java/com/indexer/admin/AdminService.java           (modify — use branch in reindex)
```

---

### Task 1: Wire Branch Through Event Pipeline

**Files:**
- Modify: `src/main/java/com/indexer/webhook/WebhookPayload.java`
- Modify: `src/main/java/com/indexer/model/IndexingEvent.java`
- Modify: `src/main/java/com/indexer/db/EventDao.java`
- Modify: `src/main/java/com/indexer/queue/EventQueuePoller.java`
- Modify: `src/main/java/com/indexer/server/HttpServer.java`

- [ ] **Step 1: Add `branch` to WebhookPayload**

Replace `src/main/java/com/indexer/webhook/WebhookPayload.java`:

```java
package com.indexer.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookPayload(String repoName, String repoPath, String eventType, String previousSha, String currentSha, String branch, String timestamp) {
    public boolean isValid() {
        return repoName != null && !repoName.isBlank()
                && repoPath != null && !repoPath.isBlank()
                && eventType != null && !eventType.isBlank()
                && currentSha != null && !currentSha.isBlank();
    }

    /** Returns branch or "main" if not specified. */
    public String effectiveBranch() {
        return (branch != null && !branch.isBlank()) ? branch : "main";
    }
}
```

- [ ] **Step 2: Add `branch` to IndexingEvent**

Replace `src/main/java/com/indexer/model/IndexingEvent.java`:

```java
package com.indexer.model;

import java.time.Instant;

public record IndexingEvent(long id, String repoName, String repoPath, String eventType, String previousSha, String currentSha, String branch, String status, String errorMessage, Instant createdAt, Instant startedAt, Instant completedAt, String workerId) {}
```

Note: `branch` inserted after `currentSha`, before `status`.

- [ ] **Step 3: Update EventDao**

In `src/main/java/com/indexer/db/EventDao.java`:

Update `insert` to accept and store branch:

```java
    public long insert(String repoName, String repoPath, String eventType, String previousSha, String currentSha, String branch) {
        return jdbi.withHandle(handle ->
                handle.createUpdate("""
                        INSERT INTO indexing_events (repo_name, repo_path, event_type, previous_sha, current_sha, branch)
                        VALUES (:repoName, :repoPath, :eventType, :previousSha, :currentSha, :branch)
                        """)
                        .bind("repoName", repoName)
                        .bind("repoPath", repoPath)
                        .bind("eventType", eventType)
                        .bind("previousSha", previousSha)
                        .bind("currentSha", currentSha)
                        .bind("branch", branch != null ? branch : "main")
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one()
        );
    }
```

Update ALL row mappers (in `claimNextPending`, `findPendingByRepo`, `findRecentFailed`, `findById`, `findFiltered`) to include `branch`. Each mapper creates `new IndexingEvent(...)` — add `rs.getString("branch")` after `currentSha`:

```java
new IndexingEvent(
    rs.getLong("id"),
    rs.getString("repo_name"),
    rs.getString("repo_path"),
    rs.getString("event_type"),
    rs.getString("previous_sha"),
    rs.getString("current_sha"),
    rs.getString("branch"),        // ← NEW
    rs.getString("status"),
    ...
)
```

There are 5 places where `IndexingEvent` is constructed from a ResultSet. Update all 5.

- [ ] **Step 4: Add `branch` to ProcessableEvent and update EventQueuePoller**

In `src/main/java/com/indexer/queue/EventQueuePoller.java`, update the `ProcessableEvent` record:

```java
    public record ProcessableEvent(long eventId, String repoName, String repoPath, String previousSha, String currentSha, String branch) {}
```

Update the two places where `ProcessableEvent` is constructed (lines ~67 and ~83) to include `primary.branch()`:

```java
processableEvent = new ProcessableEvent(
    primary.id(),
    primary.repoName(),
    primary.repoPath(),
    primary.previousSha(),
    primary.currentSha(),
    primary.branch()
);
```

And in the collapsed event case:
```java
processableEvent = new ProcessableEvent(
    primary.id(),
    primary.repoName(),
    primary.repoPath(),
    collapsed.previousSha(),
    collapsed.currentSha(),
    primary.branch()
);
```

- [ ] **Step 5: Update HttpServer to pass branch**

In `src/main/java/com/indexer/server/HttpServer.java`, update the `handleWebhook` method to pass `branch`:

```java
    long eventId = eventDao.insert(payload.repoName(), payload.repoPath(), payload.eventType(),
            payload.previousSha(), payload.currentSha(), payload.effectiveBranch());
```

- [ ] **Step 6: Update callers of EventDao.insert**

Search for other callers of `eventDao.insert` (e.g., in tests). Add the `branch` parameter ("main" for existing calls).

- [ ] **Step 7: Verify build passes**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
./gradlew build 2>&1 | tail -10
```

- [ ] **Step 8: Commit**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
git add -A
git commit -m "feat: wire branch through webhook → event → poller pipeline"
```

---

### Task 2: GitOperations Branch Methods + BranchIndex DAO

**Files:**
- Modify: `src/main/java/com/indexer/repository/GitOperations.java`
- Create: `src/main/java/com/indexer/model/BranchIndex.java`
- Create: `src/main/java/com/indexer/db/BranchIndexDao.java`

- [ ] **Step 1: Add branch operations to GitOperations**

Add these methods to `src/main/java/com/indexer/repository/GitOperations.java`:

```java
    /**
     * Get the list of files changed between main and a branch SHA.
     * Uses three-dot diff to find changes relative to the merge base.
     */
    public List<String> diffFromMain(Path repoDir, String branchSha) throws IOException {
        List<String> cmd = List.of("git", "diff", "--name-only", "main..." + branchSha);
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

    /**
     * Read file content from a specific git ref (commit SHA or branch) without checkout.
     * Uses 'git show ref:path'.
     */
    public String showFile(Path repoDir, String ref, String filePath) throws IOException {
        List<String> cmd = List.of("git", "show", ref + ":" + filePath);
        return runCommandOutput(cmd, repoDir, null);
    }

    /**
     * Check if a remote branch exists.
     */
    public boolean remoteBranchExists(Path repoDir, String branch) throws IOException {
        try {
            List<String> cmd = List.of("git", "branch", "-r", "--list", "origin/" + branch);
            String output = runCommandOutput(cmd, repoDir, null);
            return !output.trim().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Get the SHA of a specific ref (branch name, tag, etc.).
     */
    public String getShaForRef(Path repoDir, String ref) throws IOException {
        List<String> cmd = List.of("git", "rev-parse", ref);
        return runCommandOutput(cmd, repoDir, null).trim();
    }
```

- [ ] **Step 2: Create BranchIndex model**

Create `src/main/java/com/indexer/model/BranchIndex.java`:

```java
package com.indexer.model;

import java.time.Instant;

public record BranchIndex(int id, int repoId, String branch, String baseSha, String indexedSha, Instant indexedAt, Instant lastAccessedAt) {}
```

- [ ] **Step 3: Create BranchIndexDao**

Create `src/main/java/com/indexer/db/BranchIndexDao.java`:

```java
package com.indexer.db;

import com.indexer.model.BranchIndex;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class BranchIndexDao {

    private final Jdbi jdbi;

    public BranchIndexDao(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public void upsert(int repoId, String branch, String baseSha, String indexedSha) {
        jdbi.useHandle(handle ->
                handle.createUpdate("""
                        INSERT INTO branch_index (repo_id, branch, base_sha, indexed_sha, indexed_at, last_accessed_at)
                        VALUES (:repoId, :branch, :baseSha, :indexedSha, NOW(), NOW())
                        ON CONFLICT (repo_id, branch) DO UPDATE
                            SET base_sha = EXCLUDED.base_sha,
                                indexed_sha = EXCLUDED.indexed_sha,
                                indexed_at = NOW(),
                                last_accessed_at = NOW()
                        """)
                        .bind("repoId", repoId)
                        .bind("branch", branch)
                        .bind("baseSha", baseSha)
                        .bind("indexedSha", indexedSha)
                        .execute()
        );
    }

    public Optional<BranchIndex> find(int repoId, String branch) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM branch_index WHERE repo_id = :repoId AND branch = :branch")
                        .bind("repoId", repoId)
                        .bind("branch", branch)
                        .map((rs, ctx) -> new BranchIndex(
                                rs.getInt("id"),
                                rs.getInt("repo_id"),
                                rs.getString("branch"),
                                rs.getString("base_sha"),
                                rs.getString("indexed_sha"),
                                rs.getTimestamp("indexed_at").toInstant(),
                                rs.getTimestamp("last_accessed_at").toInstant()
                        ))
                        .findOne()
        );
    }

    public void touchLastAccessed(int repoId, String branch) {
        jdbi.useHandle(handle ->
                handle.createUpdate("UPDATE branch_index SET last_accessed_at = NOW() WHERE repo_id = :repoId AND branch = :branch")
                        .bind("repoId", repoId)
                        .bind("branch", branch)
                        .execute()
        );
    }

    public List<BranchIndex> findExpired(int ttlDays) {
        return jdbi.withHandle(handle ->
                handle.createQuery("""
                        SELECT bi.*, r.name AS repo_name FROM branch_index bi
                        JOIN repositories r ON bi.repo_id = r.id
                        WHERE bi.last_accessed_at < NOW() - CAST(:ttlDays || ' days' AS INTERVAL)
                        """)
                        .bind("ttlDays", ttlDays)
                        .map((rs, ctx) -> new BranchIndex(
                                rs.getInt("id"),
                                rs.getInt("repo_id"),
                                rs.getString("branch"),
                                rs.getString("base_sha"),
                                rs.getString("indexed_sha"),
                                rs.getTimestamp("indexed_at").toInstant(),
                                rs.getTimestamp("last_accessed_at").toInstant()
                        ))
                        .list()
        );
    }

    public void delete(int repoId, String branch) {
        jdbi.useHandle(handle ->
                handle.createUpdate("DELETE FROM branch_index WHERE repo_id = :repoId AND branch = :branch")
                        .bind("repoId", repoId)
                        .bind("branch", branch)
                        .execute()
        );
    }

    public int countByRepo(int repoId) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT COUNT(*) FROM branch_index WHERE repo_id = :repoId")
                        .bind("repoId", repoId)
                        .mapTo(Integer.class)
                        .one()
        );
    }
}
```

- [ ] **Step 4: Verify build**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
./gradlew build 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add GitOperations branch methods + BranchIndex model + BranchIndexDao"
```

---

### Task 3: Branch Indexing in Pipeline + Application Wiring

**Files:**
- Modify: `src/main/java/com/indexer/indexing/FileIndexer.java`
- Modify: `src/main/java/com/indexer/indexing/IndexingPipeline.java`
- Modify: `src/main/java/com/indexer/Application.java`

- [ ] **Step 1: Add `indexFileFromContent` to FileIndexer**

In `src/main/java/com/indexer/indexing/FileIndexer.java`, add a method that indexes a file from a string content (used when reading via `git show` instead of disk):

```java
    /**
     * Index a file from provided content string (e.g., from git show).
     * Used for branch indexing where the working tree is on a different branch.
     */
    public void indexFileFromContent(int repoId, String branch, String relativePath, String content, String commitSha) {
        String language = languageRegistry.detectLanguage(relativePath);
        if (language == null) {
            return; // Skip files with unknown extensions
        }

        if (content.length() > maxFileSizeBytes) {
            log.debug("Skipping oversized file: {} ({} bytes)", relativePath, content.length());
            return;
        }

        SourceFile sourceFile = new SourceFile(0, repoId, branch, relativePath, language, content.length(), commitSha, Instant.now());
        int fileId = fileDao.upsert(sourceFile);

        // Delete existing symbols/imports for this file (re-index)
        symbolDao.deleteSymbolsByFileId(fileId);
        symbolDao.deleteImportsByFileId(fileId);

        // Insert content
        indexContent(fileId, content);

        // If core language: run SymbolExtractor
        if (languageRegistry.isCoreLanguage(language)) {
            indexSymbols(fileId, content, language);
        }
    }
```

Note: This method needs access to `indexContent` and `indexSymbols` which are existing private methods in FileIndexer. Check that they are accessible (they should be since this is in the same class).

Also add the import for `SourceFile`:
```java
import com.indexer.model.SourceFile;
```

And ensure `Instant` is imported.

- [ ] **Step 2: Add `branchIndex` to IndexingPipeline**

In `src/main/java/com/indexer/indexing/IndexingPipeline.java`, add:

```java
    /**
     * Index a feature branch by computing its delta from main.
     * Only changed files are indexed; unchanged files fall through to main via overlay queries.
     */
    public void branchIndex(int repoId, String branch, Path repoDir, String branchSha) throws IOException {
        log.info("Branch indexing repo {} branch {} at SHA {}", repoId, branch, branchSha);

        // Get the main branch HEAD SHA for the base_sha record
        String mainSha = gitOps.getCurrentSha(repoDir);

        // Find files changed between main and this branch
        List<String> changedFiles = gitOps.diffFromMain(repoDir, branchSha);
        log.info("Branch {} has {} files changed from main", branch, changedFiles.size());

        for (String relativePath : changedFiles) {
            try {
                // Read file content from the branch ref (not the working tree)
                String content = gitOps.showFile(repoDir, branchSha, relativePath);
                fileIndexer.indexFileFromContent(repoId, branch, relativePath, content, branchSha);
            } catch (IOException e) {
                // File might have been deleted on the branch — skip it
                log.debug("Could not read {} from branch {} (may be deleted): {}", relativePath, branch, e.getMessage());
            } catch (Exception e) {
                log.error("Failed to index branch file {} in repo {}: {}", relativePath, repoId, e.getMessage(), e);
            }
        }

        log.info("Branch index complete for repo {} branch {}", repoId, branch);
    }
```

Also add to the constructor or as a field: the pipeline needs a `BranchIndexDao` to record that the branch was indexed. Update the constructor:

```java
    private final RepositoryDao repositoryDao;
    private final FileIndexer fileIndexer;
    private final GitOperations gitOps;
    private final BranchIndexDao branchIndexDao; // may be null for backward compat

    public IndexingPipeline(RepositoryDao repositoryDao, FileIndexer fileIndexer, GitOperations gitOps) {
        this(repositoryDao, fileIndexer, gitOps, null);
    }

    public IndexingPipeline(RepositoryDao repositoryDao, FileIndexer fileIndexer, GitOperations gitOps, BranchIndexDao branchIndexDao) {
        this.repositoryDao = repositoryDao;
        this.fileIndexer = fileIndexer;
        this.gitOps = gitOps;
        this.branchIndexDao = branchIndexDao;
    }
```

And at the end of `branchIndex`, record the branch index:

```java
        // Record branch index state
        if (branchIndexDao != null) {
            branchIndexDao.upsert(repoId, branch, mainSha, branchSha);
        }
```

Add imports:
```java
import com.indexer.db.BranchIndexDao;
```

- [ ] **Step 3: Update Application.java event processor**

In `src/main/java/com/indexer/Application.java`:

First, create the `BranchIndexDao` and pass it to `IndexingPipeline`:

```java
            var branchIndexDao = new BranchIndexDao(jdbi);
            var indexingPipeline = new IndexingPipeline(repositoryDao, fileIndexer, gitOps, branchIndexDao);
```

Then update the event processor lambda (around line 121) to handle branch events:

```java
            poller = new EventQueuePoller(eventDao, event -> {
                var repo = repositoryDao.findByName(event.repoName()).orElse(null);
                if (repo == null) {
                    throw new RuntimeException("Unknown repo: " + event.repoName());
                }
                try {
                    String branch = event.branch() != null ? event.branch() : "main";
                    if ("main".equals(branch) || branch.equals(repo.branch())) {
                        // Main branch: fetch, fast-forward, incremental index
                        gitOps.fetch(Path.of(repo.clonePath()), null);
                        gitOps.fastForward(Path.of(repo.clonePath()), repo.branch());
                        indexingPipeline.incrementalIndex(repo.id(), branch, Path.of(repo.clonePath()),
                                event.previousSha(), event.currentSha());
                    } else {
                        // Feature branch: fetch, then delta-index from main
                        gitOps.fetch(Path.of(repo.clonePath()), null);
                        indexingPipeline.branchIndex(repo.id(), branch, Path.of(repo.clonePath()), event.currentSha());
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Indexing failed: " + e.getMessage(), e);
                }
            }, 1000);
```

Also update `AdminService` if it constructs `IndexingPipeline` — pass the `BranchIndexDao`.

- [ ] **Step 4: Verify build**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
./gradlew build 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: branch indexing pipeline — delta from main via git show + BranchIndexDao tracking"
```

---

## Verification Checklist

1. `./gradlew build` — BUILD SUCCESSFUL
2. Webhook with `{ "branch": "feature/x" }` inserts event with branch
3. EventQueuePoller passes branch through to processor
4. Non-main events trigger `branchIndex()` (delta from main)
5. Main events still use `incrementalIndex()` (unchanged behavior)
6. `BranchIndexDao.upsert()` records branch index state
7. `GitOperations.diffFromMain()` and `showFile()` work correctly
