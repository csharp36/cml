#!/usr/bin/env python3
"""Pick ~12 type-resolution questions from the bytecode oracle (main scope).
Weighted toward interfaces with many INDIRECT implementers (structural), with a few
lexical controls (shallow hierarchies). Records the oracle answer (FQN + simple closure)."""
import json, sys
from collections import defaultdict
sys.path.insert(0, "oracle")
from build_oracle import transitive_closure, simple_name


def indirect_ratio(direct, transitive):
    return (transitive - direct) / max(transitive, 1)


def pick(candidates, n=12):
    structural = sorted([c for c in candidates if c["stratum"] == "structural"],
                        key=lambda c: -indirect_ratio(c["direct"], c["transitive"]))
    lexical = sorted([c for c in candidates if c["stratum"] == "lexical"],
                     key=lambda c: -c["transitive"])
    k_struct = max(1, n * 2 // 3)
    out = structural[:k_struct] + lexical[: n - k_struct]
    return out[:n]


if __name__ == "__main__":
    oracle = json.load(open("oracle/oracle.json"))
    graph = oracle["graph"]
    parent_to_children = defaultdict(set)
    for c, ps in graph.items():
        for p in ps:
            parent_to_children[p].add(c)
    cand = []
    for t in graph:
        # production Hazelcast targets only; skip test fixtures and nested test types
        if not t.startswith("com.hazelcast."):
            continue
        if "Test" in t or "$" in t:
            continue
        trans = transitive_closure(graph, t)
        if len(trans) < 5:
            continue
        direct = len(parent_to_children.get(t, ()))
        ratio = indirect_ratio(direct, len(trans))
        cand.append({"type": t, "direct": direct, "transitive": len(trans),
                     "stratum": "structural" if ratio >= 0.5 else "lexical",
                     "answer": sorted(trans)})
    picked = pick(cand, n=12)
    with open("questions.jsonl", "w") as f:
        for p in picked:
            simple = simple_name(p["type"])
            f.write(json.dumps({
                "id": p["type"].replace('.', '_'),
                "type_simple": simple,
                "type_fqn": p["type"],
                "stratum": p["stratum"],
                "direct": p["direct"], "transitive": p["transitive"],
                "question": f"List every concrete class that is a {simple} "
                            f"(implements it directly or through inheritance).",
                "answer_fqns": p["answer"],
                "answer_simple": sorted({simple_name(a) for a in p["answer"]}),
            }) + "\n")
    print(f"wrote {len(picked)} questions; "
          f"structural={sum(1 for p in picked if p['stratum']=='structural')} "
          f"lexical={sum(1 for p in picked if p['stratum']=='lexical')}")
    for p in picked:
        print(f"  [{p['stratum'][:4]}] {p['type_simple']:32s} direct={p['direct']:4d} transitive={p['transitive']:4d}")
