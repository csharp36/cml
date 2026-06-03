from recon.graph import build_call_graph, max_chain_depth, shared_data_fan

def test_build_call_graph_unions_call_and_xctl_edges():
    facts = {
        "A": {"static_calls": {"B"}, "static_xctl_link": {"C"}},
        "B": {"static_calls": {"C"}, "static_xctl_link": set()},
        "C": {"static_calls": set(), "static_xctl_link": set()},
    }
    g = build_call_graph(facts)
    assert g["A"] == {"B", "C"}
    assert g["B"] == {"C"}
    assert g["C"] == set()

def test_max_chain_depth_counts_nodes_in_longest_path():
    g = {"A": {"B"}, "B": {"C"}, "C": set()}     # A->B->C
    assert max_chain_depth(g) == 3

def test_max_chain_depth_tolerates_cycles():
    g = {"A": {"B"}, "B": {"A"}}                  # must not infinite-loop
    assert max_chain_depth(g) == 2

def test_shared_data_fan_counts_resources_touched_by_threshold():
    facts = {
        "A": {"copybooks": {"REC1"}, "file_ops": {"FA"}},
        "B": {"copybooks": {"REC1"}, "file_ops": {"FA"}},
        "C": {"copybooks": {"REC1"}, "file_ops": set()},
        "D": {"copybooks": set(), "file_ops": {"FA"}},
    }
    fan = shared_data_fan(facts, threshold=3)
    # REC1 touched by A,B,C (>=3) -> counted; FA touched by A,B,D (>=3) -> counted
    assert fan["resources_at_or_above_threshold"] == 2
    assert fan["copybook_to_programs"]["REC1"] == {"A", "B", "C"}
