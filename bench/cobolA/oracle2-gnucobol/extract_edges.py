#!/usr/bin/env python3
"""Independent control-flow + data-coupling extractor for the GnuCOBOL cross-check oracle.

Consumes GnuCOBOL-preprocessed source (`normalized/*.cob` from preprocess.sh) and
re-derives, by a DIFFERENT implementation than the ProLeap extractor, three things
per program:

  * resolved control-transfer edges (literal CALL + CICS XCTL/LINK, with variable
    operands resolved through transitive VALUE-constant propagation and OCCURS
    menu-table harvest),
  * the SELECT -> ASSIGN ddname file map (physical file identity),
  * DB2 table references.

It is deliberately blind to oracle.json: it never reads the ProLeap answers. The
point is to see whether an independent pass over independently-normalized text
arrives at the same edges. Disagreements are reported, not tuned away.

Resolution rules (principled, not fitted to the oracle):
  R1  CALL 'LIT'                      -> edge to LIT
  R2  CALL var / XCTL|LINK PROGRAM(op)-> resolve op:
        - 'LIT'                       -> LIT
        - VAR                         -> const-prop literal set of VAR
        - VAR(subscript)              -> const-prop set of VAR  UNION
                                         corpus-name VALUE literals declared in
                                         the same 01-record as VAR (OCCURS table)
  Const-prop: seed each data item with its own VALUE 'LIT'; propagate across
  `MOVE src TO dst` (dst inherits src's literals) to a fixpoint. A commarea field
  with no literal source (e.g. CDEMO-FROM-PROGRAM) therefore resolves to nothing
  and is excluded by construction — matching the declared dynamic ceiling.
"""
from __future__ import annotations
import re
import sys
import json
from pathlib import Path
from collections import defaultdict

PROGNAME_RE = re.compile(r"^[A-Z][A-Z0-9]{1,7}$")


def _strip_seqno(line: str) -> str:
    # cobc -E already strips fixed-format columns 1-6 and the indicator column,
    # but trailing sequence numbers in cols 73-80 can survive; drop a trailing
    # run of >=6 digits that is clearly a sequence number.
    return re.sub(r"\s+\d{6,8}\s*$", "", line.rstrip("\n"))


def load_text(path: Path) -> str:
    raw = path.read_text(errors="replace").splitlines()
    return "\n".join(_strip_seqno(l) for l in raw)


# ---- data division: VALUE literals, MOVE chains, OCCURS record scoping --------

def parse_data_facts(text: str):
    """Parse the data division.

    Returns (value_lits, record_of, occurs_vars, record_lits):
      value_lits[var]  = set of literal strings from `var ... VALUE 'lit'`
      record_of[var]   = the enclosing 01-level record name for a data item
      occurs_vars      = set of data items declared (at or under) an OCCURS group
      record_lits[rec] = set of all literal VALUEs declared anywhere in that 01-record
    """
    value_lits = defaultdict(set)
    record_of = {}
    record_lits = defaultdict(set)
    occurs_vars = set()

    cur_record = None
    in_occurs_level = None  # level number of the active OCCURS group

    # Work on period-terminated data sentences, newlines flattened to spaces.
    flat = re.sub(r"\s+", " ", text)
    sentences = [s.strip() for s in flat.split(".") if s.strip()]
    for s in sentences:
        m = re.match(r"^(\d\d)\s+([A-Z0-9][\w-]*)\b(.*)$", s)
        if not m:
            continue
        level, name, rest = m.group(1), m.group(2), m.group(3)
        if level == "01":
            cur_record = name
            in_occurs_level = None
        # track OCCURS scope by level nesting
        if in_occurs_level is not None and int(level) <= in_occurs_level:
            in_occurs_level = None
        is_occurs = bool(re.search(r"\bOCCURS\b", rest))
        if cur_record is not None:
            record_of[name] = cur_record
        if in_occurs_level is not None or is_occurs:
            occurs_vars.add(name)
        if is_occurs:
            in_occurs_level = int(level)
        for lit in re.findall(r"VALUE\s+'([^']*)'", rest):
            value_lits[name].add(lit)
            if cur_record is not None:
                record_lits[cur_record].add(lit)
    return value_lits, record_of, occurs_vars, record_lits


def parse_moves(text: str):
    move_edges = []
    move_lit = defaultdict(set)
    flat = re.sub(r"\s+", " ", text)
    for m in re.finditer(r"\bMOVE\s+'([^']+)'\s+TO\s+([A-Z0-9][\w-]*)", flat):
        move_lit[m.group(2)].add(m.group(1))
    for m in re.finditer(r"\bMOVE\s+([A-Z0-9][\w-]*)\s+TO\s+([A-Z0-9][\w-]*)", flat):
        move_edges.append((m.group(1), m.group(2)))
    return move_edges, move_lit


def const_prop(value_lits, move_lit, move_edges):
    """Fixpoint literal set per variable: own VALUE + MOVE 'lit' + inherited via MOVE."""
    lits = defaultdict(set)
    for v, s in value_lits.items():
        lits[v] |= s
    for v, s in move_lit.items():
        lits[v] |= s
    changed = True
    while changed:
        changed = False
        for src, dst in move_edges:
            if lits[src] - lits[dst]:
                lits[dst] |= lits[src]
                changed = True
    return lits


