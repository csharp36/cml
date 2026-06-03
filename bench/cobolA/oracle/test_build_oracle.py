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
            "direct_call_edges",
        }
        assert set(o.keys()) == expected_keys

    def test_output_is_deterministic_sorted_lists(self, edges_map):
        o1 = build_oracle(edges_map)
        o2 = build_oracle(edges_map)
        assert json.dumps(o1, sort_keys=True) == json.dumps(o2, sort_keys=True)
