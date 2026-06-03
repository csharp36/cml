# COBOL→Java Decomposition Feasibility — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and run the Phase 0 reconnaissance gate of the CardDemo decomposition feasibility study — a cheap, regex-level characterization of CardDemo's COBOL that decides whether a call/data-coupling reachability oracle could plausibly beat grep, or whether we stop with a "not a fit" verdict.

**Architecture:** A self-contained `bench/cobolA/` benchmark directory mirroring the existing `bench/arenaA/` conventions (pytest, JSONL, `results/`). Phase 0 is pure-Python static analysis over the cloned CardDemo source: fixed-format COBOL line normalization → per-program fact extraction (CALL / CICS XCTL-LINK / COPY / file ops / SQL) → a crude static call graph → depth + coupling metrics → a `PHASE0-recon.md` verdict against pre-registered gate thresholds. No ProLeap, no MCP, no agents at this phase — those are gated behind the recon result.

**Tech Stack:** Python 3.13, pytest 9, ripgrep/grep, git (CardDemo clone). ProLeap (Java/ANTLR4), MCP shim, and the agent harness arrive in Phases 1–3, planned in detail only after the Phase 0 gate passes.

**Spec:** `docs/superpowers/specs/2026-06-03-cobol-decomposition-feasibility-design.md`

---

## File Structure (Phase 0)

```
bench/cobolA/
  .gitignore                 # ignores corpus/ (cloned CardDemo, not vendored)
  pin.env                    # CardDemo repo URL + branch; resolved SHA written at clone time
  clone_corpus.sh            # clones CardDemo, pins the SHA into pin.env
  recon/
    __init__.py
    extract.py               # fixed-format normalization + per-program fact extraction (pure)
    graph.py                 # static call graph: depth + shared-data coupling metrics (pure)
    test_extract.py
    test_graph.py
    fixtures/
      progA.cbl              # known-count fixture: static+dynamic CALL, XCTL literal+var, COPY
      chain.cbl             # A→B→C static chain fixture for depth
  recon.py                   # driver: glob corpus → aggregate → recon.json + PHASE0-recon.md
  recon.json                 # committed machine-readable recon output
  PHASE0-recon.md            # committed human-readable verdict + gate recommendation
```

Phases 1–4 add `oracle/`, `questions.jsonl`, `arms/`, `score.py`, `run_all.sh`, `results/` — see the roadmap at the end.

---

## Task 1: Scaffold `bench/cobolA/` and pin the CardDemo corpus

**Files:**
- Create: `bench/cobolA/.gitignore`
- Create: `bench/cobolA/pin.env`
- Create: `bench/cobolA/clone_corpus.sh`
- Create: `bench/cobolA/recon/__init__.py` (empty package marker)

- [ ] **Step 1: Create the gitignore so the corpus is never vendored**

`bench/cobolA/.gitignore`:
```
corpus/
__pycache__/
.pytest_cache/
```

- [ ] **Step 2: Create `pin.env` with the upstream coordinates**

`bench/cobolA/pin.env`:
```bash
# AWS CardDemo — IBM Enterprise COBOL mainframe modernization sample (MIT-0).
CORPUS_URL=https://github.com/aws-samples/aws-mainframe-modernization-carddemo.git
CORPUS_BRANCH=main
# CORPUS_SHA is written by clone_corpus.sh on first clone (pin-on-clone).
CORPUS_SHA=
```

- [ ] **Step 3: Create `clone_corpus.sh` (clones + pins SHA)**

`bench/cobolA/clone_corpus.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
source pin.env
if [ ! -d corpus/.git ]; then
  git clone --branch "$CORPUS_BRANCH" "$CORPUS_URL" corpus
fi
SHA=$(git -C corpus rev-parse HEAD)
# Pin the SHA into pin.env (idempotent rewrite of the CORPUS_SHA line).
if grep -q '^CORPUS_SHA=' pin.env; then
  sed -i.bak "s|^CORPUS_SHA=.*|CORPUS_SHA=$SHA|" pin.env && rm -f pin.env.bak
fi
echo "CardDemo pinned at $SHA"
echo "COBOL programs: $(find corpus -iname '*.cbl' | wc -l | tr -d ' ')"
echo "Copybooks:      $(find corpus -iname '*.cpy' | wc -l | tr -d ' ')"
```

