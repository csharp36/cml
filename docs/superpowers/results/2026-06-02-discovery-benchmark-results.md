# Discovery Benchmark — Semantic Index vs. grep/find

**Date:** 2026-06-02
**Task type:** *locate-the-surface* — "which files and symbols would you change to implement X?"
**Corpus:** 22 merged Hazelcast PRs (≈2.0M LOC, ~40 modules)
**Arms:** `semantic` (Source Code Indexer MCP tools, grep/find blocked) vs. `baseline` (grep/find/read only, MCP blocked)
**Model:** one Claude model, both arms; **answer rate:** 100% / 100%

> Companion to the implementation-task writeup (`2026-06-01-semantic-vs-grep-experiment-findings.md`). That benchmark asked *can the agent ship the fix faster/cheaper?*; this one isolates the **discovery phase** alone — no edits, no tests, just "point at the change surface" — because the implementation benchmark's bottleneck was the debug loop, not discovery.

---

## TL;DR

For locating the change surface of a real PR in a 2M-LOC codebase, the semantic index is **not a clear win over `grep`/`find`**:

- **Accuracy: a tie.** On the mean, the two arms are within noise on every metric. Per-instance head-to-head, file-F1 splits **8–8** (6 ties) and the LLM judge actually favors `grep` **9–6** (7 ties).
- **Cost: `grep` wins clearly.** The index costs **~50% more** in total agent spend, with **higher variance** and **longer wall time**.
- Both arms score **low in absolute terms** — discovery at this scale is genuinely hard for either approach.

This matches the implementation-benchmark direction and the working mental model: **the index is one signal to use *alongside* `grep`, not a replacement for it.**

---

## The meta-finding: the benchmark found (and fixed) a real bug in the tool it was measuring

Before producing a single valid data point, building the harness surfaced two correctness problems and one product bug:

1. **Indexer bug — backward overlays returned nothing (fixed).** The any-ref overlay used a three-dot diff (`git diff base...ref`), which diffs from the *merge-base* to `ref`. When a ref is *behind* the indexed base (e.g. an old release tag/SHA — exactly the advertised "debug a production release without a checkout" use case), the merge-base **is** the ref, so the diff is empty and the copy-on-write overlay stored **zero files**. Switched to two-dot (`base..ref`); verified on real Hazelcast that refs behind base went from **0 → 42 / 1161** overlay files. *(commit `ec39a4f`)*
2. **Answer leak via git history (closed).** The agent's workspace was a `git worktree` sharing the clone's full `.git`. The agent could — and did — run `git log --all | grep <PR#>` to find the fixing merge, then `git diff <merge>^1 <merge>` to read the **exact ground truth**. Fixed by handing the agent a `git archive` export of the tree at the base SHA (no history). Confirmed leak-free in the final runs (0 git/answer-key commands in the audit logs).
3. **Grading crash (fixed).** The LLM-judge prompt template crashed on its own literal JSON example braces, which the harness then silently scored as a zero. Fixed and isolated so a judge failure can never discard the deterministic F1 scores.

That a discovery benchmark's first job was to expose a discovery bug in the indexer is, itself, a result.

---

## Method

