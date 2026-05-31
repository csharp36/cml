# Phase 4 — Tag Lifecycle + Default-Branch Generalization

**Status:** Approved (2026-05-30)
**Predecessor:** `docs/superpowers/specs/2026-05-29-tagged-release-full-indexing-design.md` (Phases 0–3, merged)
**Milestone position:** Final phase of the "index any ref" milestone.

## Summary

Three coordinated changes that complete the "any ref" story established in Phases 0–3:

- **A — Tag-push triggering:** wire the GitHub webhook so `refs/tags/...` pushes are no longer silently dropped, gated by a tag-name glob; verify and test the existing lazy fault-in path as the universal fallback.
- **B — Ref-kind-aware retention + pinning:** stop `BranchCleanupTask` from expiring immutable refs (tags/SHAs) on the 14-day branch window; give them a longer access-based window plus an explicit pin escape hatch.
- **C — Default-branch generalization:** replace the hardcoded literal `main` (in the diff base and the overlay query) with each repo's configured base branch, fixing repos whose default branch is `develop`/`master`/etc.

There is **no new admin "index-ref" endpoint.** A tag's type-resolution layer already arrives automatically via CI SCIP upload (Phase 3, keyed per `X-Git-SHA`). Its structural overlay arrives either at push time (pre-warm, Section A) or on first query (lazy fault-in). These two existing mechanisms are sufficient.

## Background / current state (verified against code)

- **TTL already keys off `last_accessed_at`**, not `indexed_at`, and `touchLastAccessed()` fires on every query (`QueryExecutor.java:1723`). The "never auto-expire a ref with active queries" guarantee already holds — the only problem is the flat 14-day window applied to every `ref_kind`.
- `BranchIndexDao.findExpired(ttlDays)` (`BranchIndexDao.java:67-85`) selects `WHERE last_accessed_at < NOW() - :ttlDays days`. It maps `ref_kind` but never filters on it.
- **Tag pushes are silently dropped today:** `GitHubPushPayload.branch()` returns `null` for `refs/tags/...`, so the handler rejects them at the configured-branch match (`GitHubWebhookApi.java:84-88`) before the `RefKind.BRANCH` hardcode at `Application.java:250` is ever reached.
- **Lazy fault-in already resolves branch→tag→SHA** and indexes synchronously (`QueryExecutor.java:1710-1745`; `GitOperations.resolveAnyRef` at `GitOperations.java:156-167`).
- `branch_index` columns: `id, repo_id, branch, base_sha, indexed_sha, indexed_at, last_accessed_at, ref_kind` (V2 + V5). `RefKind` enum = `BRANCH | TAG | SHA` (`RefKind.java:6-9`), stored lowercase.
- Reindex endpoint pattern to model admin routes on: `POST /admin/repos/{name}/reindex` → async 202 → background `fullIndex` (`AdminApi.java:123-133`, `AdminService.java:159-177`).

## Section A — Tag-push triggering

### A.1 Webhook tag handling (`GitHubWebhookApi`, `GitHubPushPayload`)

Today the payload parser only understands `refs/heads/...`. Change it to classify the pushed ref:

- `refs/heads/X` → `(name=X, kind=BRANCH)`
- `refs/tags/X`  → `(name=X, kind=TAG)`

Handler behavior:

- **Branch push:** unchanged — must match the repo's configured branch, then enqueue.
- **Tag push:** bypass the configured-branch match. If `tags.autoIndex` is true **and** the tag name matches `tags.pattern` (glob), enqueue an indexing event for that tag ref. A tag that does not match the pattern is **accepted but ignored** at push time (HTTP 200, no enqueue) — it remains reachable later via lazy fault-in.
- **Tag deletion** (all-zero `after` SHA): ignored, same rule as branch deletion today.
- Non-push events (`ping`, etc.): accepted and ignored, unchanged.
- Fail-closed HMAC verification (`X-Hub-Signature-256`, per-repo `webhookSecret`) is unchanged and applies to tag pushes identically.

