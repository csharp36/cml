# Semantic Indexer vs. grep/find Benchmark — Design

**Date:** 2026-05-31
**Status:** Approved (design phase)
**Scope:** A defensible, automated benchmark comparing two code-discovery strategies for implementing a real medium-size feature on a large codebase (hazelcast): (A) the Source Code Indexer MCP semantic tools, vs. (B) standard bash discovery (`grep`/`find`/Read). Metrics: token usage, agent turns, wall-clock, gated on objective correctness.

## Goal & Hypotheses

Quantify whether the semantic indexer makes a coding agent more *efficient* at implementing a feature on a large codebase than the default grep/find workflow.

Pre-registered hypotheses (fixed before any run executes):

- **H1 (primary):** the semantic arm reaches a passing implementation using **fewer total tokens** than the baseline arm.
- **H2:** the semantic arm uses **fewer agent turns** (tool-call count).
- **H3 (secondary, noisy):** the semantic arm has **lower wall-clock** time.
- **Co-primary — success rate:** fraction of runs that pass the correctness oracle, per arm. A method that is cheaper but fails more often is not better; token/turn/time deltas are computed over *successful* runs only, with success rate always reported alongside.
- **H0:** no difference between arms.

This is a **defensible benchmark** (replicated, variance-reported, correctness-gated, hardened tool enforcement), not a one-shot demo. It uses **one feature with many replications** (tight variance, with an explicit single-task generalization caveat — see Threats to Validity).

## Conditions (Two Forced Arms)

Everything is identical across arms except the code-discovery path, which is the independent variable.

| | Semantic (cml) arm | Baseline arm |
|---|---|---|
| Indexer MCP server | connected (`mcp__source-code-indexer__*`) | **not connected** |
| Denied tools (hook + `permissions.deny`) | `Grep`, `Glob`, `Bash(grep/egrep/fgrep/find/rg/ag/ack *)` | `mcp__*` |
| Allowed (auto-approved, no prompt) | Read, Edit, Write, Bash (build/test), `mcp__source-code-indexer__*` | Read, Edit, Write, Bash incl. `grep`/`find` |

Both arms retain Read/Edit/Write and Bash-for-build/test, so the **only** variable is *how code is located and understood*. The baseline is real Claude Code with its normal toolset minus the MCP server — no artificial handicap.

### Information parity

- One **identical** task prompt for both arms: behavioral, GitHub-issue style, with **no file paths or location hints in prose**.
- Both arms see the same test files (the oracle, applied via the test patch) and the same scoped `mvn` command that defines "done." Test files reveal some target names, but symmetrically to both arms; locating and understanding the *production* code still requires discovery.
- Same model id, same sampling settings, same base code state, same standardized session context.

### Non-interactivity & enforcement (no permission prompts)

Each run is headless (`claude -p … --output-format json`). A single `PreToolUse` hook (matcher `.*`) is the authority for every tool call:

- returns `permissionDecision: "allow"` for permitted tools → **suppresses the prompt entirely** (no "do you approve?" ever appears),
- returns `permissionDecision: "deny"` for the arm's forbidden set → hard block with a reason fed back to the model,
- **logs every call** → compliance/audit trail.

Backed by `permissions.deny` for the forbidden set as a second hard layer. **Do not use `--dangerously-skip-permissions` / `bypassPermissions`** — that voids deny rules and would break enforcement. Headless mode *denies* (does not hang) on an unhandled tool, so the hook's allow-by-default is what guarantees zero stalls. The config is validated by a `--verbose` smoke run before the batch.

> Note: exact `--permission-mode` flag naming is confirmed during harness build via the smoke run; the design relies on the well-established PreToolUse `permissionDecision` allow/deny mechanism + `permissions.deny`, which is the robust path regardless of mode-flag specifics.

## Task Construction (SWE-bench-style real-PR backtest)

The feature and its correctness oracle come from a **real merged hazelcast PR**, reverted and re-implemented.

### PR selection criteria (pre-registered, chosen before seeing any results)

