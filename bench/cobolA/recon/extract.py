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


_PROGRAM_ID = re.compile(r"\bPROGRAM-ID\.\s+([A-Z0-9][A-Z0-9-]*)", re.I)
_CALL_LIT = re.compile(r"\bCALL\s+['\"]([A-Z0-9][A-Z0-9-]*)['\"]", re.I)
_CALL_VAR = re.compile(r"(?<![A-Za-z0-9-])CALL\s+(?!['\"])([A-Z0-9][A-Z0-9-]*)", re.I)
_STRLIT = re.compile(r"'[^']*'|\"[^\"]*\"")
_XCTL_LINK = re.compile(
    r"\bEXEC\s+CICS\s+(?:XCTL|LINK)\b[^.]*?\bPROGRAM\s*\(\s*(['\"]?)([A-Z0-9][A-Z0-9-]*)\1?",
    re.I | re.S,
)
_COPY = re.compile(r"\bCOPY\s+([A-Z0-9][A-Z0-9-]*)", re.I)
# NOTE (Phase 0 crude scope): captures only bare COBOL file verbs. CICS file I/O
# (EXEC CICS READ/WRITE FILE(...)) is NOT captured, so file_ops and the coupling
# metrics derived from it are reliable for batch programs but under-count the CICS
# (online) programs. The PHASE0-recon report flags this; full coverage is the gated
# Phase 1 (ProLeap) job. The (?<![A-Za-z0-9-]) lookbehind stops `READ` from matching
# the tail of hyphenated identifiers like END-READ / WS-...-READ.
_FILE_OP = re.compile(
    r"(?<![A-Za-z0-9-])(?:READ|WRITE|REWRITE|DELETE|START)\s+([A-Z0-9][A-Z0-9-]*)", re.I)
_SQL = re.compile(r"\bEXEC\s+SQL\b", re.I)
_CICS = re.compile(r"\bEXEC\s+CICS\b", re.I)


def _code_text(source_text: str, strip_literals: bool = False) -> str:
    """Join the normalized (comment-stripped) code area of every line.

    strip_literals=True additionally blanks the CONTENTS of string literals
    (keeping the quotes as ''), so passes that must not read literal text
    (dynamic CALL, file verbs) don't match words like CALL/START inside a
    DISPLAY string. The non-stripped text is still used for _CALL_LIT / _XCTL
    / _COPY, which legitimately need the quoted operand.
    """
    out = []
    for raw in source_text.splitlines():
        norm = normalize_line(raw)
        if norm is not None:
            out.append(norm)
    code = "\n".join(out)
    if strip_literals:
        code = _STRLIT.sub("''", code)
    return code


def extract_program_facts(source_text: str) -> dict:
    code = _code_text(source_text)
    code_nostr = _code_text(source_text, strip_literals=True)
    pid = _PROGRAM_ID.search(code)
    static_xctl = {m.group(2).upper() for m in _XCTL_LINK.finditer(code) if m.group(1)}
    dynamic_xctl = sum(1 for m in _XCTL_LINK.finditer(code) if not m.group(1))
    return {
        "program_id": pid.group(1).upper() if pid else "",
        "static_calls": {m.group(1).upper() for m in _CALL_LIT.finditer(code)},
        "dynamic_call_count": len(_CALL_VAR.findall(code_nostr)),
        "static_xctl_link": static_xctl,
        "dynamic_xctl_link_count": dynamic_xctl,
        "copybooks": {m.group(1).upper() for m in _COPY.finditer(code)},
        "file_ops": {m.group(1).upper() for m in _FILE_OP.finditer(code_nostr)},
        "uses_sql": bool(_SQL.search(code)),
        "uses_cics": bool(_CICS.search(code)),
    }