- [ ] **Step 4: Run the clone and verify the corpus shape**

Run:
```bash
chmod +x bench/cobolA/clone_corpus.sh && bench/cobolA/clone_corpus.sh
```
Expected: prints a pinned SHA and non-zero program/copybook counts (CardDemo has on the order of dozens of `.cbl` and `.cpy` files). `git -C bench/cobolA/corpus rev-parse HEAD` now matches `CORPUS_SHA` in `pin.env`.

- [ ] **Step 5: Create the empty package marker**

`bench/cobolA/recon/__init__.py`: (empty file)

- [ ] **Step 6: Commit**

```bash
git add bench/cobolA/.gitignore bench/cobolA/pin.env bench/cobolA/clone_corpus.sh bench/cobolA/recon/__init__.py
git commit -m "bench(cobolA): scaffold + pin CardDemo corpus for Phase 0 recon"
```

---

## Task 2: Fixed-format line normalization

COBOL in CardDemo is **fixed-format**: columns 1–6 are a sequence area, column 7 is an indicator (`*` or `/` = comment), columns 8–72 are code, 73+ is ignored. Naive regex over raw lines miscounts (comments, sequence numbers). Normalization is the foundation everything else rests on, so it is tested first.

**Files:**
- Create: `bench/cobolA/recon/extract.py`
- Test: `bench/cobolA/recon/test_extract.py`

- [ ] **Step 1: Write the failing test**

`bench/cobolA/recon/test_extract.py`:
```python
from recon.extract import normalize_line

def test_strips_sequence_area_and_trailing_columns():
    # base statement padded to col 72, then junk in cols 73+ which must be dropped
    raw = "001000     CALL 'PROGB'.".ljust(72) + "X" * 20
    assert normalize_line(raw) == "    CALL 'PROGB'."

def test_comment_line_returns_none():
    assert normalize_line("001000* this is a comment") is None
    assert normalize_line("001000/ page eject") is None

def test_short_or_blank_line_returns_empty():
    assert normalize_line("") == ""
    assert normalize_line("000100") == ""
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bench/cobolA && python -m pytest recon/test_extract.py::test_strips_sequence_area_and_trailing_columns -v`
Expected: FAIL with `ImportError`/`ModuleNotFoundError` (no `normalize_line`).

- [ ] **Step 3: Write minimal implementation**

`bench/cobolA/recon/extract.py`:
```python
"""Static fact extraction from fixed-format COBOL source (Phase 0, regex-level)."""
import re


def normalize_line(raw: str) -> str | None:
    """Return the code area (cols 8-72) of a fixed-format COBOL line.

    None  -> comment/continuation line (indicator col 7 is '*' or '/').
    ''    -> blank or sequence-only line.
    """
    if len(raw) < 7:
        return ""
    indicator = raw[6]
    if indicator in ("*", "/"):
        return None
    return raw[7:72].rstrip()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd bench/cobolA && python -m pytest recon/test_extract.py -v`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add bench/cobolA/recon/extract.py bench/cobolA/recon/test_extract.py
