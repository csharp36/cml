#!/usr/bin/env python3
"""Arena 0: re-slice existing discovery results by blind lexical/structural label.
Labels were produced by a blind labeler (task text only, no results)."""
import csv, statistics as st
from collections import defaultdict

LABELS = {
 "1cdf99545c24":"lexical","926337320379":"lexical","7765c3b6d78b":"lexical",
 "b51ce572bb8e":"lexical","cfcc57058b7a":"lexical","15cb7285ca22":"lexical",
 "69ef8054ecad":"lexical","60ca2aaf2250":"lexical","459f15017e1a":"lexical",
 "1ac3ce799e45":"lexical","8f5f00237c90":"lexical",
 "2c7c4b1d9f66":"structural","3d10a69afb8d":"structural","e67f30b1c7a2":"structural",
 "2273489ecaa8":"structural","0e4a310bdf03":"structural","813ee32fbc48":"structural",
 "26253c4b9a34":"structural","63f793bf0315":"structural","e4b5aa440034":"structural",
 "fa7660e5d53b":"structural","316fe8451b08":"structural",
}
BORDERLINE = {"1cdf99545c24","3d10a69afb8d","0e4a310bdf03","b51ce572bb8e",
 "63f793bf0315","15cb7285ca22","e4b5aa440034","8f5f00237c90","316fe8451b08"}

rows=defaultdict(dict)  # id -> arm -> row
with open('bench/results/discovery-results.csv') as f:
    for r in csv.DictReader(f):
        rows[r['instance_id']][r['arm']]=r

def f(x): return float(x)
def agg(ids, key, arm, fn):
    return fn([f(rows[i][arm][key]) for i in ids])

def stratum(name, ids):
    print(f"\n{'='*64}\nSTRATUM: {name}   (n={len(ids)} instances)\n{'='*64}")
    for metric in ["file_f1","symbol_f1","judge_score","cost_usd","turns"]:
        sm_mean=agg(ids,metric,'semantic',st.mean); bl_mean=agg(ids,metric,'baseline',st.mean)
        sm_med =agg(ids,metric,'semantic',st.median); bl_med=agg(ids,metric,'baseline',st.median)
        print(f"  {metric:11s}  semantic mean {sm_mean:7.3f} / med {sm_med:7.3f}   "
              f"baseline mean {bl_mean:7.3f} / med {bl_med:7.3f}   "
              f"Δmean(sem-base) {sm_mean-bl_mean:+.3f}")
    # head-to-head on file_f1 and judge
    for metric in ["file_f1","judge_score"]:
        sw=bw=tie=0
        for i in ids:
            s=f(rows[i]['semantic'][metric]); b=f(rows[i]['baseline'][metric])
            if abs(s-b)<1e-9: tie+=1
            elif s>b: sw+=1
            else: bw+=1
        print(f"  H2H {metric:11s}  semantic {sw} | baseline {bw} | tie {tie}")
    return {m:(agg(ids,m,'semantic',st.mean)-agg(ids,m,'baseline',st.mean)) for m in ["file_f1","judge_score","cost_usd"]}

lex=[i for i in rows if LABELS[i]=="lexical"]
stru=[i for i in rows if LABELS[i]=="structural"]
allids=list(rows)

g_all=stratum("ALL (sanity vs published report)", allids)
g_lex=stratum("LEXICAL", lex)
g_stru=stratum("STRUCTURAL", stru)

print(f"\n{'#'*64}\nSIGNAL TEST\n{'#'*64}")
print(f"file_f1 Δmean(sem-base):  lexical {g_lex['file_f1']:+.3f}   structural {g_stru['file_f1']:+.3f}")
print(f"judge   Δmean(sem-base):  lexical {g_lex['judge_score']:+.3f}   structural {g_stru['judge_score']:+.3f}")
print(f"cost$   Δmean(sem-base):  lexical {g_lex['cost_usd']:+.3f}   structural {g_stru['cost_usd']:+.3f}")
print()
acc_win = g_stru['file_f1']>0 and g_stru['judge_score']>0
bigger  = g_stru['file_f1']>g_lex['file_f1']
print(f"Index wins structural accuracy (file_f1 AND judge Δ>0)?  {acc_win}")
print(f"Structural file_f1 gap > lexical file_f1 gap?            {bigger}")
print(f"=> ARENA 0 SIGNAL: {'YES (keep-leaning)' if (acc_win and bigger) else 'NO / partial'}")

# robustness: drop borderline, structural-confident only
stru_c=[i for i in stru if i not in BORDERLINE]
lex_c=[i for i in lex if i not in BORDERLINE]
print(f"\n--- ROBUSTNESS: confident-only (drop borderline) ---")
print(f"confident lexical n={len(lex_c)}, confident structural n={len(stru_c)}")
import statistics
def dmean(ids,m): return agg(ids,m,'semantic',st.mean)-agg(ids,m,'baseline',st.mean)
print(f"file_f1 Δmean: lexical {dmean(lex_c,'file_f1'):+.3f}  structural {dmean(stru_c,'file_f1'):+.3f}")
print(f"judge   Δmean: lexical {dmean(lex_c,'judge_score'):+.3f}  structural {dmean(stru_c,'judge_score'):+.3f}")
