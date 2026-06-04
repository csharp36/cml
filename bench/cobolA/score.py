#!/usr/bin/env python3
"""Score arm result files against the hybrid answer key. Emits results/results.csv,
per-stratum means, the gating-strata macro-average, and the pre-registered verdict.

Usage: python score.py results/grep.results.jsonl results/proxy.results.jsonl
Each result line: {"id","arm","found_simple":[...],"calls":N}
"""
from __future__ import annotations

import csv
import json
import os
import statistics as st
import sys
from collections import defaultdict

GATING_STRATA = ("call_closure", "data_access", "data_coupling")  # macro-averaged for the verdict
PROXY_MIN, GREP_MAX, GAP_MIN, NOTFIT_GAP = 0.70, 0.45, 0.30, 0.15


def prf(found: set[str], truth: set[str]) -> tuple[float, float, float]:
    tp = len(found & truth)
    p = tp / len(found) if found else 0.0
    r = tp / len(truth) if truth else 0.0
    return p, r, (2 * p * r / (p + r) if (p + r) else 0.0)


def truth_for(question: dict, hard_key: dict) -> set[str]:
    if question["key_source"] == "audited" and question["id"] in hard_key:
        return set(hard_key[question["id"]])
    return set(question["answer_simple"])


def verdict(proxy_f1: float, grep_f1: float) -> dict:
    gap = proxy_f1 - grep_f1
    if proxy_f1 >= PROXY_MIN and grep_f1 <= GREP_MAX and gap >= GAP_MIN:
        decision = "GREENLIGHT_B"
    elif gap < NOTFIT_GAP:
        decision = "NOT_A_FIT"
    else:
        decision = "AMBIGUOUS"
    return {"decision": decision, "proxy_f1": round(proxy_f1, 3),
            "grep_f1": round(grep_f1, 3), "gap": round(gap, 3)}


def _load_questions():
    return {json.loads(l)["id"]: json.loads(l) for l in open("questions.jsonl")}


def main() -> None:
    questions = _load_questions()
    hard_key = json.load(open("audit/hard_strata_key.json"))
    universe = set(json.load(open("oracle/oracle.json"))["programs"])

    rows = []
    f1s: dict[tuple[str, str], list[float]] = defaultdict(list)
    for path in sys.argv[1:]:
        for line in open(path):
            r = json.loads(line)
            q = questions[r["id"]]
            truth = truth_for(q, hard_key)
            found = set(r.get("found_simple") or []) & universe
            p, rec, f = prf(found, truth)
            f1s[(r["arm"], q["stratum"])].append(f)
            rows.append([r["id"], q["stratum"], r["arm"], len(truth), len(found),
                         round(p, 3), round(rec, 3), round(f, 3), r.get("calls", "")])

    os.makedirs("results", exist_ok=True)
    with open("results/results.csv", "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["id", "stratum", "arm", "truth_n", "found_n",
                    "precision", "recall", "f1", "calls"])
        w.writerows(rows)

    arms = sorted({r[2] for r in rows})
    strata = ["call_closure", "data_access", "data_coupling", "copybook_fan", "txn_reach"]
    print(f"wrote results/results.csv ({len(rows)} rows)\n")
    print("stratum".ljust(16) + "".join(a.ljust(12) for a in arms))
    for s in strata:
        line = s.ljust(16)
        for a in arms:
            vals = f1s.get((a, s), [])
            line += (f"{st.mean(vals):.3f}".ljust(12) if vals else "-".ljust(12))
        print(line + (" (gating)" if s in GATING_STRATA else ""))

    def macro(arm):
        per = [st.mean(f1s[(arm, s)]) for s in GATING_STRATA if f1s.get((arm, s))]
        return st.mean(per) if per else 0.0

    print("\n--- VERDICT (macro-avg over gating strata 1-3) ---")
    if "proxy" in arms and "grep" in arms:
        print(json.dumps(verdict(macro("proxy"), macro("grep")), indent=2))
    else:
        print("need both 'grep' and 'proxy' arm results to render the verdict")


if __name__ == "__main__":
    main()
