import pathlib
from run_recon import aggregate_facts, gate_metrics, gate_recommendation

FIX = pathlib.Path(__file__).parent / "fixtures"

def test_aggregate_and_metrics_over_fixture_dir():
    facts = aggregate_facts(FIX)               # parses progA.cbl + chain.cbl
    m = gate_metrics(facts)
    # progA: 1 dynamic call + 1 dynamic xctl = 2 dynamic; static edges: PROGB,PROGD,CHAINC...
    assert m["dynamic_edges"] == 2
    assert m["total_edges"] >= 3
    assert 0.0 < m["dynamic_share"] <= 1.0
    assert m["max_chain_depth"] >= 1

def test_gate_recommendation_proceeds_on_dynamic_dispatch():
    rec = gate_recommendation({"dynamic_share": 0.40, "max_chain_depth": 2,
                               "resources_at_or_above_threshold": 0})
    assert rec["decision"] == "PROCEED"

def test_gate_recommendation_stops_when_flat_and_static():
    rec = gate_recommendation({"dynamic_share": 0.02, "max_chain_depth": 2,
                               "resources_at_or_above_threshold": 1})
    assert rec["decision"] == "STOP"
