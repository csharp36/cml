#!/usr/bin/env python3
"""Derive the 5-strata decomposition query workload from oracle.json.

Deterministic: candidates are sorted by (descending answer size, node name) and
capped per stratum, so re-running produces an identical questions.jsonl.

Usage: python select_questions.py            # reads oracle/oracle.json, writes questions.jsonl
"""
from __future__ import annotations

import json
import sys

AUDITED = {"call_closure", "data_coupling", "txn_reach"}  # hybrid key: hand-audited strata
ORACLE_KEYED = {"data_access", "copybook_fan"}            # independently grep-verifiable

PROMPTS = {
    "call_closure": "List every CardDemo program reachable from program {node} by following "
                    "CALL and CICS XCTL/LINK transfers (directly or transitively).",
    "data_access":  "List every CardDemo program that reads or writes the data resource {node}.",
    "data_coupling": "List every CardDemo program that shares a data store (file or DB2 table) "
                     "with program {node}.",
    "copybook_fan": "List every CardDemo program that COPYs the copybook {node}.",
    "txn_reach":    "List every CardDemo program reachable when CICS transaction {node} starts "
                    "(its entry program and everything that entry transitively transfers to).",
}

DEFAULT_CAPS = {  # None = take all candidates that clear the min-answer-size floor
    "call_closure": None, "data_access": None, "data_coupling": None,
    "copybook_fan": 15, "txn_reach": None,
}


def _key_source(stratum: str) -> str:
    return "audited" if stratum in AUDITED else "oracle"


def build_questions(oracle: dict, caps: dict | None = None) -> list[dict]:
    caps = {**DEFAULT_CAPS, **(caps or {})}
    universe = set(oracle["programs"])
    cand: list[dict] = []

    def filt(names):  # keep only corpus programs, sorted
        return sorted(set(names) & universe)

    # stratum 1 — transitive call closure (answer >= 2 corpus programs)
    for pid, closure in oracle["transitive_call_closure"].items():
        ans = filt(closure)
        if len(ans) >= 2:
            cand.append(_mk("call_closure", pid, ans))

    # stratum 2 — data access (resource with >= 2 accessors)
    for res, progs in oracle["data_access"].items():
        ans = filt(progs)
        if len(ans) >= 2:
            cand.append(_mk("data_access", res, ans))

    # stratum 3 — data coupling (program with >= 1 coupled program)
    for pid, coupled in oracle["data_coupling"].items():
        ans = filt(coupled)
        if len(ans) >= 1:
            cand.append(_mk("data_coupling", pid, ans))

    # stratum 4 — copybook fan (copybook with >= 2 includers)
    for cb, progs in oracle["copybook_fan"].items():
        ans = filt(progs)
        if len(ans) >= 2:
            cand.append(_mk("copybook_fan", cb, ans))

    # stratum 5 — txn reach (entry exists in corpus; answer >= 2)
    for txn, reach in oracle.get("txn_reach", {}).items():
        ans = filt(reach)
        if len(ans) >= 2:
            cand.append(_mk("txn_reach", txn, ans))

    # deterministic per-stratum cap: biggest answers first, tie-break by node
    out: list[dict] = []
    for stratum in PROMPTS:
        group = sorted((c for c in cand if c["stratum"] == stratum),
                       key=lambda c: (-len(c["answer_simple"]), c["node"]))
        cap = caps.get(stratum)
        out.extend(group if cap is None else group[:cap])
    return out


def _mk(stratum: str, node: str, answer: list[str]) -> dict:
    return {
        "id": f"{stratum}__{node}",
        "stratum": stratum,
        "kind": stratum,
        "node": node,
        "question": PROMPTS[stratum].format(node=node),
        "answer_simple": answer,
        "key_source": _key_source(stratum),
    }


def main() -> None:
    oracle = json.load(open("oracle/oracle.json"))
    qs = build_questions(oracle)
    with open("questions.jsonl", "w") as fh:
        for q in qs:
            fh.write(json.dumps(q) + "\n")
    from collections import Counter
    counts = Counter(q["stratum"] for q in qs)
    print(f"wrote {len(qs)} questions: " + ", ".join(f"{k}={counts[k]}" for k in PROMPTS),
          file=sys.stderr)


if __name__ == "__main__":
    main()
