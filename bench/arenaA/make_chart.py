#!/usr/bin/env python3
"""Headline figure for Article 3 — type-resolved reachability.
Means across the 12 graded questions, scored vs the compiled-bytecode oracle.
Source: bench/arenaA/results/ (see ../../docs/superpowers/results/2026-06-02-arenaA-graded-results.md)."""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

# Medium editorial palette (matches ../make_chart.py): charcoal text, light grid, white bg.
INK = "#242424"; GRID = "#E6E6E6"
plt.rcParams.update({
    "text.color": INK, "axes.labelcolor": INK,
    "xtick.color": INK, "ytick.color": INK,
    "axes.edgecolor": "#BBBBBB",
    "font.family": "DejaVu Sans",
})

BASELINE = "#C2693C"   # terracotta = grep-iterative
TREESIT  = "#C9A227"   # amber      = tree-sitter (parsed, name-matched)
SCIP     = "#1A6E3C"   # green      = SCIP (compiler-resolved)

# (label, recall, F1, calls, color)
arms = [
    ("grep-iterative",      0.316, 0.324, 102, BASELINE),
    ("tree-sitter\n(CTE)",  0.496, 0.499, 1,   TREESIT),
    ("SCIP\n(type graph)",  0.972, 0.877, 1,   SCIP),
]
labels = [a[0] for a in arms]
colors = [a[4] for a in arms]
calls  = [a[3] for a in arms]

panels = [
    ("Recall (completeness)", [a[1] for a in arms]),
    ("F1", [a[2] for a in arms]),
]

fig, axes = plt.subplots(1, 2, figsize=(9.6, 4.7))
fig.suptitle("Who implements X, transitively? — 12 questions, scored vs bytecode oracle",
             fontsize=13.5, fontweight="bold", y=0.99)

for ax, (title, vals) in zip(axes, panels):
    xs = range(len(vals))
    b = ax.bar(xs, vals, color=colors, width=0.62, edgecolor="white", linewidth=0.8)
    ax.set_title(title, fontsize=12, fontweight="bold", pad=10)
    ax.set_xticks(list(xs))
    ax.set_xticklabels(labels, fontsize=9)
    ax.set_ylim(0, 1.08)
    for rect, v in zip(b, vals):
        ax.text(rect.get_x() + rect.get_width() / 2, v + 0.02,
                f"{v:.2f}", ha="center", va="bottom", fontsize=10, fontweight="bold")
    ax.spines[["top", "right"]].set_visible(False)
    ax.tick_params(axis="y", labelsize=8)
    ax.grid(axis="y", color=GRID, linewidth=0.8)

# Cost annotation on the F1 panel — the index wins accuracy AND cost.
f1_ax = axes[1]
f1_ax.annotate("1 query", xy=(2, 0.877), xytext=(1.55, 0.50),
               fontsize=9, color="#444", fontweight="bold",
               arrowprops=dict(arrowstyle="->", color="#888", lw=1.1))
f1_ax.annotate("102 queries\n(max 481)", xy=(0, 0.324), xytext=(-0.15, 0.62),
               fontsize=9, color="#444", fontweight="bold",
               arrowprops=dict(arrowstyle="->", color="#888", lw=1.1))

fig.text(0.5, 0.005,
         "Means across 12 questions (8 structural / 4 lexical), single repo / commit / language — "
         "direction only. Higher is better.  Source: github.com/csharp36/cml  (bench/arenaA/)",
         ha="center", fontsize=8, color="#666")

fig.tight_layout(rect=[0, 0.03, 1, 0.95])
out = "docs/superpowers/results/figure-arenaA-recall-f1.png"
fig.savefig(out, dpi=160, bbox_inches="tight", facecolor="white")
print("wrote", out)
