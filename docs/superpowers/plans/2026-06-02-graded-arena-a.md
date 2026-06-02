# Graded Arena A — Type-Resolution Recall & Precision vs grep Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Settle the keep-or-kill decision by measuring, on a neutral bytecode oracle, whether the index's type-resolution answers (SCIP and the new transitive CTE) beat `grep` on **recall** and **precision** for "who implements / subtypes type X" questions over Hazelcast v5.5.0.

**Architecture:** Pin everything to Hazelcast tag **v5.5.0**. Build a ground-truth type graph from the published `hazelcast-5.5.0.jar` bytecode (via `javap`) — independent of all three arms. Mechanically select ~12 type-resolution questions weighted toward interfaces with many *indirect* implementers (where the differentiator lives). Answer each question three ways — **grep-iterative** (BFS closure via grep), **index-cte** (`find_implementations transitive=true`), **index-scip** (`get_type_hierarchy down`) — score precision/recall/F1 against the oracle, and report. Tool-direct (deterministic) first; agent-mediated cost layer is optional and deferred.

**Tech Stack:** Bash + Python 3 (harness, scoring), JDK 21 `javap` (oracle), the indexer's HTTP MCP endpoint (`POST /mcp`) with a hazelcast-scoped API key, `scripts/scip-upload.sh` + `scip-java` + `./gradlew shadowJar` (SCIP population).

---

## Why three arms (the SCIP point)

The Arena A sniff showed the shipped `find_implementations` was recall-equivalent to grep and SCIP was empty. We have since built a **transitive CTE** (`find_implementations transitive=true`). But the CTE is **name-matched** on unqualified `related_name`, so it recovers recall while *inheriting grep's precision problem* (it over-counts when simple names collide across packages). **SCIP is the only arm that is type-resolved** and can be both high-recall and high-precision. So the experiment must include SCIP, and must measure **precision** separately from recall — otherwise it cannot see SCIP's actual advantage. Predicted shape:

| arm | recall | precision | cost/effort |
|---|---|---|---|
| grep-iterative | high (with effort) | low (string FPs + name collisions) | high (many calls) |
| index-cte | high | medium (no string FPs; still name-collision FPs) | 1 call |
| index-scip | high | high (type-resolved) | 1 call |

The keep signal is **SCIP precision materially > both** at equal-or-better recall. If SCIP ties the CTE on precision too, the type-resolution layer adds nothing the cheap CTE doesn't — a kill-leaning result.

## Oracle independence (the anti-circularity rule)

Ground truth comes from **compiled bytecode** of `hazelcast-5.5.0.jar` (already in `~/.m2/repository/com/hazelcast/hazelcast/5.5.0/`), read with `javap`. This is the JVM's actual type graph. It is independent of: grep (source text), the CTE (Tree-sitter `type_relationships`), and SCIP (`scip-java`). **Never** derive the oracle from the index's own data. The indexed ref, the SCIP ref, and the oracle jar are all **v5.5.0** so there is no version skew.

## File structure

```
bench/arenaA/
  pin.env                     # PIN_TAG=v5.5.0, PIN_JAR=..., SERVER=..., HZ_READ_KEY env name
  oracle/
    build_oracle.py           # jar -> javap -> FQN type graph -> transitive closures (with unit test)
    test_build_oracle.py
    oracle.json               # OUTPUT: {type_fqn: {direct:[...], transitive:[...]}}, simple-name index
  select_questions.py         # mechanical question selection from oracle (with unit test)
  test_select_questions.py
  questions.jsonl             # OUTPUT: 12 sanitized questions + per-question oracle answer set
  arms/
    run_grep.sh               # grep-iterative BFS closure over the v5.5.0 source archive
    run_index_cte.sh          # find_implementations transitive=true via HTTP MCP
    run_index_scip.sh         # get_type_hierarchy down via HTTP MCP
  score.py                    # precision/recall/F1 + collision-precision per arm (with unit test)
  test_score.py
  run_all.sh                  # drive 12 questions x 3 arms -> results.csv
  results/
    results.csv               # OUTPUT
    report.txt                # OUTPUT
docs/superpowers/results/2026-06-02-arenaA-graded-results.md   # write-up + decision
```

---

## Phase 0 — Populate SCIP for Hazelcast v5.5.0

