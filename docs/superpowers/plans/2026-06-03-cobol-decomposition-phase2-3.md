# COBOL→Java Decomposition Feasibility — Phase 2 + Phase 3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the query workload (Phase 2) and the two scored arms (Phase 3) of the CardDemo decomposition benchmark, then render the pre-registered verdict on whether a CML-style `cobol_reachability` oracle beats a fairly-resourced `grep` arm on COBOL call/data-coupling reachability.

**Architecture:** Extends the existing `bench/cobolA/` harness (Phase 0 recon + Phase 1 ProLeap oracle, both merged). Phase 2 augments the audited `oracle.json` with two missing strata (CICS transaction→program entry from the CSD, and file/DB2 data-coupling neighbourhoods), then derives ~64 deterministic decomposition questions across **5 strata** into `questions.jsonl`. The answer key is **hybrid** (per the explicit project decision): the two static, independently-grep-verifiable strata (data-access, copybook-fan) key off `oracle.json`; the three grep-hard strata (call-closure, data-coupling, txn-reach) key off a **hand-audited independent answer key** (`audit/hard_strata_key.json` + `KEY-AUDIT.md`), so the proxy arm's F1 stays meaningful rather than circular. Phase 3 runs two deterministic arms over identical questions — a fairly-resourced `grep`/ripgrep arm and a thin stdio-MCP `cobol_reachability(node, kind)` shim backed by `oracle.json` — scores name-set P/R/F1 by stratum via a reused `score.py`, macro-averages the gating strata 1–3, and applies the pre-registered thresholds.

**Tech Stack:** Python 3.13, pytest, ripgrep/grep, JSON-RPC over stdio (minimal MCP), the committed `bench/cobolA/oracle/raw-edges.json` (ProLeap output) + `corpus/` CardDemo clone (gitignored). No Java rebuild — `oracle.json` regenerates purely from `raw-edges.json` + the corpus CSD files.

**Spec:** `docs/superpowers/specs/2026-06-03-cobol-decomposition-feasibility-design.md`
**Prior plan (Phase 0–1):** `docs/superpowers/plans/2026-06-03-cobol-decomposition-feasibility.md`

---

## Key design decisions (locked before implementation)

1. **Five strata** (spec §Phase 2), with these canonical names used verbatim as `stratum` values, `kind` arguments to the proxy tool, and `oracle.json` keys:

   | # | `stratum` / `kind` | node type | answer | gating? | key source |
   |---|---|---|---|---|---|
   | 1 | `call_closure` | program | transitive CALL/XCTL closure (corpus programs) | **yes** | audited |
   | 2 | `data_access` | file/DB2 resource | programs that read/write it | **yes** | oracle (grep-verifiable) |
   | 3 | `data_coupling` | program | programs sharing ≥1 file/DB2 resource | **yes** | audited |
   | 4 | `copybook_fan` | copybook | programs that `COPY` it | no (reported) | oracle (grep-verifiable) |
   | 5 | `txn_reach` | CICS txn id | `{entry} ∪ closure(entry)` (corpus) | no (reported) | audited |

2. **Corpus-program universe.** Every answer set is filtered to the 44 corpus programs (`oracle.json["programs"]`). Vendor/runtime stubs (`CEE3ABD`, `CBLTDLI`, `MQOPEN`, …) are excluded — they are trivially grep-visible `CALL 'literal'` targets and not decomposition-relevant. The filter is applied **once, centrally, in `score.py`**, so both arms are judged only on corpus-program recovery.

3. **Data-coupling excludes copybooks.** Stratum 3 couples programs that share a *data store* (file or DB2 table) only — not copybooks (those are stratum 4, and utility copybooks would over-connect everything). This matches the real decomposition concern: shared mutable state resists service splitting.

4. **Both arms are deterministic Python** (matching `arenaA/arms/run_grep.py`, which is a deterministic BFS, not an LLM). The benchmark measures the structural completeness ceiling of each delivery mechanism, reproducibly. The proxy arm still issues a real stdio-MCP round-trip per question so the "MCP-native delivery" claim is exercised and the `calls` cost column is meaningful.

5. **Pre-registered verdict (spec §Verdict), computed in `score.py` over the macro-average of strata 1–3:**
   - **GREENLIGHT B** if `proxy_F1 ≥ 0.70` **and** `grep_F1 ≤ 0.45` **and** `gap ≥ 0.30`.
   - **NOT A FIT** if `gap < 0.15`.
   - **AMBIGUOUS** if `0.15 ≤ gap < 0.30`.

---

## File Structure

```
bench/cobolA/
  oracle/
    csd.py                 # NEW  pure CSD parser: TRANSACTION(id) -> PROGRAM(pgm)
    test_csd.py            # NEW
    graph.py               # MODIFY add data_coupling_neighbors()
    test_graph.py          # MODIFY
    build_oracle.py        # MODIFY add cics_txn_entry, txn_reach, data_coupling strata
    test_build_oracle.py   # MODIFY
    oracle.json            # REGENERATE (committed)
  select_questions.py      # NEW  derive 5-strata questions.jsonl
  test_select_questions.py # NEW
  questions.jsonl          # NEW  (committed, generated)
  audit/
    make_key_skeleton.py   # NEW  emits the audited-strata questions for hand-verification
    hard_strata_key.json   # NEW  (committed) human-verified independent answers, strata 1/3/5
    KEY-AUDIT.md           # NEW  (committed) provenance: how each answer was derived from source
  score.py                 # NEW  hybrid-key P/R/F1 by stratum + verdict
  test_score.py            # NEW
  arms/
    run_grep.py            # NEW  fairly-resourced grep arm, dispatched per stratum
    test_run_grep.py       # NEW  (fixture mini-corpus)
    fixtures/              # NEW  tiny committed COBOL + CSD for grep-arm tests
    cobol_reachability_server.py  # NEW  stdio MCP shim over oracle.json
    run_proxy.py           # NEW  one tools/call per question
    test_proxy.py          # NEW
  run_all.sh               # NEW  run both arms, score, emit results.csv
  results/                 # generated; results.csv committed
docs/superpowers/results/
  2026-06-03-cobol-decomposition-findings.md  # NEW  the verdict writeup
```

---

# PHASE 2 — Query workload + metrics

## Task 1: CSD parser — CICS transaction → entry program

CardDemo's online entry points live in CICS CSD files (`corpus/**/*.csd`), not in COBOL source — so the Phase-1 ProLeap extractor left `cics_txn_entry` empty. A `DEFINE TRANSACTION(CM00) … PROGRAM(COMEN01C)` block names the entry program; from there the dynamic-XCTL fan-out (which grep cannot close) begins. This parser is intentionally **independent of the ProLeap extractor** so it can also seed the hand-audited key.

**Files:**
- Create: `bench/cobolA/oracle/csd.py`
- Test: `bench/cobolA/oracle/test_csd.py`

- [ ] **Step 1: Write the failing test**

`bench/cobolA/oracle/test_csd.py`:
```python
from csd import parse_csd_text

SAMPLE = """\
 DEFINE TRANSACTION(CM00) GROUP(CARDDEMO)
        PROGRAM(COMEN01C) TWASIZE(0) STATUS(ENABLED)
 DEFINE TRANSACTION(CAUP) GROUP(CARDDEMO)
 DESCRIPTION(CREDIT CARD DEMO ACCOUNT UPDATE)
        PROGRAM(COACTUPC) TWASIZE(0) STATUS(ENABLED)
 DEFINE PROGRAM(COMEN01C) GROUP(CARDDEMO)
"""

def test_parses_transaction_to_program_across_continuation_lines():
    m = parse_csd_text(SAMPLE)
    assert m == {"CM00": "COMEN01C", "CAUP": "COACTUPC"}

def test_ignores_standalone_program_define():
    # the trailing "DEFINE PROGRAM(...)" has no TRANSACTION and must not appear as a key
    m = parse_csd_text(SAMPLE)
    assert "COMEN01C" not in m  # it is a value, never a transaction key
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bench/cobolA/oracle && python -m pytest test_csd.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'csd'`.

- [ ] **Step 3: Write minimal implementation**

