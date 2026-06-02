# Arena A (sniff test) — Does the index's type resolution beat grep?

*Result — 2026-06-02. Second arena of the keep-or-kill pilot
(`docs/superpowers/specs/2026-06-02-keep-or-kill-pilot-design.md`), run as a quick sniff
test rather than a full benchmark. Decisive without a full run — for a reason worth
recording.*

## What I set out to test

Arena A's premise: on "list the implementers / subtype tree of type X," the index's
**type-resolved** queries catch indirect/transitive implementers that grep
(direct-text-match only) structurally cannot. Score recall against a neutral, non-index
oracle.

## What I found before running a single graded item

Two facts about the index's *current state* collapse the test:

1. **SCIP is not loaded** (`scip_symbols = 0`, `scip_relationships = 0`,
   `repositories.scip_sha` empty for hazelcast). So `get_type_hierarchy` and
   `get_symbol_references` — the actual type-resolution tools — have nothing to answer with.
   **Cause (per operator):** SCIP was `TRUNCATE`d after the discovery session, and during
   that session CI could not reach the on-demand indexer to upload it (the known
   SCIP-upload-502 issue). This is an ops/teardown artifact, **not** evidence the SCIP
   feature is broken — but it does mean neither this sniff nor the discovery benchmark
   exercised type-resolved queries.

2. **`find_implementations` is recall-equivalent to grep.** Reading the source
   (`QueryExecutor.findImplementations`), the query is a flat:
   ```sql
   WHERE tr.related_name = :typeName AND tr.kind = 'implements'
   ```
   Direct-only, `implements`-only, no recursion, `related_name` matched as an unqualified
   string. That is functionally what `grep "implements X"` does — including the same
   inability to disambiguate same-named types across packages.

## The empirical confirmation (hazelcast, indexed SHA `a6e5676`, grep on the aligned clone)

For interface `DataSerializable`:

| approach | implementers found | notes |
|---|---|---|
| shipped `find_implementations` | **89** | direct `implements` edges |
| `grep "implements DataSerializable"` | **91 files** | direct text matches |
| **full transitive closure** (recursive CTE over the *same* `type_relationships` data) | **989** | what a type-aware query could reach |

The index and grep return the **same answer** (89 ≈ 91). The real differentiator — the
989-member transitive set (everything that *is-a* `DataSerializable` through `extends`
chains and via `IdentifiedDataSerializable`) — is reached by **neither**. Yet the edges to
compute it are already in Postgres; a recursive query gets there. Second example,
`Tenantable`: shipped/grep find **1**; the achievable closure is **584**.

## Interpretation — this is the most useful result in the pilot

It mechanically explains *why both prior benchmarks tied*: **the index was competing with
grep using grep-equivalent capabilities.** Its two ways to beat grep on type questions were
both off the board —

- the type-resolved layer (SCIP) was unpopulated, and
- the structural tool (`find_implementations`) is a direct-only lookup that never walks the
  graph it sits on.

So "does the index beat grep at type resolution?" has not actually been tested by anything
yet — including this sniff. What the sniff *does* establish is sharper and more valuable
than a tie: **the differentiator exists latently (989 vs 89 reachable from data already
indexed) but is not realized by any shipped query path.**

## Verdict

- **Arena A as specified cannot run against the index in its current state** (no SCIP, and
  `find_implementations` is grep-equivalent). A graded run today would show a tie *by
  construction* and teach nothing.
- This is **not** a kill signal. It is a **"the experiment found a missing feature, not a
  missing advantage"** signal — the same flavor as the discovery benchmark surfacing the
  backward-overlay bug.
- There is now a concrete, cheap, falsifiable KEEP path (see below).

## What would make Arena A real (the keep path)

Either or both, then run the graded ~10-item recall test:

1. **Transitive `find_implementations`** — replace the flat query with a recursive CTE over
   `type_relationships` (`implements` ∪ `extends`, walked to closure). ~an afternoon; uses
   data already present; immediately gives a recall axis grep cannot follow (89→989).
2. **Populate SCIP for hazelcast** — fix/parameterize the upload path (the CI-reachability
   issue), so `get_type_hierarchy`/`get_symbol_references` answer with type-resolved data
   that also handles name collisions and generics (which the recursive-CTE approach, being
   name-matched, still cannot).

Only after (1) and/or (2) does the "type resolution beats grep" hypothesis become testable.

## Threats / caveats

- The recursive-CTE closure (989) is **name-matched**, so it will over-count where class
  names collide across packages — an upper-ish bound, not a verified true set. The neutral
  oracle for a real Arena A must come from an independent resolver (javac/IntelliJ/`scip-java`
  built separately), never from the index's own data.
- One repo, two interfaces — illustrative, not a measurement.
