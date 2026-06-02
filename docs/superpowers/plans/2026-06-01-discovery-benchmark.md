# Discovery Benchmark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a discovery-phase benchmark that measures whether the semantic index helps Claude Code locate the change surface for a described task (files + symbols) more accurately and cheaply than grep/find, across ~20–30 sampled Hazelcast PRs.

**Architecture:** Extend the existing `bench/` harness (reuse hermetic isolation, the `enforce-and-log.sh` tool-gating hook, audit logging, and worktree-at-base machinery from `run-one.sh`). Each instance runs as a fresh headless session per arm at the PR's base sha; the agent writes `ANSWER.json` instead of editing code; a hybrid grader (deterministic file/symbol F1 + LLM judge) scores it. The semantic arm queries the index at `branch=<base_sha>` via the any-ref overlay.

**Tech Stack:** Bash (drivers/sampler), Python 3 (truth extraction, grading, analysis — stdlib + the `claude` CLI for the judge), git, the running indexer MCP server.

Spec: `docs/superpowers/specs/2026-06-01-discovery-benchmark-design.md`.

---

## File Structure

- `bench/discovery/extract_truth.py` — gold-patch → `{files, symbols}` ground truth (neutral; git diff + java funcname).
- `bench/discovery/test_extract_truth.py` — unit tests for the above.
- `bench/discovery/grade.py` — deterministic file/symbol F1 + LLM-judge; CLI grader.
- `bench/discovery/test_grade.py` — unit tests (synthetic answers, stubbed judge).
- `bench/discovery/analyze_discovery.py` — per-arm accuracy+cost aggregates.
- `bench/discovery/test_analyze_discovery.py` — synthetic-CSV test.
- `bench/discovery/task-prompt-discovery.md` — the agent instruction template.
- `bench/discovery/sample-prs.sh` — sample PRs → `instances.jsonl`.
- `bench/run-discovery-one.sh` — one instance × arm driver (mirrors `run-one.sh`).
- `bench/run-discovery-all.sh` — preflight (+overlay pre-warm) → smoke → batch → analyze.

Conventions to match: `bench/analyze.py` (nonparametric, stdlib-only), `bench/run-one.sh`
(worktree + isolation flags + audit), `bench/hooks/enforce-and-log.sh` (already implements
the hybrid grep policy both arms need).

---

## Task 1: Ground-truth extractor (`extract_truth.py`)

**Files:**
- Create: `bench/discovery/extract_truth.py`
- Test: `bench/discovery/test_extract_truth.py`

- [ ] **Step 1: Write the failing tests**

```python
# bench/discovery/test_extract_truth.py
import extract_truth as et

def test_is_source_file():
    assert et.is_source_file("hazelcast/src/main/java/com/hazelcast/map/Foo.java")
    assert not et.is_source_file("hazelcast/src/test/java/com/hazelcast/map/FooTest.java")
    assert not et.is_source_file("hazelcast/src/main/java/com/hazelcast/map/FooIT.java")
    assert not et.is_source_file("docs/readme.md")
    assert not et.is_source_file("hazelcast/src/main/resources/x.xml")

SAMPLE_DIFF = '''diff --git a/hazelcast/src/main/java/com/hazelcast/map/impl/operation/BasePutOperation.java b/hazelcast/src/main/java/com/hazelcast/map/impl/operation/BasePutOperation.java
index 1111..2222 100644
--- a/hazelcast/src/main/java/com/hazelcast/map/impl/operation/BasePutOperation.java
+++ b/hazelcast/src/main/java/com/hazelcast/map/impl/operation/BasePutOperation.java
@@ -40,7 +40,7 @@ public void afterRunInternal() {
-        mapServiceContext.interceptAfterPut(mapContainer.getInterceptorRegistry(), dataValue);
+        mapServiceContext.interceptAfterPut(mapContainer.getInterceptorRegistry(), value);
diff --git a/hazelcast/src/test/java/com/hazelcast/map/InterceptorTest.java b/hazelcast/src/test/java/com/hazelcast/map/InterceptorTest.java
index 3333..4444 100644
--- a/hazelcast/src/test/java/com/hazelcast/map/InterceptorTest.java
+++ b/hazelcast/src/test/java/com/hazelcast/map/InterceptorTest.java
@@ -10,0 +11,5 @@ public class InterceptorTest {
+    // new test
'''

def test_parse_diff_files_excludes_tests():
    files, symbols = et.parse_diff(SAMPLE_DIFF)
    assert files == {"hazelcast/src/main/java/com/hazelcast/map/impl/operation/BasePutOperation.java"}

def test_parse_diff_symbols_from_funcname():
    files, symbols = et.parse_diff(SAMPLE_DIFF)
    assert "BasePutOperation#afterRunInternal" in symbols

def test_parse_diff_classlevel_when_no_method():
    diff = ('+++ b/hazelcast/src/main/java/com/hazelcast/map/Foo.java\n'
            '@@ -1 +1 @@ \n+x\n')
    files, symbols = et.parse_diff(diff)
    assert symbols == {"Foo"}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd bench/discovery && python3 -m pytest test_extract_truth.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'extract_truth'`.

