#!/usr/bin/env python3
"""Generate the JPEG figures for Article 4 (Crossing to COBOL).

Produces, in this directory:
  article4-hero.jpeg          conceptual hero (Java type graph lit / COBOL one-slice lit)
  article4-results.jpeg       grep vs oracle F1 by stratum (bar chart)
  article4-strata.jpeg        the 5-strata definition table
  article4-table-result.jpeg  the per-stratum results table
  article4-ablation.jpeg      §9 hub-ablation: Jaccard collapse + grep F1 0.145->0.438

All numbers match bench/cobolA/ (CardDemo @59cc6c2). Reproducible: python3 make_article4_figures.py
"""
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

OUT = Path(__file__).resolve().parent
JPG = {"quality": 90}
plt.rcParams.update({"font.family": "DejaVu Sans", "axes.titlesize": 13})

GREP = "#d1495b"      # warm red
ORACLE = "#1b998b"    # teal
INK = "#22223b"
MUTED = "#9a9a9a"


def save(fig, name):
    fig.savefig(OUT / name, dpi=200, bbox_inches="tight", pil_kwargs=JPG)
    plt.close(fig)
    print("wrote", name)


# ---------------------------------------------------------------- results bars
def results_bar():
    strata = ["call_closure", "data_access", "data_coupling", "copybook_fan", "txn_reach"]
    grep = [0.145, 0.749, 0.674, 1.000, 0.212]
    proxy = [1.0] * 5
    x = np.arange(len(strata)); w = 0.38
    fig, ax = plt.subplots(figsize=(9.5, 5), dpi=200)
    b1 = ax.bar(x - w/2, grep, w, label="grep (iterative)", color=GREP)
    b2 = ax.bar(x + w/2, proxy, w, label="oracle via one MCP call (proxy)", color=ORACLE)
    for bars, vals in ((b1, grep), (b2, proxy)):
        for r, v in zip(bars, vals):
            ax.text(r.get_x() + r.get_width()/2, v + 0.02, f"{v:.2f}",
                    ha="center", fontsize=9, color=INK)
    # mark the two grep-hard (dynamic-dispatch) strata
    for i in (0, 4):
        ax.text(i, -0.10, "grep-hard\n(dynamic dispatch)", ha="center", va="top",
                fontsize=8, color=GREP)
    ax.set_xticks(x); ax.set_xticklabels(strata, rotation=12, ha="right")
    ax.set_ylabel("F1 vs hybrid answer key"); ax.set_ylim(0, 1.12)
    ax.set_title("COBOL reachability — grep vs oracle F1 by stratum\nAWS CardDemo, 44 programs (pinned 59cc6c2)")
    ax.legend(loc="center right", frameon=False)
    ax.spines[["top", "right"]].set_visible(False)
    save(fig, "article4-results.jpeg")


# ---------------------------------------------------------------- table helper
def render_table(rows, col_labels, col_widths, name, title, highlight_col=None,
                 figsize=(10, 3.0), fontsize=10, bold_cells=None):
    fig, ax = plt.subplots(figsize=figsize, dpi=200)
    ax.axis("off")
    if title:
        ax.set_title(title, loc="left", fontsize=12, pad=12, color=INK)
    tbl = ax.table(cellText=rows, colLabels=col_labels, colWidths=col_widths,
                   cellLoc="left", loc="center")
    tbl.auto_set_font_size(False); tbl.set_fontsize(fontsize); tbl.scale(1, 1.55)
    ncol = len(col_labels)
    for (r, c), cell in tbl.get_celld().items():
        cell.set_edgecolor("#e3e3e3")
        if r == 0:
            cell.set_facecolor(INK); cell.set_text_props(color="white", weight="bold")
        elif r % 2 == 0:
            cell.set_facecolor("#f5f5f7")
        if bold_cells and (r, c) in bold_cells:
            cell.set_text_props(weight="bold", color=GREP)
    save(fig, name)


def strata_table():
    rows = [
        ["1", "call_closure", "programs transitively reachable via CALL + CICS XCTL/LINK", "yes", "audit"],
        ["2", "data_access", "programs that read/write physical file R", "yes", "oracle"],
        ["3", "data_coupling", "programs sharing a physical file / DB2 table", "yes", "audit"],
        ["4", "copybook_fan", "programs that COPY copybook C", "—", "oracle"],
        ["5", "txn_reach", "programs reachable when CICS transaction T starts", "—", "audit"],
    ]
    render_table(
        rows, ["#", "stratum", "question (the relations that matter for decomposition)", "gating", "key"],
        [0.05, 0.16, 0.59, 0.10, 0.10],
        "article4-strata.jpeg",
        "The 5 strata — 80 questions over AWS CardDemo",
        figsize=(11, 2.7), fontsize=10)


def result_table():
    rows = [
        ["call_closure", "0.145", "1.000", "21", "●"],
        ["data_access", "0.749", "1.000", "10", "●"],
        ["data_coupling", "0.674", "1.000", "13", "●"],
        ["copybook_fan", "1.000", "1.000", "15", " "],
        ["txn_reach", "0.212", "1.000", "21", " "],
    ]
    # bold the two grep-hard F1s (col 1, rows 1 and 5 in table coords incl header)
    render_table(
        rows, ["stratum", "grep F1", "proxy F1", "n", "gating"],
        [0.30, 0.18, 0.18, 0.10, 0.14],
        "article4-table-result.jpeg",
        "Per-stratum results  —  verdict: AMBIGUOUS (gating macro-avg: proxy 1.00, grep 0.523, gap 0.477)",
        figsize=(9, 2.6), fontsize=11,
        bold_cells={(1, 1), (5, 1)})


