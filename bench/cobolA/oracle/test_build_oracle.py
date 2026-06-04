"""
TDD tests for build_oracle.py — written BEFORE build_oracle.py exists (Red phase).
Uses a small 3-program fixture with a 2-hop transitive closure.
"""
import json
import pytest
from pathlib import Path
import tempfile

from build_oracle import load_edges, build_oracle


# ---------------------------------------------------------------------------
# Fixture: 3-program corpus
#
# Call graph (static_calls only):
#   PROG_A -> PROG_B -> PROG_C
#   (2-hop: A's closure = {PROG_B, PROG_C})
#
# Data:
#   PROG_A: reads FILE1, writes FILE2
#   PROG_B: reads FILE2, db2 TBLX
#   PROG_C: reads FILE1, reads FILE3
#
# Copybooks:
#   PROG_A: CB1, CB2
#   PROG_B: CB1
#   PROG_C: CB3
# ---------------------------------------------------------------------------

FIXTURE = [
    {
        "program_id": "PROG_A",
        "static_calls": ["PROG_B"],
        "dynamic_call_idents": [],
        "resolved_dynamic_calls": [],
        "static_xctl_link": [],
        "dynamic_xctl_idents": [],
        "resolved_dynamic_xctl_link": [],
        "unresolved_dynamic_count": 0,
        "copybooks": ["CB1", "CB2"],
        "files_read": ["FILE1"],
        "files_written": ["FILE2"],
        "db2_tables": [],
        "cics_txn_entry": [],
    },
    {
        "program_id": "PROG_B",
        "static_calls": ["PROG_C"],
        "dynamic_call_idents": [],
        "resolved_dynamic_calls": [],
        "static_xctl_link": [],
        "dynamic_xctl_idents": [],
        "resolved_dynamic_xctl_link": [],
        "unresolved_dynamic_count": 0,
        "copybooks": ["CB1"],
        "files_read": ["FILE2"],
        "files_written": [],
        "db2_tables": ["TBLX"],
        "cics_txn_entry": [],
    },
    {
        "program_id": "PROG_C",
        "static_calls": [],
        "dynamic_call_idents": [],
        "resolved_dynamic_calls": [],
        "static_xctl_link": [],
        "dynamic_xctl_idents": [],
        "resolved_dynamic_xctl_link": [],
        "unresolved_dynamic_count": 0,
        "copybooks": ["CB3"],
        "files_read": ["FILE1", "FILE3"],
        "files_written": [],
        "db2_tables": [],
        "cics_txn_entry": [],
    },
]


# ---------------------------------------------------------------------------
# load_edges
# ---------------------------------------------------------------------------

class TestLoadEdges:
    def test_returns_dict_keyed_by_program_id(self, tmp_path):
        p = tmp_path / "raw-edges.json"
        p.write_text(json.dumps(FIXTURE))
        result = load_edges(str(p))
        assert isinstance(result, dict)
        assert set(result.keys()) == {"PROG_A", "PROG_B", "PROG_C"}

    def test_values_have_static_calls(self, tmp_path):
        p = tmp_path / "raw-edges.json"
        p.write_text(json.dumps(FIXTURE))
        result = load_edges(str(p))
        assert result["PROG_A"]["static_calls"] == ["PROG_B"]

    def test_empty_array(self, tmp_path):
        p = tmp_path / "raw-edges.json"
        p.write_text("[]")
        result = load_edges(str(p))
        assert result == {}


# ---------------------------------------------------------------------------
# build_oracle
# ---------------------------------------------------------------------------

@pytest.fixture
def edges_map():
    return {item["program_id"]: item for item in FIXTURE}


