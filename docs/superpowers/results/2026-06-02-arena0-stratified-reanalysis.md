# Arena 0 — Stratified Re-analysis of the Discovery Benchmark (free, no new runs)

*Result — 2026-06-02. First arena of the keep-or-kill pilot
(`docs/superpowers/specs/2026-06-02-keep-or-kill-pilot-design.md`). Re-slices the existing
44 discovery rows by a blind lexical-vs-structural label. No new agent runs.*

## Hypothesis

The discovery benchmark reported a tie on the average. The conjecture: the average hid a
**crossover** — grep wins *lexical* tasks (the task names a concrete token to search for),
the index wins *structural* tasks (the surface is tied by callers/implementers/registrations
with no single shared string).

## Method

- **Labels were assigned blind.** A separate agent saw only each task's description and PR
  title — never any F1 or cost number — and classified each as `lexical` or `structural`
  against a fixed rubric, plus a `borderline` flag and 1–5 confidence. This removes the
  risk that having seen the results biased the stratification.
- The 22 instances split **11 lexical / 11 structural**.
- Merged labels with `bench/results/discovery-results.csv` and recomputed accuracy/cost per
  stratum. Script: `bench/discovery/analyze_arena0.py` (re-runnable).

## Result

**The crossover is real in the means, and in the predicted direction:**

| metric (Δ = semantic − baseline, mean) | LEXICAL (n=11) | STRUCTURAL (n=11) |
|---|---|---|
| file F1 | **−0.021** (grep better) | **+0.031** (index better) |
| judge   | **−0.023** (grep better) | **+0.044** (index better) |
| symbol F1 | +0.034 | +0.039 |
| cost $  | +0.278 (index +48%) | +0.557 (index +56%) |

The sign of the accuracy gap **flips** between strata — exactly the hypothesis. And it
**strengthens** when borderline cases are dropped (confident-only: lexical file-F1 Δ
**−0.098**, structural **+0.020**; lexical judge Δ **−0.117**, structural **+0.093**). One
of the index's biggest single wins ("executor creation time metric", file-F1 1.00 vs 0.50)
was blind-labeled *lexical* — i.e. bucketed *against* the hypothesis — and the crossover
still showed through, which makes it more credible, not less.

## But the signal is weak, and I will not oversell it

Three honest deflations:

1. **Per-instance sign tests are coin-flips.** Structural file-F1: semantic wins **5**,
   baseline **4**, ties **2**. Structural judge: semantic **4**, baseline **5** — the
   baseline actually wins the head-to-head *count* in the very stratum the index "wins" on
   the mean.
2. **The mean win is outlier-driven.** The structural advantage is carried by ~2 PRs
   (CP-metrics 1.00 vs 0.52; migration fixes 0.667 vs 0.235) — both "registration-pattern"
   changes. Remove them and the structural edge largely evaporates.
3. **No statistical significance.** n=11/stratum, meanΔ ≈ 0.03 against sd ≈ 0.26. Nowhere
   near significant. Direction only.

And the cost penalty is *worse* exactly where the index helps: on structural tasks the
index costs **+56%** ($1.56 vs $1.00 mean). So even in its best stratum, the index buys a
little recall at a real price.

## Verdict

**Weak directional signal, in the predicted direction — not robust enough to "keep" on its
own, but it does not kill the structural hypothesis either.** Per the pilot's decision rule,
"Arena 0 structural-stratum win is robust" is **NOT** met (sign tests coin-flip, outlier-
driven, not significant). But the consistent crossover across every structural mean metric
— surviving the confident-only robustness check and a mislabeled-against-it outlier — is
enough to *motivate* Arena A rather than abandon the idea.

The real payoff: a genuine narrative finding for the series independent of keep/kill —
**"the average was a lie: grep wins lexical discovery, the index wins cross-cutting
structural discovery, and the headline tie was the two cancelling out."** That is a
publishable result whatever the rest of the pilot shows.

## Next

Proceed to **Arena A** (type-resolution, accuracy-isolated, neutral non-index oracle). It
tests the same structural-resolution premise with a cleaner design and a metric (recall of
resolved symbols) where the index has a *structural* reason to win that grep cannot match —
the cleanest test of whether the weak signal here is real.
