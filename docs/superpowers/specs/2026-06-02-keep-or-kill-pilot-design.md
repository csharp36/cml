# Keep-or-Kill Pilot — Does the Semantic Index Shine Anywhere grep Can't Reach?

*Design doc — 2026-06-02. Deliverable of a kill-or-keep brainstorming session for the
Source Code Indexer MCP server ("cml"). This document is both the decision and the
instrument that finalizes it.*

---

## Context

Two controlled experiments have now compared the semantic code index against plain
`grep`/`find` for Claude Code:

1. **Implementation benchmark** (`2026-06-01-semantic-vs-grep-experiment-findings.md`) —
   one real Hazelcast PR, end-to-end feature build. The index arm cost ~6× the grep
   baseline after confounds were removed. Key finding: *we measured the wrong phase* —
   both arms found the code instantly; the cost lived in the implement-and-debug loop,
   where a code index does nothing.
2. **Discovery benchmark** (`2026-06-02-discovery-benchmark-results.md`) — 22 Hazelcast
   PRs, locate-the-change-surface only. Accuracy was a tie (file-F1 split 8–8); grep cost
   ~50% less. Honest synthesis: the index is one signal to use *alongside* grep, not a
   replacement.

Both experiments share a structural feature that this document treats as the central
unexamined variable: **they fought on grep's home court.** In both, the entire codebase
sat on local disk, one repo at a time, at or near a single commit. That is precisely the
arena where grep is unbeatable — local, free, instant, zero round-trips, and Claude is
world-class at wielding it. The index's genuine differentiators — the queries grep
*structurally cannot answer* rather than the ones it answers more slowly — were never the
battlefield:

- Code that **isn't on local disk** (multi-repo, monorepo too big to clone, sparse checkout)
- **Refs nobody has checked out** (a production release tag/SHA, N branches at once)
- **Type-resolved semantics** (real implementers across an inheritance chain, transitive
  callers) where grep sees text, not types
- **Cross-repo blast radius** — "I change this API in repo A; who in B/C/D breaks?" — which
  a single checkout literally cannot see

## The decision and its bar

This is a **content / learning engine**, not a product bid. Its primary output is the
Medium series, the rigor practice, and what gets learned building it. Therefore "keep"
needs exactly one thing: **one more honest, genuinely interesting experiment.** A win for
the index is a bonus, not a requirement.

**Verdict: Conditional KEEP — for exactly one more cycle, structured as a three-arena
pilot that is itself the kill/keep instrument.**

Reasoning, stated against the thesis rather than for it:

- **Why not an outright keep.** There is no honest promise that the index *wins* anywhere.
  Even in its theoretical home turf (type resolution) the real competitor is an LSP/IDE
  that is free and local, so "beats grep" is a low bar a skeptic waves away.