- [ ] **Step 3: Write the implementation**

```python
# bench/discovery/extract_truth.py
#!/usr/bin/env python3
"""Neutral ground-truth extraction from a PR's gold patch.
Usage: extract_truth.py <repo> <base_sha> <merge_sha>  ->  prints {"files":[...],"symbols":[...]}
Symbols come from git's built-in java funcname hunk headers — NOT from our index."""
import json, os, re, subprocess, sys

_TEST_RE = re.compile(r"(^|/)src/test/|Test\.java$|IT\.java$|/it/", re.I)

def is_source_file(path):
    return path.endswith(".java") and "/src/main/" in path and not _TEST_RE.search(path)

def _method_name(ctx):
    """Java funcname header is a signature line; the identifier just before '(' is the method."""
    if "(" not in ctx:
        return None
    head = ctx[:ctx.index("(")]
    ids = re.findall(r"[A-Za-z_]\w*", head)
    return ids[-1] if ids else None

def parse_diff(diff_text):
    files, symbols = set(), set()
    cur_file = cur_class = None
    for line in diff_text.splitlines():
        if line.startswith("+++ b/"):
            path = line[6:].strip()
            cur_file = path if is_source_file(path) else None
            cur_class = os.path.basename(cur_file)[:-5] if cur_file else None
            if cur_file:
                files.add(cur_file)
        elif cur_file and line.startswith("@@"):
            m = re.match(r"@@ .*? @@ ?(.*)", line)
            method = _method_name((m.group(1) if m else "").strip())
            symbols.add(f"{cur_class}#{method}" if method else cur_class)
    return files, symbols

def _enable_java_funcname(repo):
    attrs = os.path.join(repo, ".git", "info", "attributes")
    have = os.path.exists(attrs) and "*.java diff=java" in open(attrs).read()
    if not have:
        with open(attrs, "a") as f:
            f.write("*.java diff=java\n")

def extract(repo, base, merge):
    _enable_java_funcname(repo)
    diff = subprocess.run(["git", "-C", repo, "diff", f"{base}..{merge}"],
                          capture_output=True, text=True, check=True).stdout
    files, symbols = parse_diff(diff)
    return {"files": sorted(files), "symbols": sorted(symbols)}

if __name__ == "__main__":
    repo, base, merge = sys.argv[1], sys.argv[2], sys.argv[3]
    print(json.dumps(extract(repo, base, merge)))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd bench/discovery && python3 -m pytest test_extract_truth.py -v`
Expected: PASS (4 tests).

- [ ] **Step 5: Validate against the real PR #4317**

Run: `python3 bench/discovery/extract_truth.py /Users/csharpl/Projects/hazelcast b4d75e77eaa1 39c14ca464 | python3 -m json.tool`
Expected: JSON whose `files` include `.../operation/BasePutOperation.java` and exclude any `src/test/` path; `symbols` include `BasePutOperation#afterRunInternal`. Eyeball that it matches the known fix.

- [ ] **Step 6: Commit**

```bash
git add bench/discovery/extract_truth.py bench/discovery/test_extract_truth.py
git commit -m "bench(discovery): neutral PR ground-truth extractor (files+symbols)"
```

---

