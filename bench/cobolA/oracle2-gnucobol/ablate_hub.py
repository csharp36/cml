#!/usr/bin/env python3
"""Hub-ablation experiment for the COBOL call_closure win.

Motivation (converged on by two independent reviews — see audit/REVIEW-AI-DRYRUN.md
and the Desktop-Opus critique): the online tier is one strongly-connected component
only because of *navigation-shell* edges — every screen transfers back to the signon
program (`COSGN00C`, via the `EIBCALEN=0` cold-start guard and PF3 signoff) and/or to
its parent menu, and the menus fan back out through the OCCURS dispatch table. For a
*decomposition* use case, that shell (signon + the two menus) is exactly where you'd
cut: a screen that can only "reach" another screen by navigating home through the menu
is not coupled to it in any way you'd preserve.

So we ablate the shell and recompute closures, to separate two things the headline
number conflates:
  * the *structural win* — enumerating the menu's OCCURS/VALUE dispatch, the one move
    grep genuinely cannot make; vs
  * the *degeneracy* — the SCC that makes every online closure ≈ "the whole tier",
    inflating the win's apparent breadth (the same answer measured ~42 times).

HUB = {COSGN00C, COMEN01C, COADM01C}. Ablation = hub nodes are non-expanding when
reached as intermediates (you may transfer *to* the shell, but coupling does not
propagate *through* it); a hub node is still expanded when it is the query seed
(the menu still owns its OCCURS fan-out — that's the structural fact under test).

Compares, for the 21 call_closure nodes, the oracle graph (oracle2.json, on which the
two extractors agree 44/44) and the grep arm's resolvable graph, under the normal vs
ablated relation. NOT tuned: prints what falls out.
"""
import json
import sys
from pathlib import Path
from collections import defaultdict

HERE = Path(__file__).resolve().parent
BENCH = HERE.parent
sys.path.insert(0, str(BENCH / "arms"))
import run_grep  # noqa: E402

HUB = {"COSGN00C", "COMEN01C", "COADM01C"}


def closure(edges: dict, seed: str, corpus: set, ablate: bool) -> set:
    """BFS closure. If ablate, hub nodes reached as intermediates are included but
    not expanded; the seed is always expanded."""
    seen, stack = set(), [(seed, True)]
    while stack:
        node, is_seed = stack.pop()
        if node in seen:
            continue
        seen.add(node)
        expand = is_seed or not (ablate and node in HUB)
        if expand:
            for t in edges.get(node, []):
                if t in corpus and t not in seen:
                    stack.append((t, False))
    seen.discard(seed)
    return seen & corpus


def f1(found: set, truth: set) -> float:
    tp = len(found & truth)
    p = tp / len(found) if found else 0.0
    r = tp / len(truth) if truth else 0.0
    return 2 * p * r / (p + r) if (p + r) else 0.0


def mean_pairwise_jaccard(answer_sets: list) -> float:
    """Near-duplication of the answer-sets. ~1.0 = degenerate (all the same)."""
    sets = [set(s) for s in answer_sets]
    sims, n = [], len(sets)
    for i in range(n):
        for j in range(i + 1, n):
            u = sets[i] | sets[j]
            sims.append(len(sets[i] & sets[j]) / len(u) if u else 1.0)
    return sum(sims) / len(sims) if sims else 1.0


def main():
    oracle_edges = json.load(open(HERE / "oracle2.json"))["direct_corpus_edges"]
    corpus = set(json.load(open(HERE / "oracle2.json"))["programs"])

    # grep's resolvable edge graph (literal CALL/XCTL + MOVE 'lit' TO var; no OCCURS)
    progs = run_grep._load_programs(str(BENCH / "corpus"))
    grep_edges = {p: sorted(run_grep._direct_targets(code) & corpus)
                  for p, code in progs.items()}

    nodes = [q["node"] for q in map(json.loads, open(BENCH / "questions.jsonl"))
             if q["stratum"] == "call_closure"]

    rows = []
    for n in nodes:
        o_norm = closure(oracle_edges, n, corpus, ablate=False)
        o_abl = closure(oracle_edges, n, corpus, ablate=True)
        g_norm = closure(grep_edges, n, corpus, ablate=False)
        g_abl = closure(grep_edges, n, corpus, ablate=True)
        rows.append({
            "node": n, "is_hub": n in HUB,
            "o_norm": o_norm, "o_abl": o_abl, "g_norm": g_norm, "g_abl": g_abl,
            "f1_norm": f1(g_norm, o_norm), "f1_abl": f1(g_abl, o_abl),
        })

    sizes_norm = sorted({len(r["o_norm"]) for r in rows})
    sizes_abl = sorted({len(r["o_abl"]) for r in rows})
    print("=== degeneracy of the 21 call_closure answer-sets ===")
    print(f"  normal  relation: closure sizes = {sizes_norm}; mean pairwise Jaccard = "
          f"{mean_pairwise_jaccard([r['o_norm'] for r in rows]):.3f}")
    print(f"  ablated relation: closure sizes = {sizes_abl}; mean pairwise Jaccard = "
          f"{mean_pairwise_jaccard([r['o_abl'] for r in rows]):.3f}")
    print("  (Jaccard ~1.0 = near-duplicate questions; the seed-exclusion hides this in a"
          " raw distinct-set count, since each closure is 'the whole tier minus the seed'.)")

    floor = [r for r in rows if len(r["o_abl"]) >= 2]
    print(f"\n=== questions surviving the >=2 answer-size floor after ablation: {len(floor)}/21 ===")

    def mean(xs):
        return sum(xs) / len(xs) if xs else 0.0

    print("\n=== grep F1 vs oracle key (mean over 21 call_closure nodes) ===")
    print(f"  normal  relation: grep F1 = {mean([r['f1_norm'] for r in rows]):.3f}"
          f"   (oracle/proxy F1 = 1.000 by construction)")
    print(f"  ablated relation: grep F1 = {mean([r['f1_abl'] for r in rows]):.3f}")
    print(f"  ablated, floor>=2 only ({len(floor)} q): grep F1 = {mean([r['f1_abl'] for r in floor]):.3f}")

    print("\n=== where the post-ablation gap lives (oracle closure size & grep F1) ===")
    print(f"  {'node':10} {'hub':4} {'o_norm':7} {'o_abl':6} {'g_abl':6} {'F1_norm':8} {'F1_abl':7}")
    for r in sorted(rows, key=lambda r: -len(r["o_abl"])):
        print(f"  {r['node']:10} {'Y' if r['is_hub'] else '.':4} "
              f"{len(r['o_norm']):7} {len(r['o_abl']):6} {len(r['g_abl']):6} "
              f"{r['f1_norm']:.3f}    {r['f1_abl']:.3f}")


if __name__ == "__main__":
    main()
