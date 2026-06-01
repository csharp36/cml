#!/usr/bin/env python3
"""Generate the headline figure for the findings writeup / Medium article.
Real numbers from the isolated runs (n=1/arm): take 1 (pre-confound-fix) and
take 2 (post-fix). PR #4317, hazelcast."""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

# Medium editorial palette: charcoal text, light grid, white bg.
INK = "#242424"; GRID = "#E6E6E6"
plt.rcParams.update({
    "text.color": INK, "axes.labelcolor": INK,
    "xtick.color": INK, "ytick.color": INK,
    "axes.edgecolor": "#BBBBBB",
    "font.family": "DejaVu Sans",
})

# (label, cost_usd, turns, wall_min, color)
SEMANTIC = "#1A6E3C"   # Medium green (dark) = semantic / index arm
SEMANTIC2 = "#74B98C"  # Medium green (light) = semantic after fixes
BASELINE = "#C2693C"   # muted terracotta = grep/find baseline

bars = [
    ("Semantic\n(take 1)", 15.12, 116, 63.1, SEMANTIC),
    ("Semantic\n(take 2,\nconfounds fixed)", 5.87, 82, 53.5, SEMANTIC2),
    ("Baseline\n(grep/find)", 0.93, 21, 6.3, BASELINE),
]
labels  = [b[0] for b in bars]
colors  = [b[4] for b in bars]
metrics = [
    ("Cost per run (USD)", [b[1] for b in bars], lambda v: f"${v:,.2f}"),
    ("Agent turns",        [b[2] for b in bars], lambda v: f"{v:,.0f}"),
    ("Wall-clock (min)",   [b[3] for b in bars], lambda v: f"{v:,.0f}"),
]

fig, axes = plt.subplots(1, 3, figsize=(12, 4.6))
fig.suptitle("Implementing one real PR (#4317) — semantic index vs grep/find baseline",
             fontsize=14, fontweight="bold", y=0.99)

for ax, (title, vals, fmt) in zip(axes, metrics):
    xs = range(len(vals))
    b = ax.bar(xs, vals, color=colors, width=0.66, edgecolor="white", linewidth=0.8)
    ax.set_title(title, fontsize=11.5, fontweight="bold", pad=10)
    ax.set_xticks(list(xs))
    ax.set_xticklabels(labels, fontsize=8.5)
    ax.set_ylim(0, max(vals) * 1.22)
    for rect, v in zip(b, vals):
        ax.text(rect.get_x() + rect.get_width() / 2, v + max(vals) * 0.025,
                fmt(v), ha="center", va="bottom", fontsize=9.5, fontweight="bold")
    ax.spines[["top", "right"]].set_visible(False)
    ax.tick_params(axis="y", labelsize=8)
    ax.grid(axis="y", color=GRID, linewidth=0.8)

# ratio annotations on the cost panel (the headline)
cost_ax = axes[0]
cost_ax.annotate("13× cheaper", xy=(2, 0.93), xytext=(0.55, 12.5),
                 fontsize=9, color="#444", fontweight="bold",
                 arrowprops=dict(arrowstyle="->", color="#888", lw=1.1))
cost_ax.annotate("6.3× cheaper", xy=(2, 0.93), xytext=(1.05, 6.0),
                 fontsize=9, color="#444", fontweight="bold",
                 arrowprops=dict(arrowstyle="->", color="#888", lw=1.1))

fig.text(0.5, 0.005,
         "Both arms passed the PR's tests. n = 1 per arm, single task — direction only. "
         "Lower is better.  Source: github.com/csharp36/cml  (bench/)",
         ha="center", fontsize=8, color="#666")

fig.tight_layout(rect=[0, 0.03, 1, 0.95])
out = "docs/superpowers/results/figure-cost-turns-wall.png"  # tracked, next to the writeup
fig.savefig(out, dpi=160, bbox_inches="tight", facecolor="white")
print("wrote", out)
