#!/usr/bin/env python3
"""Editorial hero image for the discovery-benchmark article.

Concept: a vast dark field of code-file tiles; two routes start from the same
point and converge on the same lit "change-surface" tile — the index route a
clean structural arc (green), the grep route a scattered sweeping trail
(terracotta). Both arrive (accuracy tie); one is tidier. 16:9, no body text.

Deterministic layout (fixed seed via explicit coords) so it regenerates identically.
"""
import math
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch
from matplotlib.patheffects import withStroke

BG = "#15171A"; TILE = "#23272D"; TILE_E = "#2B3037"
SEMANTIC = "#2E9E57"; BASELINE = "#D07A45"; TARGET = "#F4D06F"; INK = "#E8E8E8"

fig, ax = plt.subplots(figsize=(12.8, 7.2))
fig.patch.set_facecolor(BG); ax.set_facecolor(BG)
ax.set_xlim(0, 16); ax.set_ylim(0, 9); ax.axis("off")

# --- field of code-file tiles -------------------------------------------------
cols, rows = 16, 9
target = (12.4, 5.7)     # the lit change-surface tile
start = (2.0, 3.0)       # where both searches begin
for i in range(cols):
    for j in range(rows):
        x = 0.7 + i * 0.95; y = 0.6 + j * 0.92
        # deterministic pseudo-jitter
        jx = 0.12 * math.sin(i * 1.7 + j); jy = 0.10 * math.cos(j * 1.9 + i)
        w, h = 0.62, 0.34
        d = math.hypot(x - target[0], y - target[1])
        alpha = 0.30 + 0.45 * max(0, 1 - d / 9)
        tile = FancyBboxPatch((x + jx, y + jy), w, h,
                              boxstyle="round,pad=0.015,rounding_size=0.06",
                              fc=TILE, ec=TILE_E, lw=0.7, alpha=alpha, zorder=1)
        ax.add_patch(tile)

# --- the two routes -----------------------------------------------------------
def arc(p0, p1, lift, color, lw, alpha, z, dashed=False):
    fa = FancyArrowPatch(p0, p1, connectionstyle=f"arc3,rad={lift}",
                         arrowstyle="-", color=color, lw=lw, alpha=alpha,
                         zorder=z, capstyle="round",
                         linestyle=(0, (1.2, 2.2)) if dashed else "solid")
    ax.add_patch(fa)

# grep: scattered sweeping trail (several jagged probes before reaching target)
probes = [(4.6, 6.4), (6.2, 1.7), (8.1, 6.9), (9.6, 2.2), (10.8, 6.1), target]
prev = start
for k, pt in enumerate(probes):
    arc(prev, pt, 0.22 if k % 2 else -0.22, BASELINE, 2.0, 0.55, 2, dashed=True)
    ax.scatter(*pt, s=22, color=BASELINE, alpha=0.7, zorder=3)
    prev = pt

# index: one clean structural arc through a few connected nodes
nodes = [start, (5.5, 4.6), (8.8, 5.9), target]
for a, b in zip(nodes, nodes[1:]):
    arc(a, b, -0.18, SEMANTIC, 3.2, 0.95, 4)
ax.scatter([n[0] for n in nodes], [n[1] for n in nodes],
           s=42, color=SEMANTIC, edgecolor=BG, lw=1.2, zorder=5)

# --- start + target glyphs ----------------------------------------------------
ax.scatter(*start, s=240, color=INK, edgecolor=BG, lw=2, zorder=6)
ax.scatter(*start, s=70, color=BG, zorder=7)
# target tile lit
tgt = FancyBboxPatch((target[0] - 0.42, target[1] - 0.26), 0.84, 0.5,
                     boxstyle="round,pad=0.02,rounding_size=0.08",
                     fc=TARGET, ec="#FFFFFF", lw=1.4, zorder=8)
tgt.set_path_effects([withStroke(linewidth=10, foreground="#F4D06F", alpha=0.30)])
ax.add_patch(tgt)

# --- minimal editorial labels -------------------------------------------------
# minimal inline labels only (no title/subtitle — Medium's own headline carries it)
ax.text(start[0], start[1] - 0.72, "the question", color=INK, fontsize=11.5,
        ha="center", va="top", family="DejaVu Sans", alpha=0.85)
ax.text(target[0], target[1] + 0.72, "the change surface", color=TARGET, fontsize=12,
        ha="center", va="bottom", family="DejaVu Sans", fontweight="bold")
ax.text(5.5, 4.6 + 0.52, "index", color=SEMANTIC, fontsize=11.5, ha="center",
        fontweight="bold", family="DejaVu Sans")
ax.text(6.2, 1.7 - 0.58, "grep / find", color=BASELINE, fontsize=11.5, ha="center",
        fontweight="bold", family="DejaVu Sans")

# soft vignette: transparent center, darkening toward the corners (adds depth)
import numpy as np
gx, gy = np.meshgrid(np.linspace(0, 16, 240), np.linspace(0, 9, 135))
edge = np.clip(((gx - 8) / 9.2) ** 2 + ((gy - 4.5) / 5.4) ** 2, 0, 1) ** 1.3
vig = np.zeros((*edge.shape, 4)); vig[..., 3] = edge * 0.45  # black, alpha rises at edges
ax.imshow(vig, extent=(0, 16, 0, 9), origin="lower", zorder=9, aspect="auto")

out = "docs/superpowers/results/discovery-hero.png"
fig.savefig(out, dpi=150, bbox_inches="tight", facecolor=BG, pad_inches=0.2)
print("wrote", out)
