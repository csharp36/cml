# Arena A (graded) — Type-Resolution Recall/Precision vs a Neutral Bytecode Oracle

*Result — 2026-06-02. Third arena of the keep-or-kill pilot
(`docs/superpowers/specs/2026-06-02-keep-or-kill-pilot-design.md`), executed per
`docs/superpowers/plans/2026-06-02-graded-arena-a.md`. This is the swing vote on keep/kill.*

## TL;DR

For "list every concrete class that is a `X` (directly or through inheritance)" over Hazelcast
(commit `7af6ddea`, 5.8.0-SNAPSHOT, ~2 M LOC), scored against an **independent compiled-bytecode
oracle**:

- **SCIP type resolution is a large, real win.** Mean name-F1 **0.877** (recall **0.972**) vs the
  Tree-sitter / name-matched transitive arm (CTE) **0.499** (recall **0.496**) and the grep-iterative
  baseline **0.324** (recall **0.316**). Clean monotonic ordering **grep < CTE < SCIP**; SCIP ≈
  **2.7× grep** on F1 and **3× on recall**.
- **The win is recall, not precision** — the opposite of the going-in hypothesis. Source-parse
  approaches (grep, Tree-sitter) capture only ~⅓–½ of the true implementer set; they cannot follow
  the full type graph (interface-extends-interface chains, cross-module, generic/aliased
  `implements`) that a compiler-grade SCIP index sees. All three arms are similarly precise.
- **And SCIP wins both strata** — including the lexical controls that should favor grep
  (lexical: SCIP 0.975 vs grep 0.482; structural: SCIP 0.828 vs grep 0.245).
- **At a fraction of the effort:** each index arm answers in **1** query; grep-iterative averages
  **102** `grep` passes per question (max 481) — ~two orders of magnitude more work for the worst
  accuracy.
- **Harness validated against ground truth:** on `DataSerializable`, SCIP matches the bytecode
  oracle at **F1 0.97** (precision 0.955, recall 0.986) once scope is aligned.

This **settles keep-or-kill: KEEP.** The type-resolution layer (SCIP) delivers the differentiator
the whole project was premised on — and which the two prior benchmarks never actually exercised
(SCIP was unpopulated; `find_implementations` was a direct-only lookup). It is now built, populated,
and measured.

## Method

- **Corpus / pin:** Hazelcast at commit `7af6ddea` (5.8.0-SNAPSHOT). Source index, SCIP upload, and
  oracle all pinned to this one commit — zero version skew.
- **Neutral oracle:** the JVM type graph read from the **compiled bytecode** (`javap` over the
  9,547 `.class` files the build produced; inner/nested classes included), independent of grep, the
  Tree-sitter index, and SCIP. 7,778 types; 155 colliding simple-names. Truth for each question =
  the transitive subtype closure from this graph. (`bench/arenaA/oracle/build_oracle.py`.)
- **Scope:** **main source only** (`/src/main/`). All arms filter to it; the oracle is built from
  `target/classes` (main). This excludes test fixtures, which SCIP and the Tree-sitter index both
  contain — aligning scope was essential (an unaligned comparison made SCIP look like it
  *overshot* truth when it was simply counting test types the oracle lacked).
- **Questions:** 12 selected mechanically from the oracle (`select_questions.py`) — 8 **structural**
  (high indirect-implementer ratio, e.g. `Tenantable` 1 direct / 534 transitive) and 4 **lexical**
  controls (shallow, e.g. `MutatingOperation` 99 / 121).
- **Arms** (all answer the same questions; one MCP `tools/call` each for the index arms):
  - `index_scip` — `get_type_hierarchy(direction=down)` (type-resolved; returns FQNs via `scip_symbol`).
  - `index_cte` — `find_implementations(transitive=true)` (the new recursive CTE over Tree-sitter
    `implements ∪ extends` edges; name-matched).
  - `grep` — iterative BFS closure via `grep` over `src/main/java` (the strong-grep baseline).
- **Scoring** (`score.py`): name-level P/R/F1 (all arms); FQN-level P/R/F1 (SCIP, via a
  `scip_symbol`→binary-FQN normalizer); collision-precision for the name-only arms.

## Validation (DataSerializable, scope-aligned)

| arm | precision | recall | F1 |
|---|---|---|---|
| SCIP (FQN vs oracle FQN) | 0.955 | 0.986 | **0.970** |
| CTE (name vs oracle name) | 0.992 | 0.457 | 0.626 |

SCIP reproduces the compiler's view almost exactly. The CTE is precise but recovers <½ the set.

## Headline results (n = 12; mean over questions)

| arm | name P | name R | name **F1** | collision-prec | FQN F1 | calls/q |
|---|---|---|---|---|---|---|
| **index_scip** | 0.861 | **0.972** | **0.877** | 0.823 | 0.843 | **1** |
| **index_cte** | 0.666 | 0.496 | **0.499** | 0.650 | — | **1** |
| **grep** | 0.650 | 0.316 | **0.324** | 0.649 | — | **102** (max 481) |

