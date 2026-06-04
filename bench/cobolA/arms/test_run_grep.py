import pathlib
from run_grep import answer_question

FIX = str(pathlib.Path(__file__).parent / "fixtures")

def test_call_closure_follows_literal_call_and_xctl():
    q = {"id": "call_closure__DRIVER", "stratum": "call_closure", "node": "DRIVER"}
    assert answer_question(q, FIX) == {"WORKER", "SCREEN"}

def test_data_access_finds_file_accessors():
    q = {"id": "data_access__ACCTFILE", "stratum": "data_access", "node": "ACCTFILE"}
    assert answer_question(q, FIX) == {"DRIVER", "WORKER"}

def test_copybook_fan_finds_includers():
    q = {"id": "copybook_fan__SHARED", "stratum": "copybook_fan", "node": "SHARED"}
    assert answer_question(q, FIX) == {"WORKER", "SCREEN"}

def test_data_coupling_finds_costakeholders_of_a_file():
    q = {"id": "data_coupling__DRIVER", "stratum": "data_coupling", "node": "DRIVER"}
    assert answer_question(q, FIX) == {"WORKER"}

def test_txn_reach_resolves_entry_then_closes():
    q = {"id": "txn_reach__DR00", "stratum": "txn_reach", "node": "DR00"}
    assert answer_question(q, FIX) == {"DRIVER", "WORKER", "SCREEN"}