> Risk: `scip-java` must compile Hazelcast (~2M LOC). If a full build fails, fall back to a
> subset of modules (Task 0.3 fallback) — a partial SCIP layer still supports the benchmark
> as long as the selected questions' types are covered (enforce in Task 1.3).

### Task 0.1: Pin config and index the v5.5.0 tag

**Files:**
- Create: `bench/arenaA/pin.env`

- [ ] **Step 1: Write the pin file**

```bash
cat > bench/arenaA/pin.env <<'EOF'
PIN_TAG=v5.5.0
PIN_JAR="$HOME/.m2/repository/com/hazelcast/hazelcast/5.5.0/hazelcast-5.5.0.jar"
SERVER="http://localhost:8080"
# API keys (export before running; never commit). HZ_READ_KEY needs repos:[hazelcast];
# HZ_SCIP_KEY needs scipUpload:true + repos:[hazelcast].
: "${HZ_READ_KEY:?set HZ_READ_KEY}"
: "${HZ_SCIP_KEY:?set HZ_SCIP_KEY}"
EOF
```

- [ ] **Step 2: Ensure a hazelcast-scoped read key + scipUpload key exist**

Edit `~/.source-code-indexer/config.yaml` under `auth.apiKeys` (this session's current key is
`cml`-only, which is why MCP queries for hazelcast were denied):

```yaml
auth:
  apiKeys:
    - key: ${HZ_READ_KEY}
      id: arenaA-read
      name: Arena A read
      repos: [hazelcast]
    - key: ${HZ_SCIP_KEY}
      id: arenaA-scip
      name: Arena A scip upload
      scipUpload: true
      repos: [hazelcast]
```

- [ ] **Step 3: Index the tag via the any-ref overlay (fault-in by querying it)**

Run:
```bash
source bench/arenaA/pin.env
curl -s "$SERVER/mcp" -H "Authorization: Bearer $HZ_READ_KEY" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_repo_summary","arguments":{"repo":"hazelcast","branch":"v5.5.0"}}}' | tee /tmp/hz_v550_summary.json
```
Expected: a JSON result with a non-zero file/symbol count for `v5.5.0` (overlay faulted in).

- [ ] **Step 4: Commit**

```bash
git add bench/arenaA/pin.env
git commit -m "chore(arenaA): pin v5.5.0 and scope keys for graded Arena A"
```

### Task 0.2: Generate the SCIP index for v5.5.0

**Files:** none committed (build artifact).

- [ ] **Step 1: Check out the tag in a scratch worktree**

Run:
```bash
git -C ~/.source-code-indexer/repos/hazelcast worktree add /tmp/hz-v550 v5.5.0
```
Expected: worktree created at `/tmp/hz-v550` at tag v5.5.0.

- [ ] **Step 2: Generate SCIP via scip-java (Maven mode)**

Run:
```bash
cd /tmp/hz-v550 && scip-java index --build-tool maven -- -DskipTests -pl hazelcast -am
```
Expected: `index.scip` produced in `/tmp/hz-v550`. If the full reactor fails, retry with a
narrower `-pl` module list (record which modules; carry that list to Task 1.3).

- [ ] **Step 3: Verify the SCIP file parses and note its size**

Run:
```bash
ls -l /tmp/hz-v550/index.scip
```
Expected: a file (likely > 50 MB → multi-part upload path in Task 0.3).

### Task 0.3: Upload SCIP and verify type-resolved queries work

**Files:** none committed.

- [ ] **Step 1: Build the splitter jar**

Run: `./gradlew shadowJar`
Expected: `build/libs/indexer.jar` exists.

- [ ] **Step 2: Upload (single invocation handles any size)**

Run:
```bash
source bench/arenaA/pin.env
./scripts/scip-upload.sh --server "$SERVER" --repo hazelcast --api-key "$HZ_SCIP_KEY" \
  --scip-file /tmp/hz-v550/index.scip --splitter-jar build/libs/indexer.jar \
  --git-sha "$(git -C ~/.source-code-indexer/repos/hazelcast rev-parse v5.5.0^{commit})"
```
Expected: terminal JSON `{"repo":"hazelcast","sha":"...","symbols":>0,"relationships":>0,...}`.

- [ ] **Step 3: Verify get_type_hierarchy now returns SCIP data at v5.5.0**