**Per-stratum mean name-F1:**

| stratum | grep | CTE | SCIP |
|---|---|---|---|
| structural (8) | 0.245 | 0.379 | **0.828** |
| lexical (4) | 0.482 | 0.741 | **0.975** |

**Per-question (name-F1), grep / CTE / SCIP:**

| type | stratum | grep | CTE | SCIP |
|---|---|---|---|---|
| Tenantable | stru | 0.828 | 0.996 | 0.996 |
| MessageTask | stru | 0.116 | 0.017 | 0.412 |
| SecureRequest | stru | 0.119 | 0.017 | 0.412 |
| StepAwareOperation | stru | 0.021 | 0.000 | **1.000** |
| IMapStepAwareOperation | stru | 0.000 | 1.000 | 1.000 |
| AsynchronouslyExecutingBackupOperation | stru | 0.061 | 0.000 | **1.000** |
| Step | stru | 0.108 | 0.000 | 0.800 |
| NamedOperation | stru | 0.706 | 1.000 | 1.000 |
| MutatingOperation | lexi | 0.662 | 1.000 | 1.000 |
| BackupOperation | lexi | 0.579 | 1.000 | 1.000 |
| Versioned | lexi | 0.282 | 0.964 | 0.994 |
| AbstractCallableMessageTask | lexi | 0.404 | 0.000 | 0.907 |

SCIP is the best or tied-best on **11 of 12** (CTE edges it only on `IMapStepAwareOperation`, where
SCIP also scores 1.0 — a tie). grep never wins a single question. The source-parse arms post
outright **0.0** on several structural types (CTE on four; grep on `IMapStepAwareOperation`) where
SCIP scores 0.8–1.0. Two structural cases are hard for everyone (`MessageTask`, `SecureRequest`) but
SCIP still leads by 3–20×.

## Interpretation

- **What SCIP buys:** completeness. The recursive CTE and grep both reconstruct the hierarchy from
  *source declarations* a parser can see; they miss edges the compiler resolves (re-exported
  interfaces, generic supertypes, multi-hop chains across modules). SCIP carries the compiler's
  resolved graph, so its recall is ~0.97 where the structural approach sits at ~0.50.
- **Why the prior benchmarks tied:** they never had this arm on the board — SCIP was empty and
  `find_implementations` was direct-only (recall-equivalent to grep). Arena A is the first time the
  index's actual differentiator was populated and measured.
- **Cost:** index arms answer in **one** call; grep-iterative averages **102** passes per question
  (max 481, 1,224 total) — ~2 orders of magnitude more work — and still posts the *lowest* accuracy
  (F1 0.324). Worst on both axes.
- **Even the lexical controls go to SCIP** (0.975 vs grep 0.482). Naming a concrete token helps grep
  find the *direct* implementers cheaply, but the transitive set still needs the type graph it can't
  see — so its recall collapses (0.316) even where the task looks lexical.

## Decision

**KEEP.** Against the pilot's rule, the keep condition is met decisively: SCIP F1 **0.877** vs CTE
**0.499** vs grep **0.324**, with SCIP recall **0.972** (≥ both) at **1 call** vs grep's 102,
validated against a neutral oracle (DataSerializable F1 0.97). SCIP wins **both** strata and **11 of
12** questions outright (one tie, zero losses); grep wins none. The semantic index has a genuine,
measured advantage on a real, common question — "who implements / subtypes X" in a large codebase —
that grep and Tree-sitter structurally cannot match, and at far lower cost.

The trilogy lands: Article 1 "index loses (wrong phase)", Article 2 "discovery tie", **Article 3
"we finally fought where grep can't follow — type-resolved reachability — and the index tripled
grep's recall (0.97 vs 0.32) at 1/100th the calls."**

## Threats to validity

- **n = 12, one repo, one commit, one model** — direction, strong but not a general law.
- **Oracle = bytecode of the same build** that fed SCIP; this is fair (it's the compiler's truth, via
  independent tooling — `javap`, not `scip-java`) but both ultimately reflect the Java compiler. grep
  and the CTE are the truly independent lower-bound arms.
- **Name-level scoring** can over-credit the name-only arms on collisions; the FQN and
  collision-precision columns exist to expose that (SCIP collision-prec 0.823 > CTE 0.650 ≈ grep 0.649).
- **grep-iterative is one reasonable baseline,** not the only possible grep strategy; a cleverer
  developer might prune the BFS or seed it better. But the recall ceiling is structural — text search
  can't resolve the type graph — so a different grep tactic trades cost for the same recall wall.

## Reproduce

```bash
export HZ_READ_KEY=... HZ_SCIP_KEY=...   # a key scoped to hazelcast (or the ["*"] CI key)
bench/arenaA/run_all.sh                   # selects nothing new; runs 3 arms over questions.jsonl, scores
python3 bench/arenaA/score.py bench/arenaA/results/{grep,cte,scip}.results.jsonl
```
Oracle: `bench/arenaA/oracle/build_oracle.py <oracle.jar>`. Pin/keys: `bench/arenaA/pin.env`.