`bench/cobolA/oracle/csd.py`:
```python
"""Parse CICS CSD resource definitions to recover transaction -> entry program.

Pure text scan of `.csd` files, independent of the ProLeap extractor (so it can
also seed the independent hand-audited answer key). A `DEFINE TRANSACTION(id)`
block carries its entry program in a `PROGRAM(pgm)` attribute that may sit on a
later continuation line; each transaction is bound to the first PROGRAM(...) that
appears inside its own DEFINE block.
"""
from __future__ import annotations

import pathlib
import re

_TXN = re.compile(r"DEFINE\s+TRANSACTION\(([A-Z0-9$@#]+)\)", re.I)
_PGM = re.compile(r"\bPROGRAM\(([A-Z0-9$@#]+)\)", re.I)


def parse_csd_text(text: str) -> dict[str, str]:
    """Return {TXN_ID: PROGRAM} for every transaction define in one CSD's text."""
    out: dict[str, str] = {}
    # Split into per-DEFINE blocks; lookahead keeps the DEFINE keyword on each block.
    for block in re.split(r"(?=\bDEFINE\b)", text, flags=re.I):
        mt = _TXN.search(block)
        if not mt:
            continue
        mp = _PGM.search(block)
        if mp:
            out[mt.group(1).upper()] = mp.group(1).upper()
    return out


def parse_csd_dir(corpus_dir: str | pathlib.Path) -> dict[str, str]:
    """Union TXN->PROGRAM across every *.csd file under corpus_dir (sorted, deterministic)."""
    merged: dict[str, str] = {}
    for path in sorted(pathlib.Path(corpus_dir).rglob("*")):
        if path.suffix.lower() == ".csd" and path.is_file():
            merged.update(parse_csd_text(path.read_text(errors="replace")))
    return dict(sorted(merged.items()))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd bench/cobolA/oracle && python -m pytest test_csd.py -v`
Expected: PASS (2 tests).

- [ ] **Step 5: Sanity-check against the real corpus**

Run:
```bash
cd bench/cobolA/oracle && python -c "from csd import parse_csd_dir; m=parse_csd_dir('../corpus'); print(len(m),'txns'); print({k:m[k] for k in list(sorted(m))[:5]})"
```
Expected: prints `25 txns` and a sample like `{'CA00': 'COADM01C', 'CAUP': 'COACTUPC', ...}`. (24 of the 25 entry programs exist in the corpus; one maps outside it and will be dropped by the corpus filter in Task 3.)

- [ ] **Step 6: Commit**

```bash
git add bench/cobolA/oracle/csd.py bench/cobolA/oracle/test_csd.py
git commit -m "bench(cobolA): CSD parser — CICS transaction -> entry program"
```

---

## Task 2: `data_coupling_neighbors` graph function

Stratum 3 asks: which other programs share a data store with program X? This is the file/DB2 resource-coupling neighbourhood — a pure computation over the edges map, added beside the existing `graph.py` functions.

**Files:**
- Modify: `bench/cobolA/oracle/graph.py`
- Test: `bench/cobolA/oracle/test_graph.py`

- [ ] **Step 1: Write the failing test**

Append to `bench/cobolA/oracle/test_graph.py`:
```python
from graph import data_coupling_neighbors

def test_data_coupling_neighbors_shares_file_or_db2_only():
    edges = {
        "A": {"files_read": ["F1"], "files_written": [], "db2_tables": [], "copybooks": ["SHARED"]},
        "B": {"files_read": [], "files_written": ["F1"], "db2_tables": [], "copybooks": ["SHARED"]},
        "C": {"files_read": [], "files_written": [], "db2_tables": ["T1"], "copybooks": ["SHARED"]},
        "D": {"files_read": [], "files_written": [], "db2_tables": ["T1"], "copybooks": []},
    }
    n = data_coupling_neighbors(edges)
    assert n["A"] == {"B"}        # A,B share file F1
    assert n["B"] == {"A"}
    assert n["C"] == {"D"}        # C,D share DB2 table T1
    assert n["D"] == {"C"}
    # copybook-only sharing must NOT create coupling (that is stratum 4)
    assert "C" not in n["A"]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bench/cobolA/oracle && python -m pytest test_graph.py::test_data_coupling_neighbors_shares_file_or_db2_only -v`
Expected: FAIL with `ImportError: cannot import name 'data_coupling_neighbors'`.

- [ ] **Step 3: Write minimal implementation**

Append to `bench/cobolA/oracle/graph.py`:
```python
def data_coupling_neighbors(edges_map: dict[str, dict]) -> dict[str, set[str]]:
    """Return {program: set of OTHER programs sharing >=1 file/DB2 resource}.

    Copybooks are deliberately excluded — shared record layouts are stratum 4;
    stratum 3 is coupling through a mutable data store, which is what resists a
    clean service split.
    """
    from collections import defaultdict

    res_to_progs: dict[str, set[str]] = defaultdict(set)
    for pid, obj in edges_map.items():
        for res in _resources(obj):
            res_to_progs[res].add(pid)

    neighbors: dict[str, set[str]] = {pid: set() for pid in edges_map}
    for group in res_to_progs.values():
        for pid in group:
            neighbors[pid] |= group - {pid}
    return neighbors
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd bench/cobolA/oracle && python -m pytest test_graph.py -v`
Expected: PASS (all prior graph tests + the new one).

- [ ] **Step 5: Commit**

```bash
git add bench/cobolA/oracle/graph.py bench/cobolA/oracle/test_graph.py
git commit -m "bench(cobolA): data_coupling_neighbors graph function (file/DB2 sharing)"
```

---

## Task 3: Augment `build_oracle.py` with txn + coupling strata, regenerate `oracle.json`

Add three derived structures to the oracle: `cics_txn_entry` (from the CSD), `txn_reach` (entry ∪ closure, corpus-filtered), and `data_coupling` (the neighbour map, corpus-filtered). The builder now takes the corpus dir as a second argument so it can read the CSD; it stays deterministic.

**Files:**
- Modify: `bench/cobolA/oracle/build_oracle.py`
- Modify: `bench/cobolA/oracle/test_build_oracle.py`
- Regenerate: `bench/cobolA/oracle/oracle.json`

- [ ] **Step 1: Write the failing test**

Append to `bench/cobolA/oracle/test_build_oracle.py`:
```python
from build_oracle import build_oracle

def test_build_oracle_adds_txn_and_coupling_strata():
    edges_map = {
        "COMEN01C": {"static_calls": [], "resolved_dynamic_calls": [],
                     "static_xctl_link": [], "resolved_dynamic_xctl_link": ["COBIL00C"],
                     "copybooks": [], "files_read": [], "files_written": [], "db2_tables": []},
        "COBIL00C": {"static_calls": [], "resolved_dynamic_calls": [],
                     "static_xctl_link": [], "resolved_dynamic_xctl_link": [],
                     "copybooks": [], "files_read": ["BILLFILE"], "files_written": [],
                     "db2_tables": []},
        "CBTRN02C": {"static_calls": [], "resolved_dynamic_calls": [],
                     "static_xctl_link": [], "resolved_dynamic_xctl_link": [],
                     "copybooks": [], "files_read": ["BILLFILE"], "files_written": [],
                     "db2_tables": []},
    }
    txn_entry = {"CM00": "COMEN01C", "CX99": "NOTINCORPUS"}
    oracle = build_oracle(edges_map, txn_entry)

    # cics_txn_entry keeps only transactions whose entry program is in the corpus
    assert oracle["cics_txn_entry"] == {"CM00": "COMEN01C"}
    # txn_reach = {entry} U closure(entry), corpus-filtered, sorted
    assert oracle["txn_reach"]["CM00"] == ["COBIL00C", "COMEN01C"]
    # data_coupling: BILLFILE shared by COBIL00C and CBTRN02C
    assert oracle["data_coupling"]["COBIL00C"] == ["CBTRN02C"]
    assert oracle["data_coupling"]["CBTRN02C"] == ["COBIL00C"]
    assert oracle["data_coupling"]["COMEN01C"] == []
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bench/cobolA/oracle && python -m pytest test_build_oracle.py::test_build_oracle_adds_txn_and_coupling_strata -v`
Expected: FAIL — `build_oracle()` currently takes one argument and emits no `cics_txn_entry`/`txn_reach`/`data_coupling`.

- [ ] **Step 3: Update the implementation**

In `bench/cobolA/oracle/build_oracle.py`, change the import line:
```python
from graph import call_edges, transitive_call_closure, copybook_fan, data_coupling_neighbors
```