Run:
```bash
curl -s "$SERVER/mcp" -H "Authorization: Bearer $HZ_READ_KEY" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_type_hierarchy","arguments":{"repo":"hazelcast","symbol_name":"DataSerializable","direction":"down","branch":"v5.5.0","depth":5}}}'
```
Expected: a non-empty subtypes tree (not "Requires SCIP data"). If empty, SCIP upload/sha
resolution failed — fix before proceeding (this is the exact failure the original CI hit).

- [ ] **Step 4: Smoke-check the DB directly**

Run:
```bash
docker exec -e PGPASSWORD=changeme indexer-pg psql -U indexer -d source_code_index -tAc \
  "select count(*) from scip_symbols; select count(*) from scip_relationships;"
```
Expected: both counts > 0.

---

## Phase 1 — Neutral bytecode oracle + question selection

### Task 1.1: Extract the oracle jar's class list

**Files:** none committed (uses `$PIN_JAR`).

- [ ] **Step 1: Confirm the jar exists and has classes**

Run:
```bash
source bench/arenaA/pin.env
unzip -l "$PIN_JAR" | grep -c '\.class$'
```
Expected: a count in the thousands.

### Task 1.2: Build the type-graph oracle from bytecode

**Files:**
- Create: `bench/arenaA/oracle/build_oracle.py`
- Test: `bench/arenaA/oracle/test_build_oracle.py`

- [ ] **Step 1: Write the failing test**

```python
# test_build_oracle.py
from build_oracle import parse_javap_decl, transitive_closure

def test_parse_javap_decl_extracts_super_and_interfaces():
    line = "public class com.example.UserRepo extends com.example.AbstractRepo implements com.example.Repository, java.io.Serializable {"
    fqn, supers = parse_javap_decl(line)
    assert fqn == "com.example.UserRepo"
    assert set(supers) == {"com.example.AbstractRepo", "com.example.Repository", "java.io.Serializable"}

def test_transitive_closure_walks_extends_and_implements():
    # edges: child -> parents
    graph = {
        "AbstractRepo": ["Repository"],
        "UserRepo": ["AbstractRepo"],
        "AdminRepo": ["UserRepo"],
        "Unrelated": ["OtherThing"],
    }
    # all types that are-a Repository (transitive subtypes)
    assert transitive_closure(graph, "Repository") == {"AbstractRepo", "UserRepo", "AdminRepo"}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bench/arenaA/oracle && python3 -m pytest test_build_oracle.py -v`
Expected: FAIL (`ModuleNotFoundError`/`ImportError`: build_oracle has no such functions).

- [ ] **Step 3: Write minimal implementation**

```python
# build_oracle.py
import re, subprocess, sys, json, zipfile, os
from collections import defaultdict

_DECL = re.compile(r'\b(?:class|interface)\s+([\w.$]+)(?:<[^>]*>)?'
                   r'(?:\s+extends\s+([\w.$,<>\s]+?))?(?:\s+implements\s+([\w.$,<>\s]+?))?\s*\{')

def parse_javap_decl(line):
    m = _DECL.search(line)
    if not m:
        return None, []
    fqn = m.group(1)
    supers = []
    for grp in (m.group(2), m.group(3)):
        if grp:
            for t in grp.split(','):
                t = re.sub(r'<[^>]*>', '', t).strip()
                if t:
                    supers.append(t)
    return fqn, supers

def transitive_closure(child_to_parents, target):
    parent_to_children = defaultdict(set)
    for c, ps in child_to_parents.items():
        for p in ps:
            parent_to_children[p].add(c)
    seen, stack = set(), [target]
    while stack:
        cur = stack.pop()
        for child in parent_to_children.get(cur, ()):
            if child not in seen:
                seen.add(child); stack.append(child)
    return seen

def build_graph_from_jar(jar_path, javap="javap"):
    names = []
    with zipfile.ZipFile(jar_path) as z:
        for n in z.namelist():
            if n.endswith('.class') and '$' not in n:
                names.append(n[:-6].replace('/', '.'))
    graph = {}
    # batch javap over the classpath for speed
    for i in range(0, len(names), 200):
        batch = names[i:i+200]
        out = subprocess.run([javap, "-cp", jar_path, "-p", *batch],
                             capture_output=True, text=True).stdout
        for line in out.splitlines():
            if (' class ' in line or ' interface ' in line) and line.rstrip().endswith('{'):
                fqn, supers = parse_javap_decl(line)
                if fqn:
                    graph[fqn] = supers
    return graph

if __name__ == "__main__":
    jar = sys.argv[1]
    graph = build_graph_from_jar(jar)
    # index by simple name to expose collisions (SCIP's precision advantage)
    simple = defaultdict(set)
    for fqn in graph:
        simple[fqn.split('.')[-1]].add(fqn)
    out = {"graph": graph, "simple_name_index": {k: sorted(v) for k, v in simple.items()}}
    json.dump(out, open("oracle.json", "w"))
    print(f"types={len(graph)} colliding_simple_names="
          f"{sum(1 for v in simple.values() if len(v) > 1)}")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd bench/arenaA/oracle && python3 -m pytest test_build_oracle.py -v`