# ---------------------------------------------------------------- ablation
def ablation_fig():
    fig, (axL, axR) = plt.subplots(1, 2, figsize=(11, 4.6), dpi=200)

    # left: degeneracy (mean pairwise Jaccard of the 21 call_closure answer-sets)
    axL.bar(["normal\n(through shell)", "hub ablated\n(shell = boundary)"], [0.913, 0.242],
            color=[MUTED, ORACLE], width=0.55)
    for i, v in enumerate([0.913, 0.242]):
        axL.text(i, v + 0.02, f"{v:.3f}", ha="center", fontsize=11, color=INK)
    axL.set_ylim(0, 1.05); axL.set_ylabel("mean pairwise Jaccard of answer-sets")
    axL.set_title("call_closure degeneracy\n(1.0 = every question ≈ same answer)")
    axL.spines[["top", "right"]].set_visible(False)

    # right: grep F1 on call_closure, normal vs ablated, with the 0.45 bar
    axR.bar(["normal", "hub ablated\n(floor ≥2)"], [0.145, 0.438],
            color=[GREP, GREP], width=0.55)
    axR.text(0, 0.145 + 0.02, "0.145", ha="center", fontsize=11, color=INK)
    axR.text(1, 0.438 - 0.045, "0.438", ha="center", fontsize=11, color="white", weight="bold")
    axR.axhline(0.45, ls="--", color=INK, lw=1)
    axR.text(-0.45, 0.55, "pre-registered grep ≤ 0.45 bar", ha="left", fontsize=8.5, color=INK)
    axR.set_ylim(0, 0.66); axR.set_ylabel("grep F1 on call_closure")
    axR.set_title("most of the call_closure 'win'\nwas the navigation shell")
    axR.spines[["top", "right"]].set_visible(False)

    fig.suptitle("Hub ablation — separating the structural win from the SCC navigation artifact",
                 fontsize=13, y=1.02)
    save(fig, "article4-ablation.jpeg")


# ---------------------------------------------------------------- hero
def hero():
    rng = np.random.default_rng(7)
    fig, ax = plt.subplots(figsize=(12, 6), dpi=200)
    fig.patch.set_facecolor("#0d1117"); ax.set_facecolor("#0d1117")
    ax.set_xlim(0, 12); ax.set_ylim(0, 6.4); ax.axis("off")

    def cluster(cx, cy, n, spread, seed):
        r = np.random.default_rng(seed)
        return np.column_stack([r.normal(cx, spread, n), r.normal(cy, spread*0.62, n)])

    # LEFT — Java: dense, fully lit type graph
    J = cluster(3.0, 3.0, 16, 1.05, 1)
    for i in range(len(J)):
        for j in range(i + 1, len(J)):
            if np.hypot(*(J[i] - J[j])) < 1.5:
                ax.plot(*zip(J[i], J[j]), color="#39c5cf", lw=0.7, alpha=0.5, zorder=1)
    ax.scatter(J[:, 0], J[:, 1], s=130, color="#56d4dd", edgecolor="white",
               linewidth=0.6, zorder=2)

    # RIGHT — COBOL: mostly dim, one bright OCCURS-dispatch cluster
    C = cluster(8.8, 3.0, 18, 1.25, 4)
    ax.scatter(C[:, 0], C[:, 1], s=95, color="#3a3f4b", edgecolor="#555", linewidth=0.5, zorder=2)
    occ = cluster(9.2, 3.2, 6, 0.55, 9)
    hub = np.array([9.2, 3.2])
    for p in occ:
        ax.plot(*zip(hub, p), color="#f5b301", lw=1.0, alpha=0.8, zorder=3)
    ax.scatter(occ[:, 0], occ[:, 1], s=120, color="#ffce3a", edgecolor="white",
               linewidth=0.6, zorder=4)
    ax.scatter([hub[0]], [hub[1]], s=240, color="#ffe07a", edgecolor="white",
               linewidth=1.0, zorder=5)

    # seam
    ax.axvline(6.0, color="#30363d", lw=1.4, ls=(0, (6, 5)))

    # labels
    ax.text(3.0, 5.5, "JAVA", color="#56d4dd", fontsize=20, fontweight="bold", ha="center")
    ax.text(3.0, 5.05, "type graph — mostly lit", color="#9fe7ec", fontsize=11, ha="center")
    ax.text(8.9, 5.5, "COBOL", color="#ffce3a", fontsize=20, fontweight="bold", ha="center")
    ax.text(8.9, 5.05, "one slice lit: the OCCURS dispatch", color="#f0d68a", fontsize=11, ha="center")
    ax.text(6.0, 0.55, "Does the win cross a language barrier?", color="white",
            fontsize=15, ha="center", style="italic")
    save(fig, "article4-hero.jpeg")


if __name__ == "__main__":
    results_bar()
    strata_table()
    result_table()
    ablation_fig()
    hero()
