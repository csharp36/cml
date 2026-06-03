"""
TDD tests for graph.py — written BEFORE graph.py exists (Red phase).
All asserts are over small synthetic fixtures with known expected sets.
"""
import pytest
from graph import (
    call_edges,
    transitive_call_closure,
    data_access_set,
    copybook_fan,
    shared_data_coupling,
)

# ---------------------------------------------------------------------------
# Shared synthetic fixture
# ---------------------------------------------------------------------------
#
# Graph shape (call edges):
#   A -> B, C
#   B -> C       (C reachable from A via two paths)
#   C -> A       (cycle back to A)
#   D -> (nothing)
#
# Data:
#   A reads FILE1, FILE2
#   B reads FILE2, FILE3; uses DB TABLE1
#   C reads FILE1
#   D reads FILE3
#
# Copybooks:
#   A copies CB1, CB2
#   B copies CB1
#   C copies CB2
#   D copies CB3

EDGES_MAP = {
    "A": {
        "static_calls": ["B", "C"],
        "resolved_dynamic_calls": [],
        "static_xctl_link": [],
        "resolved_dynamic_xctl_link": [],
        "copybooks": ["CB1", "CB2"],
        "files_read": ["FILE1", "FILE2"],
        "files_written": [],
        "db2_tables": [],
    },
    "B": {
        "static_calls": ["C"],
        "resolved_dynamic_calls": [],
        "static_xctl_link": [],
        "resolved_dynamic_xctl_link": [],
        "copybooks": ["CB1"],
        "files_read": ["FILE2", "FILE3"],
        "files_written": [],
        "db2_tables": ["TABLE1"],
    },
    "C": {
        "static_calls": [],
        "resolved_dynamic_calls": [],
        "static_xctl_link": ["A"],          # xctl back to A — cycle
        "resolved_dynamic_xctl_link": [],
        "copybooks": ["CB2"],
        "files_read": ["FILE1"],
        "files_written": [],
        "db2_tables": [],
    },
    "D": {
        "static_calls": [],
        "resolved_dynamic_calls": [],
        "static_xctl_link": [],
        "resolved_dynamic_xctl_link": [],
        "copybooks": ["CB3"],
        "files_read": ["FILE3"],
        "files_written": ["FILE4"],
        "db2_tables": [],
    },
}


# ---------------------------------------------------------------------------
# call_edges
# ---------------------------------------------------------------------------

class TestCallEdges:
    def test_static_calls_included(self):
        assert "B" in call_edges(EDGES_MAP["A"])
        assert "C" in call_edges(EDGES_MAP["A"])

    def test_xctl_included(self):
        assert "A" in call_edges(EDGES_MAP["C"])   # static_xctl_link

    def test_resolved_dynamic_included(self):
        prog = {
            "static_calls": [],
            "resolved_dynamic_calls": ["X"],
            "static_xctl_link": [],
            "resolved_dynamic_xctl_link": ["Y"],
            "copybooks": [],
            "files_read": [],
            "files_written": [],
            "db2_tables": [],
        }
        assert call_edges(prog) == {"X", "Y"}

    def test_empty_program(self):
        assert call_edges(EDGES_MAP["D"]) == set()

    def test_returns_set(self):
        result = call_edges(EDGES_MAP["A"])
        assert isinstance(result, set)

    def test_union_is_correct(self):
        # A has B, C from static_calls; nothing from others
        assert call_edges(EDGES_MAP["A"]) == {"B", "C"}


# ---------------------------------------------------------------------------
# transitive_call_closure
# ---------------------------------------------------------------------------

class TestTransitiveCallClosure:
    def test_basic_two_hop(self):
        # A -> B -> C; closure from A should be {B, C}
        # C -> A (cycle), but A is excluded (it's the start)
        closure = transitive_call_closure(EDGES_MAP, "A")
        assert closure == {"B", "C"}

    def test_cycle_safe(self):
        # Starting from B: B -> C -> A -> B (cycle); should not recurse forever
        closure = transitive_call_closure(EDGES_MAP, "B")
        assert "C" in closure
        assert "A" in closure
        assert "B" not in closure           # start excluded

    def test_start_excluded(self):
        closure = transitive_call_closure(EDGES_MAP, "A")
        assert "A" not in closure

    def test_isolated_node(self):
        closure = transitive_call_closure(EDGES_MAP, "D")
        assert closure == set()

    def test_returns_set(self):
        assert isinstance(transitive_call_closure(EDGES_MAP, "A"), set)

    def test_unknown_callee_not_in_corpus(self):
        # B calls "C" (in corpus) and "UNKNOWN" callee not in edges_map
        local_map = {
            "X": {
                "static_calls": ["EXTERNAL_LIB"],
                "resolved_dynamic_calls": [],
                "static_xctl_link": [],
                "resolved_dynamic_xctl_link": [],
                "copybooks": [],
                "files_read": [],
                "files_written": [],
                "db2_tables": [],
            }
        }
        # Should not raise, EXTERNAL_LIB just has no outgoing edges
        closure = transitive_call_closure(local_map, "X")
        assert "EXTERNAL_LIB" in closure   # it IS reachable, just not itself indexable


