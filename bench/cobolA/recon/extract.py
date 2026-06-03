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
