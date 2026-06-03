#!/usr/bin/env python3
"""Render the three Article-3 result tables as JPEGs for Medium (which doesn't
render markdown tables). Matches the editorial style of bench/make_discovery_chart.py.
Numbers are the means across the 12 graded questions vs the bytecode oracle —
source: docs/superpowers/results/2026-06-02-arenaA-graded-results.md."""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

INK = "#242424"
WIN  = "#E7F2EB"   # green tint  = SCIP (the proven winner)
MID  = "#FBF6E9"   # faint amber = tree-sitter
LOSE = "#F6E9E1"   # terracotta  = grep
plt.rcParams.update({
    "text.color": INK, "axes.labelcolor": INK,
    "xtick.color": INK, "ytick.color": INK, "font.family": "DejaVu Sans",
})
OUT = "docs/superpowers/results"


def render(path, title, headers, rows, subtitle, colwidths, row_tints=None,
           figsize=(9.0, 2.6), fontsize=11, header_multiline=False):
    fig, ax = plt.subplots(figsize=figsize); ax.axis("off")
    ax.set_title(title, fontsize=13.5, fontweight="bold", pad=14, loc="left")
    tbl = ax.table(cellText=rows, colLabels=headers, cellLoc="center",
                   loc="center", colWidths=colwidths)
    tbl.auto_set_font_size(False); tbl.set_fontsize(fontsize)
    tbl.scale(1, 2.4 if header_multiline else 2.1)
    for (r, c), cell in tbl.get_celld().items():
        cell.set_edgecolor("#DDDDDD")
        if r == 0:
            cell.set_facecolor("#F2F2F2"); cell.set_text_props(fontweight="bold", color=INK)
        elif c == 0:
            cell.set_text_props(fontweight="bold", color=INK, ha="left"); cell.set_facecolor("#FBFBFB")
    if row_tints:
        for (r, c), color in row_tints.items():
            tbl[(r, c)].set_facecolor(color)
    fig.text(0.5, 0.015, subtitle, ha="center", fontsize=8, color="#666")
    fig.savefig(path, dpi=170, bbox_inches="tight", facecolor="white",
                pil_kwargs={"quality": 92})
    print("wrote", path)


# ---- Table 1: TL;DR (arm rows, SCIP row tinted green) -------------------------
render(
    f"{OUT}/article3-table-tldr.jpeg",
    "Who implements X, transitively?  (12 questions)",
    ["arm", "how it works", "recall", "F1", "queries /\nquestion"],
    [["semantic index (SCIP)", "compiler-grade type graph", "0.97", "0.88", "1"],
     ["tree-sitter (CTE)", "parsed implements / extends", "0.50", "0.50", "1"],
     ["grep-iterative", "BFS of grep over source", "0.32", "0.32", "102  (max 481)"]],
    "grep < tree-sitter < SCIP — a clean monotone result. Scored vs an independent compiled-bytecode oracle. "
    "Green = the index (SCIP) arm.",
    colwidths=[0.29, 0.33, 0.10, 0.09, 0.17],
    row_tints={(1, c): WIN for c in range(5)},
    figsize=(11.0, 2.6), header_multiline=True,
)

# ---- Table 2: full result (precision/recall/F1/calls) ------------------------
render(
    f"{OUT}/article3-table-result.jpeg",
    "Means across 12 questions, scored vs bytecode truth",
    ["arm", "precision", "recall", "F1", "calls / question"],
    [["SCIP", "0.861", "0.972", "0.877", "1"],
     ["tree-sitter (CTE)", "0.666", "0.496", "0.499", "1"],
     ["grep-iterative", "0.650", "0.316", "0.324", "102  (max 481)"]],
    "All three arms are similarly precise; SCIP wins on recall (completeness) and on cost. "
    "Green = SCIP.",
    colwidths=[0.30, 0.15, 0.15, 0.13, 0.20],
    row_tints={(1, c): WIN for c in range(5)},
    figsize=(9.6, 2.5),
)

# ---- Table 3: by stratum (SCIP column tinted green) --------------------------
render(
    f"{OUT}/article3-table-strata.jpeg",
    "F1 by task flavor",
    ["stratum", "grep", "tree-sitter", "SCIP"],
    [["structural (8)", "0.245", "0.379", "0.828"],
     ["lexical (4)", "0.482", "0.741", "0.975"]],
    "SCIP wins both strata — including the lexical controls that should favor grep, "
    "because the transitive answer still needs the type graph. Green = SCIP column.",
    colwidths=[0.30, 0.18, 0.22, 0.18],
    row_tints={(1, 3): WIN, (2, 3): WIN},
    figsize=(8.6, 2.0),
)
