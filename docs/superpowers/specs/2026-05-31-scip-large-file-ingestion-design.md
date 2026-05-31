# Large SCIP File Ingestion — Design

**Date:** 2026-05-31
**Status:** Approved (design phase)
**Scope:** SCIP ingestion at scale. Compile-time / build-infra concerns are explicitly out of scope.

## Problem

SCIP protobuf uploads are capped at 50 MB (`HttpServer.maxRequestSize` = `52_428_800`, plus an app-level guard in `ScipService`). A SCIP index generated for `hazelcast/hazelcast` is **484 MB** — ~9.7× the cap — so large real-world repositories cannot upload type-resolution data at all.

We need an architecture that ingests large SCIP indexes without:
- raising the per-request memory footprint (today's parse is fully buffered: raw bytes + protobuf object graph + row lists, ~3 overlapping full copies),
- accepting a single multi-hundred-MB HTTP request (hostile to enterprise proxies/load balancers/timeouts, and non-resumable).

**Design ceiling:** ~1 GB SCIP index (484 MB is roughly today's worst case; design for ~1 GB with headroom). This keeps the machinery light — a handful of chunks per upload (~10–20 parts of ≤45 MB), not heavy resumable-multipart infrastructure.

## Key Insight: SCIP Splits Natively

A SCIP file is a single protobuf `Index` message = `metadata` (field 1) + `repeated Document documents` (field 2) + `repeated SymbolInformation external_symbols` (field 3).

Protobuf's wire format guarantees that **concatenating two serialized messages merges them** — repeated fields append, singular message fields (`metadata`) merge. Therefore an index can be sliced at Document boundaries into N smaller files where **each chunk is itself a fully-valid SCIP `Index`** (`metadata` + a subset of documents).

Consequences:
- The server parses each chunk independently with the **existing `ScipParser`, untouched**.
- No chunk exceeds the 50 MB cap; the server never holds the full index in memory.
- This is strictly better than dumb byte-splitting (which would force server-side reassembly + a full multi-hundred-MB parse).

This reframes the problem from "accept huge files" to "accept **many small valid sub-indexes that belong to one logical upload**."

## Chosen Approach: Client-Side Native Split + Server-Side Staged Session

Rejected alternatives:
- **Raise the limit + server-side streaming parse (no client tool):** zero client change, but a single ~1 GB HTTP request is hostile in enterprise networks, has no resumability (drop at 90% = restart), and all-or-nothing atomicity requires a ~1 GB transaction holding locks for the whole upload.
- **Dumb byte-chunking + server reassembly:** trivial client, but reintroduces exactly the memory/parse blow-up we engineered away — the server must buffer + parse the full gigabyte — and needs temp blob storage.

The chosen approach keeps memory bounded by one part, gives clean all-or-nothing atomicity, is resumable/idempotent, is multi-instance safe, and leverages SCIP's designed-in splittability.

## Architecture & Flow

```
CI: scip-java emits index.scip  (e.g. 484 MB)
  └─ scip-upload.sh: size check
       <= threshold ──▶ POST /api/scip/{repo}            (existing single-shot path, unchanged)
       >  threshold ──▶ scip-split → part-0001..N.scip   (each a valid sub-index <=45 MB)
                         POST   /api/scip/{repo}/uploads               → uploadId
                         POST   .../uploads/{id}/parts/{n}   (xN)
                         POST   .../uploads/{id}/complete
Server:
  init     → create session row (status=open, synthetic staging key)
  part n   → ScipParser.parse(subIndex) → insert rows under upload_sha = stagingKey + ledger row   (1 tx)
  complete → tx: delete live SHA rows; UPDATE staging rows → real SHA; update repo; session=completed
  reaper   → delete abandoned open sessions + their staging rows past TTL
```

Peak memory per request stays ≤45 MB — identical to today.

## Tool Packaging Decision

The splitter ships **in this repo (`cml`)**, co-versioned with the server — not a separate repo.

Rationale:
- The splitter is coupled to *our* upload protocol (valid-sub-index requirement, part-size threshold, session/part endpoints + headers), all of which evolve together. Things that co-evolve should co-version.
- A separate repo only earns its keep if external parties consume the splitter independently of this server — they don't; the chunk contract is private to this system.
- Reuse beats rewrite: we already compile `scip.proto` into `com.sourcegraph.scip.Scip` and depend on `protobuf-java`. A JVM splitter reuses that generated class for zero proto-drift risk. A standalone Python/Go tool would re-derive the proto as a second source of truth.

Concretely:
- Implement as a CLI sub-command (e.g. `java -jar indexer.jar scip-split <file> --max-bytes 47185920 --out <dir>`) — a mode of the existing jar or a thin sibling Gradle module.
- `scripts/scip-upload.sh` orchestrates: detect oversize → call splitter → POST parts through the session lifecycle. The script stays the portable CI entrypoint.
- Distribute a prebuilt artifact (pinned release asset / fat jar; optional GraalVM native-image binary later) so CI doesn't clone or build the server.

Tradeoff: a JVM splitter wants a JVM in CI. In practice the large indexes that need splitting are overwhelmingly compiled-language (Java) repos whose CI already has a JVM; for the rest, ship a native-image binary or container. We do not add a second language to the project to dodge a distribution-layer problem.

## Data Model

Two new tables (additive Flyway migration, e.g. `V8__scip_upload_sessions.sql`):

**`scip_upload_sessions`**
- `id` (uuid = uploadId)
- `repo_id`
- `target_sha` — the real `X-Git-SHA`
- `staging_sha` — synthetic key `__staging__:{uploadId}`
- `status` — `open` | `completing` | `completed` | `aborted`
- `expected_parts` (nullable; client may declare total via `X-Scip-Parts`)
- `created_at`, `updated_at`

**`scip_upload_parts`** — the idempotency ledger
- `(session_id, part_number)` PK
- `byte_size`, `symbol_count`, `received_at`

**Staging reuses the existing `scip_symbols` / `scip_relationships` tables** — no schema change to them. Staging rows are written with `upload_sha = staging_sha`. The `__staging__:` prefix cannot collide with real (hex) SHAs.

On `complete`:
```sql
DELETE FROM scip_symbols WHERE repo_id = :r AND upload_sha = :targetSha;          -- old data for this SHA
UPDATE scip_symbols SET upload_sha = :targetSha WHERE upload_sha = :stagingSha;   -- promote, atomic
-- same for scip_relationships
```
Old target rows are deleted before the promote-`UPDATE` in the same transaction, so the `(repo_id, upload_sha, scip_symbol)` unique constraint never conflicts.

**Prune safety:** `ScipPruneTask` adds `AND upload_sha NOT LIKE '__staging__:%'` so it never touches staging rows — the session reaper owns those.

## Splitter Contract

CLI: `scip-split <input.scip> --max-bytes <N> --out <dir>` → emits `part-0001.scip … part-NNNN.scip` and prints a JSON manifest (part count, sizes) to stdout.

Algorithm (single streaming pass, no full materialization):
1. Walk the `Index` with `CodedInputStream` at the wire level.
2. Capture `metadata` (field 1) raw bytes — re-emitted verbatim into **every** part (cheap; metadata is tiny).
3. `external_symbols` (field 3) — the server currently ignores these; preserve them in part 1 for forward-compat.
4. `documents` (field 2, length-delimited): slice each document's raw bytes without parsing into objects; pack into the current bucket; when adding the next doc would exceed `--max-bytes`, flush the bucket as a part = `[metadata bytes][concatenated document entries]`.
5. Each part is a valid `Index` by protobuf merge/concatenation semantics.

**Default threshold 45 MB** — headroom under the 50 MB server cap for framing + replicated metadata.

**Known limitation:** a *single* document larger than `--max-bytes` (one enormous generated file) cannot be split further — the splitter **fails loudly** with a clear message (`document X exceeds max part size; raise --max-bytes`). At a 1 GB ceiling / 45 MB parts this is extraordinarily unlikely.

## Upload Protocol (HTTP)

All endpoints under existing auth (Bearer api-key with `scipUpload`, repo name in URL).

| Endpoint | Behavior |
|---|---|
| `POST /api/scip/{repo}/uploads` (hdr `X-Git-SHA`, opt `X-Scip-Parts`) | Validate repo + perm. Create session. → `201 {uploadId, stagingKey}` |
| `POST .../uploads/{id}/parts/{n}` (body: sub-index ≤50 MB) | Parse + insert staging rows + ledger row, **one tx**. **Idempotent**: if part `n` already in ledger, no-op `200`. → `200 {part, symbols, relationships}` |
| `POST .../uploads/{id}/complete` | Run the file-overlap (422) check on staged docs; tx: delete old SHA rows, promote staging→real SHA, update `repositories.scip_sha`, mark `completed`. Idempotent if already completed. → `200 {repo, sha, symbols, relationships, parts}` |
| `DELETE .../uploads/{id}` | Abort: delete staging rows + session. → `204` |

**Idempotency is via the ledger, not row-deletion.** A part that failed mid-insert rolled back its transaction, so it is *not* in the ledger and retry re-inserts cleanly; a part that succeeded is in the ledger and retry is a no-op. `complete` takes `SELECT … FOR UPDATE` on the session row to serialize across instances.

The existing single-shot `POST /api/scip/{repo}` stays **as-is** (the degenerate 1-part case), sharing the parser + insert code.

## Failure Handling

- **Client dies mid-upload:** session stays `open`; staging rows are invisible (queries only read live SHAs). Reaper deletes open sessions older than TTL (default 24 h) + their staging rows.
- **Server restart mid-upload:** all state in PG; client resumes by re-POSTing remaining parts (idempotent) then `complete`.
- **Server restart mid-complete:** `complete` is one transaction → committed (done) or rolled back (staging intact, retry). Idempotent.
- **Concurrent re-index of same SHA:** each session has its own staging key; last `complete` wins (SHA is immutable, so the data is identical anyway).
- **Multi-instance:** parts may hit different instances — all state in PG, ledger upsert handles races, `complete` serialized via the session row lock.
- **Oversize part:** rejected by `maxRequestSize` (only happens on a splitter bug or an oversize single document).

## Backward Compatibility

- Existing single-shot endpoint and existing CI flows are unchanged.
- `scip-upload.sh` only *adds* the split path when a file exceeds threshold; small files keep the single-shot flow.
- The new Flyway migration is additive.

## Testing

- **Splitter unit:** split a multi-doc fixture → every part parses as a valid `Index`; the union of documents == the original; each part ≤ threshold; oversize-single-document fails loudly.
- **Session DAO unit:** init/part/complete/abort; idempotent part re-upload; prune excludes staging keys.
- **Integration (Testcontainers PG):** full multipart flow → resulting live rows **identical** to a single-shot ingest of the same index; interrupted session invisible to queries; reaper cleans abandoned sessions; mid-complete failure leaves old data intact.
- **E2E:** `scip-upload.sh` against a running server with an oversize fixture.

## Out of Scope

- Compile-time / build-infra strategy for large codebases (separate concern; SCIP is produced in CI before it reaches the server).
- Multi-GB / unbounded SCIP indexes and heavy resumable-multipart infrastructure (revisit if the ~1 GB ceiling proves too low).
