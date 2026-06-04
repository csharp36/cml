"""Parse COBOL SELECT/ASSIGN entries to recover the physical ddname for each
logical file name declared in a program's FILE-CONTROL paragraph.

Fixed-format COBOL layout (standard 80-col):
  cols 1–6  : sequence number (ignored)
  col  7    : indicator area — '*' or '/' means comment line; skip it
  cols 8–72 : program text (area A + area B)

Two programs may declare the same logical name (e.g. TRANSACT-FILE) but ASSIGN
it to *different* ddnames (TRANSACT vs TRANFILE). Keying file coupling on the
logical name produces false couplings; this module extracts the physical ddname
so callers can key on that instead.

Public API
----------
parse_selects_text(text)   -> dict[str, str]   {LOGICAL_NAME: DDNAME}
parse_program_ddnames(text) -> set[str]         set of ASSIGN ddnames
ddnames_by_program(corpus_dir) -> dict[str, set[str]]   {PROGRAM_ID: ddnames}
"""
from __future__ import annotations

import pathlib
import re

# ---------------------------------------------------------------------------
# Regexes
# ---------------------------------------------------------------------------

# Matches: SELECT <logical-name>
_SELECT_RE = re.compile(
    r"\bSELECT\s+([A-Z0-9][A-Z0-9-]*)",
    re.IGNORECASE,
)

# Matches: ASSIGN TO <ddname>  (one or more spaces before ddname)
_ASSIGN_RE = re.compile(
    r"\bASSIGN\s+TO\s+([A-Z0-9][A-Z0-9-]*)",
    re.IGNORECASE,
)

# PROGRAM-ID paragraph value — matches "PROGRAM-ID. NAME." on the same line,
# or "PROGRAM-ID." followed by the name on the next line (IBM format with
# 6-digit sequence numbers).  We search on the stripped-content view.
_PROGRAM_ID_RE = re.compile(
    r"PROGRAM-ID[.\s]+([A-Z][A-Z0-9-]*)",
    re.IGNORECASE | re.DOTALL,
)


def _non_comment_lines(text: str):
    """Yield lines that are not fixed-format COBOL comment lines.

    A line is a comment when the indicator area (col 7, 0-indexed col 6) is
    '*' or '/'.  Lines shorter than 7 characters cannot have an indicator area
    and are yielded as-is (they contain no meaningful text).
    """
    for line in text.splitlines():
        if len(line) >= 7 and line[6] in ("*", "/"):
            continue
        yield line


def parse_selects_text(text: str) -> dict[str, str]:
    """Return {LOGICAL_NAME: DDNAME} for every SELECT/ASSIGN pair in *text*.

    Fixed-format aware: skips col-7 comment lines ('*' or '/').
    Handles multi-space variants like ``ASSIGN TO   XREFFILE``.
    Names are uppercased.
    """
    # Join non-comment lines into a single string for multi-line token scanning.
    # SELECT and ASSIGN appear on separate physical lines in practice, but the
    # regex scan is token-order based: we collect (SELECT tokens, ASSIGN tokens)
    # in document order and pair them up sequentially — each SELECT is followed
    # by exactly one ASSIGN before the next SELECT.
    joined = " ".join(_non_comment_lines(text))

    result: dict[str, str] = {}

    # Scan for alternating SELECT / ASSIGN tokens in document order.
    # We use finditer on the joined text; SELECT always precedes its ASSIGN.
    tokens = list(re.finditer(
        r"\b(SELECT)\s+([A-Z0-9][A-Z0-9-]*)\b|\bASSIGN\s+TO\s+([A-Z0-9][A-Z0-9-]*)",
        joined,
        re.IGNORECASE,
    ))

    pending_logical: str | None = None
    for m in tokens:
        if m.group(1):  # matched the SELECT branch
            pending_logical = m.group(2).upper()
        elif pending_logical is not None:  # matched the ASSIGN branch
            ddname = m.group(3).upper()
            result[pending_logical] = ddname
            pending_logical = None  # consumed; wait for next SELECT

    return result


def parse_program_ddnames(text: str) -> set[str]:
    """Return the set of physical ddnames (ASSIGN targets) declared in *text*.

    Equivalent to ``set(parse_selects_text(text).values())``.
    """
    return set(parse_selects_text(text).values())


def _extract_content(text: str) -> str:
    """Extract the program-text area from fixed-format COBOL lines.

    Handles two common variants:
    - Standard 72-col (cols 1-6 blank/sequence, col 7 indicator, cols 8-72 text):
        strip first 6 chars; content is the rest up to col 72.
    - IBM numbered (6-digit sequence prefix + 8-digit suffix):
        same col layout but lines are longer; strip leading 6 and trailing 8.

    Comment lines (col-7 indicator '*' or '/') are dropped. Lines shorter than
    7 characters are emitted as-is.
    """
    content_lines: list[str] = []
    for line in text.splitlines():
        if len(line) >= 7:
            if line[6] in ("*", "/"):
                continue
            # Keep cols 7-72 (0-indexed 6-71); strip trailing sequence if present.
            content = line[6:72].rstrip()
        else:
            content = line
        content_lines.append(content)
    return "\n".join(content_lines)


def ddnames_by_program(corpus_dir: str | pathlib.Path) -> dict[str, set[str]]:
    """Walk *.cbl files under *corpus_dir* and return {PROGRAM_ID: set(ddnames)}.

    PROGRAM-ID is extracted via regex on the content-area view of each file
    (stripping sequence numbers and trailing suffixes); files whose PROGRAM-ID
    cannot be determined are skipped.  Result is deterministic (sorted by key).
    """
    out: dict[str, set[str]] = {}
    for path in sorted(pathlib.Path(corpus_dir).rglob("*")):
        if path.suffix.lower() != ".cbl" or not path.is_file():
            continue
        text = path.read_text(errors="replace")
        content = _extract_content(text)
        m = _PROGRAM_ID_RE.search(content)
        if not m:
            continue
        program_id = m.group(1).upper()
        out[program_id] = parse_program_ddnames(text)
    return dict(sorted(out.items()))