class TestBuildOracle:
    def test_programs_sorted(self, edges_map):
        o = build_oracle(edges_map)
        assert o["programs"] == sorted(["PROG_A", "PROG_B", "PROG_C"])

    def test_resources_sorted_unique(self, edges_map):
        o = build_oracle(edges_map)
        # FILE1 (A read, C read), FILE2 (A write, B read), FILE3 (C read), TBLX (B db2)
        assert o["resources"] == sorted(["FILE1", "FILE2", "FILE3", "TBLX"])

    def test_copybooks_sorted_unique(self, edges_map):
        o = build_oracle(edges_map)
        assert o["copybooks"] == sorted(["CB1", "CB2", "CB3"])

    def test_transitive_closure_prog_a_two_hop(self, edges_map):
        o = build_oracle(edges_map)
        # A -> B -> C  (2-hop closure from A = {PROG_B, PROG_C})
        assert o["transitive_call_closure"]["PROG_A"] == ["PROG_B", "PROG_C"]

    def test_transitive_closure_prog_b_one_hop(self, edges_map):
        o = build_oracle(edges_map)
        assert o["transitive_call_closure"]["PROG_B"] == ["PROG_C"]

    def test_transitive_closure_prog_c_empty(self, edges_map):
        o = build_oracle(edges_map)
        assert o["transitive_call_closure"]["PROG_C"] == []

    def test_data_access_per_resource(self, edges_map):
        o = build_oracle(edges_map)
        # FILE1 accessed by PROG_A (read) and PROG_C (read)
        assert o["data_access"]["FILE1"] == sorted(["PROG_A", "PROG_C"])
        # FILE2 accessed by PROG_A (write) and PROG_B (read)
        assert o["data_access"]["FILE2"] == sorted(["PROG_A", "PROG_B"])
        # TBLX only by PROG_B
        assert o["data_access"]["TBLX"] == ["PROG_B"]

    def test_copybook_fan_field(self, edges_map):
        o = build_oracle(edges_map)
        # CB1 copied by PROG_A and PROG_B
        assert o["copybook_fan"]["CB1"] == sorted(["PROG_A", "PROG_B"])
        assert o["copybook_fan"]["CB2"] == ["PROG_A"]
        assert o["copybook_fan"]["CB3"] == ["PROG_C"]

    def test_direct_call_edges_field(self, edges_map):
        o = build_oracle(edges_map)
        assert o["direct_call_edges"]["PROG_A"] == ["PROG_B"]
        assert o["direct_call_edges"]["PROG_B"] == ["PROG_C"]
        assert o["direct_call_edges"]["PROG_C"] == []

    def test_all_top_level_keys_present(self, edges_map):
        o = build_oracle(edges_map)
        expected_keys = {
            "programs",
            "resources",
            "copybooks",
            "transitive_call_closure",
            "data_access",
            "copybook_fan",
            "data_coupling",
            "cics_txn_entry",
            "txn_reach",
            "direct_call_edges",
        }
        assert set(o.keys()) == expected_keys

    def test_output_is_deterministic_sorted_lists(self, edges_map):
        o1 = build_oracle(edges_map)
        o2 = build_oracle(edges_map)
        assert json.dumps(o1, sort_keys=True) == json.dumps(o2, sort_keys=True)


