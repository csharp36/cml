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