# ---------------------------------------------------------------------------
# data_access_set
# ---------------------------------------------------------------------------

class TestDataAccessSet:
    def test_file_read(self):
        # FILE1 is read by A and C
        result = data_access_set(EDGES_MAP, "FILE1")
        assert result == {"A", "C"}

    def test_file_write(self):
        # FILE4 is written by D only
        result = data_access_set(EDGES_MAP, "FILE4")
        assert result == {"D"}

    def test_db2_table(self):
        # TABLE1 is used by B
        result = data_access_set(EDGES_MAP, "TABLE1")
        assert result == {"B"}

    def test_shared_resource(self):
        # FILE3 is accessed by B (read) and D (read)
        result = data_access_set(EDGES_MAP, "FILE3")
        assert result == {"B", "D"}

    def test_unknown_resource(self):
        result = data_access_set(EDGES_MAP, "NONEXISTENT")
        assert result == set()

    def test_returns_set(self):
        assert isinstance(data_access_set(EDGES_MAP, "FILE1"), set)


# ---------------------------------------------------------------------------
# copybook_fan
# ---------------------------------------------------------------------------

class TestCopybookFan:
    def test_cb1_fan(self):
        fan = copybook_fan(EDGES_MAP)
        assert fan["CB1"] == {"A", "B"}

    def test_cb2_fan(self):
        fan = copybook_fan(EDGES_MAP)
        assert fan["CB2"] == {"A", "C"}

    def test_unique_copybook(self):
        fan = copybook_fan(EDGES_MAP)
        assert fan["CB3"] == {"D"}

    def test_all_copybooks_present(self):
        fan = copybook_fan(EDGES_MAP)
        assert set(fan.keys()) == {"CB1", "CB2", "CB3"}

    def test_returns_dict_of_sets(self):
        fan = copybook_fan(EDGES_MAP)
        assert isinstance(fan, dict)
        for v in fan.values():
            assert isinstance(v, set)

    def test_empty_copybooks_program_not_in_fan(self):
        local_map = {
            "Z": {
                "static_calls": [],
                "resolved_dynamic_calls": [],
                "static_xctl_link": [],
                "resolved_dynamic_xctl_link": [],
                "copybooks": [],
                "files_read": [],
                "files_written": [],
                "db2_tables": [],
            }
        }
        fan = copybook_fan(local_map)
        assert fan == {}


# ---------------------------------------------------------------------------
# shared_data_coupling
# ---------------------------------------------------------------------------

class TestSharedDataCoupling:
    def test_coupling_file(self):
        # FILE2 is accessed by A and B
        # set_a = {A}, set_b = {B} → shared resource = {FILE2}
        result = shared_data_coupling(EDGES_MAP, {"A"}, {"B"})
        assert "FILE2" in result

    def test_no_coupling(self):
        # D accesses FILE3 (read) and FILE4 (write); A does NOT
        # set_a = {A}, set_b = {D} → only FILE2 is shared? No:
        # A accesses FILE1, FILE2; D accesses FILE3, FILE4 → no overlap
        result = shared_data_coupling(EDGES_MAP, {"A"}, {"D"})
        assert result == set()

    def test_db2_coupling(self):
        # B accesses TABLE1; we need another program accessing TABLE1
        local_map = dict(EDGES_MAP)
        local_map["E"] = {
            "static_calls": [],
            "resolved_dynamic_calls": [],
            "static_xctl_link": [],
            "resolved_dynamic_xctl_link": [],
            "copybooks": [],
            "files_read": [],
            "files_written": [],
            "db2_tables": ["TABLE1"],
        }
        result = shared_data_coupling(local_map, {"B"}, {"E"})
        assert "TABLE1" in result

    def test_overlap_requires_both_sides(self):
        # FILE1 is used by A and C; but not by D
        # set_a = {A}, set_b = {D}: FILE1 not in result
        result = shared_data_coupling(EDGES_MAP, {"A"}, {"D"})
        assert "FILE1" not in result

    def test_returns_set(self):
        assert isinstance(shared_data_coupling(EDGES_MAP, {"A"}, {"B"}), set)

    def test_multi_program_sets(self):
        # set_a = {A, D}, set_b = {B, C}
        # Resources in A or D: FILE1, FILE2, FILE3, FILE4
        # Resources in B or C: FILE2, FILE3, TABLE1, FILE1
        # Intersection: FILE1, FILE2, FILE3
        result = shared_data_coupling(EDGES_MAP, {"A", "D"}, {"B", "C"})
        assert result == {"FILE1", "FILE2", "FILE3"}
