#!/usr/bin/env python3
"""Grade an ANSWER.json against an instance's ground truth.
Usage: grade.py <answer.json> <instance.json> [--no-judge]  ->  prints a score dict (JSON)."""
import json, subprocess, sys

def prf(pred, gold):
    pred, gold = set(pred), set(gold)
    if not pred and not gold:
        return (1.0, 1.0, 1.0)
    tp = len(pred & gold)
    p = tp / len(pred) if pred else 0.0
    r = tp / len(gold) if gold else 0.0
    f1 = 2 * p * r / (p + r) if (p + r) else 0.0
    return (p, r, f1)

def norm_path(p):
    return p.strip().lstrip("./")

def norm_sym(s):
    cls, sep, meth = s.strip().partition("#")
    cls = cls.split(".")[-1]
    return f"{cls}#{meth}" if sep else cls

def grade_files(answer_files, truth_files):
    return prf({norm_path(x) for x in answer_files}, {norm_path(x) for x in truth_files})

def grade_symbols(answer_syms, truth_syms):
    return prf({norm_sym(x) for x in answer_syms}, {norm_sym(x) for x in truth_syms})

# append to bench/discovery/grade.py
JUDGE_MODEL = "claude-sonnet-4-6"  # pinned for reproducibility
_JUDGE_INSTR = (
    "You are grading a code-navigation answer. A developer was asked WHERE in the codebase "
    "to implement a task; they named files and symbols. Compare their answer to the gold "
    "change surface. Award credit for semantically correct locations even if phrased "
    "differently (e.g. the enclosing class instead of the exact method, or a valid alternative "
    "site). Respond with ONLY compact JSON: {\"score\": <0.0-1.0>, \"rationale\": \"<one sentence>\"}.\n\n"
    "TASK:\n{task}\n\nGOLD SURFACE:\n{gold}\n\nDEVELOPER ANSWER:\n{answer}\n"
)

def _claude_judge(task, answer, gold):
    prompt = _JUDGE_INSTR.format(task=task, gold=json.dumps(gold), answer=json.dumps(answer))
    out = subprocess.run(
        ["claude", "-p", prompt, "--output-format", "json", "--model", JUDGE_MODEL,
         "--setting-sources", "project,local", "--disable-slash-commands", "--strict-mcp-config"],
        capture_output=True, text=True)
    data = json.loads(out.stdout)
    res = json.loads(data["result"])  # the model returns a JSON string
    return {"score": float(res["score"]), "rationale": str(res["rationale"])}

def judge(task, answer, gold, judge_fn=None):
    return (judge_fn or _claude_judge)(task, answer, gold)

def _load(path):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None

def score_instance(answer_path, instance_path, judge_fn=None):
    inst = _load(instance_path)
    ans = _load(answer_path)
    answered = 1 if (ans and isinstance(ans.get("files"), list)) else 0
    a_files = ans.get("files", []) if answered else []
    a_syms = ans.get("symbols", []) if answered else []
    fp, fr, ff1 = grade_files(a_files, inst["truth_files"])
    sp, sr, sf1 = grade_symbols(a_syms, inst["truth_symbols"])
    j = judge(inst.get("title", ""), {"files": a_files, "symbols": a_syms},
              {"files": inst["truth_files"], "symbols": inst["truth_symbols"]},
              judge_fn=judge_fn) if answered else {"score": 0.0, "rationale": "no answer"}
    return {"id": inst["id"], "answered": answered,
            "file_p": round(fp, 4), "file_r": round(fr, 4), "file_f1": round(ff1, 4),
            "symbol_f1": round(sf1, 4), "judge_score": round(j["score"], 4),
            "judge_rationale": j["rationale"]}

if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--no-judge"]
    jf = (lambda *_: {"score": 0.0, "rationale": "judge disabled"}) if "--no-judge" in sys.argv else None
    print(json.dumps(score_instance(args[0], args[1], judge_fn=jf)))
