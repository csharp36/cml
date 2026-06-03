#!/usr/bin/env python3
"""Score arm result files against the bytecode oracle. Emits results.csv + prints aggregates.
name F1 for all arms; FQN precision/recall for arms that return FQNs (scip); collision-precision
(expected FQN precision of a name-only answer) for grep/cte."""
import json, sys, csv, statistics as st
from collections import defaultdict
sys.path.insert(0, "oracle")
from build_oracle import normalize_scip_symbol


def prf(found, truth):
    tp=len(found & truth); p=tp/len(found) if found else 0.0; r=tp/len(truth) if truth else 0.0
    return p, r, (2*p*r/(p+r) if (p+r) else 0.0)


def collision_precision(found_simple, truth_fqn, simple_index):
    truth=set(truth_fqn)
    if not found_simple: return 0.0
    tot=0.0
    for s in found_simple:
        cands=simple_index.get(s, [])
        if not cands: continue
        tot += sum(1 for c in cands if c in truth)/len(cands)
    return tot/len(found_simple)


if __name__ == "__main__":
    questions={json.loads(l)["id"]: json.loads(l) for l in open("questions.jsonl")}
    simple_index=json.load(open("oracle/oracle.json"))["simple_name_index"]
    rows=[]
    for path in sys.argv[1:]:
        for l in open(path):
            r=json.loads(l); q=questions[r["id"]]
            ts=set(q["answer_simple"]); tf=set(q["answer_fqns"])
            fs=set(r.get("found_simple") or [])
            p,rec,f=prf(fs, ts)
            cp=collision_precision(fs, tf, simple_index)
            fp=fr=ff=""
            if r.get("found_fqn"):
                nf={normalize_scip_symbol(x) for x in r["found_fqn"]}
                fp,fr,ff=[round(x,3) for x in prf(nf, tf)]
            rows.append([r["id"], q["stratum"], r["arm"], len(ts), len(fs),
                         round(p,3),round(rec,3),round(f,3),round(cp,3),fp,fr,ff,r.get("calls","")])
    import os; os.makedirs("results", exist_ok=True)
    w=csv.writer(open("results/results.csv","w"))
    w.writerow(["id","stratum","arm","truth_n","found_n","precision","recall","f1",
                "collision_precision","fqn_precision","fqn_recall","fqn_f1","calls"])
    w.writerows(rows)
    # aggregates
    def m(pred,key):
        v=[float(x[key]) for x in [dict(zip(["id","stratum","arm","truth_n","found_n","precision","recall","f1","collision_precision","fqn_precision","fqn_recall","fqn_f1","calls"],row)) for row in rows] if pred(x) and str(x[key])!=""]
        return round(st.mean(v),3) if v else None
    print(f"wrote results/results.csv ({len(rows)} rows)\n")
    for arm in ("grep","index_cte","index_scip"):
        P=lambda x,a=arm: x["arm"]==a
        print(f"{arm:10s} name[P {m(P,'precision')} R {m(P,'recall')} F1 {m(P,'f1')}]  "
              f"collP {m(P,'collision_precision')}  fqn[P {m(P,'fqn_precision')} R {m(P,'fqn_recall')} F1 {m(P,'fqn_f1')}]")