# ---- control transfers --------------------------------------------------------

def extract_control_targets(text: str, corpus: set) -> set:
    flat = re.sub(r"\s+", " ", text)
    value_lits, record_of, occurs_vars, record_lits = parse_data_facts(text)
    move_edges, move_lit = parse_moves(text)
    lits = const_prop(value_lits, move_lit, move_edges)

    targets = set()

    # R1: literal CALL
    for lit in re.findall(r"\bCALL\s+'([^']+)'", flat):
        targets.add(lit)

    # dynamic CALL <ident>
    for var in re.findall(r"\bCALL\s+([A-Z][\w-]*)\b", flat):
        targets |= lits.get(var, set())

    # XCTL / LINK PROGRAM( op )
    for m in re.finditer(r"\b(?:XCTL|LINK)\b[^.]*?PROGRAM\s*\(\s*([^)]+?)\s*\)", flat):
        op = m.group(1).strip()
        lit = re.match(r"^'([^']+)'$", op)
        if lit:
            targets.add(lit.group(1))
            continue
        # identifier, possibly subscripted: BASE or BASE(SUB) or BASE(a:b)
        base = re.match(r"^([A-Z][\w-]*)", op)
        if not base:
            continue
        bname = base.group(1)
        subscripted = "(" in op
        targets |= lits.get(bname, set())
        if subscripted:
            rec = record_of.get(bname)
            if rec:
                # OCCURS menu-table harvest: corpus-name VALUEs in the same record
                targets |= {l for l in record_lits.get(rec, set()) if l in corpus}
    return {t for t in targets}


# ---- files / DB2 --------------------------------------------------------------

def extract_files(text: str) -> set:
    """Physical file identity = the SELECT ... ASSIGN TO <ddname>, not the logical name."""
    flat = re.sub(r"\s+", " ", text)
    ddnames = set()
    for m in re.finditer(r"\bSELECT\s+[A-Z0-9][\w-]*\s+ASSIGN\s+TO\s+([A-Z0-9][\w-]*)", flat):
        ddnames.add(m.group(1))
    return ddnames


def extract_program(path: Path, corpus: set) -> dict:
    text = load_text(path)
    pid = path.stem.upper()
    control = extract_control_targets(text, corpus)
    ddnames = extract_files(text)
    flat = re.sub(r"\s+", " ", text)
    db2 = set()
    for m in re.finditer(r"\b(?:FROM|INTO|UPDATE)\s+([A-Z][A-Z0-9_]*\.[A-Z][A-Z0-9_]*)", flat):
        db2.add(m.group(1))
    corpus_edges = sorted((control - {pid}) & corpus)
    return {
        "program_id": pid,
        "control_targets_all": sorted(control),
        "corpus_edges": corpus_edges,
        "ddnames": sorted(ddnames),
        "db2_tables": sorted(db2),
    }


def transitive_closure(edges: dict, corpus: set) -> dict:
    out = {}
    for p in corpus:
        seen, stack = set(), list(edges.get(p, []))
        while stack:
            n = stack.pop()
            if n in seen or n not in corpus:
                continue
            seen.add(n)
            stack.extend(edges.get(n, []))
        seen.discard(p)
        out[p] = sorted(seen)
    return out


def data_coupling(file_map: dict, db2_map: dict, corpus: set) -> dict:
    # program -> set(resources); coupled iff share >=1 resource
    res_to_progs = defaultdict(set)
    for p in corpus:
        for r in file_map.get(p, []):
            res_to_progs[("DD", r)].add(p)
        for t in db2_map.get(p, []):
            res_to_progs[("DB2", t)].add(p)
    coupled = defaultdict(set)
    for _, progs in res_to_progs.items():
        for p in progs:
            coupled[p] |= (progs - {p})
    return {p: sorted(coupled.get(p, set())) for p in corpus}


def main():
    here = Path(__file__).resolve().parent
    norm = here / "normalized"
    files = sorted(norm.glob("*.cob"))
    corpus = {f.stem.upper() for f in files}
    programs = {}
    file_map, db2_map, edge_map = {}, {}, {}
    for f in files:
        rec = extract_program(f, corpus)
        programs[rec["program_id"]] = rec
        file_map[rec["program_id"]] = rec["ddnames"]
        db2_map[rec["program_id"]] = rec["db2_tables"]
        edge_map[rec["program_id"]] = rec["corpus_edges"]
    out = {
        "programs": sorted(corpus),
        "direct_corpus_edges": edge_map,
        "transitive_call_closure": transitive_closure(edge_map, corpus),
        "data_coupling": data_coupling(file_map, db2_map, corpus),
        "file_map": file_map,
        "db2_map": db2_map,
    }
    (here / "oracle2.json").write_text(json.dumps(out, indent=1, sort_keys=True))
    print(f"wrote oracle2.json: {len(corpus)} programs")


if __name__ == "__main__":
    main()