Expected: PASS (2 passed).

- [ ] **Step 5: Build the real oracle and eyeball it**

Run:
```bash
source ../../arenaA/pin.env 2>/dev/null || source bench/arenaA/pin.env
cd bench/arenaA/oracle && python3 build_oracle.py "$PIN_JAR"
```
Expected: prints `types=<thousands> colliding_simple_names=<some>` and writes `oracle.json`.
The colliding-simple-names count > 0 is what makes the precision axis meaningful.

- [ ] **Step 6: Commit**

```bash
git add bench/arenaA/oracle/build_oracle.py bench/arenaA/oracle/test_build_oracle.py
git commit -m "feat(arenaA): bytecode type-graph oracle from hazelcast-5.5.0.jar"
```

### Task 1.3: Select questions mechanically (indirect-heavy + lexical controls)

**Files:**
- Create: `bench/arenaA/select_questions.py`
- Test: `bench/arenaA/select_questions.py` test → `bench/arenaA/test_select_questions.py`

- [ ] **Step 1: Write the failing test**

```python
# test_select_questions.py
from select_questions import indirect_ratio, pick

def test_indirect_ratio_prefers_deep_hierarchies():
    # direct = literal implements/extends of target; transitive = full closure
    assert indirect_ratio(direct=2, transitive=200) > indirect_ratio(direct=2, transitive=3)

def test_pick_returns_requested_count_and_mixes_strata():
    cand = [{"type": f"I{i}", "direct": 1, "transitive": 100, "stratum": "structural"} for i in range(10)]
    cand += [{"type": f"L{i}", "direct": 1, "transitive": 1, "stratum": "lexical"} for i in range(10)]
    picked = pick(cand, n=12)
    assert len(picked) == 12
    assert any(p["stratum"] == "lexical" for p in picked)
    assert any(p["stratum"] == "structural" for p in picked)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bench/arenaA && python3 -m pytest test_select_questions.py -v`
Expected: FAIL (ImportError).

- [ ] **Step 3: Write minimal implementation**

```python
# select_questions.py
import json, sys
from collections import defaultdict

def indirect_ratio(direct, transitive):
    return (transitive - direct) / max(transitive, 1)

def pick(candidates, n=12):
    structural = sorted([c for c in candidates if c["stratum"] == "structural"],
                        key=lambda c: -indirect_ratio(c["direct"], c["transitive"]))
    lexical = sorted([c for c in candidates if c["stratum"] == "lexical"],
                     key=lambda c: -c["transitive"])
    k_struct = max(1, n * 2 // 3)
    out = structural[:k_struct] + lexical[: n - k_struct]
    return out[:n]

if __name__ == "__main__":
    oracle = json.load(open("oracle/oracle.json"))
    graph = oracle["graph"]
    # transitive subtype set per type (only com.hazelcast.* targets, only resolvable)
    parent_to_children = defaultdict(set)
    for c, ps in graph.items():
        for p in ps:
            parent_to_children[p].add(c)
    def closure(t):
        seen, stack = set(), [t]
        while stack:
            cur = stack.pop()
            for ch in parent_to_children.get(cur, ()):
                if ch not in seen:
                    seen.add(ch); stack.append(ch)
        return seen
    cand = []
    for t in graph:
        if not t.startswith("com.hazelcast."):
            continue
        trans = closure(t)
        if len(trans) < 2:
            continue
        direct = len(parent_to_children.get(t, ()))
        ratio = indirect_ratio(direct, len(trans))
        cand.append({"type": t, "direct": direct, "transitive": len(trans),
                     "stratum": "structural" if ratio >= 0.5 else "lexical",
                     "answer": sorted(trans)})
    picked = pick(cand, n=12)
    with open("questions.jsonl", "w") as f:
        for p in picked:
            simple = p["type"].split('.')[-1]
            # sanitized: ask by simple name + kind, do not reveal the answer set
            f.write(json.dumps({
                "id": p["type"].replace('.', '_'),
                "type_simple": simple,
                "type_fqn": p["type"],
                "stratum": p["stratum"],
                "question": f"List every concrete class that is a {simple} "
                            f"(implements it directly or through inheritance).",
                "answer_fqns": p["answer"],
                "answer_simple": sorted({a.split('.')[-1] for a in p["answer"]}),
            }) + "\n")
    print(f"wrote {len(picked)} questions; "
          f"structural={sum(1 for p in picked if p['stratum']=='structural')}")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd bench/arenaA && python3 -m pytest test_select_questions.py -v`
