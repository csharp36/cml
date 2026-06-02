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

import json

def test_judge_uses_injected_fn():
    calls = {}
    def fake(task, answer, gold):
        calls["task"] = task
        return {"score": 0.5, "rationale": "partial"}
    out = grade.judge("find X", {"files": ["a"]}, {"files": ["a", "b"]}, judge_fn=fake)
    assert out == {"score": 0.5, "rationale": "partial"}
    assert calls["task"] == "find X"

def test_score_instance_combines(tmp_path):
    answer = {"files": ["hazelcast/x/Foo.java"], "symbols": ["a.b.Foo#m"]}
    inst = {"id": "pr1", "title": "do thing",
            "truth_files": ["hazelcast/x/Foo.java"], "truth_symbols": ["Foo#m"]}
    a = tmp_path / "ANSWER.json"; a.write_text(json.dumps(answer))
    i = tmp_path / "inst.json";   i.write_text(json.dumps(inst))
    row = grade.score_instance(str(a), str(i), judge_fn=lambda *_: {"score": 1.0, "rationale": "ok"})
    assert row["answered"] == 1
    assert row["file_f1"] == 1.0 and row["symbol_f1"] == 1.0 and row["judge_score"] == 1.0

def test_score_instance_missing_answer_is_nonanswer(tmp_path):
    inst = {"id": "pr1", "title": "t", "truth_files": ["x"], "truth_symbols": ["Y"]}
    i = tmp_path / "inst.json"; i.write_text(json.dumps(inst))
    row = grade.score_instance(str(tmp_path / "nope.json"), str(i),
                               judge_fn=lambda *_: {"score": 0.0, "rationale": "no answer"})
    assert row["answered"] == 0 and row["file_f1"] == 0.0 and row["symbol_f1"] == 0.0

def test_judge_instr_formats_without_keyerror():
    # _JUDGE_INSTR carries a literal JSON example {"score":..,"rationale":..}; its braces
    # must be escaped so .format() doesn't treat them as fields and raise KeyError.
    s = grade._JUDGE_INSTR.format(task="t", gold="{}", answer="{}")
    assert '"score"' in s and '"rationale"' in s  # the example survives literally

def test_score_instance_survives_judge_crash(tmp_path):
    # A judge that raises must NOT zero the deterministic file/symbol F1.
    answer = {"files": ["x"], "symbols": ["Y"]}
    inst = {"id": "pr1", "title": "t", "truth_files": ["x"], "truth_symbols": ["Y"]}
    a = tmp_path / "ANSWER.json"; a.write_text(json.dumps(answer))
    i = tmp_path / "inst.json";   i.write_text(json.dumps(inst))
    def boom(*_): raise RuntimeError("judge exploded")
    row = grade.score_instance(str(a), str(i), judge_fn=boom)
    assert row["answered"] == 1
    assert row["file_f1"] == 1.0 and row["symbol_f1"] == 1.0
    assert row["judge_score"] == 0.0  # judge failed -> 0, but F1 preserved