Change the signature and add the three strata. Replace `def build_oracle(edges_map: dict[str, dict]) -> dict:` and its `return` block:
```python
def build_oracle(edges_map: dict[str, dict], txn_entry: dict[str, str] | None = None) -> dict:
    """Pre-compute all oracle strata from edges_map (+ optional CSD txn->program map)."""
    programs = sorted(edges_map.keys())
    prog_set = set(programs)
    txn_entry = txn_entry or {}

    all_resources: set[str] = set()
    for obj in edges_map.values():
        all_resources |= set(obj.get("files_read", []))
        all_resources |= set(obj.get("files_written", []))
        all_resources |= set(obj.get("db2_tables", []))
    resources = sorted(all_resources)

    all_copybooks: set[str] = set()
    for obj in edges_map.values():
        all_copybooks |= set(obj.get("copybooks", []))
    copybooks = sorted(all_copybooks)

    transitive: dict[str, list[str]] = {
        pid: sorted(transitive_call_closure(edges_map, pid)) for pid in programs
    }

    data_access: dict[str, list[str]] = {}
    for resource in resources:
        data_access[resource] = sorted(
            pid for pid, obj in edges_map.items()
            if resource in (set(obj.get("files_read", []))
                            | set(obj.get("files_written", []))
                            | set(obj.get("db2_tables", [])))
        )

    raw_fan = copybook_fan(edges_map)
    cb_fan: dict[str, list[str]] = {cb: sorted(pids) for cb, pids in sorted(raw_fan.items())}

    direct: dict[str, list[str]] = {pid: sorted(call_edges(obj)) for pid, obj in edges_map.items()}
    direct = dict(sorted(direct.items()))

    # ---- NEW: data coupling (file/DB2), corpus-filtered ------------------
    neigh = data_coupling_neighbors(edges_map)
    data_coupling: dict[str, list[str]] = {
        pid: sorted(neigh[pid] & prog_set) for pid in programs
    }

    # ---- NEW: CICS txn -> entry program (corpus-filtered) ---------------
    cics_txn_entry: dict[str, str] = {
        txn: pgm for txn, pgm in sorted(txn_entry.items()) if pgm in prog_set
    }

    # ---- NEW: txn -> reachable programs = {entry} U closure(entry) -------
    txn_reach: dict[str, list[str]] = {}
    for txn, entry in cics_txn_entry.items():
        reach = ({entry} | transitive_call_closure(edges_map, entry)) & prog_set
        txn_reach[txn] = sorted(reach)

    return {
        "programs": programs,
        "resources": resources,
        "copybooks": copybooks,
        "transitive_call_closure": transitive,
        "data_access": data_access,
        "copybook_fan": cb_fan,
        "data_coupling": data_coupling,
        "cics_txn_entry": cics_txn_entry,
        "txn_reach": txn_reach,
        "direct_call_edges": direct,
    }
```

Replace the `main()` body so it reads the CSD from the corpus dir:
```python
def main() -> None:
    if len(sys.argv) not in (3, 4):
        print(f"Usage: {sys.argv[0]} <raw-edges.json> <oracle.json> [corpus_dir]", file=sys.stderr)
        sys.exit(1)
    in_path, out_path = sys.argv[1], sys.argv[2]
    corpus_dir = sys.argv[3] if len(sys.argv) == 4 else "../corpus"
    edges_map = load_edges(in_path)
    from csd import parse_csd_dir
    txn_entry = parse_csd_dir(corpus_dir)
    oracle = build_oracle(edges_map, txn_entry)
    Path(out_path).write_text(json.dumps(oracle, indent=2, sort_keys=False) + "\n", encoding="utf-8")
    print(f"oracle.json written: {len(oracle['programs'])} programs, "
          f"{len(oracle['resources'])} resources, {len(oracle['copybooks'])} copybooks, "
          f"{len(oracle['cics_txn_entry'])} txns", file=sys.stderr)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd bench/cobolA/oracle && python -m pytest test_build_oracle.py -v`
Expected: PASS (all prior + new test).

- [ ] **Step 5: Regenerate the real `oracle.json` and eyeball the new strata**

Run:
```bash
cd bench/cobolA/oracle && python build_oracle.py raw-edges.json oracle.json ../corpus
python -c "import json;o=json.load(open('oracle.json'));print('txns',len(o['cics_txn_entry']));print('CM00 reach',o['txn_reach'].get('CM00'));print('coupling COBIL00C',o['data_coupling'].get('COBIL00C'))"
```
Expected: `txns 24`; `CM00 reach` is a sorted list including `COMEN01C` and its submenu programs; `coupling COBIL00C` is a non-empty sorted program list. Re-running `build_oracle.py` a second time must produce a zero `git diff` (determinism).

- [ ] **Step 6: Commit**

```bash
git add bench/cobolA/oracle/build_oracle.py bench/cobolA/oracle/test_build_oracle.py bench/cobolA/oracle/oracle.json
git commit -m "bench(cobolA): oracle.json gains cics_txn_entry, txn_reach, data_coupling strata"
```

---

## Task 4: `select_questions.py` — derive the 5-strata workload

Deterministically derive ~64 questions from `oracle.json`. Counts per stratum reflect the small corpus: stratum 1 = all 11 programs whose corpus closure ≥ 2; stratum 2 = all 6 resources with ≥ 2 accessors; stratum 3 = all 8 programs with ≥ 2 coupled programs; stratum 4 = top 15 copybooks by fan; stratum 5 = all 24 corpus-entry transactions. Each question records the oracle answer as `answer_simple` and a `key_source` flag (`oracle` for strata 2/4, `audited` for strata 1/3/5).

**Files:**
- Create: `bench/cobolA/select_questions.py`
- Test: `bench/cobolA/test_select_questions.py`

- [ ] **Step 1: Write the failing test**

`bench/cobolA/test_select_questions.py`:
```python
from select_questions import build_questions

ORACLE = {
    "programs": ["A", "B", "C"],
    "transitive_call_closure": {"A": ["B", "C", "ZZVENDOR"], "B": ["C"], "C": []},
    "data_access": {"FILE1": ["A", "B"], "FILE2": ["A"]},
    "copybook_fan": {"CPY1": ["A", "B", "C"], "CPY2": ["A"]},
    "data_coupling": {"A": ["B"], "B": ["A"], "C": []},
    "cics_txn_entry": {"TX01": "A"},
    "txn_reach": {"TX01": ["A", "B", "C"]},
}

def test_build_questions_covers_five_strata_and_filters_universe():
    qs = build_questions(ORACLE, caps={"copybook_fan": 15})
    by = {}
    for q in qs:
        by.setdefault(q["stratum"], []).append(q)
    assert set(by) == {"call_closure", "data_access", "data_coupling", "copybook_fan", "txn_reach"}

    # stratum 1: only programs whose corpus closure (excludes ZZVENDOR) has >= 2 entries
    a = next(q for q in by["call_closure"] if q["node"] == "A")
    assert a["answer_simple"] == ["B", "C"]      # ZZVENDOR filtered out (not in programs)
    assert a["key_source"] == "audited"
    assert all(q["node"] != "B" for q in by["call_closure"])  # B closure = {C}, only 1 -> excluded

    # stratum 2: resources with >= 2 accessors only; oracle-keyed
    assert {q["node"] for q in by["data_access"]} == {"FILE1"}
    assert by["data_access"][0]["key_source"] == "oracle"

    # stratum 4: copybooks with >= 2 includers; oracle-keyed
    assert {q["node"] for q in by["copybook_fan"]} == {"CPY1"}

    # stratum 5: txn reach; audited
    assert by["txn_reach"][0]["answer_simple"] == ["A", "B", "C"]
    assert by["txn_reach"][0]["key_source"] == "audited"

    # every question has a stable id + a prompt + kind == stratum
    for q in qs:
        assert q["id"] and q["question"] and q["kind"] == q["stratum"]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bench/cobolA && python -m pytest test_select_questions.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'select_questions'`.

- [ ] **Step 3: Write minimal implementation**

