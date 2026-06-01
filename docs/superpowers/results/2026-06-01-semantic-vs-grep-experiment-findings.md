# Does a semantic code index make Claude Code a better engineer? A controlled experiment

*Findings writeup — 2026-06-01. Source material for a Medium article. Single-task,
small-n; every number below is reproducible from `bench/` on the
`design/semantic-vs-grep-benchmark` branch.*

---

## TL;DR

We built a PostgreSQL-backed semantic code index (exposed to Claude Code as MCP
tools) and asked a simple question: **does it make Claude implement a real feature
faster and cheaper than plain `grep`/`find`?** We ran a head-to-head, SWE-bench-style
experiment on a real pull request in [**Hazelcast**](https://github.com/hazelcast/hazelcast)
— a mature, open-source Java distributed-computing platform of **~2.0M lines across
~11,200 Java files, 40 Maven modules, and ~9,000 classes.** We chose it deliberately:
it's large enough that "which file do I touch?" is a genuine question (not something you
hold in your head), it's the kind of enterprise JVM codebase this index is built for, and
as active open source it offers real merged PRs to use as neutral ground truth. (For
scale, our index parsed 12,065 files and 165,162 symbols from it.)

The first valid result was the opposite of our hypothesis — the semantic arm cost
**13× more** than the grep baseline. Instead of publishing that, we treated it as a
bug in the experiment and diagnosed it. Two confounds explained most of the gap
(a flaky test the agent chased into a phantom rabbit hole, and an over-broad tool ban
that handicapped the index arm). After fixing them, the gap shrank to ~6× — but the
baseline still won.

The real lesson wasn't "the index is slower." It was that **we measured the wrong
phase of work.** Both arms located the code almost instantly; the entire cost
difference lived in the *implement-and-debug* loop, where a code index offers nothing.
The index's value proposition — "tell me *which file to read* in a large, unfamiliar
codebase" — was never under test. That insight is now driving a redesigned,
discovery-focused benchmark.

---

## 1. The hypothesis

