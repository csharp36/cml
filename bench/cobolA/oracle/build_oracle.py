"""
build_oracle.py — turns raw-edges.json into the ground-truth oracle.json.

Usage:
    python3 build_oracle.py raw-edges.json oracle.json [corpus_dir]

Output is deterministic (all lists sorted) so re-running produces a zero diff.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from graph import call_edges, transitive_call_closure, copybook_fan, data_coupling_neighbors


# ---------------------------------------------------------------------------
# I/O helpers
# ---------------------------------------------------------------------------

def load_edges(path: str) -> dict[str, dict]:
    """Read the raw-edges JSON array and return a map keyed by program_id."""
    with open(path, encoding="utf-8") as fh:
        records = json.load(fh)
    return {rec["program_id"]: rec for rec in records}


# ---------------------------------------------------------------------------
# Core builder
# ---------------------------------------------------------------------------

def build_oracle(
    edges_map: dict[str, dict],
    txn_entry: dict[str, str] | None = None,
    ddnames_by_program: dict[str, set[str]] | None = None,
) -> dict:
    """Pre-compute all oracle strata from edges_map.

    Parameters
    ----------
    edges_map:
        program_id -> edges-object (from raw-edges.json).
    txn_entry:
        {TXN: entry-program} from CSD parsing.  Corpus-filtered inside.
    ddnames_by_program:
        Optional {program_id: set(ddnames)} from ASSIGN-clause parsing
        (selects.ddnames_by_program).

        When provided, file resources are keyed on the PHYSICAL ddname (the
        ASSIGN target) instead of the logical file name from files_read /
        files_written.  This is Option-2 fix for the precision defect: two
        programs can SELECT the same logical name but ASSIGN it to *different*
        physical ddnames, producing false data-coupling.  Keying on the ddname
        eliminates those false positives.

        Implementation: we build a normalised working copy of edges_map where
        each program's files_read is replaced by its sorted ddname list and
        files_written is set to [] (db2_tables is untouched — it is already a
        physical name).  All resource/data_access/data_coupling logic then runs
        unchanged over this normalised map.

        When None (default), the OLD behaviour is preserved: files_read /
        files_written are used as file resources.  This keeps existing unit-test
        callers that pass synthetic logical names working without modification.

    Returns a dict with:
      programs                 — sorted list of program_ids
      resources                — sorted unique files+db2 across corpus
      copybooks                — sorted unique copybooks across corpus
      transitive_call_closure  — {pid: sorted(closure)}
      data_access              — {resource: sorted(program_ids)}
      copybook_fan             — {copybook: sorted(program_ids)}
      data_coupling            — {pid: sorted(peers sharing >=1 file/DB2)}
      cics_txn_entry           — {txn: entry program} (corpus-filtered)
      txn_reach                — {txn: sorted(reachable programs)}
      direct_call_edges        — {pid: sorted(call_edges)}   (for reference/debug)
    """
    # ---- programs --------------------------------------------------------
    programs = sorted(edges_map.keys())
    prog_set = set(edges_map.keys())
    txn_entry = txn_entry or {}

    # ---- normalise file resources to physical ddnames when provided ------
    # Build a working copy of edges_map where files_read carries the ddnames
    # (physical ASSIGN targets) instead of logical SELECT names. files_written
    # is zeroed out because ddnames_by_program already covers read+write ASSIGN
    # declarations; db2_tables is left untouched (already physical).
    if ddnames_by_program is not None:
        working_map: dict[str, dict] = {}
        for pid, obj in edges_map.items():
            ddnames = sorted(ddnames_by_program.get(pid, set()))
            working_map[pid] = dict(obj, files_read=ddnames, files_written=[])
    else:
        # Backward-compatible: use edges_map as-is (logical names).
        working_map = edges_map

    # ---- resources (files + db2) -----------------------------------------
    all_resources: set[str] = set()
    for obj in working_map.values():
        all_resources |= set(obj.get("files_read", []))
        all_resources |= set(obj.get("files_written", []))
        all_resources |= set(obj.get("db2_tables", []))
    resources = sorted(all_resources)

    # ---- copybooks -------------------------------------------------------
    # Copybooks are always taken from the original edges_map (not affected by
    # the ddname normalisation — COPY statements are independent of file I/O).
    all_copybooks: set[str] = set()
    for obj in edges_map.values():
        all_copybooks |= set(obj.get("copybooks", []))
    copybooks = sorted(all_copybooks)

    # ---- transitive call closure (per program) ---------------------------
    # Call edges don't use file resources, so edges_map and working_map are
    # equivalent here; we use edges_map for clarity.
    transitive: dict[str, list[str]] = {
        pid: sorted(transitive_call_closure(edges_map, pid))
        for pid in programs
    }

    # ---- data access (per resource) --------------------------------------
    data_access: dict[str, list[str]] = {}
    for resource in resources:
        accessors: list[str] = sorted(
            pid
            for pid, obj in working_map.items()
            if resource in (
                set(obj.get("files_read", []))
                | set(obj.get("files_written", []))
                | set(obj.get("db2_tables", []))
            )
        )
        data_access[resource] = accessors

    # ---- copybook fan (per copybook) ------------------------------------
    raw_fan = copybook_fan(edges_map)
    cb_fan: dict[str, list[str]] = {
        cb: sorted(pids) for cb, pids in sorted(raw_fan.items())
    }

    # ---- data coupling (file/DB2), corpus-filtered ----------------------
    neigh = data_coupling_neighbors(working_map)
    data_coupling: dict[str, list[str]] = {
        pid: sorted(neigh[pid] & prog_set) for pid in programs
    }

    # ---- CICS txn -> entry program (corpus-filtered) --------------------
    cics_txn_entry: dict[str, str] = {
        txn: pgm for txn, pgm in sorted(txn_entry.items()) if pgm in prog_set
    }

    # ---- txn -> reachable programs = {entry} U closure(entry) -----------
    txn_reach: dict[str, list[str]] = {}
    for txn, entry in cics_txn_entry.items():
        reach = ({entry} | set(transitive[entry])) & prog_set
        txn_reach[txn] = sorted(reach)

    # ---- direct call edges (per program) --------------------------------
    direct: dict[str, list[str]] = {
        pid: sorted(call_edges(obj))
        for pid, obj in edges_map.items()
    }
    # Sort by pid for deterministic output
    direct = dict(sorted(direct.items()))

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


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

def main() -> None:
    if len(sys.argv) not in (3, 4):
        print(f"Usage: {sys.argv[0]} <raw-edges.json> <oracle.json> [corpus_dir]", file=sys.stderr)
        sys.exit(1)
    in_path, out_path = sys.argv[1], sys.argv[2]
    corpus_dir = sys.argv[3] if len(sys.argv) == 4 else "../corpus"
    edges_map = load_edges(in_path)
    from csd import parse_csd_dir  # deferred: build_oracle() stays csd-free for unit tests
    from selects import ddnames_by_program as _ddnames_by_program  # deferred: same reason
    txn_entry = parse_csd_dir(corpus_dir)
    dbn = _ddnames_by_program(corpus_dir)
    oracle = build_oracle(edges_map, txn_entry, dbn)
    Path(out_path).write_text(json.dumps(oracle, indent=2, sort_keys=False) + "\n", encoding="utf-8")
    print(f"oracle.json written: {len(oracle['programs'])} programs, "
          f"{len(oracle['resources'])} resources, {len(oracle['copybooks'])} copybooks, "
          f"{len(oracle['cics_txn_entry'])} txns", file=sys.stderr)


if __name__ == "__main__":
    main()