`bench/cobolA/select_questions.py`:
```python
#!/usr/bin/env python3
"""Derive the 5-strata decomposition query workload from oracle.json.

Deterministic: candidates are sorted by (descending answer size, node name) and
capped per stratum, so re-running produces an identical questions.jsonl.

Usage: python select_questions.py            # reads oracle/oracle.json, writes questions.jsonl
"""
from __future__ import annotations

import json
import sys

AUDITED = {"call_closure", "data_coupling", "txn_reach"}  # hybrid key: hand-audited strata
ORACLE_KEYED = {"data_access", "copybook_fan"}            # independently grep-verifiable

PROMPTS = {
    "call_closure": "List every CardDemo program reachable from program {node} by following "
                    "CALL and CICS XCTL/LINK transfers (directly or transitively).",
    "data_access":  "List every CardDemo program that reads or writes the data resource {node}.",
    "data_coupling": "List every CardDemo program that shares a data store (file or DB2 table) "
                     "with program {node}.",
    "copybook_fan": "List every CardDemo program that COPYs the copybook {node}.",
    "txn_reach":    "List every CardDemo program reachable when CICS transaction {node} starts "
                    "(its entry program and everything that entry transitively transfers to).",
}

DEFAULT_CAPS = {  # None = take all candidates that clear the min-answer-size floor
    "call_closure": None, "data_access": None, "data_coupling": None,
    "copybook_fan": 15, "txn_reach": None,
}


def _key_source(stratum: str) -> str:
    return "audited" if stratum in AUDITED else "oracle"


def build_questions(oracle: dict, caps: dict | None = None) -> list[dict]:
    caps = {**DEFAULT_CAPS, **(caps or {})}
    universe = set(oracle["programs"])
    cand: list[dict] = []

    def filt(names):  # keep only corpus programs, sorted
        return sorted(set(names) & universe)

    # stratum 1 — transitive call closure (answer >= 2 corpus programs)
    for pid, closure in oracle["transitive_call_closure"].items():
        ans = filt(closure)
        if len(ans) >= 2:
            cand.append(_mk("call_closure", pid, ans))

    # stratum 2 — data access (resource with >= 2 accessors)
    for res, progs in oracle["data_access"].items():
        ans = filt(progs)
        if len(ans) >= 2:
            cand.append(_mk("data_access", res, ans))

    # stratum 3 — data coupling (program with >= 2 coupled programs)
    for pid, coupled in oracle["data_coupling"].items():
        ans = filt(coupled)
        if len(ans) >= 2:
            cand.append(_mk("data_coupling", pid, ans))

    # stratum 4 — copybook fan (copybook with >= 2 includers)
    for cb, progs in oracle["copybook_fan"].items():
        ans = filt(progs)
        if len(ans) >= 2:
            cand.append(_mk("copybook_fan", cb, ans))

    # stratum 5 — txn reach (entry exists in corpus; answer >= 2)
    for txn, reach in oracle.get("txn_reach", {}).items():
        ans = filt(reach)
        if len(ans) >= 2:
            cand.append(_mk("txn_reach", txn, ans))

    # deterministic per-stratum cap: biggest answers first, tie-break by node
    out: list[dict] = []
    for stratum in PROMPTS:
        group = sorted((c for c in cand if c["stratum"] == stratum),
                       key=lambda c: (-len(c["answer_simple"]), c["node"]))
        cap = caps.get(stratum)
        out.extend(group if cap is None else group[:cap])
    return out


def _mk(stratum: str, node: str, answer: list[str]) -> dict:
    return {
        "id": f"{stratum}__{node}",
        "stratum": stratum,
        "kind": stratum,
        "node": node,
        "question": PROMPTS[stratum].format(node=node),
        "answer_simple": answer,
        "key_source": _key_source(stratum),
    }


def main() -> None:
    oracle = json.load(open("oracle/oracle.json"))
    qs = build_questions(oracle)
    with open("questions.jsonl", "w") as fh:
        for q in qs:
            fh.write(json.dumps(q) + "\n")
    from collections import Counter
    counts = Counter(q["stratum"] for q in qs)
    print(f"wrote {len(qs)} questions: " + ", ".join(f"{k}={counts[k]}" for k in PROMPTS),
          file=sys.stderr)


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd bench/cobolA && python -m pytest test_select_questions.py -v`
Expected: PASS.

- [ ] **Step 5: Generate the real workload and verify the shape**

Run:
```bash
cd bench/cobolA && python select_questions.py
wc -l questions.jsonl
python -c "import json,collections;c=collections.Counter(json.loads(l)['stratum'] for l in open('questions.jsonl'));print(dict(c))"
```
Expected: ~64 questions; counts roughly `{'call_closure': 11, 'data_access': 6, 'data_coupling': 8, 'copybook_fan': 15, 'txn_reach': 24}` (exact numbers may shift ±1 with corpus SHA but every stratum is non-empty).

- [ ] **Step 6: Commit**

```bash
git add bench/cobolA/select_questions.py bench/cobolA/test_select_questions.py bench/cobolA/questions.jsonl
git commit -m "bench(cobolA): 5-strata decomposition query workload (questions.jsonl)"
```

---

## Task 5: Build the independent hand-audited answer key (strata 1, 3, 5)

This is the hybrid key's rigorous half: an answer key for the three grep-hard strata, derived **from source independent of `oracle.json`**, so the proxy arm's F1 measures ProLeap's real accuracy rather than scoring against itself. The corpus is tiny (44 programs, call depth 3) and the dynamic dispatch is concentrated in two already-audited menu programs, so this is bounded analyst work — not a code placeholder.

**Method (extends `oracle/AUDIT.md`):** for each audited question, derive the answer by hand-reading source — `grep`/`Read` for static `CALL '…'` and `EXEC CICS XCTL PROGRAM('…')` literals; for variable XCTL, read the `OCCURS`/`REDEFINES` menu tables (`app/cpy/COMEN02Y.cpy`, `COADM02Y.cpy`) and `MOVE 'literal' TO` antecedents; for `txn_reach`, resolve the entry from the CSD then chase its transfers; for `data_coupling`, `grep` the file/DB2 resources the program touches (including `EXEC CICS READ FILE(...)` and `EXEC SQL`) then `grep` co-accessors. Record the **command/evidence** per answer in `KEY-AUDIT.md`. Where a target is genuinely unresolvable statically (truly dynamic — e.g. the 3 `COPAUS1C` chains the Phase-1 audit flagged), **omit it from the key** so neither arm is charged for an unanswerable case.

**Files:**
- Create: `bench/cobolA/audit/make_key_skeleton.py`
- Create: `bench/cobolA/audit/hard_strata_key.json` (human-verified)
- Create: `bench/cobolA/audit/KEY-AUDIT.md` (provenance)

- [ ] **Step 1: Write the skeleton generator (lists exactly what must be audited)**

`bench/cobolA/audit/make_key_skeleton.py`:
```python
#!/usr/bin/env python3
"""Emit the audited-strata questions as a skeleton for independent hand-verification.

For each call_closure/data_coupling/txn_reach question it prints the node, the prompt,
and the CURRENT oracle answer as a *candidate to be checked against source* — NOT as the
key. The human verifies each against COBOL/CSD source and writes the final answer into
hard_strata_key.json, recording evidence in KEY-AUDIT.md.
"""
import json

AUDITED = {"call_closure", "data_coupling", "txn_reach"}

if __name__ == "__main__":
    skeleton = {}
    for line in open("../questions.jsonl"):
        q = json.loads(line)
        if q["stratum"] in AUDITED:
            skeleton[q["id"]] = {
                "node": q["node"],
                "stratum": q["stratum"],
                "oracle_candidate": q["answer_simple"],
                "audited_answer": None,   # <- human fills this from source
            }
    print(json.dumps(skeleton, indent=2))
```

- [ ] **Step 2: Generate the skeleton**

Run:
```bash
cd bench/cobolA/audit && python make_key_skeleton.py > skeleton.json
python -c "import json;print(len(json.load(open('skeleton.json'))),'questions to audit')"
```
Expected: ~43 audited questions (11 call_closure + 8 data_coupling + 24 txn_reach).

- [ ] **Step 3: Hand-verify each answer against source and fill `hard_strata_key.json`**

For every entry in `skeleton.json`, independently derive the corpus-program answer set from source. Worked anchors (already established in `oracle/AUDIT.md`, reuse verbatim):
- `call_closure__COMEN01C` → the 11 submenu programs (`COACTVWC, COACTUPC, COCRDLIC, COCRDSLC, COCRDUPC, COTRN00C, COTRN01C, COTRN02C, CORPT00C, COBIL00C, COPAUS0C`) **+ `COSGN00C`**, verified against `app/cpy/COMEN02Y.cpy` lines 28–89 and the two XCTL sites in `COMEN01C.cbl`.
- `call_closure__COADM01C` → `COUSR00C, COUSR01C, COUSR02C, COUSR03C, COTRTLIC, COTRTUPC` **+ `COSGN00C`**, verified against `app/cpy/COADM02Y.cpy`.
- `txn_reach__CM00` = `call_closure__COMEN01C ∪ {COMEN01C}` (CSD: `CM00 → COMEN01C`); `txn_reach__CA00` similarly off `COADM01C`. Each `txn_reach` answer is mechanically `{entry} ∪ call_closure(entry)` once the entry's closure is audited — so audit the 11 closures first, then txn answers fall out.
- `data_coupling__CBTRN02C` etc. → `grep -rln` the program's files (`ACCOUNT-FILE`, `TRANSACT-FILE`, …) and DB2 tables across `corpus`, take co-accessors. Example already audited: `ACCOUNT-FILE` is touched by exactly `CBACT04C, CBTRN01C, CBTRN02C`.

