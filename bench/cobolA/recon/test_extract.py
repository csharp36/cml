from recon.extract import normalize_line

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
