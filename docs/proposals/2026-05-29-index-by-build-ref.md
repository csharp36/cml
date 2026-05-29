# Proposal: Index a build by tag / commit SHA (addressable build refs)

Status: Draft · 2026-05-29

## Motivation

CML's most compelling story for an AI client is **debugging a production regression from a stack
trace without checking out any code** (see the README "Flagship example"). That workflow leans on
`diff_branches(build_new, build_old)` plus symbol lookups, all server-side.

But today the indexable unit is a **branch name**:

- `ensureBranchIndexed` resolves a ref via `gitOps.getShaForRef(repoDir, "origin/" + branch)` — it
  only understands remote branches, not tags or arbitrary commit SHAs.
- Branch data is copy-on-write vs. `main` and TTL-expired.

Production builds are almost always identified by a **git tag** (`v2.3.1`) or a **commit SHA**, not
a long-lived branch. So the highest-value workflow currently requires the user to manufacture
`release/*` branches for every build — friction that undercuts the whole point.

**Goal:** make any git ref — branch, tag, or SHA — an addressable "build" that can be indexed and
diffed.

## Current state (what exists)

- `files.branch` is a free-form `TEXT` column already storing an arbitrary ref string. The overlay
  query (`branch IN (:ref, 'main')`) is ref-agnostic.
- `IndexingPipeline.branchIndex(repoId, ref, repoDir, sha)` already takes a ref string + a SHA and
  indexes files that differ from `main`.
- `BranchIndexDao` tracks indexed refs + `last_accessed_at` for TTL.

So the storage and overlay layers are **already ref-agnostic**. The gap is purely in **resolution**
(`ensureBranchIndexed` assumes `origin/<branch>`) and **API surface** (no way to ask for a tag/SHA).

## Proposed change

### 1. Generalize ref resolution

Replace the `origin/<branch>`-only logic in `ensureBranchIndexed` with a resolver that tries, in
order:

1. `origin/<ref>` (remote branch) — current behavior
2. `refs/tags/<ref>` (tag)
3. `<ref>` as a raw commit SHA (`git cat-file -e <ref>^{commit}`)

`gitOps.fetch(repoDir, null)` should fetch tags too (`--tags`). For a bare SHA not reachable from
any ref, fall back to `git fetch origin <sha>` where the server allows it.

```java
Optional<String> resolved = gitOps.resolveAnyRef(repoDir, ref); // branch | tag | sha -> sha
if (resolved.isEmpty()) return; // unknown ref -> main-only results (current fallback)
indexingPipeline.branchIndex(repoObj.id(), ref, repoDir, resolved.get());
```

### 2. Immutability optimization

A tag/SHA is **immutable**, unlike a branch HEAD that moves. Record the ref *kind* in
`branch_index` so the cleanup task can:

- skip the "is this ref stale vs. its moving HEAD?" check for SHAs/tags, and
- optionally pin frequently-diffed builds against TTL eviction.

### 3. API surface

Two non-breaking options (pick one):

- **(a) Overload the existing params.** `diff_branches` / branch-aware tools already accept a
  `branch` string; document that it accepts any ref and resolve accordingly. Lowest cost, slight
  naming smell.
- **(b) Add a `diff_builds(repo, build_a, build_b)` tool** (thin alias of `diff_branches`) plus an
  optional `ref` parameter on query tools. Clearer intent for the prod-debug workflow.

Recommendation: **(a)** now (zero new surface, just resolution + docs), **(b)** later if the
build-debugging workflow becomes a first-class, marketed feature.

### 4. SCIP per build

Type-resolved diffing of two builds needs SCIP for each build SHA. SCIP is already keyed by
`upload_sha`; CI should upload SCIP per release tag/SHA (it already runs per push — see
`.github/workflows/scip-upload.yml`). No schema change; just retain SCIP for more than the latest
SHA (today each upload *replaces* the repo's SCIP — a follow-up would be SCIP retention keyed by
SHA).

## Out of scope / follow-ups

- **SCIP retention by SHA** (today an upload replaces the repo's SCIP). Needed for *type-resolved*
  multi-build diff; structural (Tree-sitter) multi-build diff works without it.
- **Disk strategy** for indexing old SHAs: index from `git archive <sha>` or a transient worktree
  rather than mutating the primary clone's checkout, so concurrent queries aren't disrupted.
- **Retention policy UI** for pinned builds.

## Risks

- **Overlay size for old builds.** Copy-on-write is vs. *current* `main`; a months-old build SHA
  differs in many files, so its overlay is large. Acceptable for occasional debugging; if it
  becomes common, consider diffing against the nearest indexed baseline instead of `main`.
- **Fetch permissions.** Fetching an arbitrary SHA may be disabled on some remotes; degrade
  gracefully to "ref not found → main-only" (already the fallback contract).

## Prerequisite

This workflow's signal quality depends on `diff_branches` not emitting phantom `modified` entries on
overloaded symbols — fixed in the `diff_branches` overload PR. Without that fix, a build diff is
drowned in false positives and the stack-trace intersection is unreliable.