Expected: PASS (2 passed).

- [ ] **Step 5: Generate questions; (SCIP-coverage filter if Phase 0 fell back to a subset)**

Run: `cd bench/arenaA && python3 select_questions.py`
Expected: `wrote 12 questions; structural=8`. If Phase 0 indexed only a module subset, drop
any picked type whose FQN package is outside the indexed modules and re-pick (so all three
arms can actually answer every question).

- [ ] **Step 6: Commit**

```bash
git add bench/arenaA/select_questions.py bench/arenaA/test_select_questions.py bench/arenaA/questions.jsonl
git commit -m "feat(arenaA): mechanical question selection (indirect-heavy + lexical controls)"
```

---

## Phase 2 — The three arms

Each arm reads `questions.jsonl` and emits, per question, a JSON line:
`{"id":..., "arm":..., "found_simple":[...], "found_fqn":[...] | null, "calls": <int>}`.
`found_fqn` is populated only by arms that resolve packages (scip); grep/cte set it null.

### Task 2.1: grep-iterative closure (strong grep baseline)

**Files:**
- Create: `bench/arenaA/arms/run_grep.sh`

- [ ] **Step 1: Export the source tree at the tag (no history — fair discovery)**

```bash
mkdir -p /tmp/hz-src-v550 && git -C ~/.source-code-indexer/repos/hazelcast archive v5.5.0 | tar -x -C /tmp/hz-src-v550
```

- [ ] **Step 2: Write the arm (BFS: seed = simple name; expand via implements/extends)**

```bash
cat > bench/arenaA/arms/run_grep.sh <<'EOF'
#!/usr/bin/env bash
# Usage: run_grep.sh <src_dir> <questions.jsonl> > grep.results.jsonl
set -euo pipefail
SRC="$1"; Q="$2"
while IFS= read -r line; do
  id=$(jq -r .id <<<"$line"); seed=$(jq -r .type_simple <<<"$line")
  declare -A seen=(); frontier=("$seed"); calls=0
  while [ ${#frontier[@]} -gt 0 ]; do
    nxt=(); for name in "${frontier[@]}"; do
      [ -n "${seen[$name]:-}" ] && continue; seen[$name]=1
      calls=$((calls+1))
      # classes whose declaration implements/extends $name
      while IFS= read -r hit; do
        cls=$(sed -nE 's/.*\b(class|interface)\s+([A-Za-z0-9_]+).*/\2/p' <<<"$hit")
        [ -n "$cls" ] && nxt+=("$cls")
      done < <(grep -rhoE "(class|interface)\s+[A-Za-z0-9_]+[^{]*\b(implements|extends)\b[^{]*\b${name}\b" "$SRC" --include='*.java' || true)
    done; frontier=("${nxt[@]:-}")
  done
  found=$(printf '%s\n' "${!seen[@]}" | grep -v "^${seed}$" | jq -R . | jq -cs .)
  jq -cn --arg id "$id" --argjson f "$found" --argjson c "$calls" \
     '{id:$id, arm:"grep", found_simple:$f, found_fqn:null, calls:$c}'
done < "$Q"
EOF
chmod +x bench/arenaA/arms/run_grep.sh
```

- [ ] **Step 3: Smoke test on one question**