### A.2 Thread `ref_kind` through the event queue (`Application.java:250`)

Replace the hardcoded `RefKind.BRANCH` by **persisting the ref kind on the event** and reading it back in the poller. The webhook already knows the kind authoritatively (it parsed `refs/tags/` vs `refs/heads/` in A.1), so we carry that fact through the queue rather than reconstructing it.

This mirrors the existing `branch` field, which is already threaded webhook → `EventDao.insert` → `claimNextPending` → `event.branch()` (it began as a post-V1 column added by a later migration). `ref_kind` is its twin.

- **`V7` migration:** `ALTER TABLE indexing_events ADD COLUMN ref_kind TEXT NOT NULL DEFAULT 'branch';` (backward-compatible; covers the generic `/webhook` and any legacy producers).
- **`EventDao.insert`** gains a `RefKind` parameter; `claimNextPending` selects `ref_kind`; the `IndexingEvent` record gains a `refKind` field.
- **GitHub webhook** sets `BRANCH` / `TAG` from the ref it parsed; the generic `/webhook` defaults to `BRANCH`.
- **Poller:** `indexingPipeline.branchIndex(repo.id(), branch, repoDir, event.currentSha(), event.refKind())`.

**Why not re-derive via `resolveAnyRef` at processing time:** that approach (branch→tag→SHA, first hit wins) is **incorrect under name collisions** — if a repo has a branch and a tag with the same name, a tag push would resolve to `BRANCH`. It also adds a git resolution per event. Persisting the kind preserves authoritative intent and removes the extra git call. With `ref_kind` + `current_sha` + `branch` all on the event, the poller needs no `resolveAnyRef` call in the push path. (Lazy fault-in's use of `resolveAnyRef` at query time is unaffected.)

### A.3 Lazy fault-in (already implemented)

No new production code. Scope is **verification + tests**:

- A tag/SHA that was never pre-warmed faults in synchronously on first query.
- The "tag faulted-in (a `branch_index` row exists) but SCIP was never uploaded for its SHA" path returns a clean `Symbol not found in SCIP data` rather than an error.

### A.4 New configuration

```yaml
tags:
  autoIndex: true     # pre-warm matching tags on push
  pattern: "v*"       # glob; only matching tag names pre-warm at push time
```

Defaults: `autoIndex: true`, `pattern: "v*"`. When `tags` is absent, defaults apply. The glob only decides **pre-warm at push** vs **index on first query** — never **available** vs **unavailable**.

## Section B — Ref-kind-aware retention + pinning

### B.1 Schema (`V8` migration)

```sql
ALTER TABLE branch_index
    ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE;
```

(Migration order: `V7` adds `ref_kind` to `indexing_events` — Section A.2; `V8` adds `pinned` to `branch_index` — this section.)

### B.2 TTL model (`BranchIndexDao.findExpired` rewrite)

Still access-based on `last_accessed_at`; now ref-kind-aware and pin-aware:

```sql
SELECT * FROM branch_index
WHERE pinned = FALSE
  AND (
        (ref_kind = 'branch'       AND last_accessed_at < NOW() - CAST(:branchTtlDays   || ' days' AS INTERVAL))
     OR (ref_kind IN ('tag','sha') AND last_accessed_at < NOW() - CAST(:immutableTtlDays || ' days' AS INTERVAL))
      )
```

- `branch` → existing `branches.ttlDays` (default 14).
- `tag` + `sha` → new `branches.immutableRefTtlDays` (default 90).
- Pinned rows are fully exempt from cleanup regardless of kind or access time.
- The "active refs never expire" guarantee is preserved (the window is just longer for immutable refs).

`BranchCleanupTask` passes both TTL values into `findExpired`. Scheduling is unchanged (`branches.cleanupIntervalHours`, default 24).

### B.3 Pinning

- **Any** ref kind (branch/tag/SHA) is pinnable — `findExpired` simply excludes `pinned = TRUE`. Pinning `main` is moot, as `main` is not a `branch_index` row.
- Admin endpoints (bearer-token auth, audited, modeled on existing `/admin/*` routes):
  - `POST   /admin/repos/{name}/refs/{ref}/pin` → set `pinned = TRUE`; 404 if no indexed ref by that name for the repo.
  - `DELETE /admin/repos/{name}/refs/{ref}/pin` → set `pinned = FALSE`; 404 likewise.
- Both actions write an audit log entry (`admin:pin` / `admin:unpin`), consistent with other admin operations.

### B.4 New configuration

```yaml
branches:
  ttlDays: 14
  immutableRefTtlDays: 90    # tags + SHAs
  cleanupIntervalHours: 24
```

Default `immutableRefTtlDays: 90` when absent.

## Section C — Default-branch generalization

Replace every literal `"main"` with a per-repo **base branch**.

### C.1 Base-branch resolution

Resolve in order:
1. The repo's configured `branch` field (already present in config and the `repositories` table) — this is the fully-indexed base branch.
2. **Fallback:** detect `origin/HEAD` (`git symbolic-ref refs/remotes/origin/HEAD`) only if the configured `branch` is somehow empty.

### C.2 Call-site changes

- `GitOperations.diffFromMain(repoDir)` → `diffFromBase(repoDir, baseBranch)`; the `git diff` base becomes the resolved base branch.
- The `effective_files` overlay CTE (and any other `'main'` literal in `QueryExecutor`) keys off the repo's base branch instead of the string `main`.
- Resolution occurs where the repo record is loaded; the `origin/HEAD` fallback is a one-time detection, not per-query.

This fixes the production limitation flagged since Phase 1: repos whose default branch is not `main` currently get a broken overlay/diff base.

## Testing strategy

**Section A**
- Webhook tag push with a name matching `pattern` → event enqueued → tag indexed.
- Webhook tag push **not** matching `pattern` → HTTP 200, no enqueue; subsequent query faults the tag in.
- Poller indexes with the event's persisted `ref_kind` (`TAG`) rather than hardcoding `BRANCH`.
- **Name-collision regression:** a repo with a branch *and* a tag sharing a name — a tag push is indexed as `TAG` (proving the persisted kind wins, where re-derivation via `resolveAnyRef` would have wrongly picked `BRANCH`).
- `EventDao.insert`/`claimNextPending` round-trip `ref_kind`; generic `/webhook` defaults to `branch`.
- Tag faulted-in but no SCIP uploaded for its SHA → clean `Symbol not found in SCIP data`.
- Tag deletion / `ping` accepted and ignored.

**Section B**
- `findExpired` keeps tags/SHAs to the 90-day window and branches to the 14-day window in a single pass.
- A pinned row survives past its TTL; unpinning makes it eligible again.
- Pin/unpin endpoints: 200 on success, 404 on unknown ref, bearer-token enforced, audit entries written.

**Section C**
- Overlay and diff are correct for a repo whose default branch is `develop`. (CI runners default `init.defaultBranch=master`; tests already use `git init -b main`, so a non-`main` default must be created explicitly with `git init -b develop`.)
- Configured-branch path and the `origin/HEAD` fallback path are both exercised.

All integration tests are `@Tag("integration")` (Testcontainers, requires Docker), run via `./gradlew integrationTest`.

## Out of scope / deferred

- No `POST /admin/.../index-ref` pre-warm endpoint (CI SCIP upload + push-time pre-warm + lazy fault-in cover the need).
- `getSymbolDetail` / `findImplementations` `scip_status` remains repo-global (not made ref-aware), as in Phase 3.
- Query-count-based retention (only timestamp-based `last_accessed_at` is used).

## Config reference (full Phase 4 additions)

```yaml
branches:
  autoIndex: true
  ttlDays: 14
  immutableRefTtlDays: 90      # NEW — tags + SHAs
  cleanupIntervalHours: 24

tags:                          # NEW block
  autoIndex: true
  pattern: "v*"

scip:
  pruneGraceDays: 7            # unchanged (Phase 3)
```