1. Merged to hazelcast `master`; parent commit `C` is checkoutable.
2. **Medium size:** production diff ~50–400 LOC across ~2–8 files; exclude generated/trivial changes.
3. Ships its **own tests** that are RED at `C` and GREEN after the PR.
4. **Genuinely cross-file:** touches or depends on existing interfaces, implementations, or call sites — so `find_implementations` / `find_references` / `get_type_hierarchy` are actually useful. Not a self-contained new file.
5. Tests run in **bounded, isolated** time: a single Maven module with scoped test classes; no cluster startup, network, or known flakiness; deterministic.

### Instance decomposition

- `base = parent(P)` (commit `C`).
- `test_patch` = the PR's test-only changes.
- `gold_patch` = the PR's production changes (the reference solution, used for quality sanity-checks; never shown to the agent).
- Apply `test_patch` on `base` → oracle tests are RED. Success = a production change that turns them GREEN.

### Selected task instance (PR #4317)

Chosen by applying the criteria above to hazelcast history (22 medium prod+test single-module PRs surveyed; #4317 selected for the strongest discovery stress with a clean behavioral oracle).

- **PR:** hazelcast #4317 — *"Immutable put interceptor inputs [HZG-381]"*
- **Merge commit (full PR):** `39c14ca464`
- **`base` (parent / re-index + worktree SHA):** `b4d75e77eaa1`
- **Module:** `hazelcast` (core)
- **`gold_patch` (production — reference solution, never shown to the agent):**
  - `hazelcast/src/main/java/com/hazelcast/map/MapInterceptor.java` (Javadoc contract only)
  - `hazelcast/src/main/java/com/hazelcast/map/impl/MapServiceContextInterceptorSupport.java` (+~52 — the behavioral change: pass interceptors a value the caller can't mutate into the store)
  - `hazelcast/src/main/java/com/hazelcast/map/impl/operation/BasePutOperation.java`
- **`test_patch` (oracle):**
  - `hazelcast/src/test/java/com/hazelcast/map/InterceptorTest.java` (+~112 — primary gate, e.g. `testAfterGetModifyInputValue_noAffectToStoredValue`)
  - `hazelcast/src/test/java/com/hazelcast/map/EntryProcessorInterceptorTest.java`
  - `hazelcast/src/test/java/com/hazelcast/map/OffloadableEntryProcessorInterceptorTest.java`
- **Behavioral spec for the task prompt (no location hints):** "A `MapInterceptor` must not be able to corrupt stored map data by mutating the value passed to it. Mutations an interceptor makes to its input value must not affect the value stored in the map. Make the failing tests pass."
- **Oracle command:** `mvn -pl hazelcast -am -Dtest=InterceptorTest,EntryProcessorInterceptorTest,OffloadableEntryProcessorInterceptorTest test` (the `@QuickTest` classes boot in-JVM members; deterministic; oracle time is not counted as implementation time).
- **RED-at-base check:** without the production change, `testAfterGetModifyInputValue_noAffectToStoredValue` fails (the mutated input leaks into the store). Verified the change is cleanly separable into test-only vs production files.

### Semantic-arm index alignment

Re-index hazelcast at the exact `base` SHA (`b4d75e77eaa1`) so the semantic index matches the code state the agent edits. (~5 min, one-time per task. Assert the indexed SHA == `base` before running.) The baseline arm reads the working tree directly. The index-build cost is reported **separately as amortized infrastructure** — the product premise is that the org already maintains the index — and is not charged per run.

### Correctness oracle

A single scoped Maven invocation that compiles the affected module(s) and runs only the oracle test classes (e.g. `mvn -pl <module> -am -Dtest=<TestClasses> test`), bounded by a timeout. The **same** command is the success gate for both arms and is run post-hoc (its time is not counted as "implementation time"). Also run the affected module's pre-existing tests to catch regressions (a passing hack that breaks neighbors is not a success).

## Run Protocol

- **N = 10 runs per arm** (20 total) plus a smoke pilot (run 0).
- Fixed model id for all runs; default sampling — replication averages out stochasticity.
- **Per-run isolation:** a fresh git worktree at `base + test_patch`, a fresh session (no resumed context), standardized context. No state leaks between runs.
- **Interleave arms** (A, B, A, B, …) run back-to-back so API-latency drift affects both equally; record a timestamp per run.
- **Smoke run 0** (`--verbose`, one per arm): confirm zero prompts/stalls, hooks enforce correctly, oracle runs, tokens captured. Launch the batch only after the smoke run is clean.
- Guardrails: per-run wall-clock timeout and max-turn cap; a run that hits either is recorded as a failure/timeout.

## Measurement

- **Tokens & turns:** from the `--output-format json` result `usage` (input, output, cache-read, cache-creation tokens; `num_turns`). Report input and output separately plus a combined total.
- **Agent wall-clock:** duration of the `claude -p` invocation only. Oracle verification is timed separately and excluded from "implementation time."
- **Success:** the scoped `mvn` oracle passes on the resulting worktree (exit 0 + expected tests green), with no regression in the module's existing tests.
- **Compliance/cheat audit:** parse the transcript + hook log for forbidden-tool attempts (should be denied; counted). Any run with *successful* forbidden-tool use is invalidated and investigated.
- **Quality (secondary, optional):** diff size vs `gold_patch`; whether the change generalizes beyond the oracle. Kept light.

## Analysis

Two independent samples of 10 (runs are independent trajectories, not paired). Token distributions are skewed → use **nonparametric** methods:

- Per arm: median + IQR for tokens, turns, wall-clock.
- The arm delta + a **bootstrap 95% CI** on the median difference, plus an effect size.
- Success rate per arm with a confidence interval.
- Deltas computed over successful runs only; success rate reported as co-primary.

The hypotheses and this analysis plan are pre-registered before running.

## Harness Components (`bench/`)

- `task.md` — the chosen PR, `base` SHA, `test_patch`, `gold_patch`, the scoped oracle command.
- `settings.semantic.json`, `settings.baseline.json` — per-arm `permissions.deny`, hook wiring, and (semantic) MCP server config.
- `hooks/enforce-and-log.sh` — the `PreToolUse` allow/deny/log hook; reads the arm's forbidden set from env.
- `task-prompt.md` — the single identical behavioral task prompt.
- `run-one.sh <arm> <run-id>` — create worktree at `base + test_patch`, launch `claude -p` with the arm's settings, capture the result JSON + timing, run the oracle, append a results row.
- `run-all.sh` — smoke run, then the interleaved N-loop; aggregate into a CSV.
- `analyze.py` — nonparametric stats, bootstrap CIs, success rates, plots, and the final report.
- `results/` — per-run result JSON + transcripts, the aggregate CSV, and the report.

## Threats to Validity & Mitigations

| Threat | Mitigation |
|---|---|
| LLM stochasticity | N=10/arm; nonparametric stats; variance reported |
| API-latency confound on *time* | interleave arms back-to-back; tokens/turns primary, time secondary |
| Index/code mismatch | re-index at the exact `base` SHA; assert index SHA == `base` |
| Unfair prompt / information leakage | identical behavioral prompt, no location hints; symmetric test visibility |
| Forbidden-tool leakage | hard `deny` + hook + transcript audit; invalidate any violating run |
| Task cherry-picking bias | pre-registered selection criteria; PR chosen before results |
| Single-task generalization | **explicitly stated:** result holds *for this feature*, not as a general law — the accepted tradeoff for tight variance |
| Weak oracle (a passing hack) | also run the module's existing tests (no regressions); report diff-vs-`gold` as a sanity check |
| Setup-cost asymmetry | index-build cost reported separately as amortized infra, with the premise stated |
| "Standard Claude" fidelity | baseline is real Claude Code, only the MCP server removed — no artificial handicap |
| Model/version drift | pin the model id; run all 20 in one sitting; record tool/model versions |

## Out of Scope

- Multi-task generalization (a benchmark *suite*) — a possible later expansion; this design is one feature ×10.
- A free-choice "both tools available" control arm — could be added later; the two forced arms are the core comparison.
- Comparing models or model versions — the model is held fixed.
- Measuring the indexer's own indexing throughput — already benchmarked separately (~5 min for hazelcast).