- **Why not a kill.** On the content bar, "keep" only needs one more interesting
  experiment, and there is a structurally guaranteed-interesting one available: the
  trilogy capstone that finally tests the arenas grep was never allowed to lose in. The
  prior two experiments *explicitly deferred* this ("the index's claimed strength was
  never exercised"). It is the one genuinely unfinished thread in the series.
- **The hinge.** The pilot is cheap and self-terminating. Signal → "keep, here's the
  capstone." No signal anywhere → "kill, here's the honest eulogy." Both are publishable,
  so the content bet cannot be lost by running it.

The single condition that flips this to immediate KILL: **loss of interest in writing the
series.** The entire "keep" rests on the content bar; with no audience there is no reason.

## The trilogy arc

- Article 1 — *"index loses; we measured the wrong phase."*
- Article 2 — *"discovery in isolation; still a tie, grep's cheaper."*
- Article 3 — the capstone — *"we finally fought on ground grep can't reach."*

This arc pays off **whether the index wins or loses**, provided Article 3 is honest.

## Guiding rule for the pilot

The pilot must "let the data pick" — it does not pre-commit to a frame. It runs cheap
probes across three candidate arenas, and the strongest honest result decides both the
keep/kill call and Article 3's framing. Every arena is designed to emit a binary
**signal / no-signal**, and every arena guards against the construction-bias trap the
discovery benchmark already caught: **ground truth must never be derived from the index's
own data.**

---

## Arena 0 — Re-slice the data you already have (free; run first)

- **Hypothesis.** The index already won the *structural* PRs and lost the *lexical* ones;
  the average washed it out. The discovery writeup says exactly this ("decisively won the
  two metrics PRs… grep won the lexical ones").
- **Method.** Zero new runs. Take the existing 44 discovery rows
  (`bench/results/discovery-results.csv`), blind-label each of the 22 tasks as **lexical**
  (names a concrete token to grep for) vs **structural** (surface tied by
  callers/implementers/registrations, no shared string), then recompute file-F1 and cost
  *per stratum*.
- **Oracle.** Unchanged — the real PR diffs. Neutral by construction.
- **Signal.** The index wins the structural stratum on accuracy *and* the gap is larger
  than the lexical-stratum gap.
- **No-signal.** The strata look the same → the "structure" story was noise.
- **Why first.** It is an afternoon of re-analysis that either hands over a real finding
  for free or kills the most plausible fair-fight hypothesis before any spend on Arena A.

## Arena A — Type resolution: the fair fight (grep competes, loses on accuracy not cost)

- **Hypothesis.** On "list every concrete implementer of interface X / the full subtype
  tree of Y / who actually calls Z," grep *produces an answer* but is structurally wrong:
  it misses indirect implementers (`class C extends AbstractX implements X`), generics,
  overrides, and import-aliased calls, and false-positives on comments/strings. This is
  the one arena where grep is a live competitor yet structurally capped.
- **Task shape.** ~10 type-resolution Q&A items on Hazelcast. No editing. Both arms
  answer; the grep arm uses grep/find, the index arm uses `find_implementations`,
  `get_type_hierarchy`, `get_symbol_references`.
- **Oracle (critical).** **Not** the index's own SCIP. Use an *independent* resolver —
  IntelliJ "Find Implementations / Call Hierarchy" export, or `scip-java` built by a
  separate toolchain — hand-verified on a sample. This is what keeps the arena honest.
- **Metric.** Precision / recall / F1 of resolved symbols, plus cost.
- **Signal.** The index shows materially higher recall (catches the indirect cases grep
  provably cannot) at comparable-or-lower cost.
- **No-signal.** Grep's textual answer is "good enough" that F1 ties → concede that for
  navigation, types do not pay.
- **Mandatory honesty footnote.** An LSP also wins here and is free/local. The index's
  *only* defensible edge over an LSP is Arena B. The article must name this or a skeptic
  will.

## Arena B — The grep-blind arena (asymmetric: capability demo, not a scored head-to-head)

- **Hypothesis.** There is a real, common question class where grep *and even a local LSP*
  are blind because the code is not on disk: **multi-repo blast radius** ("change API M in
  repo A — who in repos B…N breaks?") and **uncloned-ref debugging** ("trace this bug in
  release tag `v2.3.1` without checking it out" — the any-ref overlay whose backward-diff
  bug the discovery benchmark surfaced; a deliberate callback).
- **Method.** Not a fair head-to-head — that is the point. Frame it as a capability
  vignette: pose the task, show what the grep arm must do to even attempt it (clone N
  repos, scan, still get textual errors), show the index answering directly, and report
  the asymmetry up front.
- **Signal — the strict bar that decides keep/kill here.** The question class must be
  (1) **real** — something a developer actually asks, anchored to a true cross-repo or
  release scenario — and (2) **not better served by an existing deployed tool**
  (Sourcegraph, GitHub code search). If the honest answer to "why not Sourcegraph?" is
  only "I built mine myself," that is **no-signal** — a portfolio fact, not a finding.
- **Why it belongs despite being asymmetric.** The "let the data pick" choice makes this
  arena's data qualitative: *does a grep-AND-LSP-blind question class survive the "why not
  the incumbent?" test?* That is a real result either way.

---

## The decision rule the pilot feeds

**KEEP** (→ build the full Article 3 around the winning arena) if **any** of:

- Arena 0 structural-stratum win is robust;
- Arena A shows real recall advantage vs a neutral oracle;
- Arena B surfaces a grep-AND-LSP-blind question class that beats the "why not
  Sourcegraph?" test.

**KILL gracefully** (→ Article 3 = the honest eulogy + salvage) if all three come back
no-signal. Salvage candidates: the hermetic-benchmark harness, the rigor lessons
(environment pinning, flaky-test traps, neutral oracles), and the any-ref overlay as a
standalone capability.

**Total spend before the decision:** one free re-analysis (Arena 0) + ~10 cheap Q&A
runs × 2 arms (Arena A) + a handful of unscored vignettes (Arena B). The kill-or-keep is
settled for a few dollars and an afternoon or two.

## Success criteria for this pilot

1. Arena 0 re-analysis is complete and its strata verdict is recorded.
2. Arena A produces F1 + cost numbers against a **non-index** oracle for ~10 items.
3. Arena B produces a written verdict on each candidate question class against the
   "real + not-better-served-by-an-incumbent" bar.
4. A single keep/kill decision is recorded, with the winning arena (if keep) or the
   salvage list (if kill).

## Out of scope

- Any change to the indexer's production code (except a fix if the pilot surfaces a bug,
  per the precedent set by the discovery benchmark).
- A full N≈22 Article-3 benchmark — that is the *next* cycle, gated on a KEEP verdict.
- Product/positioning work — the bar here is content/learning, not a deployment bid.