git commit -m "bench(cobolA): fixed-format COBOL line normalization"
```

---

## Task 3: Per-program fact extraction

Extract the edges and coupling signals that matter for decomposition. Crucially this includes **CICS `XCTL`/`LINK`** (CardDemo's online programs transfer control via `EXEC CICS XCTL PROGRAM(...)`, frequently with the target in a *variable* — the dynamic dispatch grep cannot resolve), not just COBOL `CALL`.

**Files:**
- Modify: `bench/cobolA/recon/extract.py`
- Test: `bench/cobolA/recon/test_extract.py`
- Create: `bench/cobolA/recon/fixtures/progA.cbl`

- [ ] **Step 1: Create the known-count fixture**

`bench/cobolA/recon/fixtures/progA.cbl` (columns matter — code starts at col 8):
```
000100 IDENTIFICATION DIVISION.
000200 PROGRAM-ID. PROGA.
000300 PROCEDURE DIVISION.
000400     CALL 'PROGB'.
000500     MOVE 'PROGC' TO WS-PGM.
000600     CALL WS-PGM.
000700     EXEC CICS XCTL PROGRAM('PROGD') END-EXEC.
000800     EXEC CICS XCTL PROGRAM(WS-NEXT) END-EXEC.
000900     COPY CUSTREC.
001000     READ ACCTFILE.
001100* commented CALL 'NOPE' should be ignored
001200     EXEC SQL SELECT 1 END-EXEC.
001300     STOP RUN.
```

- [ ] **Step 2: Write the failing test**

Append to `bench/cobolA/recon/test_extract.py`:
```python
import pathlib
from recon.extract import extract_program_facts

FIX = pathlib.Path(__file__).parent / "fixtures"

def test_extract_program_facts_counts():
    facts = extract_program_facts((FIX / "progA.cbl").read_text())
    assert facts["program_id"] == "PROGA"
    assert facts["static_calls"] == {"PROGB"}        # CALL 'PROGB'
    assert facts["dynamic_call_count"] == 1          # CALL WS-PGM
    assert facts["static_xctl_link"] == {"PROGD"}    # XCTL PROGRAM('PROGD')
    assert facts["dynamic_xctl_link_count"] == 1     # XCTL PROGRAM(WS-NEXT)
    assert facts["copybooks"] == {"CUSTREC"}
    assert facts["file_ops"] == {"ACCTFILE"}
    assert facts["uses_sql"] is True
    assert facts["uses_cics"] is True
    # the commented CALL 'NOPE' must not leak in
    assert "NOPE" not in facts["static_calls"]
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd bench/cobolA && python -m pytest recon/test_extract.py::test_extract_program_facts_counts -v`
Expected: FAIL (`AttributeError`/`ImportError` — no `extract_program_facts`).

- [ ] **Step 4: Write minimal implementation**

Append to `bench/cobolA/recon/extract.py`:
```python
_PROGRAM_ID = re.compile(r"\bPROGRAM-ID\.\s+([A-Z0-9][A-Z0-9-]*)", re.I)
_CALL_LIT = re.compile(r"\bCALL\s+['\"]([A-Z0-9][A-Z0-9-]*)['\"]", re.I)
_CALL_VAR = re.compile(r"\bCALL\s+(?!['\"])([A-Z0-9][A-Z0-9-]*)", re.I)
_XCTL_LINK = re.compile(
    r"\bEXEC\s+CICS\s+(?:XCTL|LINK)\b[^.]*?\bPROGRAM\s*\(\s*(['\"]?)([A-Z0-9][A-Z0-9-]*)\1?",
    re.I | re.S,
)
_COPY = re.compile(r"\bCOPY\s+([A-Z0-9][A-Z0-9-]*)", re.I)
# (?<![A-Za-z0-9-]) stops READ matching the tail of hyphenated identifiers like END-READ.
# Phase 0 crude scope: bare COBOL file verbs only — CICS file I/O (EXEC CICS READ FILE(...))
# is NOT captured, so file_ops under-counts CICS programs. PHASE0-recon.md must flag this.
_FILE_OP = re.compile(
    r"(?<![A-Za-z0-9-])(?:READ|WRITE|REWRITE|DELETE|START)\s+([A-Z0-9][A-Z0-9-]*)", re.I)
_SQL = re.compile(r"\bEXEC\s+SQL\b", re.I)
_CICS = re.compile(r"\bEXEC\s+CICS\b", re.I)


def _code_text(source_text: str) -> str:
    """Join the normalized (comment-stripped) code area of every line."""
    out = []
    for raw in source_text.splitlines():
        norm = normalize_line(raw)
        if norm is not None:
            out.append(norm)
    return "\n".join(out)


