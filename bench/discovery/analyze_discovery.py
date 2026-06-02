#!/usr/bin/env python3
"""Per-arm accuracy + cost analysis for the discovery benchmark.
Usage: analyze_discovery.py discovery-results.csv"""
import csv, sys, statistics as st

_INTS = ("answered", "in_tokens", "out_tokens", "cache_read", "cache_create", "turns", "denied_attempts")
_FLOATS = ("file_f1", "symbol_f1", "judge_score", "wall_s", "cost_usd")

def load(fp):
    rows = []
    for r in csv.DictReader(fp):
        for k in _INTS:
            r[k] = int(float(r[k]))
        for k in _FLOATS:
            r[k] = float(r[k])
        r["billable_tokens"] = r["in_tokens"] + r["out_tokens"] + r["cache_read"] + r["cache_create"]
        rows.append(r)
    return rows

def med(xs):
    return st.median(xs) if xs else float("nan")

def answer_rate(rows):
    return (sum(r["answered"] for r in rows) / len(rows)) if rows else float("nan")

def _report(rows):
    arms = {a: [r for r in rows if r["arm"] == a] for a in ("semantic", "baseline")}
    print("=" * 64)
    print("DISCOVERY BENCHMARK — semantic index vs grep/find (locate-the-surface)")
    print("=" * 64)
    for name, rs in arms.items():
        print(f"\n[{name}]  instances={len(rs)}  answer_rate={answer_rate(rs)*100:.0f}%")
    answered = {a: [r for r in arms[a] if r["answered"] == 1 and r.get("is_error") == "false"]
                for a in arms}
    metrics = [("file_f1", "file F1", True), ("symbol_f1", "symbol F1", True),
               ("judge_score", "judge", True), ("cost_usd", "cost $", False),
               ("turns", "turns", False), ("wall_s", "wall s", False),
               ("billable_tokens", "billable tok", False)]
    print("\n--- answered instances only (median; higher F1 better, lower cost better) ---")
    for key, label, _ in metrics:
        s = med([r[key] for r in answered["semantic"]])
        b = med([r[key] for r in answered["baseline"]])
        print(f"{label:>13}: semantic {s:>10.3f}   baseline {b:>10.3f}")
    print("\nNote: ~20-30 instances, one repo/model — direction, not a general law.")

def main():
    with open(sys.argv[1]) as f:
        _report(load(f))

if __name__ == "__main__":
    main()