Write the verified answers into `bench/cobolA/audit/hard_strata_key.json`, shape `{question_id: [sorted corpus program names]}`. Example (illustrative — produce the real, complete file):
```json
{
  "call_closure__COMEN01C": ["COACTUPC","COACTVWC","COBIL00C","COCRDLIC","COCRDSLC","COCRDUPC","COPAUS0C","CORPT00C","COSGN00C","COTRN00C","COTRN01C","COTRN02C"],
  "call_closure__COADM01C": ["COSGN00C","COTRTLIC","COTRTUPC","COUSR00C","COUSR01C","COUSR02C","COUSR03C"]
}
```

- [ ] **Step 4: Record provenance in `KEY-AUDIT.md`**

`bench/cobolA/audit/KEY-AUDIT.md` documents, per audited question (or per group where the method is identical), *how* the answer was derived from source independent of the extractor — the grep command(s), the copybook/CSD lines read, and any target deliberately omitted as truly-dynamic. Open with the corpus SHA (`git -C corpus rev-parse HEAD`) and a one-paragraph method statement mirroring `oracle/AUDIT.md`. Close with a **delta table**: every question where the audited answer differs from `oracle_candidate`, and why (ProLeap ceiling vs. audit miss). An empty delta table is a valid, strong result (it means ProLeap matched the independent audit exactly).

- [ ] **Step 5: Validate the key is well-formed and covers exactly the audited questions**

Run:
```bash
cd bench/cobolA && python -c "
import json
key=json.load(open('audit/hard_strata_key.json'))
qs=[json.loads(l) for l in open('questions.jsonl')]
audited={q['id'] for q in qs if q['key_source']=='audited'}
universe=set(json.load(open('oracle/oracle.json'))['programs'])
assert set(key)==audited, f'key/question mismatch: {set(key)^audited}'
for qid,ans in key.items():
    assert ans==sorted(set(ans)), f'{qid} not sorted/deduped'
    assert set(ans)<=universe, f'{qid} has non-corpus names: {set(ans)-universe}'
print('OK: audited key covers', len(key), 'questions, all corpus-scoped + sorted')
"
```
Expected: `OK: audited key covers 43 questions, …`.

- [ ] **Step 6: Commit**

```bash
git add bench/cobolA/audit/make_key_skeleton.py bench/cobolA/audit/hard_strata_key.json bench/cobolA/audit/KEY-AUDIT.md
git commit -m "bench(cobolA): independent hand-audited answer key for grep-hard strata (1/3/5)"
```

---

## Task 6: `score.py` — hybrid-key P/R/F1 by stratum + verdict

Score arm result files against the hybrid truth (audited key where present, else the oracle answer), filtering both arms' found-sets to the corpus universe centrally. Emit `results/results.csv`, per-stratum means per arm, the macro-average over gating strata 1–3, and the pre-registered verdict.

**Files:**
- Create: `bench/cobolA/score.py`
- Test: `bench/cobolA/test_score.py`

- [ ] **Step 1: Write the failing test**

`bench/cobolA/test_score.py`:
```python
from score import prf, truth_for, verdict

def test_prf_basic():
    p, r, f = prf({"A", "B", "X"}, {"A", "B", "C"})
    assert round(p, 2) == 0.67 and round(r, 2) == 0.67 and round(f, 2) == 0.67

def test_prf_empty_found_is_zero():
    assert prf(set(), {"A"}) == (0.0, 0.0, 0.0)

def test_truth_prefers_audited_key_over_oracle_answer():
    q = {"id": "call_closure__X", "key_source": "audited", "answer_simple": ["WRONG"]}
    hard = {"call_closure__X": ["A", "B"]}
    assert truth_for(q, hard) == {"A", "B"}

def test_truth_uses_oracle_answer_for_oracle_keyed():
    q = {"id": "data_access__F", "key_source": "oracle", "answer_simple": ["A", "B"]}
    assert truth_for(q, {}) == {"A", "B"}

def test_verdict_greenlight():
    assert verdict(proxy_f1=0.95, grep_f1=0.30)["decision"] == "GREENLIGHT_B"

def test_verdict_not_a_fit_small_gap():
    assert verdict(proxy_f1=0.55, grep_f1=0.50)["decision"] == "NOT_A_FIT"

def test_verdict_ambiguous():
    assert verdict(proxy_f1=0.70, grep_f1=0.50)["decision"] == "AMBIGUOUS"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bench/cobolA && python -m pytest test_score.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'score'`.

- [ ] **Step 3: Write minimal implementation**

`bench/cobolA/score.py`:
```python
#!/usr/bin/env python3
"""Score arm result files against the hybrid answer key. Emits results/results.csv,
per-stratum means, the gating-strata macro-average, and the pre-registered verdict.

Usage: python score.py results/grep.results.jsonl results/proxy.results.jsonl
Each result line: {"id","arm","found_simple":[...],"calls":N}
"""
from __future__ import annotations

import csv
import json
import os
import statistics as st
import sys
from collections import defaultdict

GATING_STRATA = ("call_closure", "data_access", "data_coupling")  # macro-averaged for the verdict
PROXY_MIN, GREP_MAX, GAP_MIN, NOTFIT_GAP = 0.70, 0.45, 0.30, 0.15


def prf(found: set[str], truth: set[str]) -> tuple[float, float, float]:
    tp = len(found & truth)
    p = tp / len(found) if found else 0.0
    r = tp / len(truth) if truth else 0.0
    return p, r, (2 * p * r / (p + r) if (p + r) else 0.0)


def truth_for(question: dict, hard_key: dict) -> set[str]:
    if question["key_source"] == "audited" and question["id"] in hard_key:
        return set(hard_key[question["id"]])
    return set(question["answer_simple"])


def verdict(proxy_f1: float, grep_f1: float) -> dict:
    gap = proxy_f1 - grep_f1
    if proxy_f1 >= PROXY_MIN and grep_f1 <= GREP_MAX and gap >= GAP_MIN:
        decision = "GREENLIGHT_B"
    elif gap < NOTFIT_GAP:
        decision = "NOT_A_FIT"
    else:
        decision = "AMBIGUOUS"
    return {"decision": decision, "proxy_f1": round(proxy_f1, 3),
            "grep_f1": round(grep_f1, 3), "gap": round(gap, 3)}


def _load_questions():
    return {json.loads(l)["id"]: json.loads(l) for l in open("questions.jsonl")}


def main() -> None:
    questions = _load_questions()
    hard_key = json.load(open("audit/hard_strata_key.json"))
    universe = set(json.load(open("oracle/oracle.json"))["programs"])

    rows = []
    # per-(arm, stratum) F1 accumulator
    f1s: dict[tuple[str, str], list[float]] = defaultdict(list)
    for path in sys.argv[1:]:
        for line in open(path):
            r = json.loads(line)
            q = questions[r["id"]]
            truth = truth_for(q, hard_key)
            found = set(r.get("found_simple") or []) & universe  # central corpus filter
            p, rec, f = prf(found, truth)
            f1s[(r["arm"], q["stratum"])].append(f)
            rows.append([r["id"], q["stratum"], r["arm"], len(truth), len(found),
                         round(p, 3), round(rec, 3), round(f, 3), r.get("calls", "")])

    os.makedirs("results", exist_ok=True)
    with open("results/results.csv", "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["id", "stratum", "arm", "truth_n", "found_n",
                    "precision", "recall", "f1", "calls"])
        w.writerows(rows)

    arms = sorted({r[2] for r in rows})
    strata = ["call_closure", "data_access", "data_coupling", "copybook_fan", "txn_reach"]
    print(f"wrote results/results.csv ({len(rows)} rows)\n")
    header = "stratum".ljust(16) + "".join(a.ljust(12) for a in arms)
    print(header)
    for s in strata:
        line = s.ljust(16)
        for a in arms:
            vals = f1s.get((a, s), [])
            line += (f"{st.mean(vals):.3f}".ljust(12) if vals else "-".ljust(12))
        gate = " (gating)" if s in GATING_STRATA else ""
        print(line + gate)

    # macro-average over gating strata 1-3
    def macro(arm):
        per = [st.mean(f1s[(arm, s)]) for s in GATING_STRATA if f1s.get((arm, s))]
        return st.mean(per) if per else 0.0

    print("\n--- VERDICT (macro-avg over gating strata 1-3) ---")
    if "proxy" in arms and "grep" in arms:
        v = verdict(macro("proxy"), macro("grep"))
        print(json.dumps(v, indent=2))
    else:
        print("need both 'grep' and 'proxy' arm results to render the verdict")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd bench/cobolA && python -m pytest test_score.py -v`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add bench/cobolA/score.py bench/cobolA/test_score.py