def test_ddname_keying_decouples_same_logical_different_ddname():
    """ddnames_by_program kwarg: same logical name, different ASSIGN ddname -> NOT coupled.

    PROG_X: SELECT FILE-A ASSIGN TO DDNAME_X  (ddname = DDNAME_X)
    PROG_Y: SELECT FILE-A ASSIGN TO DDNAME_Y  (ddname = DDNAME_Y)

    Under logical-name keying (ddnames_by_program=None) both programs share
    'FILE-A' and would be coupled.  Under ddname keying they share no physical
    resource and must NOT be coupled.

    Also asserts the reverse: PROG_Y and PROG_Z share the same ddname DDNAME_Y
    and MUST be coupled when ddname keying is in effect.
    """
    edges_map = {
        "PROG_X": {
            "static_calls": [], "resolved_dynamic_calls": [],
            "static_xctl_link": [], "resolved_dynamic_xctl_link": [],
            "copybooks": [],
            "files_read": ["FILE-A"],   # logical name
            "files_written": [],
            "db2_tables": [],
        },
        "PROG_Y": {
            "static_calls": [], "resolved_dynamic_calls": [],
            "static_xctl_link": [], "resolved_dynamic_xctl_link": [],
            "copybooks": [],
            "files_read": ["FILE-A"],   # same logical name as PROG_X
            "files_written": [],
            "db2_tables": [],
        },
        "PROG_Z": {
            "static_calls": [], "resolved_dynamic_calls": [],
            "static_xctl_link": [], "resolved_dynamic_xctl_link": [],
            "copybooks": [],
            "files_read": ["FILE-B"],   # different logical name
            "files_written": [],
            "db2_tables": [],
        },
    }
    # Physical ddnames: X -> DDNAME_X, Y and Z -> DDNAME_Y (shared)
    dbn = {
        "PROG_X": {"DDNAME_X"},
        "PROG_Y": {"DDNAME_Y"},
        "PROG_Z": {"DDNAME_Y"},
    }

    # --- Without ddname keying (old behaviour) ---
    oracle_logical = build_oracle(edges_map)
    # PROG_X and PROG_Y both read FILE-A → coupled under logical keying
    assert "PROG_Y" in oracle_logical["data_coupling"]["PROG_X"], \
        "logical keying should couple PROG_X/PROG_Y (sanity check)"

    # --- With ddname keying (new behaviour) ---
    oracle_ddname = build_oracle(edges_map, ddnames_by_program=dbn)
    dc = oracle_ddname["data_coupling"]

    # PROG_X has DDNAME_X; PROG_Y has DDNAME_Y — no shared physical resource
    assert "PROG_Y" not in dc["PROG_X"], \
        "ddname keying must NOT couple PROG_X and PROG_Y (different physical files)"
    assert "PROG_X" not in dc["PROG_Y"], \
        "ddname keying must NOT couple PROG_Y and PROG_X"

    # PROG_Y and PROG_Z both use DDNAME_Y — they MUST be coupled
    assert "PROG_Z" in dc["PROG_Y"], \
        "ddname keying must couple PROG_Y and PROG_Z (same ddname DDNAME_Y)"
    assert "PROG_Y" in dc["PROG_Z"], \
        "ddname keying must couple PROG_Z and PROG_Y"

    # Resources under ddname keying are ddnames, not logical names
    assert "DDNAME_X" in oracle_ddname["resources"]
    assert "DDNAME_Y" in oracle_ddname["resources"]
    assert "FILE-A" not in oracle_ddname["resources"]
    assert "FILE-B" not in oracle_ddname["resources"]


def test_build_oracle_adds_txn_and_coupling_strata():
    edges_map = {
        "COMEN01C": {"static_calls": [], "resolved_dynamic_calls": [],
                     "static_xctl_link": [], "resolved_dynamic_xctl_link": ["COBIL00C"],
                     "copybooks": [], "files_read": [], "files_written": [], "db2_tables": []},
        "COBIL00C": {"static_calls": [], "resolved_dynamic_calls": [],
                     "static_xctl_link": [], "resolved_dynamic_xctl_link": [],
                     "copybooks": [], "files_read": ["BILLFILE"], "files_written": [],
                     "db2_tables": []},
        "CBTRN02C": {"static_calls": [], "resolved_dynamic_calls": [],
                     "static_xctl_link": [], "resolved_dynamic_xctl_link": [],
                     "copybooks": [], "files_read": ["BILLFILE"], "files_written": [],
                     "db2_tables": []},
    }
    txn_entry = {"CM00": "COMEN01C", "CX99": "NOTINCORPUS"}
    oracle = build_oracle(edges_map, txn_entry)

    assert oracle["cics_txn_entry"] == {"CM00": "COMEN01C"}
    assert oracle["txn_reach"]["CM00"] == ["COBIL00C", "COMEN01C"]
    assert oracle["data_coupling"]["COBIL00C"] == ["CBTRN02C"]
    assert oracle["data_coupling"]["CBTRN02C"] == ["COBIL00C"]
    assert oracle["data_coupling"]["COMEN01C"] == []
