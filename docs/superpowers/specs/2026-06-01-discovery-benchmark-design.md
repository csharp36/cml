# Discovery Benchmark — Design Spec

*Semantic code index vs. grep/find, measured on the phase the index actually targets:
**code discovery**. Companion to the implementation-task benchmark
(`2026-05-31-semantic-vs-grep-benchmark-design.md`); motivated by its findings
(`../results/2026-06-01-semantic-vs-grep-experiment-findings.md`).*

Status: **approved design, pre-implementation.** Date: 2026-06-01.

---

## 1. Why this benchmark exists

The implementation-task benchmark (PR #4317) showed the semantic index losing on
*total task cost* — but transcript diagnosis revealed the cost was dominated by the
**implement-and-debug loop**, a phase a code index cannot help. Both arms located the
relevant code almost instantly, so the index's actual value proposition — *"quickly
tell the LLM which file to read in a large, unfamiliar codebase"* — was never under
test.

This benchmark isolates **discovery**: the agent must identify *where* a change would
go, with **no editing and no test loop**. It directly tests the hypothesis the first
experiment could not.

### Hypothesis

> On a large, unfamiliar codebase, an agent equipped with the semantic index identifies
> the correct change surface (files + symbols) for a described task with **equal-or-better
> accuracy at lower cost** (tokens / turns / wall-clock) than an agent restricted to
> `grep`/`find`. In particular, on changes spread across the codebase, the index's
> completeness should beat grep's.

## 2. Decisions (locked)

| Dimension | Decision |
|---|---|
| Task shape | Code-comprehension Q&A — "where would you implement this?" (no edits, no tests) |
| Ground truth | Git/PR-derived: non-test source files + symbols touched by the gold patch |
| Metrics | **Accuracy** (file & symbol P/R/F1) **and** cost (tokens/turns/wall) |
| Task set | Semi-auto-sampled **~20–30** Hazelcast PRs (≥2 non-test source files, size-bounded, recent) |
| Sessions | **One instance per fresh session, per arm** (~40–60 short sessions) |
| Code state | **Per-PR base sha** `Bᵢ` — agent sees code *before* the change ("where would you add it") |
| Index serving | Semantic arm queries the index at `branch=Bᵢ` via the **any-ref overlay** |
| Grading | **Hybrid**: deterministic file/symbol F1 backbone + independent LLM judge for partial credit / near-misses |
| Architecture | **Extend `bench/`** (reuse isolation + tool-gating hook + audit + worktree core); answer via **`ANSWER.json`** |

## 3. Arms

Identical to the implementation benchmark, reusing `bench/hooks/enforce-and-log.sh` and
the hermetic-isolation flags (`--setting-sources project,local`,
`--disable-slash-commands`, `--strict-mcp-config`; OAuth preserved — no `--bare`).

| Arm | Discovery tooling | Index (MCP) |
|---|---|---|
| **semantic** | `grep`/`find`/`glob` blocked for code discovery; output-filtering (`cmd \| grep`) allowed (hybrid policy) | available, queried at `branch=Bᵢ` |
| **baseline** | standard shell discovery | blocked |

Both arms receive an identical prompt and an identical code state (`Bᵢ`). Only discovery
tooling differs.

## 4. End-to-end flow (one instance × arm)

1. **Worktree** at `Bᵢ` (parent of the PR merge), reusing `run-one.sh`'s worktree logic.
2. **Launch** headless Claude Code with the discovery prompt, arm tool policy, isolation
   flags, and the audit hook.
3. **Investigate:** semantic arm calls MCP tools with `branch=Bᵢ`; baseline greps/reads
   the worktree.
4. **Answer:** the agent writes `ANSWER.json` (§6) to the worktree. It must **not** edit
   source; any edits are ignored and the worktree is discarded.
5. **Grade** (§7) and capture cost/turns/wall from the result JSON + audit log.
6. Append a row to `bench/results/discovery-results.csv`.

## 5. Components (new, under `bench/discovery/`)

- **`sample-prs.sh`** — samples merged Hazelcast PRs into `instances.jsonl`, one object per
  instance: `{id, merge_sha, base_sha, title, body, truth_files[], truth_symbols[]}`.
  Filters: merged within a recent window (keeps any-ref overlays small); ≥2 non-test
  source files touched; total diff size bounded; every touched source file resolves at
  `base_sha`; **has a usable problem statement** (non-empty description or a linked issue
  to synthesize one from). Identification of PR/merge pairs is from `git log` on the
  Hazelcast clone. The derived task statement is **sanitized** (§9) to strip explicit
  file/symbol names that would leak the answer.
- **`extract-truth.py`** — for an instance, runs `git diff base_sha..merge_sha` and
  derives:
  - `truth_files`: non-test source files changed (excludes `*/test/*`, generated, docs).
  - `truth_symbols`: enclosing symbols from **git's diff function-context hunk headers**
    (`@@ … @@ <signature>`), a neutral source independent of our index. Class-level
    symbols parsed from the file where hunk context is method-level.
- **`task-prompt-discovery.md`** — instruction template. Gives the PR title/body as a task,
  instructs the agent to investigate and write `ANSWER.json`, and explicitly forbids
  implementing. For the semantic arm, instructs querying the index with `branch=Bᵢ`
  (the harness substitutes the sha).
- **`grade.py`** — reads `ANSWER.json` + instance truth; computes file & symbol P/R/F1;
  invokes the LLM judge; writes per-instance scores.
- **`run-discovery-one.sh`** — driver: `Usage: run-discovery-one.sh <semantic|baseline>
  <instance-id>`. Reuses the isolation/hook/audit core of `run-one.sh`; replaces
  test-patch + oracle with prompt-injection + `ANSWER.json` grading.
- **`run-discovery-all.sh`** — resets the CSV header, preflights (incl. overlay pre-warm),
  runs one smoke instance × both arms, then loops the batch, then `analyze-discovery.py`.
- **`analyze-discovery.py`** — per-arm aggregates + the accuracy×cost scatter.

## 6. Answer protocol — `ANSWER.json`

```json
{
  "files":   ["hazelcast/src/main/java/com/hazelcast/map/impl/operation/BasePutOperation.java"],
  "symbols": ["com.hazelcast.map.impl.operation.BasePutOperation#afterRunInternal"]
}
```

Repo-relative file paths; symbols as `fqcn#member` (member optional for class-level).
Written via the Write tool by either arm. Missing or malformed → recorded as a
**non-answer**: file/symbol F1 = 0 for that instance, tracked separately so per-arm
**answer-rate** is reported (a model that declines is distinct from one that answers
wrong).

## 7. Grading detail

**Deterministic backbone**
- **File F1:** precision/recall/F1 of `answer.files` vs `truth_files`, on normalized
  repo-relative paths.
- **Symbol F1:** same over `truth_symbols`, with normalization tolerant of overload
  signatures and `fqcn` vs `fqcn#member` granularity (a correct class without the exact
  method earns partial credit at file level and is adjudicated by the judge at symbol
  level).

**LLM judge (independent, pinned model + prompt)**
- For each instance, given the task, the agent's answer, and the gold surface, the judge
  awards partial credit when the agent named a *semantically correct* location that does
  not string-match the gold set (enclosing class vs exact method; a valid alternative
  implementation site).
- The judge score is **reported alongside** the deterministic F1 and **audited against it**
  on a sample of instances. It never silently overrides the deterministic numbers; both
  are published.

## 8. Metrics & analysis

Per instance × arm: `file_f1, symbol_f1, judge_score, in_tokens, out_tokens, cache_read,
cache_create, turns, wall_s, cost_usd, denied_attempts, answered`.

Per arm (over instances): median file-F1, median symbol-F1, median judge-score, median
cost/turns/wall, answer-rate, and a **scatter of accuracy (F1) vs cost** — the headline
view: *does the index reach a better answer for fewer tokens?* Report medians with IQR
and the per-instance paired deltas (same instance, semantic − baseline).

## 9. Error handling & edge cases

- **No / malformed `ANSWER.json`** → non-answer (recall 0, flagged in answer-rate).
- **Test/generated-only PRs** → excluded at sampling.
- **Overlay fault-in failure** for a `Bᵢ` → instance skipped and logged; never silently
  dropped (per the "no silent caps" principle).
- **Agent edits code anyway** → edits ignored; only `ANSWER.json` graded; worktree discarded.
- **Semantic arm omits `branch=Bᵢ`** → MCP calls audited for the ref; instances with
  wrong-ref queries are flagged (and the prompt is hardened) so we never compare an index
  answer computed against the wrong code state.
- **Answer leakage in the task statement:** a PR title/body may name the exact file or
  symbol it changes, trivializing discovery. The derived task statement is **sanitized** —
  explicit source file paths and fully-qualified symbol names from the gold surface are
  removed or generalized to the described *behavior*. (Note: any residual leakage affects
  **both arms equally**, so it never biases the comparison — sanitization only preserves
  the task's discriminating power.)
- **Fairness:** identical prompt and identical `Bᵢ` code state across arms; only discovery
  tooling differs.

## 10. Testing

- `extract-truth.py`: unit-tested on PR #4317 (known surface) + 2–3 hand-verified PRs.
- `grade.py`: unit-tested with synthetic answers (perfect / partial / wrong / empty →
  expected F1), mirroring `analyze.py`'s synthetic-test discipline.
- `enforce-and-log.sh`: existing `test-hook.sh` already covers the hybrid grep policy used
  here.
- Sampler dry-run: manually eyeball ~3 sampled instances' truth sets.
- **Smoke gate:** 1 instance × both arms before any batch, same discipline as the impl
  benchmark; gate the full batch with the operator.

## 11. Known risks

- **Any-ref overlay cost** for historical shas grows with distance from the indexed tip →
  mitigated by sampling recent PRs and pre-warming each `Bᵢ` overlay in preflight (so
  fault-in latency doesn't pollute the agent's measured wall-clock).
- **Ground-truth noise** (PRs touch incidental files) → report strict and core-file F1;
  the LLM judge absorbs reasonable alternative locations.
- **Prompt-dependence of the `branch=Bᵢ` instruction** in the semantic arm → audited and
  flagged (§9); hardened if drift appears.
- **Modest n (~20–30), one repo, one model** → stated as a caveat in any writeup, as in v1.

## 12. Out of scope (YAGNI)

- "Find all callers / implementers" questions with tool-derived ground truth (a different,
  richer benchmark — deferred).
- Multi-repo / multi-language coverage.
- Human-baseline comparison.
- Any code modification or test execution by the agent.

## 13. Reproducibility / deliverables

- New assets under `bench/discovery/`; results in `bench/results/discovery-results.csv`;
  per-instance audit logs, answers, and judge rationales retained.
- A findings writeup (sibling to the v1 results doc) once the batch completes, including
  the accuracy×cost scatter generated by `analyze-discovery.py`.