Run:
```bash
head -1 bench/arenaA/questions.jsonl > /tmp/q1.jsonl
bench/arenaA/arms/run_grep.sh /tmp/hz-src-v550 /tmp/q1.jsonl
```
Expected: one JSON line with a non-empty `found_simple` and `calls` > 1.

- [ ] **Step 4: Commit**

```bash
git add bench/arenaA/arms/run_grep.sh
git commit -m "feat(arenaA): grep-iterative closure arm"
```

### Task 2.2: index-cte arm (transitive find_implementations)

**Files:**
- Create: `bench/arenaA/arms/run_index_cte.sh`

- [ ] **Step 1: Write the arm (one HTTP MCP call per question)**

```bash
cat > bench/arenaA/arms/run_index_cte.sh <<'EOF'
#!/usr/bin/env bash
# Usage: HZ_READ_KEY=.. SERVER=.. run_index_cte.sh <questions.jsonl> > cte.results.jsonl
set -euo pipefail
Q="$1"
while IFS= read -r line; do
  id=$(jq -r .id <<<"$line"); name=$(jq -r .type_simple <<<"$line")
  req=$(jq -cn --arg n "$name" '{jsonrpc:"2.0",id:1,method:"tools/call",
        params:{name:"find_implementations",arguments:{type_name:$n,repo:"hazelcast",branch:"v5.5.0",transitive:true}}}')
  resp=$(curl -s "$SERVER/mcp" -H "Authorization: Bearer $HZ_READ_KEY" -H 'Content-Type: application/json' -d "$req")
  found=$(jq -r '..|.class_name? // empty' <<<"$resp" | sort -u | jq -R . | jq -cs .)
  jq -cn --arg id "$id" --argjson f "$found" '{id:$id, arm:"index_cte", found_simple:$f, found_fqn:null, calls:1}'
done < "$Q"
EOF
chmod +x bench/arenaA/arms/run_index_cte.sh
```

- [ ] **Step 2: Smoke test**

Run: `source bench/arenaA/pin.env && bench/arenaA/arms/run_index_cte.sh /tmp/q1.jsonl`
Expected: one JSON line, `found_simple` non-empty, `calls`=1.

- [ ] **Step 3: Commit**

```bash
git add bench/arenaA/arms/run_index_cte.sh
git commit -m "feat(arenaA): index transitive-CTE arm"
```

### Task 2.3: index-scip arm (get_type_hierarchy)

**Files:**
- Create: `bench/arenaA/arms/run_index_scip.sh`

- [ ] **Step 1: Write the arm (one HTTP MCP call; capture FQN for precision)**

```bash
cat > bench/arenaA/arms/run_index_scip.sh <<'EOF'
#!/usr/bin/env bash
# Usage: HZ_READ_KEY=.. SERVER=.. run_index_scip.sh <questions.jsonl> > scip.results.jsonl
set -euo pipefail
Q="$1"
while IFS= read -r line; do
  id=$(jq -r .id <<<"$line"); name=$(jq -r .type_simple <<<"$line")
  req=$(jq -cn --arg n "$name" '{jsonrpc:"2.0",id:1,method:"tools/call",
        params:{name:"get_type_hierarchy",arguments:{repo:"hazelcast",symbol_name:$n,direction:"down",branch:"v5.5.0",depth:10}}}')
  resp=$(curl -s "$SERVER/mcp" -H "Authorization: Bearer $HZ_READ_KEY" -H 'Content-Type: application/json' -d "$req")
  # SCIP nodes carry display names and/or fqns; collect both defensively
  fqn=$(jq -r '..|.fqn? // .symbol? // empty' <<<"$resp" | grep -E '[A-Za-z]' | sort -u | jq -R . | jq -cs .)
  simple=$(jq -r '..|.name? // .display_name? // empty' <<<"$resp" | sort -u | jq -R . | jq -cs .)
  jq -cn --arg id "$id" --argjson s "$simple" --argjson q "$fqn" \
     '{id:$id, arm:"index_scip", found_simple:$s, found_fqn:$q, calls:1}'
done < "$Q"
EOF
chmod +x bench/arenaA/arms/run_index_scip.sh
```

- [ ] **Step 2: Smoke test (confirms Phase 0 SCIP is actually queryable)**

Run: `source bench/arenaA/pin.env && bench/arenaA/arms/run_index_scip.sh /tmp/q1.jsonl`
Expected: one JSON line, `found_simple` non-empty. Empty ⇒ revisit Phase 0 Task 0.3.