def extract_program_facts(source_text: str) -> dict:
    code = _code_text(source_text)
    pid = _PROGRAM_ID.search(code)
    static_xctl = {m.group(2).upper() for m in _XCTL_LINK.finditer(code) if m.group(1)}
    dynamic_xctl = sum(1 for m in _XCTL_LINK.finditer(code) if not m.group(1))
    return {
        "program_id": pid.group(1).upper() if pid else "",
        "static_calls": {m.group(1).upper() for m in _CALL_LIT.finditer(code)},
        "dynamic_call_count": len(_CALL_VAR.findall(code)),
        "static_xctl_link": static_xctl,
        "dynamic_xctl_link_count": dynamic_xctl,
        "copybooks": {m.group(1).upper() for m in _COPY.finditer(code)},
        "file_ops": {m.group(1).upper() for m in _FILE_OP.finditer(code)},
        "uses_sql": bool(_SQL.search(code)),
        "uses_cics": bool(_CICS.search(code)),
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd bench/cobolA && python -m pytest recon/test_extract.py -v`
Expected: PASS (all tests). If `dynamic_call_count` is 2 instead of 1, the `_CALL_VAR` regex matched `CALL 'PROGB'`'s literal — confirm the `(?!['\"])` negative lookahead is present.

- [ ] **Step 6: Commit**

```bash
git add bench/cobolA/recon/extract.py bench/cobolA/recon/test_extract.py bench/cobolA/recon/fixtures/progA.cbl
git commit -m "bench(cobolA): per-program COBOL fact extraction (CALL/XCTL/COPY/file/SQL)"
```

---

## Task 4: Static call graph — depth and coupling metrics

The gate needs three numbers: how much **dynamic dispatch** exists, how **deep** static call chains run, and how much **shared-data coupling** there is. Depth and coupling are pure graph computations over the Task 3 facts.

**Files:**
- Create: `bench/cobolA/recon/graph.py`
- Test: `bench/cobolA/recon/test_graph.py`
- Create: `bench/cobolA/recon/fixtures/chain.cbl` (referenced by the driver test in Task 5; created here for completeness)

- [ ] **Step 1: Write the failing test**

`bench/cobolA/recon/test_graph.py`:
```python
from recon.graph import build_call_graph, max_chain_depth, shared_data_fan

def test_build_call_graph_unions_call_and_xctl_edges():
    facts = {
        "A": {"static_calls": {"B"}, "static_xctl_link": {"C"}},
        "B": {"static_calls": {"C"}, "static_xctl_link": set()},
        "C": {"static_calls": set(), "static_xctl_link": set()},
    }
    g = build_call_graph(facts)
    assert g["A"] == {"B", "C"}
    assert g["B"] == {"C"}
    assert g["C"] == set()

def test_max_chain_depth_counts_nodes_in_longest_path():
    g = {"A": {"B"}, "B": {"C"}, "C": set()}     # A->B->C
    assert max_chain_depth(g) == 3

def test_max_chain_depth_tolerates_cycles():
    g = {"A": {"B"}, "B": {"A"}}                  # must not infinite-loop
    assert max_chain_depth(g) == 2

def test_shared_data_fan_counts_resources_touched_by_threshold():
    facts = {
        "A": {"copybooks": {"REC1"}, "file_ops": {"FA"}},
        "B": {"copybooks": {"REC1"}, "file_ops": {"FA"}},
        "C": {"copybooks": {"REC1"}, "file_ops": set()},
        "D": {"copybooks": set(), "file_ops": {"FA"}},
    }
    fan = shared_data_fan(facts, threshold=3)
    # REC1 touched by A,B,C (>=3) -> counted; FA touched by A,B,D (>=3) -> counted
    assert fan["resources_at_or_above_threshold"] == 2
    assert fan["copybook_to_programs"]["REC1"] == {"A", "B", "C"}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bench/cobolA && python -m pytest recon/test_graph.py -v`
Expected: FAIL (`ModuleNotFoundError: recon.graph`).

- [ ] **Step 3: Write minimal implementation**

`bench/cobolA/recon/graph.py`:
```python
"""Static call-graph depth and shared-data coupling metrics (Phase 0)."""
from collections import defaultdict


def build_call_graph(facts_by_program: dict) -> dict:
    """Union static CALL and static CICS XCTL/LINK edges into adjacency sets."""
    g = {}
    for pid, f in facts_by_program.items():
        g[pid] = set(f.get("static_calls", set())) | set(f.get("static_xctl_link", set()))
    # ensure every referenced node exists as a key
    for succs in list(g.values()):
        for s in succs:
            g.setdefault(s, set())
    return g


def max_chain_depth(graph: dict) -> int:
    """Longest simple-path node count. Cycle-safe via on-stack tracking + memo."""
    memo = {}

    def dfs(node, stack):
        if node in stack:            # cycle: stop expanding this branch
            return 0
        if node in memo:
            return memo[node]
        stack.add(node)
        best = 0
        for nxt in graph.get(node, ()):  # depth of the subtree below `node`
            best = max(best, dfs(nxt, stack))
        stack.remove(node)
        memo[node] = 1 + best
        return memo[node]

    return max((dfs(n, set()) for n in graph), default=0)


def shared_data_fan(facts_by_program: dict, threshold: int = 3) -> dict:
    copy_to = defaultdict(set)
    file_to = defaultdict(set)
    for pid, f in facts_by_program.items():
        for c in f.get("copybooks", set()):
            copy_to[c].add(pid)
        for fl in f.get("file_ops", set()):
            file_to[fl].add(pid)
    at_or_above = sum(1 for s in copy_to.values() if len(s) >= threshold) + \
                  sum(1 for s in file_to.values() if len(s) >= threshold)
    return {
        "copybook_to_programs": {k: v for k, v in copy_to.items()},
        "file_to_programs": {k: v for k, v in file_to.items()},
        "resources_at_or_above_threshold": at_or_above,
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd bench/cobolA && python -m pytest recon/test_graph.py -v`
Expected: PASS (4 tests).

- [ ] **Step 5: Create the chain fixture for the driver test**

`bench/cobolA/recon/fixtures/chain.cbl`:
```
000100 IDENTIFICATION DIVISION.
000200 PROGRAM-ID. CHAINB.
000300 PROCEDURE DIVISION.
000400     CALL 'CHAINC'.
000500     STOP RUN.
```

- [ ] **Step 6: Commit**

```bash
git add bench/cobolA/recon/graph.py bench/cobolA/recon/test_graph.py bench/cobolA/recon/fixtures/chain.cbl
git commit -m "bench(cobolA): call-graph depth + shared-data coupling metrics"
```

---

## Task 5: Recon driver — aggregate corpus, emit verdict

Ties the pieces together over the real corpus, computes the three gate numbers, and writes both `recon.json` (machine-readable) and `PHASE0-recon.md` (human verdict) with a recommendation against the pre-registered gate rule.

**Files:**
- Create: `bench/cobolA/recon.py`
- Test: `bench/cobolA/recon/test_driver.py`

- [ ] **Step 1: Write the failing test (drives the aggregation logic on fixtures)**

`bench/cobolA/recon/test_driver.py`:
```python
import pathlib
from recon import aggregate_facts, gate_metrics, gate_recommendation

FIX = pathlib.Path(__file__).parent / "fixtures"

def test_aggregate_and_metrics_over_fixture_dir():
    facts = aggregate_facts(FIX)               # parses progA.cbl + chain.cbl
    m = gate_metrics(facts)
    # progA: 1 dynamic call + 1 dynamic xctl = 2 dynamic; static edges: PROGB,PROGC?,PROGD...
    assert m["dynamic_edges"] == 2
    assert m["total_edges"] >= 3
    assert 0.0 < m["dynamic_share"] <= 1.0
    assert m["max_chain_depth"] >= 1

def test_gate_recommendation_proceeds_on_dynamic_dispatch():
    rec = gate_recommendation({"dynamic_share": 0.40, "max_chain_depth": 2,
                               "resources_at_or_above_threshold": 0})
    assert rec["decision"] == "PROCEED"

def test_gate_recommendation_stops_when_flat_and_static():
    rec = gate_recommendation({"dynamic_share": 0.02, "max_chain_depth": 2,
                               "resources_at_or_above_threshold": 1})
    assert rec["decision"] == "STOP"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bench/cobolA && python -m pytest recon/test_driver.py -v`
Expected: FAIL (`ImportError` — `recon.py` has no such functions).

- [ ] **Step 3: Write minimal implementation**

`bench/cobolA/recon.py`:
```python
#!/usr/bin/env python3
"""Phase 0 recon driver: characterize a COBOL corpus and recommend the gate decision.

Usage: python recon.py corpus/   (writes recon.json + PHASE0-recon.md next to this file)
"""
import json
import pathlib
import sys
from recon.extract import extract_program_facts
from recon.graph import build_call_graph, max_chain_depth, shared_data_fan

# Pre-registered gate thresholds (see spec). PROCEED if ANY structural signal is present.
DYNAMIC_SHARE_PROCEED = 0.15
DEPTH_PROCEED = 3
FAN_PROCEED = 5


def aggregate_facts(corpus_dir) -> dict:
    facts = {}
    for path in sorted(pathlib.Path(corpus_dir).rglob("*")):
        if path.suffix.lower() == ".cbl" and path.is_file():
            f = extract_program_facts(path.read_text(errors="replace"))
            if f["program_id"]:
                facts[f["program_id"]] = f
    return facts


def gate_metrics(facts: dict) -> dict:
    dynamic_edges = sum(f["dynamic_call_count"] + f["dynamic_xctl_link_count"]
                        for f in facts.values())
    static_edges = sum(len(f["static_calls"]) + len(f["static_xctl_link"])
                       for f in facts.values())
    total = dynamic_edges + static_edges
    graph = build_call_graph(facts)
    fan = shared_data_fan(facts)
    return {
        "programs": len(facts),
        "static_edges": static_edges,
        "dynamic_edges": dynamic_edges,
        "total_edges": total,
        "dynamic_share": (dynamic_edges / total) if total else 0.0,
        "max_chain_depth": max_chain_depth(graph),
        "resources_at_or_above_threshold": fan["resources_at_or_above_threshold"],
    }


def gate_recommendation(m: dict) -> dict:
    signals = []
    if m["dynamic_share"] >= DYNAMIC_SHARE_PROCEED:
        signals.append(f"dynamic dispatch {m['dynamic_share']:.0%} >= {DYNAMIC_SHARE_PROCEED:.0%}")
    if m["max_chain_depth"] >= DEPTH_PROCEED:
        signals.append(f"call depth {m['max_chain_depth']} >= {DEPTH_PROCEED}")
    if m["resources_at_or_above_threshold"] >= FAN_PROCEED:
        signals.append(f"shared-data fan {m['resources_at_or_above_threshold']} >= {FAN_PROCEED}")
    return {"decision": "PROCEED" if signals else "STOP", "signals": signals}


def _write_reports(here: pathlib.Path, m: dict, rec: dict) -> None:
    (here / "recon.json").write_text(json.dumps({"metrics": m, "recommendation": rec}, indent=2))
    lines = [
        "# Phase 0 — CardDemo Reconnaissance", "",
        f"**Gate decision (script recommendation): `{rec['decision']}`**", "",
        "## Metrics", "",
        f"- Programs parsed: {m['programs']}",
        f"- Static call/XCTL edges: {m['static_edges']}",
        f"- Dynamic dispatch edges: {m['dynamic_edges']}",
        f"- Dynamic share: {m['dynamic_share']:.1%}",
        f"- Max static call-chain depth: {m['max_chain_depth']}",
        f"- Resources (copybook/file) shared by >=3 programs: {m['resources_at_or_above_threshold']}",
        "", "## Gate thresholds", "",
        f"- PROCEED if dynamic share >= {DYNAMIC_SHARE_PROCEED:.0%}, "
        f"OR max depth >= {DEPTH_PROCEED}, OR shared-data fan >= {FAN_PROCEED}.",
        "", "## Signals firing", "",
    ]
    lines += [f"- {s}" for s in rec["signals"]] or ["- (none — corpus is flat/static)"]
    lines += ["", "## Interpretation", "",
              "PROCEED → a call/data-coupling oracle could plausibly beat grep; build Phase 1.",
              "STOP → grep is sufficient for CardDemo-scale decomposition; verdict: **not a fit**.",
              "", "## Known Phase 0 limitation", "",
              "`file_ops` (and the shared-data fan derived from it) captures only bare COBOL "
              "file verbs; CICS file I/O (`EXEC CICS READ FILE(...)`) is NOT captured, so the "
              "coupling metric under-counts the online/CICS programs. Coupling here is a lower "
              "bound — Phase 1 (ProLeap) closes this."]
    (here / "PHASE0-recon.md").write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    corpus = sys.argv[1] if len(sys.argv) > 1 else "corpus"
    here = pathlib.Path(__file__).parent
    facts = aggregate_facts(corpus)
    metrics = gate_metrics(facts)
    rec = gate_recommendation(metrics)
    _write_reports(here, metrics, rec)
    print(json.dumps({"metrics": metrics, "recommendation": rec}, indent=2))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd bench/cobolA && python -m pytest recon/ -v`
Expected: PASS (all extract/graph/driver tests).

- [ ] **Step 5: Commit**

```bash
git add bench/cobolA/recon.py bench/cobolA/recon/test_driver.py
git commit -m "bench(cobolA): recon driver — gate metrics + PHASE0-recon report"
```

---

## Task 6: Run recon on real CardDemo and record the gate verdict

**Files:**
- Create (generated): `bench/cobolA/recon.json`, `bench/cobolA/PHASE0-recon.md`

- [ ] **Step 1: Ensure the corpus is present**

Run: `test -d bench/cobolA/corpus || bench/cobolA/clone_corpus.sh`
Expected: corpus directory exists.

- [ ] **Step 2: Run the recon over the real corpus**

Run: `cd bench/cobolA && python recon.py corpus/`
Expected: prints a metrics+recommendation JSON; writes `recon.json` and `PHASE0-recon.md`. Sanity-check that `programs` is in the expected dozens, not 0 (0 ⇒ glob/extension bug — confirm CardDemo's COBOL really lives under `*.cbl`).

- [ ] **Step 3: Eyeball-validate against grep (cheap cross-check)**

Run:
```bash
cd bench/cobolA
echo "raw CALL lines:"; grep -rIic "CALL " corpus --include=*.cbl | awk -F: '{s+=$2} END{print s}'
echo "XCTL/LINK lines:"; grep -rIiE "EXEC +CICS +(XCTL|LINK)" corpus --include=*.cbl | wc -l
```
Expected: the recon edge counts are in the same ballpark as these raw greps (recon will be lower — it strips comments and dedupes static targets per program). A wild mismatch signals a normalization bug.

- [ ] **Step 4: Commit the recon outputs**

```bash
git add bench/cobolA/recon.json bench/cobolA/PHASE0-recon.md
git commit -m "bench(cobolA): Phase 0 recon results on CardDemo + gate recommendation"
```

---

## ⛔ GATE CHECKPOINT — STOP HERE

**Do not proceed to Phase 1 without an explicit human decision.** This is the de-risking point the entire study is built around.

Read `bench/cobolA/PHASE0-recon.md` and decide:

- **`STOP`** (corpus is flat/static, no structural signal) → the study is **complete with a "not a fit" verdict**. Write it up in `docs/superpowers/results/2026-MM-DD-cobol-decomposition-findings.md` using the verdict-against-the-thesis voice, citing the recon numbers. Done — cheaply, as designed.
- **`PROCEED`** (real dynamic dispatch / depth / coupling) → the win is *plausible*. Return to the writing-plans skill to expand the Phase 1–3 roadmap below into bite-sized TDD tasks, then continue.

The script's recommendation is advisory; a human confirms the gate.

---

## Roadmap — Phases 1–4 (expand into bite-sized tasks only if the gate says PROCEED)

These are intentionally **not** broken into executable steps yet. Detailed planning is deferred behind the gate so we never build the expensive oracle for a corpus that grep already handles. Each becomes its own task block on return to writing-plans.

### Phase 1 — Independent oracle (ProLeap)
- Add a small Java extractor (standalone Gradle module under `bench/cobolA/oracle/extractor/`) depending on the **ProLeap COBOL parser** (Java/ANTLR4 — independent of tree-sitter, which is reserved for a future Approach B). It parses CardDemo with copybook preprocessing and emits raw edges (CALL, XCTL/LINK incl. resolvable dynamic targets, COPY, file READ/WRITE, embedded-SQL tables, CICS txn→program).
- `oracle/build_oracle.py` consumes the raw edges and precomputes the query-answer structures (transitive closures, access sets, coupling sets, copybook fan-out) into `oracle/oracle.json`, mirroring `arenaA/oracle/oracle.json`.
- **Human audit** of the generated graph (corpus is small); record dynamic `CALL`/`XCTL` that ProLeap cannot statically resolve as a declared completeness ceiling.
- TDD: `oracle/test_graph.py` (closure/coupling math on synthetic graphs), `oracle/test_build_oracle.py` (shape of `oracle.json`).

### Phase 2 — Query workload + metrics
- `select_questions.py` derives ~60–100 questions from `oracle.json` across 5 strata (transitive CALL closure / data-access set / shared-data coupling / copybook fan-out / CICS-txn reachability) into `questions.jsonl` (schema: `id`, `stratum`, `prompt`, `answer_simple`).
- `score.py` adapted from `arenaA/score.py` for name-set P/R/F1 by stratum; `test_score.py` ported.

### Phase 3 — Two arms, same agent
- `arms/run_grep.py` — fairly-resourced grep/ripgrep arm over `corpus/` (multi-step, manual COPY/CALL chasing), adapted from `arenaA/arms/run_grep.py`.
- `arms/cobol_reachability_server.py` — thin MCP shim exposing `cobol_reachability(node, kind)` backed by `oracle.json`; `arms/mcp_call.py` driver adapted from `arenaA/arms/mcp_call.py`.
- `run_all.sh` runs both arms over `questions.jsonl`; `score.py` emits `results/results.csv`.
- **Verdict** against the spec's pre-registered thresholds (proxy-arm F1 ≥ 0.70 ∧ grep-arm F1 ≤ 0.45 ∧ gap ≥ 0.30 → greenlight Approach B; gap < 0.15 → not a fit; in between → ambiguous). Writeup in `docs/superpowers/results/`.

### Phase 4 — Decomposition quality (only if Phase 3 is green)
- Each arm proposes a full microservice decomposition; score vs CardDemo's natural contexts (Account, Card, Transaction, Customer/User, Reporting, Bill-Pay) by clustering similarity. Confirms the sub-query win translates to the real task before committing to Approach B.

---

## Self-Review notes

- **Spec coverage:** Phase 0 (recon gate) fully implemented as Tasks 1–6 + GATE; Phases 1–4 of the spec are represented as a gated roadmap, matching the spec's explicit de-risking design (Phase 0 may end the study). The make-vs-buy caveat and pre-registered thresholds live in the spec and are echoed at the GATE and Phase 3 roadmap.
- **Type consistency:** fact dict keys (`static_calls`, `dynamic_call_count`, `static_xctl_link`, `dynamic_xctl_link_count`, `copybooks`, `file_ops`, `uses_sql`, `uses_cics`, `program_id`) are identical across `extract.py`, `graph.py`, `recon.py`, and every test. Metric keys (`dynamic_share`, `max_chain_depth`, `resources_at_or_above_threshold`) match between `gate_metrics`, `gate_recommendation`, and `_write_reports`.
- **No placeholders in Phase 0 tasks:** every code/test step contains complete, runnable content. Phase 1–4 are explicitly labeled deferred roadmap, not TBD steps.
