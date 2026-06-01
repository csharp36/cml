import grade

def test_prf_perfect():
    assert grade.prf({"a", "b"}, {"a", "b"}) == (1.0, 1.0, 1.0)

def test_prf_both_empty_is_one():
    assert grade.prf(set(), set()) == (1.0, 1.0, 1.0)

def test_prf_half_recall():
    p, r, f1 = grade.prf({"a"}, {"a", "b"})
    assert p == 1.0 and r == 0.5 and abs(f1 - 2/3) < 1e-9

def test_prf_empty_prediction():
    assert grade.prf(set(), {"a"}) == (0.0, 0.0, 0.0)

def test_norm_symbol_reduces_fqcn():
    assert grade.norm_sym("com.hazelcast.map.impl.operation.BasePutOperation#afterRunInternal") \
        == "BasePutOperation#afterRunInternal"
    assert grade.norm_sym("com.hazelcast.map.Foo") == "Foo"

def test_grade_files_normalizes_paths():
    p, r, f1 = grade.grade_files(["./hazelcast/x/Foo.java"], ["hazelcast/x/Foo.java"])
    assert f1 == 1.0