## Task 2: Grader — deterministic F1 core (`grade.py`)

**Files:**
- Create: `bench/discovery/grade.py`
- Test: `bench/discovery/test_grade.py`

- [ ] **Step 1: Write the failing tests**

```python
# bench/discovery/test_grade.py
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd bench/discovery && python3 -m pytest test_grade.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'grade'`.

- [ ] **Step 3: Write the implementation (deterministic core only)**

```python
# bench/discovery/grade.py
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd bench/discovery && python3 -m pytest test_grade.py -v`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add bench/discovery/grade.py bench/discovery/test_grade.py
git commit -m "bench(discovery): deterministic file/symbol F1 grading core"
```

---

## Task 3: Grader — LLM judge + CLI (`grade.py`)

**Files:**
- Modify: `bench/discovery/grade.py` (append judge + `main`)
- Modify: `bench/discovery/test_grade.py` (add judge + main tests)

- [ ] **Step 1: Write the failing tests**

```python
# append to bench/discovery/test_grade.py
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd bench/discovery && python3 -m pytest test_grade.py -k "judge or score_instance" -v`
Expected: FAIL — `AttributeError: module 'grade' has no attribute 'judge'`.

- [ ] **Step 3: Write the implementation (append to `grade.py`)**

```python
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd bench/discovery && python3 -m pytest test_grade.py -v`
Expected: PASS (all, incl. the 3 new).

- [ ] **Step 5: Commit**

```bash
git add bench/discovery/grade.py bench/discovery/test_grade.py
git commit -m "bench(discovery): LLM judge + score_instance CLI (deterministic F1 + judge)"
```

---

## Task 4: Discovery task prompt (`task-prompt-discovery.md`)

**Files:**
- Create: `bench/discovery/task-prompt-discovery.md`

- [ ] **Step 1: Write the template**

```markdown
<!-- bench/discovery/task-prompt-discovery.md
     Placeholders substituted by run-discovery-one.sh: {{TASK}}, {{BRANCH_HINT}}, {{WORKTREE}} -->
You are exploring an unfamiliar large Java codebase (Hazelcast) checked out at `{{WORKTREE}}`.

A change is requested:

> {{TASK}}

Your job is **discovery only**. Do NOT implement the change. Do NOT edit any source file.
Investigate the codebase and determine exactly which source files and which symbols
(classes / methods) a developer would need to modify to make this change.

{{BRANCH_HINT}}

When you are confident, write your answer to a file named `ANSWER.json` in the current
working directory, with this exact shape (repo-relative file paths; symbols as
`fully.qualified.ClassName#method`, the `#method` part optional for class-level):

```json
{ "files": ["hazelcast/src/main/java/.../SomeClass.java"],
  "symbols": ["com.hazelcast.x.SomeClass#someMethod"] }
```

