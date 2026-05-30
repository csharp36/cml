# Design: Tagged releases as fully-indexed semantic builds

Status: Draft for review · 2026-05-29

## Problem

Two gaps block the "debug a prod regression from a stack trace, with no local checkout" workflow:

1. **`get_symbol_detail` returns `source_code: null` for any non-`main` ref.** You can locate a symbol
   on a branch/tag but not read its body — so the "read the suspect code" step of the debugging flow
   fails for exactly the refs (releases) you care about.
2. **SCIP is per-repo and upload-*replaces*.** Only the single latest uploaded SHA has type
   resolution; a tagged release loses its semantic layer as soon as the next upload lands.

**Insight:** production builds are **tagged and immutable** — a small, bounded, high-value set,
unlike the unbounded churn of feature branches. That set is worth indexing *deeply*. The goal:

> A tagged release has **full parity with `main`** — structural symbols, source, full-text search,
> and SCIP type resolution — all queryable at that tag, server-side, with no local checkout.

## Grounding facts (verified in code, 2026-05-29)

- `file_contents(file_id → content, search_vector)` is keyed 1:1 to `files`, and `files.branch`
  makes content **ref-scoped already**.
- Branch indexing (`FileIndexer.indexFileFromContent`, used by `IndexingPipeline.branchIndex`)
  **already writes `file_contents`** for a ref's delta files via `indexContent`.
- The overlay CTE (`effectiveFilesCte` / `effective_files`) **already resolves the correct file per
  ref** (`DISTINCT ON (path)` with branch priority over `main`).
- **Root cause of `source_code: null`:** `QueryExecutor.getSymbolDetail` calls
  `readSourceLines(clonePath, filePath, startLine, endLine)` — it reads the clone's **working tree**
  (always checked out to `main`), bypassing the `file_contents` row the overlay already resolved.

So the content overlay we need **mostly exists in the data layer**; one read path bypasses it.

## Storage model: copy-on-write delta + content overlay (Model A)

A ref stores only the files that differ from `main`; unchanged files inherit `main`'s symbols,
content, full-text, and SCIP via the `effective_files` overlay. No full per-tag duplication.
`file_contents` and its `search_vector` are already keyed by `file_id`, so this needs no new tables —
only that read paths consult the table (not disk) and that the overlay extends to every read path.

*Accepted caveat:* a tag cut long ago differs from current `main` in many files, so its delta grows
toward a full snapshot. Acceptable for occasional release debugging; revisit "delta vs nearest
baseline" only if disk actually hurts.

## Phases

### Phase 0 — Fix `source_code: null` (small, independently shippable)

Change `getSymbolDetail` to read source from `file_contents.content` (sliced to `start_line..end_line`
in Java) via the already-resolved `effective_files` row, instead of `readSourceLines` from disk.

- Works uniformly for `main`, branches, and tags — content is already stored for all of them.
- Removes the disk dependency entirely (robust even if the clone is mid-fetch / on another ref / the
  server holds no working tree).
- Metadata-only files (binary/oversized → no `file_contents.content`) correctly return null source.
- **Tests (TDD):** source returned for a branch-only file; source returned for a `main` file; null
  for a metadata-only file; correct line-range slice.
- Delivers the "read the suspect symbol" step of the debugging workflow on its own — ship first.

### Phase 1 — Index any ref (branch / tag / SHA)

Per `docs/proposals/2026-05-29-index-by-build-ref.md`: generalize ref resolution in
`ensureBranchIndexed` (`origin/<ref>` → `refs/tags/<ref>` → raw SHA) and record the **ref kind** in
`branch_index`. Storage/overlay are already ref-agnostic.

### Phase 2 — Full-text parity at refs ✅ verified (2026-05-30)

**Confirmed ref-aware, no extension needed.** `QueryExecutor.searchCode` already (a) calls
`ensureBranchIndexed` (which faults in any ref after Phase 1), (b) builds on `effectiveFilesCte(branch)`,
and (c) joins `file_contents fc JOIN effective_files ef ON fc.file_id = ef.id` — i.e. content is resolved
through the overlay, not a `main`-only join. Regression tests added in `BranchQueryTest`
(`searchCodeFindsBranchOnlyContentButNotOnMain`, `searchCodeBranchContentShadowsMainForSamePath`) lock
ref-scoped full-text search and branch-over-main content shadowing.

### Phase 3 — SCIP retention by SHA

Today SCIP upload deletes by `repo_id` (replace). Change to **retain SCIP keyed by `upload_sha`**;
a query for ref R resolves R's SHA and reads that SHA's SCIP, falling back to `unavailable`. Add a
retention policy (keep SCIP for retained tags + current `main`; prune superseded `main` uploads).
`scip_symbols`/`scip_relationships` already carry `upload_sha`, so this is primarily query + prune
logic, not new schema. CI uploads SCIP on tag (extend `.github/workflows/scip-upload.yml` to run on
`push: tags`).

### Phase 4 — Tag lifecycle & retention

- **Trigger** (decision below): how a tag becomes fully indexed.
- **Retention:** tags are immutable and prod-relevant → **pinned**, exempt from the 14-day branch
  TTL. The cleanup task keys on ref kind: feature branches expire on TTL; tags follow a separate,
  longer policy (keep last N releases, or tags accessed within X days — configurable).

## Decision: tag-indexing trigger

- **(a) Webhook on tag push.** Extend the GitHub webhook to handle tag-create events → enqueue a full
  index of the tag (reusing the `branchIndex` path). Live, no CI dependency; but HMAC webhook
  currently ignores non-push events — needs careful extension.
- **(b) CI-driven.** The release pipeline that builds the tag also calls an admin "index this ref"
  endpoint and uploads SCIP for the tag. Couples indexing to the existing release process; most
  reliable for "every prod build is indexed."
- **(c) Lazy fault-in.** First query of a tag triggers a synchronous full index (Phase 1 resolution).
  Zero release-process changes; first query pays a latency cost and an un-queried tag is never indexed.

**Recommendation: (b) as the source of truth** (every release deterministically indexed + SCIP
uploaded as part of shipping), with **(c) as a convenience fallback** for ad-hoc tags. (a) optional
later.

## Risks

- **Old-tag overlay size** — delta vs current `main` grows for old tags (accepted above).
- **SCIP retention storage** — multiple SHAs retained; bounded by the tag-retention policy.
- **Webhook tag events** — extending the fail-closed HMAC webhook to tags must preserve its
  fail-closed posture; non-`push` event handling is new surface.

## Open decisions for reviewer

1. **Trigger** — confirm (b)+(c), or prefer (a)?
2. **Tag retention policy** — all tags / last N / time-based? Default proposed: keep tags accessed
   within 90 days, never auto-expire a tag referenced by an active query.
3. **Scope/sequencing** — Phase 0 ships alone now; do Phases 1–4 land together as "tagged builds," or
   incrementally?

## Out of scope

- Delta-vs-nearest-baseline (Model C) chaining.
- Indexing depth tiers beyond branch (lightweight) vs tag/main (full).
- Retention-policy admin UI.