git commit -m "bench(cobolA): hybrid-key stratified scorer + pre-registered verdict"
```

---

# PHASE 3 — Two arms, same questions

## Task 7: `grep` arm — fairly-resourced, dispatched per stratum

The grep arm is the lower bound: a real `grep`/ripgrep agent, *fairly resourced* (multi-step, free to chase `COPY`/`CALL`/CSD chains manually). The win must come from completeness, not from handicapping grep. It dispatches by stratum. Crucially, for `call_closure`/`txn_reach` it follows literal `CALL '…'` and `XCTL PROGRAM('…')` and even chases `MOVE 'literal' TO var` for variable XCTL — but it **cannot enumerate the `OCCURS`/`REDEFINES` menu-table dispatch** (that needs structural understanding of the table), which is exactly where ProLeap wins.

**Files:**
- Create: `bench/cobolA/arms/run_grep.py`
- Create: `bench/cobolA/arms/fixtures/` (tiny committed corpus for the test)
- Test: `bench/cobolA/arms/test_run_grep.py`

- [ ] **Step 1: Create the fixture mini-corpus**

`bench/cobolA/arms/fixtures/DRIVER.cbl`:
```
000100 IDENTIFICATION DIVISION.
000200 PROGRAM-ID. DRIVER.
000300 PROCEDURE DIVISION.
000400     CALL 'WORKER'.
000500     EXEC CICS XCTL PROGRAM('SCREEN') END-EXEC.
000600     READ ACCTFILE.
000700     STOP RUN.
```
`bench/cobolA/arms/fixtures/WORKER.cbl`:
```
000100 IDENTIFICATION DIVISION.
000200 PROGRAM-ID. WORKER.
000300 PROCEDURE DIVISION.
000400     READ ACCTFILE.
000500     COPY SHARED.
000600     STOP RUN.
```
`bench/cobolA/arms/fixtures/SCREEN.cbl`:
```
000100 IDENTIFICATION DIVISION.
000200 PROGRAM-ID. SCREEN.
000300 PROCEDURE DIVISION.
000400     COPY SHARED.
000500     STOP RUN.
```
`bench/cobolA/arms/fixtures/app.csd`:
```
 DEFINE TRANSACTION(DR00) GROUP(TESTGRP)
        PROGRAM(DRIVER) STATUS(ENABLED)
```

- [ ] **Step 2: Write the failing test**

`bench/cobolA/arms/test_run_grep.py`:
```python
import pathlib
from run_grep import answer_question

FIX = str(pathlib.Path(__file__).parent / "fixtures")

def test_call_closure_follows_literal_call_and_xctl():
    q = {"id": "call_closure__DRIVER", "stratum": "call_closure", "node": "DRIVER"}
    assert answer_question(q, FIX) == {"WORKER", "SCREEN"}

def test_data_access_finds_file_accessors():
    q = {"id": "data_access__ACCTFILE", "stratum": "data_access", "node": "ACCTFILE"}
    assert answer_question(q, FIX) == {"DRIVER", "WORKER"}

def test_copybook_fan_finds_includers():
    q = {"id": "copybook_fan__SHARED", "stratum": "copybook_fan", "node": "SHARED"}
    assert answer_question(q, FIX) == {"WORKER", "SCREEN"}

def test_data_coupling_finds_costakeholders_of_a_file():
    q = {"id": "data_coupling__DRIVER", "stratum": "data_coupling", "node": "DRIVER"}
    # DRIVER and WORKER both READ ACCTFILE -> WORKER is coupled to DRIVER
    assert answer_question(q, FIX) == {"WORKER"}