Write `ANSWER.json` once, then stop. Your answer is graded on whether the files/symbols
match the actual change surface — favor precision and completeness over guessing.
```

- [ ] **Step 2: Validate placeholders are present**

Run: `grep -c -e '{{TASK}}' -e '{{BRANCH_HINT}}' -e '{{WORKTREE}}' bench/discovery/task-prompt-discovery.md`
Expected: `3` (each placeholder present at least once; grep `-c` counts matching lines — confirm output ≥ 3).

- [ ] **Step 3: Commit**

```bash
git add bench/discovery/task-prompt-discovery.md
git commit -m "bench(discovery): agent task prompt template (investigate -> ANSWER.json)"
```

---

## Task 5: PR sampler (`sample-prs.sh`)

**Files:**
- Create: `bench/discovery/sample-prs.sh`

- [ ] **Step 1: Write the sampler**

```bash
#!/usr/bin/env bash
# Sample merged PRs from the hazelcast clone into instances.jsonl.
# Usage: sample-prs.sh <hazelcast-clone> <out.jsonl> [max_instances] [since]
# Filters: 2-way merge commits (PRs); >=2 non-test source files changed; total <= MAX_FILES;
#          all touched source files resolve at base; has a usable subject line.
set -euo pipefail
REPO="${1:?clone path}"; OUT="${2:?out jsonl}"; MAX="${3:-30}"; SINCE="${4:-2024-01-01}"
HERE="$(cd "$(dirname "$0")" && pwd)"
MAX_FILES=20
: > "$OUT"
count=0
# Merge commits (two parents) since SINCE, newest first — these are PR merges on Hazelcast.
git -C "$REPO" log --merges --since="$SINCE" --pretty='%H %P' main 2>/dev/null \
  | while read -r merge p1 p2; do
      [[ -n "${p2:-}" ]] || continue            # need a real 2-parent merge
      base="$p1"                                 # first parent = mainline before the PR
      # changed source files between base and merge
      mapfile -t changed < <(git -C "$REPO" diff --name-only "$base..$merge" -- '*.java' \
                              | grep -E '/src/main/' | grep -vE '(^|/)src/test/|Test\.java$|IT\.java$' || true)
      (( ${#changed[@]} >= 2 )) || continue
      (( ${#changed[@]} <= MAX_FILES )) || continue
      # all changed source files must exist at base
      ok=1; for f in "${changed[@]}"; do
        git -C "$REPO" cat-file -e "$base:$f" 2>/dev/null || { ok=0; break; }; done
      (( ok == 1 )) || continue
      subj=$(git -C "$REPO" log -1 --pretty='%s' "$merge")
      body=$(git -C "$REPO" log -1 --pretty='%b' "$merge")
      [[ -n "$subj" ]] || continue
      truth=$(python3 "$HERE/extract_truth.py" "$REPO" "$base" "$merge")
      tf=$(printf '%s' "$truth" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["files"]))')
      (( tf >= 2 )) || continue
      # sanitize: drop explicit source paths / FQNs from the task text (answer-leak guard)
      task=$(printf '%s\n%s' "$subj" "$body" \
             | sed -E 's@[A-Za-z0-9_/.-]+/src/main/[A-Za-z0-9_/.]+\.java@<a source file>@g' \
             | sed -E 's/\b([a-z]+\.)+[A-Z][A-Za-z0-9_]+\b/<a class>/g')
      python3 - "$merge" "$base" "$subj" "$task" "$truth" >> "$OUT" <<'PY'
import sys, json
merge, base, subj, task, truth = sys.argv[1:6]
t = json.loads(truth)
print(json.dumps({"id": merge[:12], "merge_sha": merge, "base_sha": base,
                  "title": subj, "task": task.strip(),
                  "truth_files": t["files"], "truth_symbols": t["symbols"]}))
PY
      count=$((count+1))
      [[ "$count" -ge "$MAX" ]] && break
    done
echo "wrote $(wc -l < "$OUT") instances to $OUT"
```

- [ ] **Step 2: Make executable + dry-run on the clone**

```bash
chmod +x bench/discovery/sample-prs.sh
bash bench/discovery/sample-prs.sh /Users/csharpl/Projects/hazelcast /tmp/instances.jsonl 8 2024-06-01
```
Expected: prints `wrote N instances ...` with N≥1; `/tmp/instances.jsonl` has one JSON object per line.

- [ ] **Step 3: Eyeball 3 sampled instances**

Run: `head -3 /tmp/instances.jsonl | python3 -m json.tool`
Expected: each has `base_sha`, `merge_sha`, non-empty `task`, and a `truth_files` list of `src/main` paths. Manually confirm for one instance that `task` does not literally name a file in `truth_files` (sanitization worked).

- [ ] **Step 4: Commit**

```bash
git add bench/discovery/sample-prs.sh
git commit -m "bench(discovery): PR sampler -> instances.jsonl (filters + leak sanitization)"
```

---

## Task 6: Analysis (`analyze_discovery.py`)

**Files:**
- Create: `bench/discovery/analyze_discovery.py`
- Test: `bench/discovery/test_analyze_discovery.py`

- [ ] **Step 1: Write the failing test**

```python
# bench/discovery/test_analyze_discovery.py
import csv, io, analyze_discovery as ad

CSV = """arm,instance_id,answered,file_f1,symbol_f1,judge_score,in_tokens,out_tokens,cache_read,cache_create,turns,wall_s,cost_usd,denied_attempts,is_error
semantic,a,1,1.0,1.0,1.0,10,20,0,0,5,30.0,0.10,2,false
semantic,b,1,0.5,0.5,0.6,10,20,0,0,7,40.0,0.20,1,false
baseline,a,1,0.5,0.0,0.4,10,20,0,0,9,60.0,0.30,0,false
baseline,b,0,0.0,0.0,0.0,10,20,0,0,3,20.0,0.05,0,false
"""

def test_load_and_rates():
    rows = ad.load(io.StringIO(CSV))
    sem = [r for r in rows if r["arm"] == "semantic"]
    base = [r for r in rows if r["arm"] == "baseline"]
    assert ad.answer_rate(sem) == 1.0
    assert ad.answer_rate(base) == 0.5

def test_median_file_f1():
    rows = ad.load(io.StringIO(CSV))
    sem = [r for r in rows if r["arm"] == "semantic"]
    assert ad.med([r["file_f1"] for r in sem]) == 0.75
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bench/discovery && python3 -m pytest test_analyze_discovery.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'analyze_discovery'`.

- [ ] **Step 3: Write the implementation**

```python
# bench/discovery/analyze_discovery.py
#!/usr/bin/env python3
"""Per-arm accuracy + cost analysis for the discovery benchmark.
Usage: analyze_discovery.py discovery-results.csv"""
import csv, sys, statistics as st

_INTS = ("answered", "in_tokens", "out_tokens", "cache_read", "cache_create", "turns", "denied_attempts")
_FLOATS = ("file_f1", "symbol_f1", "judge_score", "wall_s", "cost_usd")

def load(fp):
    rows = []
    for r in csv.DictReader(fp):
        for k in _INTS:
            r[k] = int(float(r[k]))
        for k in _FLOATS:
            r[k] = float(r[k])
        r["billable_tokens"] = r["in_tokens"] + r["out_tokens"] + r["cache_read"] + r["cache_create"]
        rows.append(r)
    return rows

def med(xs):
    return st.median(xs) if xs else float("nan")

def answer_rate(rows):
    return (sum(r["answered"] for r in rows) / len(rows)) if rows else float("nan")

def _report(rows):
    arms = {a: [r for r in rows if r["arm"] == a] for a in ("semantic", "baseline")}
    print("=" * 64)
    print("DISCOVERY BENCHMARK — semantic index vs grep/find (locate-the-surface)")
    print("=" * 64)
    for name, rs in arms.items():
        print(f"\n[{name}]  instances={len(rs)}  answer_rate={answer_rate(rs)*100:.0f}%")
    answered = {a: [r for r in arms[a] if r["answered"] == 1 and r.get("is_error") == "false"]
                for a in arms}
    metrics = [("file_f1", "file F1", True), ("symbol_f1", "symbol F1", True),
               ("judge_score", "judge", True), ("cost_usd", "cost $", False),
               ("turns", "turns", False), ("wall_s", "wall s", False),
               ("billable_tokens", "billable tok", False)]
    print("\n--- answered instances only (median; higher F1 better, lower cost better) ---")
    for key, label, _ in metrics:
        s = med([r[key] for r in answered["semantic"]])
        b = med([r[key] for r in answered["baseline"]])
        print(f"{label:>13}: semantic {s:>10.3f}   baseline {b:>10.3f}")
    print("\nNote: ~20-30 instances, one repo/model — direction, not a general law.")

def main():
    with open(sys.argv[1]) as f:
        _report(load(f))

if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd bench/discovery && python3 -m pytest test_analyze_discovery.py -v`
Expected: PASS (2 tests).

- [ ] **Step 5: Smoke the report on the synthetic CSV**

Run: `printf 'arm,instance_id,answered,file_f1,symbol_f1,judge_score,in_tokens,out_tokens,cache_read,cache_create,turns,wall_s,cost_usd,denied_attempts,is_error\nsemantic,a,1,1.0,1.0,1.0,10,20,0,0,5,30,0.1,2,false\nbaseline,a,1,0.5,0.0,0.4,10,20,0,0,9,60,0.3,0,false\n' > /tmp/disc.csv && python3 bench/discovery/analyze_discovery.py /tmp/disc.csv`
Expected: a report printing per-arm answer_rate and median file/symbol F1 + cost.

- [ ] **Step 6: Commit**

```bash
git add bench/discovery/analyze_discovery.py bench/discovery/test_analyze_discovery.py
git commit -m "bench(discovery): per-arm accuracy+cost analysis"
```

---

## Task 7: One-instance driver (`run-discovery-one.sh`)

**Files:**
- Create: `bench/run-discovery-one.sh`
- Reference (do not modify): `bench/run-one.sh`, `bench/config/settings.*.json`, `bench/config/mcp.semantic.json`

- [ ] **Step 1: Write the driver**

```bash
#!/usr/bin/env bash
# One discovery run. Usage: run-discovery-one.sh <semantic|baseline> <instance-id>
# Reads the instance from bench/discovery/instances.jsonl; mirrors run-one.sh's worktree +
# hermetic isolation + audit, but replaces test-patch/oracle with prompt + ANSWER.json grading.
set -uo pipefail
ARM="$1"; IID="$2"
BENCH="$(cd "$(dirname "$0")" && pwd)"
HZ="/Users/csharpl/Projects/hazelcast"
INSTANCES="$BENCH/discovery/instances.jsonl"
R="$BENCH/results"; mkdir -p "$R" "$BENCH/worktrees"
WT="$BENCH/worktrees/disc-${ARM}-${IID}"
AUDIT="$R/disc-audit-${ARM}-${IID}.jsonl"; : > "$AUDIT"
RES="$R/disc-result-${ARM}-${IID}.json"
CSV="$R/discovery-results.csv"

inst=$(grep -F "\"id\": \"$IID\"" "$INSTANCES" | head -1)
[[ -n "$inst" ]] || { echo "[$ARM/$IID] ABORT: instance not found"; exit 5; }
BASE=$(printf '%s' "$inst" | python3 -c 'import sys,json;print(json.load(sys.stdin)["base_sha"])')
TASK=$(printf '%s' "$inst" | python3 -c 'import sys,json;print(json.load(sys.stdin)["task"])')

cleanup() { git -C "$HZ" worktree remove --force "$WT" 2>/dev/null || true; }
trap cleanup EXIT
git -C "$HZ" worktree add --force --detach "$WT" "$BASE" >/dev/null 2>&1 \
  || { echo "[$ARM/$IID] ABORT: worktree add failed (base $BASE)"; exit 4; }

# Build the prompt from the template (substitute task / branch hint / worktree).
if [[ "$ARM" == "semantic" ]]; then
  HINT="Use the source-code-indexer MCP tools for discovery. Pass branch=\"$BASE\" on every query so results reflect the code at this commit."
else
  HINT="Use standard shell tools (grep/find/ls) and file reads for discovery."
fi
PROMPT=$(python3 - "$BENCH/discovery/task-prompt-discovery.md" "$TASK" "$HINT" "$WT" <<'PY'
import sys
tpl=open(sys.argv[1]).read()
print(tpl.replace("{{TASK}}",sys.argv[2]).replace("{{BRANCH_HINT}}",sys.argv[3]).replace("{{WORKTREE}}",sys.argv[4]))
PY
)

ARGS=( -p "$PROMPT" --output-format json
       --permission-mode default --settings "$BENCH/config/settings.${ARM}.json"
       --setting-sources project,local --disable-slash-commands
       --strict-mcp-config --add-dir "$WT" )
[[ "$ARM" == "semantic" ]] && ARGS+=( --mcp-config "$BENCH/config/mcp.semantic.json" )

START=$(python3 -c 'import time;print(time.time())')
( cd "$WT" && BENCH_ARM="$ARM" BENCH_AUDIT_LOG="$AUDIT" \
    claude "${ARGS[@]}" ) > "$RES" 2> "$R/disc-stderr-${ARM}-${IID}.log"
END=$(python3 -c 'import time;print(time.time())')
WALL=$(python3 -c "print(f'{$END-$START:.2f}')")

# Grade the answer the agent wrote into the worktree.
ANSWER="$WT/ANSWER.json"
printf '%s' "$inst" > "$R/disc-instance-${ARM}-${IID}.json"
SCORE=$(python3 "$BENCH/discovery/grade.py" "$ANSWER" "$R/disc-instance-${ARM}-${IID}.json" 2>/dev/null \
        || echo '{"answered":0,"file_f1":0,"symbol_f1":0,"judge_score":0}')
get() { printf '%s' "$SCORE" | python3 -c "import sys,json;print(json.load(sys.stdin).get('$1',0))"; }
ANSWERED=$(get answered); FF1=$(get file_f1); SF1=$(get symbol_f1); JS=$(get judge_score)

if jq -e . "$RES" >/dev/null 2>&1; then
  IN=$(jq -r '.usage.input_tokens // 0' "$RES"); OUT=$(jq -r '.usage.output_tokens // 0' "$RES")
  CR=$(jq -r '.usage.cache_read_input_tokens // 0' "$RES"); CC=$(jq -r '.usage.cache_creation_input_tokens // 0' "$RES")
  TURNS=$(jq -r '.num_turns // 0' "$RES"); COST=$(jq -r '.total_cost_usd // 0' "$RES"); ERR=$(jq -r '.is_error // false' "$RES")
else
  IN=0; OUT=0; CR=0; CC=0; TURNS=0; COST=0; ERR=crashed; ANSWERED=0
fi
DENIES=$(grep -c '"decision":"deny"' "$AUDIT" 2>/dev/null || true); DENIES=${DENIES:-0}

echo "${ARM},${IID},${ANSWERED},${FF1},${SF1},${JS},${IN},${OUT},${CR},${CC},${TURNS},${WALL},${COST},${DENIES},${ERR}" >> "$CSV"
echo "[$ARM/$IID] answered=$ANSWERED file_f1=$FF1 symbol_f1=$SF1 judge=$JS turns=$TURNS wall=${WALL}s denies=$DENIES"
```

- [ ] **Step 2: Make executable + shell-lint**

```bash
chmod +x bench/run-discovery-one.sh
bash -n bench/run-discovery-one.sh && echo "syntax ok"
```
Expected: `syntax ok`.

- [ ] **Step 3: Commit (integration is validated in Task 8's smoke)**

```bash
git add bench/run-discovery-one.sh
git commit -m "bench(discovery): one-instance driver (worktree+isolation+ANSWER.json grade)"
```

---

## Task 8: Batch driver + smoke gate (`run-discovery-all.sh`)

**Files:**
- Create: `bench/run-discovery-all.sh`

- [ ] **Step 1: Write the batch driver (with overlay pre-warm)**

```bash
#!/usr/bin/env bash
# Full discovery benchmark: preflight (+pre-warm overlays) -> smoke -> batch -> analyze.
# Usage: run-discovery-all.sh
set -euo pipefail
BENCH="$(cd "$(dirname "$0")" && pwd)"
INSTANCES="$BENCH/discovery/instances.jsonl"
R="$BENCH/results"; mkdir -p "$R"
CSV="$R/discovery-results.csv"

bash "$BENCH/preflight.sh"
[[ -s "$INSTANCES" ]] || { echo "no instances — run bench/discovery/sample-prs.sh first"; exit 1; }

# Pre-warm the any-ref overlay for each base sha so fault-in latency doesn't pollute timing.
echo "== pre-warming index overlays =="
while read -r line; do
  base=$(printf '%s' "$line" | python3 -c 'import sys,json;print(json.load(sys.stdin)["base_sha"])')
  curl -s -m 120 -o /dev/null -X POST http://localhost:8080/mcp \
    -H "Authorization: Bearer bench-upload-key" -H "Content-Type: application/json" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"search_symbols\",\"arguments\":{\"repo\":\"hazelcast\",\"name\":\"Map\",\"branch\":\"$base\"}}}" \
    && echo "  warmed $base" || echo "  WARN: warm failed $base"
done < "$INSTANCES"

echo "arm,instance_id,answered,file_f1,symbol_f1,judge_score,in_tokens,out_tokens,cache_read,cache_create,turns,wall_s,cost_usd,denied_attempts,is_error" > "$CSV"

ids=$(python3 -c 'import sys,json;[print(json.loads(l)["id"]) for l in open(sys.argv[1]) if l.strip()]' "$INSTANCES")
first=$(echo "$ids" | head -1)

echo "== smoke (first instance, both arms) =="
for arm in semantic baseline; do bash "$BENCH/run-discovery-one.sh" "$arm" "$first"; done

echo "== batch (all instances, both arms) =="
for id in $ids; do
  for arm in semantic baseline; do bash "$BENCH/run-discovery-one.sh" "$arm" "$id"; done
done

python3 "$BENCH/discovery/analyze_discovery.py" "$CSV" | tee "$R/discovery-report.txt"
```

- [ ] **Step 2: Make executable + syntax check**

```bash
chmod +x bench/run-discovery-all.sh
bash -n bench/run-discovery-all.sh && echo "syntax ok"
```
Expected: `syntax ok`.

- [ ] **Step 3: Generate the real instance set**

Run: `bash bench/discovery/sample-prs.sh /Users/csharpl/Projects/hazelcast bench/discovery/instances.jsonl 25 2024-06-01`
Expected: `wrote N instances` with N in ~15–30. (If N is low, widen the `since` window.)

- [ ] **Step 4: Smoke ONE instance, both arms (the gate)**

Run: `id=$(head -1 bench/discovery/instances.jsonl | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])'); for a in semantic baseline; do bash bench/run-discovery-one.sh "$a" "$id"; done`
Expected: two lines `[arm/id] answered=1 file_f1=… symbol_f1=… judge=… …`. Verify: semantic arm shows `denies≥0` (grep blocked when reached for), both arms produced an `ANSWER.json` (`answered=1`). If `answered=0`, inspect `bench/results/disc-stderr-*.log` and the prompt before batching.

- [ ] **Step 5: Commit the harness (gate the full batch with the operator)**

```bash
git add bench/run-discovery-all.sh bench/discovery/instances.jsonl
git commit -m "bench(discovery): batch driver with overlay pre-warm + smoke gate"
```

- [ ] **Step 6: Run the full batch (operator-gated — confirm cost/time first)**

Run: `nohup bash bench/run-discovery-all.sh > /tmp/disc-all.log 2>&1 &`
Expected: appends ~2N rows to `bench/results/discovery-results.csv` and prints the per-arm accuracy+cost report. **Do not start without operator confirmation of the estimated cost/time** (≈2N short sessions).

---

## Self-Review

**Spec coverage** (against `2026-06-01-discovery-benchmark-design.md`):
- §3 arms / hybrid policy → reused via `settings.${ARM}.json` + `enforce-and-log.sh` (Task 7) ✓
- §4 flow (worktree@base → prompt → ANSWER.json → grade) → Task 7 ✓
- §5 components → extract_truth (T1), grade (T2/T3), prompt (T4), sampler (T5), analyze (T6), run-one (T7), run-all (T8) ✓
- §6 ANSWER.json protocol → Task 4 template + Task 3 parsing/non-answer handling ✓
- §7 grading (file+symbol F1 + judge) → Tasks 2–3 ✓
- §8 metrics/analysis → Task 6 ✓
- §9 edge cases: non-answer (T3 `score_instance`), test-only filtered (T5), overlay warm (T8), leak sanitization (T5), branch=Bᵢ hint (T7) ✓
- §10 testing (extract_truth on #4317, grade synthetic, sampler dry-run, smoke gate) → T1.S5, T2/T3, T5.S2-3, T8.S4 ✓
- §11 overlay pre-warm → Task 8 Step 1 ✓

**Placeholder scan:** no TBD/TODO; every code step contains complete code; commands have expected output. ✓

**Type/name consistency:** `prf`, `norm_path`, `norm_sym`, `grade_files`, `grade_symbols`, `judge`, `score_instance` defined in T2/T3 and used consistently; CSV columns identical across Task 7 (writer), Task 6 (`_INTS`/`_FLOATS` reader), Task 8 (header); instance fields (`id`, `base_sha`, `task`, `truth_files`, `truth_symbols`) consistent across T5 (writer), T7 (reader), T3 (`score_instance`). ✓

**Known follow-up:** the judge in Task 3 shells out to `claude` (cost/non-determinism) — acceptable per spec (reported alongside deterministic F1, audited on a sample); not exercised in unit tests (stubbed).
