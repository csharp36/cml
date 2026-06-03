#!/usr/bin/env python3
"""Phase 0 recon driver: characterize a COBOL corpus and recommend the gate decision.

Usage: python run_recon.py corpus/   (writes recon.json + PHASE0-recon.md next to this file)
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
