"""
TDD tests for selects.py — written BEFORE selects.py exists (Red phase).

Sample covers:
  - Two SELECT/ASSIGN entries with varying whitespace
  - A comment line (col 7 = '*') that should be skipped
  - ASSIGN TO with multiple spaces before the ddname
"""
import pytest

from selects import parse_selects_text, parse_program_ddnames


# ---------------------------------------------------------------------------
# Sample fixed-format COBOL text with two SELECTs and a comment line.
# Column layout: cols 1-6 = sequence/blank, col 7 = indicator, cols 8-72 = text.
# Col indices (0-based): indicator at index 6.
#
# Using the exact column spacing found in the CardDemo corpus:
#   "      *" → comment (indicator = '*' at col index 6)
#   "           SELECT ..." → normal code
# ---------------------------------------------------------------------------

SAMPLE_TEXT = """\
      * This is a comment line and must be skipped
           SELECT TRANSACT-FILE ASSIGN TO TRANSACT
                  ORGANIZATION IS SEQUENTIAL
      * Another comment line
           SELECT XREF-FILE ASSIGN TO   XREFFILE
                  ORGANIZATION IS INDEXED
"""

# A sample with PROGRAM-ID for testing ddnames_by_program (handled separately
# via integration; parse_selects_text and parse_program_ddnames are unit-tested
# against SAMPLE_TEXT directly).


class TestParseSelectsText:
    def test_returns_dict(self):
        result = parse_selects_text(SAMPLE_TEXT)
        assert isinstance(result, dict)

    def test_maps_logical_to_ddname_simple(self):
        result = parse_selects_text(SAMPLE_TEXT)
        # TRANSACT-FILE -> TRANSACT
        assert result.get("TRANSACT-FILE") == "TRANSACT"

    def test_maps_logical_to_ddname_extra_spaces(self):
        result = parse_selects_text(SAMPLE_TEXT)
        # XREF-FILE -> XREFFILE  (note: "ASSIGN TO   XREFFILE" with extra spaces)
        assert result.get("XREF-FILE") == "XREFFILE"

    def test_exactly_two_entries(self):
        result = parse_selects_text(SAMPLE_TEXT)
        assert len(result) == 2

    def test_comment_line_not_parsed_as_select(self):
        result = parse_selects_text(SAMPLE_TEXT)
        # Comment lines should contribute nothing
        for key in result:
            assert "COMMENT" not in key.upper()

    def test_uppercase_keys_and_values(self):
        # Even if source were lowercase, output must be uppercase
        lower_text = SAMPLE_TEXT.lower()
        result = parse_selects_text(lower_text)
        for k, v in result.items():
            assert k == k.upper()
            assert v == v.upper()

    def test_empty_text(self):
        assert parse_selects_text("") == {}

    def test_only_comments(self):
        only_comments = """\
      * SELECT FAKE-FILE ASSIGN TO FAKEDDNM
      * SELECT OTHER-FILE ASSIGN TO OTHERDDN
"""
        assert parse_selects_text(only_comments) == {}


class TestParseProgramDdnames:
    def test_returns_set(self):
        result = parse_program_ddnames(SAMPLE_TEXT)
        assert isinstance(result, set)

    def test_contains_ddnames_not_logical_names(self):
        result = parse_program_ddnames(SAMPLE_TEXT)
        # Should contain ddnames (ASSIGN targets), NOT logical names
        assert "TRANSACT" in result
        assert "XREFFILE" in result
        # Logical names must NOT appear
        assert "TRANSACT-FILE" not in result
        assert "XREF-FILE" not in result

    def test_set_size_matches_select_count(self):
        result = parse_program_ddnames(SAMPLE_TEXT)
        assert len(result) == 2

    def test_is_values_of_parse_selects_text(self):
        selects = parse_selects_text(SAMPLE_TEXT)
        ddnames = parse_program_ddnames(SAMPLE_TEXT)
        assert ddnames == set(selects.values())
