from score import prf, collision_precision

def test_prf_basic():
    p, r, f = prf({"A","B","X"}, {"A","B","C"})
    assert round(p,2)==0.67 and round(r,2)==0.67 and round(f,2)==0.67

def test_collision_precision_penalizes_ambiguous_names():
    simple_index={"Foo":["a.Foo","b.Foo"],"Bar":["a.Bar"]}
    truth={"a.Foo","a.Bar"}
    cp=collision_precision({"Foo","Bar"}, truth, simple_index)
    assert 0.0 < cp < 1.0
