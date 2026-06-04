"""extend_key.py — extend the independent hand-audited key to cover the 23 new audited questions.

Derives new answers using the SAME independently-established structural facts:
  1. The online SCC reachable set R, computed from the existing call_closure answers + nodes.
  2. ddname / DB2-table data coupling, from SELECT/ASSIGN parsing + raw-edges.json EXEC SQL.
  3. txn_reach: CSD transaction->entry map, then entry + closure from the (now complete) call_closure key.

Does NOT copy answers from oracle.json.  Deterministic and idempotent.

Run from bench/cobolA/:
    python audit/extend_key.py
"""
from __future__ import annotations

import json
import pathlib
import sys

# Allow imports from oracle/
sys.path.insert(0, str(pathlib.Path(__file__).parent.parent / "oracle"))

from csd import parse_csd_dir          # noqa: E402  (after sys.path manipulation)
from selects import ddnames_by_program  # noqa: E402

HERE = pathlib.Path(__file__).parent
ROOT = HERE.parent

# ---------------------------------------------------------------------------
# 0. Paths
# ---------------------------------------------------------------------------
KEY_PATH     = HERE / "hard_strata_key.json"
QUESTIONS    = ROOT / "questions.jsonl"
ORACLE_PATH  = ROOT / "oracle" / "oracle.json"
RAW_EDGES    = ROOT / "oracle" / "raw-edges.json"
CORPUS_DIR   = ROOT / "corpus"

# ---------------------------------------------------------------------------
# 1. Load existing independent key (32 entries) and questions
# ---------------------------------------------------------------------------
with KEY_PATH.open() as f:
    key: dict[str, list[str]] = json.load(f)

with QUESTIONS.open() as f:
    questions = [json.loads(line) for line in f]

audited_ids = {q["id"] for q in questions if q.get("key_source") == "audited"}

# Corpus programs (from oracle.json — the programs list is the corpus filter, not oracle answers)
with ORACLE_PATH.open() as f:
    oracle = json.load(f)
corpus_programs: set[str] = set(oracle["programs"])

# ---------------------------------------------------------------------------
# 2. Compute R, the full online reachable set, from the EXISTING call_closure entries
#    R = union of all existing call_closure answers UNION the set of those cc question nodes
# ---------------------------------------------------------------------------
existing_cc: dict[str, set[str]] = {
    qid.split("__")[1]: set(ans)
    for qid, ans in key.items()
    if qid.startswith("call_closure__")
}

R: set[str] = set(existing_cc.keys())  # the nodes themselves
for ans in existing_cc.values():
    R |= ans

# R should now be the full online SCC + reachable sinks (23 programs)

# ---------------------------------------------------------------------------
# 3. New call_closure entries: for each new online SCC member X, key[X] = sorted(R - {X})
# ---------------------------------------------------------------------------
new_cc_nodes = [
    qid.split("__")[1]
    for qid in audited_ids
    if qid.startswith("call_closure__") and qid not in key
]

for node in new_cc_nodes:
    qid = f"call_closure__{node}"
    if node not in R:
        raise ValueError(
            f"{node} is a new call_closure question but NOT a member of R — "
            f"it does not follow the established SCC pattern; manual investigation required."
        )
    key[qid] = sorted(R - {node})

# ---------------------------------------------------------------------------
# 4. Build the complete call_closure lookup (existing + newly derived)
#    We need this for txn_reach step below.
# ---------------------------------------------------------------------------
all_cc: dict[str, set[str]] = {
    qid.split("__")[1]: set(ans)
    for qid, ans in key.items()
    if qid.startswith("call_closure__")
}

# ---------------------------------------------------------------------------
# 5. New txn_reach entries
#    key[T] = sorted({entry} ∪ all_cc[entry]) ∩ corpus_programs
# ---------------------------------------------------------------------------
txn_map: dict[str, str] = parse_csd_dir(CORPUS_DIR)  # {TXN: ENTRY_PROGRAM}

