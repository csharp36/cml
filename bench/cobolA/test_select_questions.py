from select_questions import build_questions

ORACLE = {
    "programs": ["A", "B", "C"],
    "transitive_call_closure": {"A": ["B", "C", "ZZVENDOR"], "B": ["C"], "C": []},
    "data_access": {"FILE1": ["A", "B"], "FILE2": ["A"]},
    "copybook_fan": {"CPY1": ["A", "B", "C"], "CPY2": ["A"]},
    "data_coupling": {"A": ["B"], "B": ["A"], "C": []},
    "cics_txn_entry": {"TX01": "A"},
    "txn_reach": {"TX01": ["A", "B", "C"]},
}

def test_build_questions_covers_five_strata_and_filters_universe():
    qs = build_questions(ORACLE, caps={"copybook_fan": 15})
    by = {}
    for q in qs:
        by.setdefault(q["stratum"], []).append(q)
    assert set(by) == {"call_closure", "data_access", "data_coupling", "copybook_fan", "txn_reach"}

    a = next(q for q in by["call_closure"] if q["node"] == "A")
    assert a["answer_simple"] == ["B", "C"]      # ZZVENDOR filtered out (not in programs)
    assert a["key_source"] == "audited"
    assert all(q["node"] != "B" for q in by["call_closure"])  # B closure = {C}, only 1 -> excluded

    assert {q["node"] for q in by["data_access"]} == {"FILE1"}
    assert by["data_access"][0]["key_source"] == "oracle"

    assert {q["node"] for q in by["copybook_fan"]} == {"CPY1"}

    assert by["txn_reach"][0]["answer_simple"] == ["A", "B", "C"]
    assert by["txn_reach"][0]["key_source"] == "audited"

    for q in qs:
        assert q["id"] and q["question"] and q["kind"] == q["stratum"]