def test_txn_reach_resolves_entry_then_closes():
    q = {"id": "txn_reach__DR00", "stratum": "txn_reach", "node": "DR00"}
    assert answer_question(q, FIX) == {"DRIVER", "WORKER", "SCREEN"}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd bench/cobolA/arms && python -m pytest test_run_grep.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'run_grep'`.

- [ ] **Step 4: Write minimal implementation**

`bench/cobolA/arms/run_grep.py`:
```python
#!/usr/bin/env python3
"""Fairly-resourced grep/ripgrep arm. Deterministic; dispatched per stratum over a
COBOL corpus directory. Returns a name-set of corpus programs.

Design notes on fairness: this arm follows literal CALL/XCTL targets and chases
`MOVE 'literal' TO var` for variable XCTL within a file. It does NOT understand
OCCURS/REDEFINES menu tables (the structural construct ProLeap resolves) — that gap
is the thing the benchmark measures, not a handicap.

Usage (CLI): python run_grep.py <corpus_dir> <questions.jsonl>  -> JSONL on stdout
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

_PROGRAM_ID = re.compile(r"\bPROGRAM-ID\.\s+([A-Z0-9][A-Z0-9-]*)", re.I)
_CALL_LIT = re.compile(r"\bCALL\s+['\"]([A-Z0-9][A-Z0-9-]*)['\"]", re.I)
_XCTL_LIT = re.compile(r"\bEXEC\s+CICS\s+(?:XCTL|LINK)\b[^.]*?PROGRAM\s*\(\s*['\"]([A-Z0-9-]+)['\"]",
                       re.I | re.S)
_XCTL_VAR = re.compile(r"\bEXEC\s+CICS\s+(?:XCTL|LINK)\b[^.]*?PROGRAM\s*\(\s*([A-Z0-9][A-Z0-9-]*)\s*\)",
                       re.I | re.S)
_MOVE_LIT = re.compile(r"\bMOVE\s+['\"]([A-Z0-9][A-Z0-9-]*)['\"]\s+TO\s+([A-Z0-9][A-Z0-9-]*)", re.I)
_FILE_OP = re.compile(r"(?<![A-Za-z0-9-])(?:READ|WRITE|REWRITE|DELETE|START)\s+([A-Z0-9][A-Z0-9-]*)",
                      re.I)
_CICS_FILE = re.compile(r"\bEXEC\s+CICS\s+(?:READ|WRITE|REWRITE|DELETE|STARTBR)\b[^.]*?"
                        r"(?:FILE|DATASET)\s*\(\s*['\"]?([A-Z0-9][A-Z0-9-]*)['\"]?", re.I | re.S)
_COPY = re.compile(r"(?<![A-Za-z0-9-])COPY\s+([A-Z0-9][A-Z0-9-]*)", re.I)
_TXN = re.compile(r"DEFINE\s+TRANSACTION\(([A-Z0-9$@#]+)\)", re.I)
_CSD_PGM = re.compile(r"\bPROGRAM\(([A-Z0-9$@#]+)\)", re.I)


def _norm(raw: str) -> str:
    """Fixed-format code area (cols 8-72); '' for comment/blank/short lines."""
    if len(raw) < 7 or raw[6] in ("*", "/"):
        return ""
    return raw[7:72].rstrip()


def _load_programs(corpus_dir: str) -> dict[str, str]:
    """Return {PROGRAM_ID: normalized source text} for every *.cbl in the corpus."""
    progs: dict[str, str] = {}
    for path in sorted(pathlib.Path(corpus_dir).rglob("*")):
        if path.suffix.lower() == ".cbl" and path.is_file():
            code = "\n".join(_norm(l) for l in path.read_text(errors="replace").splitlines())
            m = _PROGRAM_ID.search(code)
            if m:
                progs[m.group(1).upper()] = code
    return progs


def _direct_targets(code: str) -> set[str]:
    """Literal CALL/XCTL targets, plus variable XCTL resolved via in-file MOVE 'lit' TO var."""
    out = {m.group(1).upper() for m in _CALL_LIT.finditer(code)}
    out |= {m.group(1).upper() for m in _XCTL_LIT.finditer(code)}
    # fair manual chase: var XCTL whose var is set by a literal MOVE in the same program
    moves = {var.upper(): lit.upper() for lit, var in
             ((m.group(1), m.group(2)) for m in _MOVE_LIT.finditer(code))}
    for m in _XCTL_VAR.finditer(code):
        var = m.group(1).upper()
        if var in moves:           # resolvable literal target
            out.add(moves[var])
    return out


def _resources(code: str) -> set[str]:
    return ({m.group(1).upper() for m in _FILE_OP.finditer(code)}
            | {m.group(1).upper() for m in _CICS_FILE.finditer(code)})


def answer_question(q: dict, corpus_dir: str) -> set[str]:
    progs = _load_programs(corpus_dir)
    universe = set(progs)
    stratum, node = q["stratum"], q["node"]

    if stratum in ("call_closure", "txn_reach"):
        if stratum == "txn_reach":
            entry = _resolve_txn(corpus_dir, node)
            if not entry:
                return set()
            seed = entry
        else:
            seed = node
        seen: set[str] = set()
        frontier = [seed]
        while frontier:
            nxt = []
            for p in frontier:
                if p in seen:
                    continue
                seen.add(p)
                for t in _direct_targets(progs.get(p, "")):
                    if t not in seen:
                        nxt.append(t)
            frontier = nxt
        result = seen
        if stratum == "call_closure":
            result = seen - {node}
        return result & universe

    if stratum == "data_access":
        return {p for p, code in progs.items() if node.upper() in _resources(code)} & universe

    if stratum == "copybook_fan":
        return {p for p, code in progs.items()
                if node.upper() in {m.group(1).upper() for m in _COPY.finditer(code)}} & universe

    if stratum == "data_coupling":
        my_res = _resources(progs.get(node, ""))
        out = set()
        for p, code in progs.items():
            if p != node and (_resources(code) & my_res):
                out.add(p)
        return out & universe

    return set()


def _resolve_txn(corpus_dir: str, txn: str) -> str | None:
    for path in sorted(pathlib.Path(corpus_dir).rglob("*")):
        if path.suffix.lower() == ".csd" and path.is_file():
            text = path.read_text(errors="replace")
            for block in re.split(r"(?=\bDEFINE\b)", text, flags=re.I):
                mt = _TXN.search(block)
                if mt and mt.group(1).upper() == txn.upper():
                    mp = _CSD_PGM.search(block)
                    if mp:
                        return mp.group(1).upper()
    return None


if __name__ == "__main__":
    corpus_dir, qpath = sys.argv[1], sys.argv[2]
    for line in open(qpath):
        q = json.loads(line)
        found = sorted(answer_question(q, corpus_dir))
        # one "call" per program file the arm had to read for closure strata; 1 otherwise (coarse)
        print(json.dumps({"id": q["id"], "arm": "grep",
                          "found_simple": found, "calls": ""}))
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd bench/cobolA/arms && python -m pytest test_run_grep.py -v`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add bench/cobolA/arms/run_grep.py bench/cobolA/arms/test_run_grep.py bench/cobolA/arms/fixtures
git commit -m "bench(cobolA): fairly-resourced grep arm dispatched per stratum"
```

---

## Task 8: `cobol_reachability` MCP shim + proxy arm

The proxy arm is the upper bound: the same questions answered through one thin MCP tool, `cobol_reachability(node, kind)`, backed by the audited `oracle.json`. A minimal stdio JSON-RPC server exercises a real MCP round-trip per question, so the "MCP-native delivery" claim is genuinely measured.

**Files:**
- Create: `bench/cobolA/arms/cobol_reachability_server.py`
- Create: `bench/cobolA/arms/run_proxy.py`
- Test: `bench/cobolA/arms/test_proxy.py`

- [ ] **Step 1: Write the failing test**

`bench/cobolA/arms/test_proxy.py`:
```python
import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).parent
ORACLE = HERE.parent / "oracle" / "oracle.json"

def _round_trip(node, kind):
    """Drive the stdio MCP server through initialize -> tools/call and return the result set."""
    proc = subprocess.Popen(
        [sys.executable, str(HERE / "cobol_reachability_server.py"), str(ORACLE)],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    msgs = [
        {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
        {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
         "params": {"name": "cobol_reachability", "arguments": {"node": node, "kind": kind}}},
    ]
    out, _ = proc.communicate("\n".join(json.dumps(m) for m in msgs) + "\n", timeout=30)
    results = [json.loads(l) for l in out.splitlines() if l.strip()]
    call_resp = next(r for r in results if r.get("id") == 2)
    payload = json.loads(call_resp["result"]["content"][0]["text"])
    return set(payload["found_simple"])

def test_proxy_returns_oracle_call_closure():
    oracle = json.load(open(ORACLE))
    node = next(p for p, c in oracle["transitive_call_closure"].items() if len(c) >= 2)
    expected = set(oracle["transitive_call_closure"][node])
    assert _round_trip(node, "call_closure") == expected

def test_proxy_unknown_node_returns_empty():
    assert _round_trip("NOPE-DOES-NOT-EXIST", "call_closure") == set()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bench/cobolA/arms && python -m pytest test_proxy.py -v`
Expected: FAIL — server file does not exist (`FileNotFoundError`/non-zero exit).

- [ ] **Step 3: Write the server**

`bench/cobolA/arms/cobol_reachability_server.py`:
```python
#!/usr/bin/env python3
"""Thin stdio JSON-RPC (MCP-subset) server exposing one tool, cobol_reachability(node, kind),
backed by oracle.json. Implements initialize / tools/list / tools/call. Line-delimited JSON.

Usage: cobol_reachability_server.py <oracle.json>
"""
import json
import sys

KIND_TO_KEY = {
    "call_closure": "transitive_call_closure",
    "data_access": "data_access",
    "data_coupling": "data_coupling",
    "copybook_fan": "copybook_fan",
    "txn_reach": "txn_reach",
}

TOOL = {
    "name": "cobol_reachability",
    "description": "Return the set of CardDemo programs reachable/related to a node under a "
                   "given relation kind, from the precomputed ProLeap oracle.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "node": {"type": "string"},
            "kind": {"type": "string", "enum": list(KIND_TO_KEY)},
        },
        "required": ["node", "kind"],
    },
}


def _lookup(oracle, node, kind):
    table = oracle.get(KIND_TO_KEY.get(kind, ""), {})
    return sorted(table.get(node, []))


def main():
    oracle = json.load(open(sys.argv[1]))
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        req = json.loads(line)
        method, rid = req.get("method"), req.get("id")
        if method == "initialize":
            _send(rid, {"protocolVersion": "2025-03-26", "capabilities": {"tools": {}},
                        "serverInfo": {"name": "cobol-reachability", "version": "0"}})
        elif method == "tools/list":
            _send(rid, {"tools": [TOOL]})
        elif method == "tools/call":
            args = req["params"]["arguments"]
            found = _lookup(oracle, args["node"].upper(), args["kind"])
            payload = {"node": args["node"].upper(), "kind": args["kind"], "found_simple": found}
            _send(rid, {"content": [{"type": "text", "text": json.dumps(payload)}]})
        elif rid is not None:
            _send(rid, None, error={"code": -32601, "message": f"method not found: {method}"})
        # notifications (no id) are acknowledged by silence


def _send(rid, result, error=None):
    msg = {"jsonrpc": "2.0", "id": rid}
    if error:
        msg["error"] = error
    else:
        msg["result"] = result
    sys.stdout.write(json.dumps(msg) + "\n")
    sys.stdout.flush()


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Write the proxy arm driver**

`bench/cobolA/arms/run_proxy.py`:
```python
#!/usr/bin/env python3
"""Proxy arm: one cobol_reachability MCP round-trip per question, over a single
long-lived server subprocess. Emits JSONL identical in shape to the grep arm.

