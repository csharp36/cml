import pathlib
from recon.extract import normalize_line
from recon.extract import extract_program_facts

FIX = pathlib.Path(__file__).parent / "fixtures"

def test_strips_sequence_area_and_trailing_columns():
    # base statement padded to col 72, then junk in cols 73+ which must be dropped
    raw = "001000     CALL 'PROGB'.".ljust(72) + "X" * 20
    assert normalize_line(raw) == "    CALL 'PROGB'."

def test_comment_line_returns_none():
    assert normalize_line("001000* this is a comment") is None
    assert normalize_line("001000/ page eject") is None

def test_short_or_blank_line_returns_empty():
    assert normalize_line("") == ""
    assert normalize_line("000100") == ""

def test_extract_program_facts_counts():
    facts = extract_program_facts((FIX / "progA.cbl").read_text())
    assert facts["program_id"] == "PROGA"
    assert facts["static_calls"] == {"PROGB"}        # CALL 'PROGB'
    assert facts["dynamic_call_count"] == 1          # CALL WS-PGM
    assert facts["static_xctl_link"] == {"PROGD"}    # XCTL PROGRAM('PROGD')
    assert facts["dynamic_xctl_link_count"] == 1     # XCTL PROGRAM(WS-NEXT)
    assert facts["copybooks"] == {"CUSTREC"}
    assert facts["file_ops"] == {"ACCTFILE"}
    assert facts["uses_sql"] is True
    assert facts["uses_cics"] is True
    assert "NOPE" not in facts["static_calls"]       # commented CALL must not leak

def test_file_ops_ignores_hyphenated_read_write_verbs():
    # `END-READ` and `...-READ` identifiers must NOT leak the next token into file_ops.
    src = "\n".join([
        "000100     READ ACCTFILE.",
        "000200     END-READ",
        "000300     EXIT.",
        "000400     MOVE WS-TOTAL-RECORDS-READ TO WS-X.",
        "000500     PERFORM SOME-PARA.",
    ])
    facts = extract_program_facts(src)
    assert facts["file_ops"] == {"ACCTFILE"}