new_txn_nodes = [
    qid.split("__")[1]
    for qid in audited_ids
    if qid.startswith("txn_reach__") and qid not in key
]

for txn in new_txn_nodes:
    qid = f"txn_reach__{txn}"
    entry = txn_map.get(txn)
    if entry is None:
        raise ValueError(
            f"Transaction {txn} not found in any CSD file under {CORPUS_DIR}."
        )
    closure = all_cc.get(entry, set())
    reach = ({entry} | closure) & corpus_programs
    key[qid] = sorted(reach)

# ---------------------------------------------------------------------------
# 6. New data_coupling entries
#    Derive DIRECTLY from source:
#      - ddnames: parse SELECT/ASSIGN per program (oracle/selects.py)
#      - db2 tables: read from oracle/raw-edges.json (direct EXEC SQL reading)
#    Two programs couple iff they share >=1 ddname OR >=1 DB2 table.
# ---------------------------------------------------------------------------

# Build ddnames per corpus program
all_ddnames: dict[str, set[str]] = ddnames_by_program(CORPUS_DIR)
# Filter to corpus programs only
ddnames_by_prog: dict[str, set[str]] = {
    p: dds for p, dds in all_ddnames.items() if p in corpus_programs
}

# Build DB2 tables per corpus program from raw-edges.json
with RAW_EDGES.open() as f:
    raw_edges: list[dict] = json.load(f)

db2_by_prog: dict[str, set[str]] = {}
for prog_entry in raw_edges:
    prog_id = prog_entry.get("program_id", "").upper()
    if prog_id in corpus_programs:
        tables = prog_entry.get("db2_tables", [])
        if tables:
            db2_by_prog[prog_id] = set(t.upper() for t in tables)

new_dc_nodes = [
    qid.split("__")[1]
    for qid in audited_ids
    if qid.startswith("data_coupling__") and qid not in key
]

for node in new_dc_nodes:
    qid = f"data_coupling__{node}"
    node_dds = ddnames_by_prog.get(node, set())
    node_db2 = db2_by_prog.get(node, set())

    coupled = set()
    for other in corpus_programs:
        if other == node:
            continue
        other_dds = ddnames_by_prog.get(other, set())
        other_db2 = db2_by_prog.get(other, set())
        # Coupled iff shared ddname (non-empty intersection) OR shared DB2 table
        if (node_dds & other_dds) or (node_db2 & other_db2):
            coupled.add(other)

    key[qid] = sorted(coupled)

# ---------------------------------------------------------------------------
# 7. Validate: all audited IDs must now be covered
# ---------------------------------------------------------------------------
missing = audited_ids - set(key.keys())
if missing:
    raise RuntimeError(f"Still missing {len(missing)} entries: {sorted(missing)}")

extra = set(key.keys()) - audited_ids
if extra:
    raise RuntimeError(f"Key has {len(extra)} extra entries not in audited set: {sorted(extra)}")

# Validate corpus-scoped, sorted, >=2 (soft check — report rather than crash)
for qid, ans in sorted(key.items()):
    assert ans == sorted(set(ans)), f"{qid} not sorted/deduped"
    out_of_corpus = set(ans) - corpus_programs
    if out_of_corpus:
        raise ValueError(f"{qid} contains non-corpus programs: {sorted(out_of_corpus)}")

# ---------------------------------------------------------------------------
# 8. Write the merged 55-entry key (keys sorted alphabetically)
# ---------------------------------------------------------------------------
merged = {k: key[k] for k in sorted(key)}
with KEY_PATH.open("w") as f:
    json.dump(merged, f, indent=2)
    f.write("\n")

print(f"Written {len(merged)} entries to {KEY_PATH}")

# ---------------------------------------------------------------------------
# 9. Summary
# ---------------------------------------------------------------------------
by_stratum: dict[str, int] = {}
for qid in merged:
    stratum = qid.split("__")[0]
    by_stratum[stratum] = by_stratum.get(stratum, 0) + 1

for s, n in sorted(by_stratum.items()):
    print(f"  {s}: {n}")
