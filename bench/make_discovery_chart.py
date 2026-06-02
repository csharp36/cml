#!/usr/bin/env python3
"""Figures for the discovery-benchmark writeup / Medium article (22 PRs, semantic vs grep).
Regenerable from the run CSV. Matches the palette of make_chart.py (the original article).

Outputs (next to the writeup, tracked):
  discovery-fig-accuracy-cost.jpeg — 2-panel bar chart (median accuracy + cost per run)
  discovery-table-headline.jpeg    — the mean/median headline table, rendered as an image
  discovery-table-perinstance.jpeg — the 22-PR per-instance table, rendered as an image
"""
import csv
import statistics as st
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

CSV = "bench/results/discovery-results.csv"
OUT = "docs/superpowers/results"

# Medium editorial palette (shared with make_chart.py)
INK = "#242424"; GRID = "#E6E6E6"
SEMANTIC = "#1A6E3C"   # green = semantic / index arm
BASELINE = "#C2693C"   # terracotta = grep/find baseline
plt.rcParams.update({
    "text.color": INK, "axes.labelcolor": INK,
    "xtick.color": INK, "ytick.color": INK,
    "axes.edgecolor": "#BBBBBB", "font.family": "DejaVu Sans",
})

rows = list(csv.DictReader(open(CSV)))
S = [r for r in rows if r["arm"] == "semantic"]
B = [r for r in rows if r["arm"] == "baseline"]
def col(arm, k): return [float(r[k]) for r in arm]
def mean(arm, k): return st.mean(col(arm, k))
def med(arm, k):  return st.median(col(arm, k))

# ---------------------------------------------------------------- figure 1
fig, (axA, axC) = plt.subplots(1, 2, figsize=(11, 4.7),
                               gridspec_kw={"width_ratios": [1.35, 1]})
fig.suptitle("Locating a PR's change surface — semantic index vs grep/find (22 Hazelcast PRs)",
             fontsize=13.5, fontweight="bold", y=0.99)

# Panel A: median accuracy (0-1, higher better)
groups = [("File F1", "file_f1"), ("Symbol F1", "symbol_f1"), ("Judge", "judge_score")]
xs = range(len(groups)); w = 0.38
sem = [med(S, k) for _, k in groups]; base = [med(B, k) for _, k in groups]
axA.bar([x - w/2 for x in xs], sem,  w, color=SEMANTIC, edgecolor="white", label="semantic (index)")
axA.bar([x + w/2 for x in xs], base, w, color=BASELINE, edgecolor="white", label="baseline (grep/find)")
for x, v in zip(xs, sem):  axA.text(x - w/2, v + 0.015, f"{v:.2f}", ha="center", fontsize=9, fontweight="bold")
for x, v in zip(xs, base): axA.text(x + w/2, v + 0.015, f"{v:.2f}", ha="center", fontsize=9, fontweight="bold")
axA.set_title("Accuracy — median (higher is better)", fontsize=11.5, fontweight="bold", pad=10)
axA.set_xticks(list(xs)); axA.set_xticklabels([g for g, _ in groups], fontsize=10)
axA.set_ylim(0, 0.85); axA.legend(fontsize=8.5, frameon=False, loc="upper right")
axA.spines[["top", "right"]].set_visible(False); axA.grid(axis="y", color=GRID, linewidth=0.8)

# Panel C: cost per run (USD, lower better) — median + mean
cgroups = [("Median", med), ("Mean", mean)]
xs = range(len(cgroups))
sem = [fn(S, "cost_usd") for _, fn in cgroups]; base = [fn(B, "cost_usd") for _, fn in cgroups]
axC.bar([x - w/2 for x in xs], sem,  w, color=SEMANTIC, edgecolor="white")
axC.bar([x + w/2 for x in xs], base, w, color=BASELINE, edgecolor="white")
for x, v in zip(xs, sem):  axC.text(x - w/2, v + 0.02, f"${v:.2f}", ha="center", fontsize=9, fontweight="bold")
for x, v in zip(xs, base): axC.text(x + w/2, v + 0.02, f"${v:.2f}", ha="center", fontsize=9, fontweight="bold")
axC.set_title("Cost per run (lower is better)", fontsize=11.5, fontweight="bold", pad=10)
axC.set_xticks(list(xs)); axC.set_xticklabels([g for g, _ in cgroups], fontsize=10)
axC.set_ylim(0, max(sem + base) * 1.25)
axC.spines[["top", "right"]].set_visible(False); axC.grid(axis="y", color=GRID, linewidth=0.8)

fig.text(0.5, 0.005,
         "Accuracy is a tie (file-F1 head-to-head 8–8; judge favors grep 9–6); "
         "grep is ~50% cheaper. n = 22 PRs, one repo/model — direction, not a law.  "
         "Source: github.com/csharp36/cml (bench/discovery/)",
         ha="center", fontsize=8, color="#666")
fig.tight_layout(rect=[0, 0.03, 1, 0.95])
p1 = f"{OUT}/discovery-fig-accuracy-cost.jpeg"
fig.savefig(p1, dpi=160, bbox_inches="tight", facecolor="white", pil_kwargs={"quality": 92})
print("wrote", p1)