- [ ] **Step 3: Commit**

```bash
git add bench/arenaA/arms/run_index_scip.sh
git commit -m "feat(arenaA): index SCIP type-hierarchy arm"
```

---

## Phase 3 — Scoring and analysis

### Task 3.1: Scorer (recall, precision, F1, collision-precision)

**Files:**
- Create: `bench/arenaA/score.py`
- Test: `bench/arenaA/test_score.py`

- [ ] **Step 1: Write the failing test**

```python
# test_score.py
from score import prf, collision_precision

def test_prf_basic():
    p, r, f = prf(found={"A", "B", "X"}, truth={"A", "B", "C"})
    assert round(p, 2) == 0.67 and round(r, 2) == 0.67 and round(f, 2) == 0.67

def test_collision_precision_penalizes_ambiguous_simple_names():
    # simple name "Foo" maps to two FQNs in the oracle; only one is truly a subtype.
    simple_index = {"Foo": ["a.Foo", "b.Foo"], "Bar": ["a.Bar"]}
    truth_fqn = {"a.Foo", "a.Bar"}
    # a name-only arm returns both Foo and Bar -> credit Bar, but Foo is ambiguous (1 of 2 right)
    cp = collision_precision(found_simple={"Foo", "Bar"}, truth_fqn=truth_fqn, simple_index=simple_index)
    assert 0.0 < cp < 1.0
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bench/arenaA && python3 -m pytest test_score.py -v`
Expected: FAIL (ImportError).

- [ ] **Step 3: Write minimal implementation**

```python
# score.py
import json, sys, csv
from collections import defaultdict

def prf(found, truth):
    tp = len(found & truth)
    p = tp / len(found) if found else 0.0
    r = tp / len(truth) if truth else 0.0
    f = 2 * p * r / (p + r) if (p + r) else 0.0
    return p, r, f

def collision_precision(found_simple, truth_fqn, simple_index):
    """Expected precision when a name-only answer is mapped back to FQNs.
    For each returned simple name, the fraction of its candidate FQNs that are truly subtypes."""
    truth = set(truth_fqn)
    if not found_simple:
        return 0.0
    total = 0.0
    for s in found_simple:
        cands = simple_index.get(s, [])
        if not cands:
            continue
        total += sum(1 for c in cands if c in truth) / len(cands)
    return total / len(found_simple)

if __name__ == "__main__":
    questions = {json.loads(l)["id"]: json.loads(l) for l in open("questions.jsonl")}
    oracle = json.load(open("oracle/oracle.json"))
    simple_index = oracle["simple_name_index"]
    rows = []
    for path in sys.argv[1:]:  # the three *.results.jsonl
        for l in open(path):
            r = json.loads(l)
            q = questions[r["id"]]
            truth_simple = set(q["answer_simple"]); truth_fqn = set(q["answer_fqns"])
            found_simple = set(r.get("found_simple") or [])
            p, rec, f = prf(found_simple, truth_simple)
            cp = collision_precision(found_simple, truth_fqn, simple_index)
            # if the arm gave FQNs (scip), also score precise FQN-level
            fqn_p = fqn_r = fqn_f = ""
            if r.get("found_fqn"):
                fqn_p, fqn_r, fqn_f = prf(set(r["found_fqn"]), truth_fqn)
            rows.append([r["id"], q["stratum"], r["arm"], len(truth_simple),
                         len(found_simple), round(p,3), round(rec,3), round(f,3),
                         round(cp,3), fqn_p, fqn_r, fqn_f, r.get("calls","")])
    w = csv.writer(open("results/results.csv","w"))
    w.writerow(["id","stratum","arm","truth_n","found_n","precision","recall","f1",
                "collision_precision","fqn_precision","fqn_recall","fqn_f1","calls"])
    w.writerows(rows)
    print(f"wrote results/results.csv ({len(rows)} rows)")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd bench/arenaA && python3 -m pytest test_score.py -v`
Expected: PASS (2 passed).

- [ ] **Step 5: Commit**

```bash
git add bench/arenaA/score.py bench/arenaA/test_score.py
git commit -m "feat(arenaA): scorer with recall/precision/F1 + collision-precision"
```

### Task 3.2: Run all arms and score

**Files:**
- Create: `bench/arenaA/run_all.sh`

