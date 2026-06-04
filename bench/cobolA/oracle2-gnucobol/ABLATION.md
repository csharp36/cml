# Hub-ablation: separating the structural win from the navigation artifact

**Why.** Two independent reviews of the benchmark (the Desktop-Opus critique and the
web-grounded dry run in `../audit/REVIEW-AI-DRYRUN.md`) converged on the same soft spot:
the `call_closure`/`txn_reach` win rests partly on a **navigation-shell artifact**, not
purely on the structural move grep can't make. This experiment quantifies the split. It
is a sensitivity analysis on the graph we already have — not a re-tuning.

## The mechanism (verified in source)

CardDemo's online tier becomes one strongly-connected component through three edge types:

1. **menu → screen** — `XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))` over an `OCCURS`
   table of `VALUE`-literal program names (`COMEN01C.cbl:149/616`, table in `COMEN02Y.cpy`).
   **This is the one grep genuinely cannot follow** (the OCCURS/VALUE dispatch).
2. **screen → menu** — the PF3 "return to parent menu" `MOVE 'COMEN01C'/'COADM01C'`
   (`COBIL00C.cbl:130`, `COUSR01C.cbl:94`). Grep-tractable (literal MOVE).
3. **screen → signon** — `IF EIBCALEN = 0 MOVE 'COSGN00C'` — the pseudo-conversational
   **cold-start / lost-session guard** (`COBIL00C.cbl:107-108`, `COUSR01C.cbl:78-79`), plus
   the actual PF3 signoff later in each program. Grep-tractable (literal MOVE). `COSGN00C`
   then forward-dispatches to both menus with literal `XCTL` (`COSGN00C.cbl:231-237`).

Of the three, only #1 is the structural wall. #2 and #3 are **navigation plumbing** — for a
*decomposition* use case (the benchmark's stated motivation), the signon + two menus are the
shell you'd cut, not a business dependency. Counting transitive closure *through* the shell
over-couples every screen to every other by construction.

## The ablation

`HUB = {COSGN00C, COMEN01C, COADM01C}`. We recompute closures treating hub nodes as a
**boundary**: a hub node reached as an intermediate is included but not expanded (you may
transfer *to* the shell; coupling does not propagate *through* it). A hub node is still
expanded when it is the query *seed* — the menu still owns its OCCURS fan-out, which is the
structural fact under test. Run over the oracle graph (`oracle2.json`, on which the two
independent extractors agree 44/44) and the grep arm's resolvable graph.

## Results (21 `call_closure` questions)

**Degeneracy — confirmed and quantified.**

| relation | closure sizes | mean pairwise Jaccard |
|---|---|---|
| normal (through-shell) | all **22** | **0.913** (near-duplicate) |
| ablated (shell as boundary) | **1–15** | **0.242** (diverse) |

The normal closures are all "the whole tier minus the seed" — the `§9` SCC degeneracy, now
measured. (A raw distinct-set count misreads this as "21 distinct" because each closure
omits a different single seed; Jaccard exposes it.)

**Most of the `call_closure` win was the shell, not the OCCURS wall.**

| relation | grep F1 (mean) | proxy F1 |
|---|---|---|
| normal | **0.145** | 1.000 |
| ablated (all 21) | 0.354 | 1.000 |
| ablated (17 surviving the ≥2 floor) | **0.438** | 1.000 |

Grep's `call_closure` F1 **triples** under the decomposition-correct relation, landing
essentially at the pre-registered `GREP_MAX` of 0.45. So a large fraction of the headline
gap on this stratum was shell-uniformity — the same near-answer scored ~21 times.

**The genuine win survives — concentrated on the dispatch-owning nodes.**

| node | role | o_norm | o_abl | grep_abl | F1 normal | F1 ablated |
|---|---|--:|--:|--:|--:|--:|
| `COMEN01C` | menu (OCCURS) | 22 | 15 | 1 | 0.167 | **0.125** |
| `COADM01C` | menu (OCCURS) | 22 | 7 | 1 | 0.167 | **0.250** |
| `COCRDLIC` `COACTUPC` `COTRTLIC` | screen w/ VALUE-dispatch | 22 | 2–3 | 0 | 0.0 | **0.0** |
| `COBIL00C` `COUSR01-03C` `CORPT00C` `COTRN0xC` | pure-nav leaf | 22 | 2–3 | 1–2 | 0.24 | **0.5–1.0** |

On the menus and the VALUE-dispatch screens, grep still scores ~0 after ablation — the
real structural win. On the **pure-navigation leaves, grep ties** (F1 0.5–1.0): those
closures are just "the shell I return to," which grep reads fine.

## Reading

- The structural win is **real and is the one move grep can't make** — but on this corpus
  it lives on a **handful of nodes** (the two menus + ~3 VALUE-dispatch screens), not across
  all 21 `call_closure` questions.
- Its **breadth was a navigation artifact**: the SCC (Jaccard 0.913) made every online
  closure ≈ "the whole tier," so one structural fact got counted ~21 times. Remove the shell
  and grep rises to the bar (0.145 → 0.438) while the win concentrates where OCCURS/VALUE
  dispatch actually lives.
- This **resolves three open caveats at once** that `§9` of the writeup could only flag:
  it quantifies the SCC degeneracy (Jaccard 0.913 → 0.242), shows the win concentrating on
  the discriminating nodes, and measures how much of the gap was shell-uniformity vs genuine
  OCCURS blindness (most of the `call_closure` gap was the former).
- Caveat on the ablation itself: `HUB` and the boundary rule are *my* modeling choices, made
  knowing the corpus — they are a defensible decomposition relation, not the only one, and
  the cut is exactly the kind of modeling decision the (still-open) human COBOL review should
  adjudicate. The robust, model-independent facts are the **Jaccard collapse** and that
  **grep ties on the pure-navigation leaves**.

## Reproduce

```bash
cd bench/cobolA
python3 oracle2-gnucobol/extract_edges.py            # -> oracle2.json (if not present)
python3 -m pytest oracle2-gnucobol/test_ablate_hub.py -q
python3 oracle2-gnucobol/ablate_hub.py
```