> A semantic code index that answers structural questions ("where is this symbol
> defined, what calls it, what implements this interface, what's the type hierarchy")
> lets an LLM coding agent complete real engineering tasks with **fewer tokens, fewer
> turns, less wall-clock time, and lower cost** than an agent restricted to standard
> shell discovery (`grep`/`find`/`ls`).

Primary metric: **tokens** (input + output + cache). Secondary: agent turns,
wall-clock, USD cost. Every run is **gated on correctness** — the task's own unit
tests must pass — so we only compare *successful* completions.

## 2. Experimental design

**Task (the "instance").** One real, merged Hazelcast PR: **#4317, "Immutable put
interceptor inputs."** A map interceptor must not be able to mutate the value that a
*subsequent* interceptor in the chain receives.

- Base (parent) commit the agent starts from: `b4d75e77eaa1`
- Gold merge commit (the human solution): `39c14ca464`
- Oracle tests (the grader): `InterceptorTest`, `EntryProcessorInterceptorTest`,
  `OffloadableEntryProcessorInterceptorTest`
- The agent is given the PR's *test patch* (the new/updated tests, which fail at base)
  and the PR description, and must make the tests pass. It never sees the gold source
  patch.

**Two arms, identical except for discovery tooling:**

| Arm | Code discovery | The index (MCP) |
|---|---|---|
| **semantic** | `grep`/`find`/`glob` **blocked** | available (17 MCP tools over the live index) |
| **baseline** | standard shell discovery | **blocked** |

**Harness.** Each run: create a fresh git worktree at base, apply the test patch,
confirm the oracle is RED, then launch headless Claude Code (`claude -p`) with the
arm's tool policy. A `PreToolUse` hook enforces the ban and writes an audit log of
every tool call (allow/deny). When the agent stops, the oracle runs; a run counts only
if tests pass. Metrics come from Claude Code's own `--output-format json` usage block.

**The index under test** is this project: a Java MCP server that clones repos, parses
them with Tree-sitter (+ SCIP for type resolution), stores symbols/imports/relationships
in PostgreSQL, and serves token-efficient structural queries. For the experiment it ran
locally against a Hazelcast clone pinned at the base commit.

## 3. Methodology matters more than we expected

Two methodological problems nearly invalidated the experiment before it produced a
single trustworthy number. Both are worth their own paragraphs because they generalize
to *any* agent benchmark.

### 3a. The benchmark must be hermetic

Headless Claude Code inherits the **operator's personal environment** — globally
installed skills/plugins and `SessionStart` hooks. Our laptop happened to have
**Superpowers** (Anthropic's official plugin suite, which mandates test-driven
development, brainstorming, and subagent-driven workflows) and a second workflow
framework (GSD) installed. Both fired
their `SessionStart` hooks and exposed their skills to *both* arms — silently injecting
a whole working methodology ("dispatch a subagent," "write the test first," "plan before
you code") into what was supposed to be a stock agent. To be clear, these are good tools
working exactly as designed; they simply have no business inside a benchmark's control
set. The effect was not subtle:

| (polluted env) | turns | wall | cost |
|---|---|---|---|
| semantic | 12 | 12.5 min | $7.55 |
| baseline | **59** | 18.6 min | $4.33 |

The baseline's 59 turns weren't "the task" — they were a plugin orchestrating subagents.
The semantic arm's *12* turns were even more misleading: its plugin hid most of the real
work inside subagents, whose iterations don't count as parent turns. **The numbers were
artifacts of the operator's laptop, not the task.**

The fix: run each arm in a hermetic Claude Code — `--setting-sources project,local`
(drop the global user settings/hooks) and `--disable-slash-commands` (no skills) —
while *preserving* OAuth/keychain auth (we specifically avoided `--bare`, which forces
an API key). The arm's own enforcement hook still loads via `--settings`, and MCP is
locked down with `--strict-mcp-config`. Only after this did the runs become reproducible.

**Lesson:** an agent benchmark that doesn't pin the agent's entire configuration is
measuring the benchmarker's machine.

### 3b. Flaky tests poison agent benchmarks

The Hazelcast interceptor suite has an intermittently-flaky test under parallel load
(`chainOfPutInterceptor…usingTransactions[OBJECT]`, ~1 flake per 83-test run). A human
shrugs at a flake. An **agent treats a red test as a signal to act** — and chases it.
(More on the damage below.) We hardened the oracle and the agent's own test runs to be
deterministic (`-DforkCount=1 -DreuseForks=true -Dsurefire.rerunFailingTestsCount=2`,
applied to both arms via `MAVEN_ARGS`).

**Lesson:** flakiness that's a nuisance for humans is an active trap for agents.

## 4. Result, take 1: the index lost by 13×

First hermetic, isolated run (n=1 per arm). Both arms **passed**.

| metric | semantic | baseline | ratio |
|---|---|---|---|
| **cost** | **$15.12** | **$1.13** | baseline 13× cheaper |
| turns | 116 | 19 | 6× fewer |
| wall-clock | 63 min | 8 min | 8× faster |
| billable tokens | 15.2M | 1.1M | 14× fewer |
| maven test cycles | 15 | 5 | — |
| code edits | 18 | **1** | — |

This is the opposite of the hypothesis, and by a wide margin. The disciplined move is
**not** to publish "semantic indexing makes Claude 13× worse." It's to ask *why* — and
to distrust a single surprising data point until its mechanism is understood.

## 5. Diagnosis: reading the transcripts

We read both full session transcripts (the semantic one was 116 turns / 1.4 MB) and
attributed the gap. The headline: **the index's discovery worked fine.** Early on, the
semantic agent's MCP queries surfaced the right files, and a sub-investigation it ran
even named the exact one-line bug the baseline ultimately fixed. Discovery was never
the problem. The 13× came from three things, none of them "the index is slow at finding
code":

1. **A phantom-race rabbit hole (~45%).** The flaky test failed once; the semantic agent
   interpreted the flake as a real concurrency bug, invented a non-existent race
   condition, added print instrumentation, and even built a `call()`-override refactor
   to "fix" it. (It later confirmed via repeated runs that the failure was flaky.) This
   is variance amplified by 3b — a different run might not hit it.

2. **An over-engineered fix (~40%).** Biased by its MCP-first exploration of the service
   layer, the semantic agent committed early to a serialization-deep-copy theory and
   shipped **119 insertions across 3 files**. The baseline grepped to the call site, read
   the surrounding code, understood it, and shipped the **real one-liner**
   (`dataValue` → `value`). *Both pass the grader* — but they are different fixes, and
   the larger one cost far more to reach.

3. **An over-broad tool ban (~10–15%).** Our hook banned `grep` *entirely* in the
   semantic arm — including piping the agent's own `maven` output through `grep` to read
   test results. The agent wrote ~16 `python3` one-liners to parse logs that the baseline
   filtered with a single `grep`. Pure tax, unrelated to the hypothesis.

The crucial realization: **two of these three are confounds in our harness, not
properties of the index.** A code index neither helps nor hurts in the implement-and-debug
loop; our experiment just happened to let that loop dominate — and let a flaky test and a
clumsy tool ban distort it.

## 6. Result, take 2: confounds removed

We made two changes and re-ran:

- **Hybrid grep policy.** This matches the index's *actual* value proposition — "tell me
  which file to read," used *alongside* normal tools. The semantic arm now blocks only
  *leading* code-search (`grep -r`, `find`) but **allows output filtering**
  (`cmd | grep …`). Discovery still goes through the index; reading your own test output
  doesn't.
- **Deterministic tests** (3b), so a flake grades and reads as a pass.

| metric | semantic v1 | semantic v2 | baseline v2 |
|---|---|---|---|
| pass | ✅ | ✅ | ✅ |
| cost | $15.12 | **$5.87** (−61%) | $0.93 |
| turns | 116 | 82 | 21 |
| wall | 63 min | 53 min | 6.3 min |
| billable tokens | 15.2M | 6.4M | 0.9M |

Removing the confounds cut the semantic arm's cost **61%** and the gap from **13× to
6.3×**. But the baseline still won decisively.

## 7. The real finding: we measured the wrong phase

Here's the insight that reframes everything. After the fixes, *both arms still found the
right code almost immediately.* The entire remaining 6× lived in the **implement-and-debug
loop** — writing the change, running tests, iterating. A semantic code index does nothing
for that phase. It answers "**where is the code**," and on PR #4317 that question was easy
for everyone; the change is small and findable with two greps.

So the experiment, as designed, **could only ever disadvantage the index.** It bundled a
cheap-discovery / expensive-implementation task and attributed the whole cost to the
discovery tooling. The index's claimed strength — *navigating a large, unfamiliar codebase
where "which file?" is genuinely hard* — was never exercised.

That is not a null result; it's a **design correction**, and arguably the most valuable
output of the whole exercise. The scientific method earned its keep: it caught a 13×
artifact, then a 6× framing error, before either became a published claim.

## 8. Caveats (stated plainly)

- **n = 1 per arm.** Every number here is a single observation. The direction is
  suggestive, not significant; the rabbit hole in §5 might not recur.
- **One task, one repo, one model.** PR #4317 in Hazelcast with one Claude model. No
  claim generalizes beyond it.
- **Both arms passed.** Correctness was never the differentiator here — only cost.
- The index is young; some structural queries may underperform a mature IDE index.

## 9. What's next: a discovery-isolating benchmark

The redesign tests the hypothesis the first experiment couldn't:

- **Task shape:** code-comprehension **Q&A** over an unfamiliar codebase — *no editing,
  no debug loop.* "Where is X defined? What calls Y? What implements Z? Trace the flow
  from A to B."
- **Neutral ground truth:** answers anchored in **real merged PRs/commits** (e.g., "which
  files+symbols does this feature touch") — *not* derived from our own index, which would
  make the semantic arm win by construction.
- **Two axes:** **accuracy** (precision/recall/F1 of the identified files+symbols vs the
  PR's touched set) *and* **cost** (tokens/turns). The hypothesis becomes: *the index
  yields equal-or-better accuracy at lower cost* — and, in a large codebase, may find
  things `grep` misses.

That design is being brainstormed now and will get its own spec.

## 10. Takeaways for anyone benchmarking coding agents

1. **Pin the whole environment.** Personal skills/plugins/hooks leak into headless runs
   and silently rewrite the agent's strategy. Hermeticity is not optional.
2. **Flaky tests are worse for agents than for people.** An agent acts on every red; a
   flake sends it chasing ghosts. Make the grader *and* the agent's own test runs
   deterministic.
3. **Restrict tools to match the hypothesis, not more.** Banning `grep` outright tested
   "no shell at all," not "use the index for discovery." Over-restriction is a confound.
4. **Measure the phase your tool targets.** A discovery tool must be judged on discovery.
   If the task's cost is dominated by implementation, discovery tooling can't move the
   needle — and a naive benchmark will blame it anyway.
5. **Distrust a surprising result; diagnose it.** Our most useful output came from
   *refusing* to publish the first 13× number and reading the transcripts instead.

---

### Appendix: reproducibility

- Branch: `design/semantic-vs-grep-benchmark`; harness in `bench/`.
- Per-run driver: `bench/run-one.sh <arm> <id>`; batch: `bench/run-all.sh N`;
  analysis: `bench/analyze.py`.
- Raw rows: `bench/results/results-isolated-v1-prefix.csv` (take 1) and
  `bench/results/results.csv` (take 2). Audit logs and full transcripts retained per run.
- Enforcement + audit: `bench/hooks/enforce-and-log.sh` (+ `test-hook.sh`).
- Oracle: `bench/task/oracle.sh`. Design spec:
  `docs/superpowers/specs/2026-05-31-semantic-vs-grep-benchmark-design.md`.