# ---------------------------------------------------------------- table image
metrics = [
    ("file F1",   "file_f1",   "{:.3f}"),
    ("symbol F1", "symbol_f1", "{:.3f}"),
    ("judge",     "judge_score", "{:.3f}"),
    ("cost ($)",  "cost_usd",  "{:.3f}"),
    ("turns",     "turns",     "{:.1f}"),
    ("wall (s)",  "wall_s",    "{:.1f}"),
]
headers = ["metric", "semantic\n(mean)", "baseline\n(mean)", "semantic\n(median)", "baseline\n(median)"]
data = [[name, fmt.format(mean(S, k)), fmt.format(mean(B, k)),
         fmt.format(med(S, k)), fmt.format(med(B, k))] for name, k, fmt in metrics]

figT, axT = plt.subplots(figsize=(8.2, 3.0)); axT.axis("off")
axT.set_title("Headline results  (n = 22 per arm)", fontsize=13, fontweight="bold", pad=14, loc="left")
tbl = axT.table(cellText=data, colLabels=headers, cellLoc="center", loc="center")
tbl.auto_set_font_size(False); tbl.set_fontsize(10.5); tbl.scale(1, 1.9)
ncols = len(headers)
for (r, c), cell in tbl.get_celld().items():
    cell.set_edgecolor("#DDDDDD")
    if r == 0:  # header row
        cell.set_facecolor("#F2F2F2"); cell.set_text_props(fontweight="bold", color=INK)
    elif c == 0:  # metric name column
        cell.set_text_props(fontweight="bold", color=INK); cell.set_facecolor("#FBFBFB")
    # tint the arm that wins each accuracy/cost row (lower cost/turns/wall = better)
for ri, (name, k, _fmt) in enumerate(metrics, start=1):
    sm_med, bs_med = med(S, k), med(B, k)
    better_sem = sm_med > bs_med if k in ("file_f1", "symbol_f1", "judge_score") else sm_med < bs_med
    tbl[(ri, 3)].set_facecolor("#E7F2EB" if better_sem else "#FFFFFF")
    tbl[(ri, 4)].set_facecolor("#FFFFFF" if better_sem else "#F6E9E1")
figT.text(0.5, 0.02, "Median favors the index on accuracy; grep is cheaper on every effort metric. "
          "Green/terracotta tint = better median per row.", ha="center", fontsize=8, color="#666")
p2 = f"{OUT}/discovery-table-headline.jpeg"
figT.savefig(p2, dpi=170, bbox_inches="tight", facecolor="white")
print("wrote", p2)

# ---------------------------------------------------------------- per-instance table
import json
inst = {}
for line in open("bench/discovery/instances.jsonl"):
    if line.strip():
        d = json.loads(line); inst[d["id"]] = d
Si = {r["instance_id"]: r for r in S}
Bi = {r["instance_id"]: r for r in B}
ids = sorted(set(Si) & set(Bi), key=lambda i: inst[i]["base_sha"])

def label(i):
    t = inst[i]["task"].splitlines()[0].strip()
    return (t[:44] + "…") if len(t) > 45 else t

pi_headers = ["PR (task)", "sem\nfile-F1", "base\nfile-F1", "sem\njudge", "base\njudge", "sem $", "base $"]
pi_rows = []
for i in ids:
    s, b = Si[i], Bi[i]
    pi_rows.append([label(i),
                    f"{float(s['file_f1']):.2f}", f"{float(b['file_f1']):.2f}",
                    f"{float(s['judge_score']):.2f}", f"{float(b['judge_score']):.2f}",
                    f"${float(s['cost_usd']):.2f}", f"${float(b['cost_usd']):.2f}"])

figP, axP = plt.subplots(figsize=(9.6, 9.4)); axP.axis("off")
axP.set_title("Per-instance results  (22 Hazelcast PRs)", fontsize=13, fontweight="bold",
              pad=14, loc="left")
tblP = axP.table(cellText=pi_rows, colLabels=pi_headers, cellLoc="center", loc="center",
                 colWidths=[0.40, 0.105, 0.105, 0.095, 0.095, 0.08, 0.08])
tblP.auto_set_font_size(False); tblP.set_fontsize(9.3); tblP.scale(1, 1.45)
for (r, c), cell in tblP.get_celld().items():
    cell.set_edgecolor("#E2E2E2")
    if r == 0:
        cell.set_facecolor("#F2F2F2"); cell.set_text_props(fontweight="bold", color=INK)
    else:
        if r % 2 == 0:
            cell.set_facecolor("#FAFAFA")
        if c == 0:
            cell.set_text_props(ha="left", color=INK); cell.PAD = 0.02
# tint the file-F1 winner per row (skip ties) to echo the head-to-head story
for ri, i in enumerate(ids, start=1):
    sf, bf = float(Si[i]["file_f1"]), float(Bi[i]["file_f1"])
    if abs(sf - bf) < 1e-9:
        continue
    win_sem = sf > bf
    tblP[(ri, 1)].set_facecolor("#E7F2EB" if win_sem else tblP[(ri, 1)].get_facecolor())
    tblP[(ri, 2)].set_facecolor("#F6E9E1" if not win_sem else tblP[(ri, 2)].get_facecolor())
figP.text(0.5, 0.015, "Green = semantic won file-F1 that PR; terracotta = grep won. "
          "Ties uncolored. n = 22, one repo/model.", ha="center", fontsize=8, color="#666")
p3 = f"{OUT}/discovery-table-perinstance.jpeg"
figP.savefig(p3, dpi=170, bbox_inches="tight", facecolor="white")
print("wrote", p3)
