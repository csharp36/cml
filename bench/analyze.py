#!/usr/bin/env python3
"""Nonparametric analysis of benchmark results. Usage: analyze.py results.csv"""
import csv, sys, statistics as st, random, math

random.seed(12345)  # deterministic bootstrap

def load(path):
    rows = []
    with open(path) as f:
        for r in csv.DictReader(f):
            for k in ("pass","in_tokens","out_tokens","cache_read","cache_create","turns","denied_attempts"):
                r[k] = int(float(r[k]))
            r["wall_s"] = float(r["wall_s"])
            r["cost_usd"] = float(r.get("cost_usd", 0) or 0)
            r["io_tokens"] = r["in_tokens"] + r["out_tokens"]
            r["billable_tokens"] = r["in_tokens"] + r["out_tokens"] + r["cache_read"] + r["cache_create"]
            rows.append(r)
    return rows

def med(xs): return st.median(xs) if xs else float("nan")
def iqr(xs):
    if len(xs) < 2: return (float("nan"), float("nan"))
    q = st.quantiles(xs, n=4); return (q[0], q[2])

def boot_ci(a, b, reps=10000):
    if not a or not b: return (float("nan"), float("nan"))
    diffs = []
    for _ in range(reps):
        ra = [random.choice(a) for _ in a]
        rb = [random.choice(b) for _ in b]
        diffs.append(st.median(rb) - st.median(ra))
    diffs.sort()
    lo = diffs[int(0.025*reps)]; hi = diffs[int(0.975*reps)]
    return (lo, hi)

def main():
    rows = load(sys.argv[1])
    arms = {"semantic": [r for r in rows if r["arm"]=="semantic"],
            "baseline": [r for r in rows if r["arm"]=="baseline"]}
    print("="*64)
    print("BENCHMARK REPORT — semantic indexer vs grep/find (hazelcast PR #4317)")
    print("="*64)
    for name, rs in arms.items():
        n = len(rs); passed = sum(r["pass"] for r in rs)
        rate = (passed/n*100) if n else float("nan")
        print(f"\n[{name}]  runs={n}  passed={passed}  success_rate={rate:.0f}%")
    metrics = [("io_tokens","io tok"),("billable_tokens","billable tok"),("cost_usd","cost $"),
               ("turns","turns"),("wall_s","wall s")]
    print("\n--- successful runs only (median [IQR]) ---")
    for key,label in metrics:
        s = [r[key] for r in arms["semantic"] if r["pass"]==1 and r.get("is_error")=="false"]
        b = [r[key] for r in arms["baseline"] if r["pass"]==1 and r.get("is_error")=="false"]
        ms, mb = med(s), med(b)
        lo, hi = boot_ci(s, b)
        delta = (mb-ms)
        pct = (delta/mb*100) if (mb and not math.isnan(mb)) else float("nan")
        s_iqr, b_iqr = iqr(s), iqr(b)
        print(f"{label:>10}: semantic {ms:>9.1f} [{s_iqr[0]:.0f},{s_iqr[1]:.0f}]  "
              f"baseline {mb:>9.1f} [{b_iqr[0]:.0f},{b_iqr[1]:.0f}]  "
              f"baseline-semantic={delta:>+9.1f} ({pct:+.0f}%)  95%CI[{lo:.0f},{hi:.0f}]")
    bad = [r for r in arms["semantic"] if r["denied_attempts"]>0]
    print(f"\ncompliance: semantic runs with >=1 blocked discovery attempt = {len(bad)} "
          f"(blocked, not executed — expected when the agent reaches for grep)")
    print("\nNote: single-task benchmark — result holds for PR #4317, not as a general law.")

if __name__ == "__main__":
    main()
