#!/usr/bin/env python3
"""Diff the independent GnuCOBOL oracle (oracle2.json) against the ProLeap oracle
(../oracle/oracle.json). Reports agreement per program on the two layers where
the audited bugs lived: resolved control-transfer edges and data coupling.

NOT tuned to converge: this prints whatever disagreement exists. Disagreements
are the signal — they are exactly what an independent second implementation is
for. Both oracles are corpus-filtered to the 44 programs before comparison.
"""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
g = json.load(open(HERE / "oracle2.json"))
p = json.load(open(HERE.parent / "oracle" / "oracle.json"))

CORPUS = set(g["programs"])


def corpus_only(seq):
    return {x for x in seq if x in CORPUS}


def cmp_layer(name, gmap, pmap):
    progs = sorted(CORPUS)
    agree = 0
    diffs = []
    for prog in progs:
        gv = corpus_only(gmap.get(prog, [])) - {prog}
        pv = corpus_only(pmap.get(prog, [])) - {prog}
        if gv == pv:
            agree += 1
        else:
            diffs.append((prog, sorted(gv - pv), sorted(pv - gv)))
    print(f"\n=== {name}: {agree}/{len(progs)} programs agree (corpus-filtered) ===")
    for prog, only_g, only_p in diffs:
        print(f"  {prog}:")
        if only_g:
            print(f"     only in GnuCOBOL oracle: {only_g}")
        if only_p:
            print(f"     only in ProLeap  oracle: {only_p}")
    return agree, len(progs), diffs


print(f"corpus programs: {len(CORPUS)}")
a1, n1, d1 = cmp_layer("direct control-transfer edges",
                       g["direct_corpus_edges"], p["direct_call_edges"])
a2, n2, d2 = cmp_layer("transitive call closure",
                       g["transitive_call_closure"], p["transitive_call_closure"])
a3, n3, d3 = cmp_layer("data coupling",
                       g["data_coupling"], p["data_coupling"])

print("\n================ SUMMARY ================")
print(f"direct edges      : {a1}/{n1} agree")
print(f"transitive closure: {a2}/{n2} agree")
print(f"data coupling     : {a3}/{n3} agree")