Usage: python run_proxy.py <oracle.json> <questions.jsonl>  -> JSONL on stdout
"""
import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).parent


def main():
    oracle_path, qpath = sys.argv[1], sys.argv[2]
    proc = subprocess.Popen(
        [sys.executable, str(HERE / "cobol_reachability_server.py"), oracle_path],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
    _rpc(proc, {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}})
    proc.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized",
                                 "params": {}}) + "\n")
    proc.stdin.flush()

    rid = 1
    for line in open(qpath):
        q = json.loads(line)
        rid += 1
        resp = _rpc(proc, {"jsonrpc": "2.0", "id": rid, "method": "tools/call",
                           "params": {"name": "cobol_reachability",
                                      "arguments": {"node": q["node"], "kind": q["kind"]}}})
        payload = json.loads(resp["result"]["content"][0]["text"])
        print(json.dumps({"id": q["id"], "arm": "proxy",
                          "found_simple": payload["found_simple"], "calls": 1}))
    proc.stdin.close()
    proc.wait(timeout=10)


def _rpc(proc, msg):
    proc.stdin.write(json.dumps(msg) + "\n")
    proc.stdin.flush()
    while True:                      # read until the response matching our id
        line = proc.stdout.readline()
        if not line:
            raise RuntimeError("server closed unexpectedly")
        resp = json.loads(line)
        if resp.get("id") == msg["id"]:
            return resp


if __name__ == "__main__":
    main()
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd bench/cobolA/arms && python -m pytest test_proxy.py -v`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add bench/cobolA/arms/cobol_reachability_server.py bench/cobolA/arms/run_proxy.py bench/cobolA/arms/test_proxy.py
git commit -m "bench(cobolA): cobol_reachability stdio-MCP shim + proxy arm driver"
```

---

## Task 9: `run_all.sh` — run both arms, score, emit results.csv

**Files:**
- Create: `bench/cobolA/run_all.sh`
- Generate: `bench/cobolA/results/grep.results.jsonl`, `results/proxy.results.jsonl`, `results/results.csv`

- [ ] **Step 1: Write the runner**

`bench/cobolA/run_all.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
test -d corpus || { echo "corpus missing — run ./clone_corpus.sh first" >&2; exit 1; }
mkdir -p results
echo "== grep arm ==" >&2
python3 arms/run_grep.py corpus questions.jsonl > results/grep.results.jsonl
echo "== proxy arm ==" >&2
python3 arms/run_proxy.py oracle/oracle.json questions.jsonl > results/proxy.results.jsonl
echo "== score ==" >&2
python3 score.py results/grep.results.jsonl results/proxy.results.jsonl
```

- [ ] **Step 2: Run the full benchmark**

Run:
```bash
chmod +x bench/cobolA/run_all.sh && bench/cobolA/run_all.sh
```
Expected: prints the per-stratum F1 table for `grep` and `proxy`, then the VERDICT JSON. Sanity: proxy F1 on the gating strata should be high (≈0.9–1.0; below 1.0 only where the audited key diverges from ProLeap), grep F1 on `call_closure`/`txn_reach` should be visibly lower (the menu-table dispatch it cannot enumerate).

- [ ] **Step 3: Inspect the result spread before trusting the verdict**

Run:
```bash
cd bench/cobolA && python3 -c "
import csv, collections, statistics as st
rows=list(csv.DictReader(open('results/results.csv')))
agg=collections.defaultdict(list)
for r in rows: agg[(r['arm'],r['stratum'])].append(float(r['f1']))
for k in sorted(agg): print(k, round(st.mean(agg[k]),3), 'n=%d'%len(agg[k]))
"
```
Expected: every (arm, stratum) cell present with a sane n; no stratum has n=0 for either arm.

- [ ] **Step 4: Commit the runner and committed results**

```bash
git add bench/cobolA/run_all.sh bench/cobolA/results/results.csv
git commit -m "bench(cobolA): run_all.sh + scored results.csv for both arms"
```

(The per-arm `*.results.jsonl` are regenerable; `.gitignore` them if noisy, or commit for provenance — match whatever `bench/arenaA/` does.)

---

## Task 10: Verdict writeup

Render the findings in the verdict-against-the-thesis voice of the prior results docs (`docs/superpowers/results/`), regardless of which way it falls.

**Files:**
- Create: `docs/superpowers/results/2026-06-03-cobol-decomposition-findings.md`

- [ ] **Step 1: Draft the writeup from the real numbers**

Write `docs/superpowers/results/2026-06-03-cobol-decomposition-findings.md` covering, with the actual figures from `results/results.csv` and `score.py`'s verdict block:
- **Question:** can a CML-style `cobol_reachability` oracle beat a fairly-resourced grep on CardDemo decomposition reachability?
- **Method:** 5 strata, ~64 questions, hybrid answer key (oracle-keyed for the grep-verifiable static strata; independent hand-audit for the grep-hard strata — link `audit/KEY-AUDIT.md` and its delta table), two deterministic arms, pre-registered thresholds judged on the macro-average of gating strata 1–3.
- **Results:** the per-stratum F1 table (both arms), the macro-averages, the gap, and the verdict (`GREENLIGHT_B` / `AMBIGUOUS` / `NOT_A_FIT`).
- **Where the gap comes from (or doesn't):** name the concrete construct — the `OCCURS`/`REDEFINES` menu-table XCTL dispatch grep cannot enumerate — and cite specific questions (e.g. `call_closure__COMEN01C`, `txn_reach__CM00`).
- **Make-vs-buy caveat (spec §Risks):** COBOL dependency analyzers already exist; CML's only distinct claim is MCP-native token-efficient agent delivery. State it plainly.
- **Honest limits:** corpus scale (44 programs; thin gating strata — stratum 2 has only 6 questions), the proxy = audited-ProLeap relationship, and the 3 truly-dynamic XCTL chains omitted from the key.
- **Next step:** if green → expand Phase 4 (decomposition quality) and then Approach B (real COBOL support in CML); if ambiguous/negative → record the "not a fit" verdict and stop, as designed.

- [ ] **Step 2: Cross-check every quoted number against the CSV**

Re-read the draft beside `results/results.csv` and the `score.py` stdout; confirm each cited F1/gap matches. Fix any drift.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/results/2026-06-03-cobol-decomposition-findings.md
git commit -m "docs(cobolA): COBOL decomposition feasibility verdict + evidence"
```

---

## Self-Review notes

- **Spec coverage:**
  - Phase 2 §"5 strata" → Tasks 1–4 implement all five (`call_closure`, `data_access`, `data_coupling`, `copybook_fan`, `txn_reach`); the stratum-5 CSD dependency the memory flagged is closed by Task 1.
  - Phase 2 §"question schema matches arenaA (`id`, `stratum`, `answer_simple`, prompt)" → `select_questions._mk` emits exactly those plus `node`/`kind`/`key_source`.
  - Phase 2 §"recall/precision/F1 per query, aggregated by stratum" → `score.py`.
  - Phase 3 §"grep arm, fairly resourced" → Task 7 (follows literal CALL/XCTL + in-file MOVE chase; the only thing withheld is structural OCCURS-table understanding, which is the measured gap, not a handicap).
  - Phase 3 §"cml-proxy arm, thin MCP `cobol_reachability(node, kind)` backed by Phase-1 graph" → Task 8.
  - §"answers are name-sets scored against the oracle via reused score.py" → Task 6, adapted to the hybrid key (the explicit user decision; circularity addressed).
  - §"Verdict (pre-registered thresholds), macro-average over strata 1–3" → `score.verdict` + `GATING_STRATA`.
  - §"deliverables" → `questions.jsonl` + `select_questions.py` (P2); `arms/` + `results/results.csv` (P3); `docs/superpowers/results/…` (verdict).
  - Phase 4 is explicitly out of scope here (conditional on a green Phase 3), matching the spec's gating.
- **Hybrid-key decision is honored end-to-end:** `key_source` set in Task 4, the independent audit produced in Task 5, consumed in Task 6 (`truth_for`), so proxy F1 reflects ProLeap's real accuracy on the gating strata rather than scoring against itself.
- **Type/name consistency:** stratum/kind names (`call_closure`, `data_access`, `data_coupling`, `copybook_fan`, `txn_reach`) are identical across `select_questions.py`, `oracle.json` keys (via `KIND_TO_KEY`), `run_grep.answer_question`, the server, and `score.py`. Result-line shape `{id, arm, found_simple, calls}` is identical between `run_grep.py`, `run_proxy.py`, and `score.main`. The corpus-universe filter lives in exactly one place (`score.py`).
- **No placeholders:** every code/test step is complete and runnable. Task 5's `hard_strata_key.json`/`KEY-AUDIT.md` are genuine analyst deliverables (independent source audit with a defined method and worked anchors), not code stubs — the only legitimately human-derived artifacts in the plan, and the validator in Task 5 Step 5 enforces their shape.
```