- **Instances:** 22 two-parent merge commits sampled from Hazelcast `master`, each touching ≥2 non-test source files. **Ground truth** = the files/symbols in `git diff <base>..<merge>` (the PR's net change surface), extracted neutrally.
- **Task statement:** the PR description, **sanitized** to strip the merge subject, PR number, and branch name (all direct lookup keys). 3 sampled merges were pure plumbing ("Merge branch … into …") with no description and were dropped (25 → 22).
- **Workspace:** the source tree at each PR's base SHA via `git archive` — **no git history**, so the agent must genuinely discover.
- **Per-PR base via the indexer's any-ref overlay:** the server indexed Hazelcast once at a 2020-06 median base; each instance's base SHA was faulted in as a copy-on-write overlay (showcasing the feature the bug above had been silently breaking).
- **Grading:** deterministic file-F1 and symbol-F1 against ground truth, plus an LLM judge (0–1, awards credit for semantically valid alternative locations).
- **Both arms** ran under identical hermetic isolation (no inherited skills/hooks); the semantic arm's `grep`/`find`/`Grep`/`Glob` were blocked and the baseline arm's MCP was blocked, each enforced by a logging hook.

---

## Headline results (n = 22 per arm)

| metric | semantic (mean) | baseline (mean) | semantic (median) | baseline (median) |
|---|--:|--:|--:|--:|
| file F1 | 0.585 | 0.580 | 0.667 | 0.545 |
| symbol F1 | 0.294 | 0.258 | 0.286 | 0.223 |
| judge | 0.577 | 0.567 | 0.660 | 0.575 |
| cost ($) | 1.206 | 0.789 | 0.906 | 0.658 |
| turns | 21.3 | 15.3 | 21.0 | 14.0 |
| wall (s) | 194.6 | 141.3 | 150.4 | 110.8 |

**Per-instance head-to-head**

- file F1: semantic wins **8**, baseline wins **8**, ties **6**
- judge:  semantic wins **6**, baseline wins **9**, ties **7**

**Cost & variance**

- Total agent spend: semantic **$26.54** vs baseline **$17.37** (sum $43.90) — semantic **+53%**
- Cost σ: semantic **0.87** vs baseline **0.43**; worst-case wall: **742s** vs **444s**

The mean/median divergence is the tell: the means are tied, but `grep` has more *total misses* (0.0 scores) that drag its median down — the index is more *consistently mediocre*, not more *correct*.

---

## Per-instance detail

| PR (task) | sem file-F1 | base file-F1 | sem judge | base judge | sem $ | base $ |
|---|--:|--:|--:|--:|--:|--:|
| Fixes around SQL temporal conversions | 0.40 | 0.33 | 0.40 | 0.55 | 1.45 | 0.72 |
| Minor TcpIpEndpointManager cleanup | 0.67 | 0.67 | 0.60 | 0.60 | 0.55 | 0.36 |
| Fixes race during connection establishment | 0.00 | 0.50 | 0.10 | 0.30 | 1.10 | 1.29 |
| Fix race in (JCache) cache creation | 0.00 | 0.00 | 0.10 | 0.10 | 1.72 | 1.74 |
| Publish CP Subsystem stats via Metrics | 1.00 | 0.52 | 0.95 | 0.35 | 3.96 | 2.04 |
| Removed IOService.getSSLConfig | 0.80 | 0.80 | 0.70 | 0.70 | 0.49 | 0.33 |
| MemberHandshake Options | 0.50 | 0.57 | 0.85 | 0.85 | 0.62 | 0.78 |
| Several migration fixes | 0.67 | 0.24 | 0.62 | 0.25 | 3.41 | 0.65 |
| ProtocolType enum is removed | 0.57 | 0.75 | 0.50 | 0.55 | 0.65 | 0.58 |
| Implement IMap.entrySet() on a PartitionIdSet | 0.32 | 0.32 | 0.25 | 0.35 | 1.09 | 0.94 |
| Allow operations while node is shutting down | 0.86 | 1.00 | 0.85 | 1.00 | 0.66 | 0.41 |
| Bind properties cleanup in TcpIpAcceptor | 0.00 | 0.00 | 0.05 | 0.05 | 0.54 | 0.66 |
| Utilize existing StandardCharsets | 0.82 | 0.97 | 0.82 | 0.88 | 1.29 | 1.07 |
| Rewrite anti-entropy task sending | 0.67 | 0.67 | 0.40 | 0.40 | 0.58 | 0.66 |
| Removed ChannelInitializerProvider | 0.80 | 0.88 | 0.70 | 0.82 | 0.91 | 0.54 |
| Multiple TCP connections between members | 0.80 | 0.95 | 0.82 | 0.88 | 1.53 | 1.14 |
| added executor creation time metric | 1.00 | 0.50 | 1.00 | 0.35 | 0.91 | 0.50 |
| custom load balancer declarative config | 0.67 | 0.46 | 0.72 | 0.62 | 0.61 | 0.65 |
| Remove typos in ConfigUtils methods | 0.40 | 1.00 | 0.25 | 0.90 | 0.61 | 0.28 |
| Revert "Add caller stacktrace to rethrown…" | 0.67 | 0.50 | 0.55 | 0.55 | 1.53 | 0.61 |
| Map/Cache/MultiMap return Int.MAX on overflow | 0.80 | 0.71 | 0.75 | 0.72 | 0.85 | 0.67 |
| Remove DTOs for JMX beans from TimedMemberState | 0.47 | 0.44 | 0.72 | 0.70 | 1.48 | 0.75 |

Notable splits: the index decisively won the two metrics-related PRs ("Publish CP Subsystem stats", "executor creation time metric") and "Several migration fixes" — multi-file, cross-cutting changes where structural search helps. `grep` decisively won the lexical ones ("Remove typos in ConfigUtils", "Allow operations while shutting down") where the task names a concrete token to search for.

---

## Interpretation

- **When the index helps:** broad, cross-cutting changes whose surface spans many files tied by *structure* (callers, implementations, metric registrations) rather than a shared string. Three of the index's clearest wins fit this shape.
- **When `grep` helps:** the task names a concrete identifier or token, and a lexical sweep lands directly on it — cheaply.
- **Why not a clear index win overall:** for the median PR, the two approaches find comparable surfaces. The index's structural queries don't add enough recall to offset their token cost, and they occasionally spiral into long, expensive investigations (the 742s / $3.96 outliers) that `grep` avoids.

The honest synthesis is the **hybrid** one: use the index as a fast "which files should I read?" oracle *alongside* `grep`, not as a replacement — and don't expect it to pay for itself on lexically-obvious tasks.

---

## Threats to validity

- **N ≈ 22, one repo, one model** — direction, not a general law.
- **Ground truth = the actual PR diff.** A semantically valid alternative implementation site scores 0 on deterministic F1 (the LLM judge partly compensates).
- **Both arms hermetically stripped** of the operator's global skills/hooks; results won't match an everyday session with those active.
- **Overlay tombstone gap:** an overlay can't represent files *deleted* between base and ref, so a backward-ref view can show a small number of stale base-layer files. Truth files (which exist at the base) are unaffected.

---

## Reproduce

```bash
# server indexed at the 2020-06 median base (see harness notes)
DISC_BUDGET_USD=50 bash bench/run-discovery-all.sh
python3 bench/discovery/analyze_discovery.py bench/results/discovery-results.csv
```

- Raw data: `bench/results/discovery-results.csv` (44 rows)
- Report: `bench/results/discovery-report.txt`
- Harness: `bench/discovery/` (sampler, truth extractor, prompt, grader, prewarm) + `bench/run-discovery-{one,all}.sh`
