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
    moves = {var.upper(): lit.upper() for lit, var in
             ((m.group(1), m.group(2)) for m in _MOVE_LIT.finditer(code))}
    for m in _XCTL_VAR.finditer(code):
        var = m.group(1).upper()
        if var in moves:
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
        print(json.dumps({"id": q["id"], "arm": "grep",
                          "found_simple": found, "calls": ""}))
