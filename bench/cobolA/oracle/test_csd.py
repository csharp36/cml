"""
TDD tests for csd.py — written BEFORE csd.py exists (Red phase).
Test parsing CICS CSD resource definitions to recover transaction -> entry program.
"""
from csd import parse_csd_text

SAMPLE = """\
 DEFINE TRANSACTION(CM00) GROUP(CARDDEMO)
        PROGRAM(COMEN01C) TWASIZE(0) STATUS(ENABLED)
 DEFINE TRANSACTION(CAUP) GROUP(CARDDEMO)
 DESCRIPTION(CREDIT CARD DEMO ACCOUNT UPDATE)
        PROGRAM(COACTUPC) TWASIZE(0) STATUS(ENABLED)
 DEFINE PROGRAM(COMEN01C) GROUP(CARDDEMO)
"""

def test_parses_transaction_to_program_across_continuation_lines():
    m = parse_csd_text(SAMPLE)
    assert m == {"CM00": "COMEN01C", "CAUP": "COACTUPC"}

def test_ignores_standalone_program_define():
    # the trailing "DEFINE PROGRAM(...)" has no TRANSACTION and must not appear as a key
    m = parse_csd_text(SAMPLE)
    assert "COMEN01C" not in m  # it is a value, never a transaction key