- [ ] **Step 1: Write the driver**

```bash
cat > bench/arenaA/run_all.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
source pin.env
mkdir -p results
arms/run_grep.sh /tmp/hz-src-v550 questions.jsonl > results/grep.results.jsonl
arms/run_index_cte.sh questions.jsonl > results/cte.results.jsonl
arms/run_index_scip.sh questions.jsonl > results/scip.results.jsonl
python3 score.py results/grep.results.jsonl results/cte.results.jsonl results/scip.results.jsonl
EOF
chmod +x bench/arenaA/run_all.sh
```

- [ ] **Step 2: Run it**

Run: `bench/arenaA/run_all.sh`
Expected: `wrote results/results.csv (36 rows)` (12 questions × 3 arms).

- [ ] **Step 3: Commit results**

```bash
git add bench/arenaA/run_all.sh bench/arenaA/results/results.csv bench/arenaA/results/*.results.jsonl
git commit -m "result(arenaA): graded run, 12 questions x 3 arms"
```

### Task 3.3: Analyze and write the decision

**Files:**
- Create: `docs/superpowers/results/2026-06-02-arenaA-graded-results.md`

- [ ] **Step 1: Compute per-arm and per-stratum aggregates**

Run:
```bash
cd bench/arenaA && python3 - <<'PY'
import csv, statistics as st
from collections import defaultdict
rows=list(csv.DictReader(open("results/results.csv")))
def agg(pred, key):
    vals=[float(r[key]) for r in rows if pred(r) and r[key] not in ("","None")]
    return round(st.mean(vals),3) if vals else None
for arm in ("grep","index_cte","index_scip"):
    P=lambda r: r["arm"]==arm
    print(arm, "recall",agg(P,"recall"), "precision",agg(P,"precision"),
          "F1",agg(P,"f1"), "collision_prec",agg(P,"collision_precision"),
          "calls",agg(P,"calls"))
PY
```
Expected: three lines of aggregates; eyeball recall (cte/scip ≥ grep?) and precision (scip > cte?).

- [ ] **Step 2: Write the results doc**

Write `docs/superpowers/results/2026-06-02-arenaA-graded-results.md` with: method recap,
the per-arm/per-stratum table, the head-to-head, the **decision against the rule below**, and
threats (name-match caveat, bytecode-vs-source oracle, n=12, single repo/tag).

- [ ] **Step 3: Apply the decision rule**

```
KEEP (type-resolution is a real differentiator) if:
  index_scip recall >= grep recall  AND  index_scip precision materially > BOTH grep and
  index_cte precision (the type-resolution layer earns its keep on precision).
PARTIAL / build-more if:
  index_cte ties grep recall with better precision, but index_scip ~= index_cte
  (SCIP adds little beyond the cheap CTE — keep the CTE, question SCIP's cost).
KILL the type-resolution thesis if:
  no arm beats grep on F1, OR scip precision ~= grep precision (type resolution buys nothing).
```

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/results/2026-06-02-arenaA-graded-results.md
git commit -m "result(arenaA): graded results + keep/kill decision"
```

---

## Optional Phase 4 — Agent-mediated cost layer (deferred)

Tool-direct Phase 3 isolates *capability* (recall/precision) without agent variance. If the
decision is KEEP, add a realistic *cost* read: run each question through a headless agent
(reuse the discovery harness's hermetic isolation) with only that arm's tools enabled, and
record tokens/turns. This answers "is the precision win worth the spend," mirroring the
discovery benchmark's cost axis. Spec this as its own plan if reached.

---

## Self-review notes

- **Spec coverage:** Phase 0 fills the SCIP gap the sniff exposed; Phases 1–3 implement the
  three-arm recall+precision comparison the user asked for (SCIP included, not just the CTE);
  oracle independence is enforced (bytecode, never the index's data).
- **Anti-circularity:** oracle = `hazelcast-5.5.0.jar` bytecode; all three arms and the
  oracle pinned to v5.5.0 — no version skew, no shared lineage with any arm.
- **Genuine-logic tasks are TDD** (oracle parse/closure, question selection, scorer); ops
  tasks (index/upload/run) are concrete commands with explicit expected output.
- **Risk surfaced, not hidden:** scip-java on a 2M-LOC repo may fail → documented module-
  subset fallback with a coverage filter so every question stays answerable by all arms.
```
