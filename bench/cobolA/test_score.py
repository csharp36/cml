from score import prf, truth_for, verdict

def test_prf_basic():
    p, r, f = prf({"A", "B", "X"}, {"A", "B", "C"})
    assert round(p, 2) == 0.67 and round(r, 2) == 0.67 and round(f, 2) == 0.67

def test_prf_empty_found_is_zero():
    assert prf(set(), {"A"}) == (0.0, 0.0, 0.0)

def test_truth_prefers_audited_key_over_oracle_answer():
    q = {"id": "call_closure__X", "key_source": "audited", "answer_simple": ["WRONG"]}
    hard = {"call_closure__X": ["A", "B"]}
    assert truth_for(q, hard) == {"A", "B"}

def test_truth_uses_oracle_answer_for_oracle_keyed():
    q = {"id": "data_access__F", "key_source": "oracle", "answer_simple": ["A", "B"]}
    assert truth_for(q, {}) == {"A", "B"}

def test_verdict_greenlight():
    assert verdict(proxy_f1=0.95, grep_f1=0.30)["decision"] == "GREENLIGHT_B"

def test_verdict_not_a_fit_small_gap():
    assert verdict(proxy_f1=0.55, grep_f1=0.50)["decision"] == "NOT_A_FIT"

def test_verdict_ambiguous():
    assert verdict(proxy_f1=0.70, grep_f1=0.50)["decision"] == "AMBIGUOUS"
