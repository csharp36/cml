# Semantic vs. grep/find Benchmark — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an automated, defensible harness that runs Claude Code headless N×2 ways on one real hazelcast PR (#4317) — once with the Source Code Indexer MCP tools (grep/find blocked), once with standard bash discovery (MCP blocked) — and reports token/turn/wall-clock deltas gated on the PR's own tests.

**Architecture:** Each run is a hermetic `claude -p … --output-format json` invocation in a fresh git worktree at the PR's base commit + its test patch. A single `PreToolUse` hook is the per-run authority: allow-by-default (so no permission prompt ever stalls a headless run), deny the arm's forbidden discovery tools, and log every call (audit). The PR's scoped `mvn` test run is the correctness oracle. A driver interleaves arms; `analyze.py` produces nonparametric stats with bootstrap CIs.

**Tech Stack:** Bash, `jq`, Python 3 (stdlib only), Claude Code CLI 2.1.x (`claude -p`), the running Source Code Indexer (HTTP MCP at `localhost:8080`), hazelcast Maven build, Testcontainers-free.

**Spec:** `docs/superpowers/specs/2026-05-31-semantic-vs-grep-benchmark-design.md`

---

## Pinned constants (used throughout)

| Name | Value |
|---|---|
| Benchmark home | `/Users/csharpl/Projects/SourceCodeIndexerMCP/bench` |
| Local hazelcast clone | `/Users/csharpl/Projects/hazelcast` |
| Server hazelcast clone | `~/.source-code-indexer/repos/hazelcast` |
| PR | hazelcast #4317 (full commit `39c14ca464`) |
| Base SHA (worktree + index) | `b4d75e77eaa1` |
| Module | `hazelcast` |
| Oracle test classes | `InterceptorTest,EntryProcessorInterceptorTest,OffloadableEntryProcessorInterceptorTest` |
| Prod (gold) files | `hazelcast/src/main/java/com/hazelcast/map/MapInterceptor.java`, `.../map/impl/MapServiceContextInterceptorSupport.java`, `.../map/impl/operation/BasePutOperation.java` |
| Test files | `hazelcast/src/test/java/com/hazelcast/map/{InterceptorTest,EntryProcessorInterceptorTest,OffloadableEntryProcessorInterceptorTest}.java` |
| Indexer MCP URL | `http://localhost:8080/mcp` |
| MCP auth key (repos `["*"]`) | `bench-upload-key` |
| Admin token | `bench-admin` |
| DB monitor | `docker exec indexer-pg psql -U indexer -d source_code_index` |

`claude -p --output-format json` returns a final object with `.usage.{input_tokens,output_tokens,cache_read_input_tokens,cache_creation_input_tokens}`, `.num_turns`, `.is_error`, `.duration_ms`, `.session_id`, `.total_cost_usd`.

---

## File Structure (`bench/`)

- `bench/task/task-prompt.md` — the single identical behavioral prompt (no location hints).
- `bench/task/test_patch.diff` / `bench/task/gold_patch.diff` — extracted from PR #4317 (test-only / production-only).
- `bench/task/oracle.sh` — run the scoped `mvn` oracle on a worktree; exit 0 = pass.
- `bench/hooks/enforce-and-log.sh` — `PreToolUse` allow/deny/log hook (arm-aware via `BENCH_ARM`).
- `bench/config/settings.semantic.json` / `settings.baseline.json` — per-arm settings (hook wiring + `deny`).
- `bench/config/mcp.semantic.json` — indexer MCP server config (semantic arm only).
- `bench/setup-index.sh` — checkout server clone to base + reindex + assert `last_indexed_sha == base`.
- `bench/preflight.sh` — assert server up (new build) + index == base before a batch.
- `bench/run-one.sh` — one run: worktree → test_patch → RED-assert → `claude` → capture → oracle → results row.
- `bench/run-all.sh` — preflight → smoke → interleaved N-loop → `analyze.py`.
- `bench/analyze.py` — nonparametric stats, bootstrap CIs, success rates, report.
- `bench/results/` — per-run JSON/transcript/audit, `results.csv`, `report.txt` (gitignored).

---

## Task 1: Scaffold `bench/`, extract & verify patches, write the prompt

**Files:** Create `bench/.gitignore`, `bench/task/task-prompt.md`, `bench/task/test_patch.diff`, `bench/task/gold_patch.diff`.

- [ ] **Step 1: Create the directory + gitignore**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
mkdir -p bench/task bench/hooks bench/config bench/results bench/worktrees
cat > bench/.gitignore <<'EOF'
results/
worktrees/
EOF
```

- [ ] **Step 2: Extract the test patch and gold patch from PR #4317**

```bash
HZ=/Users/csharpl/Projects/hazelcast
BENCH=/Users/csharpl/Projects/SourceCodeIndexerMCP/bench
git -C "$HZ" diff b4d75e77eaa1 39c14ca464 -- \
  hazelcast/src/test/java/com/hazelcast/map/InterceptorTest.java \
  hazelcast/src/test/java/com/hazelcast/map/EntryProcessorInterceptorTest.java \
  hazelcast/src/test/java/com/hazelcast/map/OffloadableEntryProcessorInterceptorTest.java \
  > "$BENCH/task/test_patch.diff"
git -C "$HZ" diff b4d75e77eaa1 39c14ca464 -- \
  hazelcast/src/main/java/com/hazelcast/map/MapInterceptor.java \
  hazelcast/src/main/java/com/hazelcast/map/impl/MapServiceContextInterceptorSupport.java \
  hazelcast/src/main/java/com/hazelcast/map/impl/operation/BasePutOperation.java \
  > "$BENCH/task/gold_patch.diff"
wc -l "$BENCH/task/test_patch.diff" "$BENCH/task/gold_patch.diff"
```

Expected: both diffs non-empty (test_patch ~120+ lines, gold_patch ~100+ lines).

- [ ] **Step 3: Verify both patches apply cleanly on a base worktree**

```bash
HZ=/Users/csharpl/Projects/hazelcast
git -C "$HZ" worktree add --force --detach /tmp/hz-verify b4d75e77eaa1
git -C /tmp/hz-verify apply --check /Users/csharpl/Projects/SourceCodeIndexerMCP/bench/task/test_patch.diff && echo "test_patch applies"
git -C /tmp/hz-verify apply /Users/csharpl/Projects/SourceCodeIndexerMCP/bench/task/test_patch.diff
git -C /tmp/hz-verify apply --check /Users/csharpl/Projects/SourceCodeIndexerMCP/bench/task/gold_patch.diff && echo "gold_patch applies on top"
git -C "$HZ" worktree remove --force /tmp/hz-verify
```

Expected: `test_patch applies` and `gold_patch applies on top`.

- [ ] **Step 4: Write the task prompt**

Create `bench/task/task-prompt.md`:

```markdown
You are fixing a defect in the Hazelcast codebase, a large Java project. Your working
directory is the repository root.

## Requirement

A `MapInterceptor` must not be able to corrupt data stored in a map by mutating the
value object passed to its interceptor methods. Any mutation an interceptor makes to its
input value must NOT affect the value that is stored in the map.

## Definition of done

Failing tests in the repository already specify this behavior. Make them pass without
modifying any test file. Verify with exactly this command:

    mvn -pl hazelcast -am -Dtest=InterceptorTest,EntryProcessorInterceptorTest,OffloadableEntryProcessorInterceptorTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test

When those tests pass, you are done. Do not edit files under `src/test/`.
```

- [ ] **Step 5: Commit**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
git add bench/.gitignore bench/task/task-prompt.md bench/task/test_patch.diff bench/task/gold_patch.diff
git commit -m "bench: scaffold + PR #4317 test/gold patches + task prompt"
```

---

## Task 2: Oracle script + validate the instance is RED→GREEN (linchpin)

This proves the whole task instance is valid *and* warms the Maven build before any agent run.

**Files:** Create `bench/task/oracle.sh`.

- [ ] **Step 1: Write `oracle.sh`**

```bash
#!/usr/bin/env bash
# Run the scoped hazelcast oracle on a worktree. Exit 0 iff all named tests pass.
# Usage: oracle.sh <worktree-dir> <comma-separated-test-classes>
set -euo pipefail
WT="$1"; TESTS="$2"
cd "$WT"
MVN="mvn"; [[ -x ./mvnw ]] && MVN="./mvnw"
timeout 1800 "$MVN" -q -pl hazelcast -am \
  -Dtest="$TESTS" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

```bash
chmod +x /Users/csharpl/Projects/SourceCodeIndexerMCP/bench/task/oracle.sh
```

- [ ] **Step 2: RED check — oracle must FAIL at base + test_patch (no production change)**

```bash
HZ=/Users/csharpl/Projects/hazelcast
BENCH=/Users/csharpl/Projects/SourceCodeIndexerMCP/bench
git -C "$HZ" worktree add --force --detach /tmp/hz-red b4d75e77eaa1
git -C /tmp/hz-red apply "$BENCH/task/test_patch.diff"
if "$BENCH/task/oracle.sh" /tmp/hz-red "InterceptorTest,EntryProcessorInterceptorTest,OffloadableEntryProcessorInterceptorTest"; then
  echo "UNEXPECTED: oracle GREEN at base (test_patch not RED)"; else echo "OK: RED at base (exit $?)"; fi
```

Expected: `OK: RED at base` (the new assertions fail without the immutability fix). This first run also downloads Maven deps (slow, one-time).

- [ ] **Step 3: GREEN check — apply gold_patch, oracle must PASS**

```bash
BENCH=/Users/csharpl/Projects/SourceCodeIndexerMCP/bench
git -C /tmp/hz-red apply "$BENCH/task/gold_patch.diff"
if "$BENCH/task/oracle.sh" /tmp/hz-red "InterceptorTest,EntryProcessorInterceptorTest,OffloadableEntryProcessorInterceptorTest"; then
  echo "OK: GREEN with gold_patch"; else echo "PROBLEM: still RED with gold_patch"; fi
git -C /Users/csharpl/Projects/hazelcast worktree remove --force /tmp/hz-red
```

Expected: `OK: GREEN with gold_patch`. If this fails, STOP — the oracle/JDK/build is misconfigured (e.g. wrong JDK for this base commit); resolve before proceeding (the benchmark is meaningless without a working RED→GREEN oracle).

- [ ] **Step 4: Commit**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
git add bench/task/oracle.sh
git commit -m "bench: scoped mvn oracle + validated RED->GREEN for PR #4317"
```

---

## Task 3: The `PreToolUse` enforce-and-log hook + unit test

**Files:** Create `bench/hooks/enforce-and-log.sh`, `bench/hooks/test-hook.sh`.

- [ ] **Step 1: Write the failing hook test**

Create `bench/hooks/test-hook.sh`:

```bash
#!/usr/bin/env bash
# Feeds fake PreToolUse payloads to the hook and asserts allow/deny + logging.
set -euo pipefail
HOOK="$(cd "$(dirname "$0")" && pwd)/enforce-and-log.sh"
LOG=$(mktemp)
decision() { BENCH_ARM="$1" BENCH_AUDIT_LOG="$LOG" bash "$HOOK" <<<"$2" | jq -r '.hookSpecificOutput.permissionDecision'; }

# semantic arm: Grep denied, bash grep denied, Read allowed, mcp allowed
[[ $(decision semantic '{"tool_name":"Grep","tool_input":{}}') == deny ]] || { echo FAIL grep-tool; exit 1; }
[[ $(decision semantic '{"tool_name":"Bash","tool_input":{"command":"grep -r Foo ."}}') == deny ]] || { echo FAIL bash-grep; exit 1; }
[[ $(decision semantic '{"tool_name":"Bash","tool_input":{"command":"cat x | grep y"}}') == deny ]] || { echo FAIL pipe-grep; exit 1; }
[[ $(decision semantic '{"tool_name":"Bash","tool_input":{"command":"find . -name X"}}') == deny ]] || { echo FAIL find; exit 1; }
[[ $(decision semantic '{"tool_name":"Read","tool_input":{"file_path":"a"}}') == allow ]] || { echo FAIL read; exit 1; }
[[ $(decision semantic '{"tool_name":"mcp__source-code-indexer__search_symbols","tool_input":{}}') == allow ]] || { echo FAIL mcp-allow; exit 1; }
[[ $(decision semantic '{"tool_name":"Bash","tool_input":{"command":"mvn -q test"}}') == allow ]] || { echo FAIL mvn; exit 1; }

# baseline arm: grep allowed, mcp denied
[[ $(decision baseline '{"tool_name":"Bash","tool_input":{"command":"grep -r Foo ."}}') == allow ]] || { echo FAIL base-grep; exit 1; }
[[ $(decision baseline '{"tool_name":"mcp__source-code-indexer__search_symbols","tool_input":{}}') == deny ]] || { echo FAIL base-mcp; exit 1; }

# logging: every call recorded
[[ $(wc -l < "$LOG") -ge 9 ]] || { echo "FAIL log count $(wc -l < "$LOG")"; exit 1; }
echo "ALL HOOK TESTS PASS"
```

```bash
chmod +x /Users/csharpl/Projects/SourceCodeIndexerMCP/bench/hooks/test-hook.sh
```

- [ ] **Step 2: Run it — fails (hook missing)**

Run: `bash bench/hooks/test-hook.sh`
Expected: failure (`enforce-and-log.sh` does not exist).

- [ ] **Step 3: Write the hook**

Create `bench/hooks/enforce-and-log.sh`:

```bash
#!/usr/bin/env bash
# PreToolUse hook: allow-by-default (suppresses prompts), deny the arm's forbidden
# discovery tools, log every call to $BENCH_AUDIT_LOG. Arm via $BENCH_ARM.
set -euo pipefail
input=$(cat)
tool=$(printf '%s' "$input" | jq -r '.tool_name // ""')
cmd=$(printf '%s'  "$input" | jq -r '.tool_input.command // ""')
arm="${BENCH_ARM:-unknown}"
log="${BENCH_AUDIT_LOG:-/dev/null}"
ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)

decision="allow"; reason="permitted"
if [[ "$arm" == "semantic" ]]; then
  if [[ "$tool" == "Grep" || "$tool" == "Glob" ]]; then
    decision="deny"; reason="search tool '$tool' forbidden in semantic arm"
  elif [[ "$tool" == "Bash" ]] && printf '%s' "$cmd" \
        | grep -qE '(^|[;&|`]|[[:space:]])(grep|egrep|fgrep|rg|ag|ack|find)([[:space:]]|$)'; then
    decision="deny"; reason="shell search command forbidden in semantic arm"
  fi
elif [[ "$arm" == "baseline" ]]; then
  if [[ "$tool" == mcp__* ]]; then
    decision="deny"; reason="MCP tools forbidden in baseline arm"
  fi
fi

printf '{"ts":"%s","arm":"%s","tool":"%s","decision":"%s","cmd":%s}\n' \
  "$ts" "$arm" "$tool" "$decision" "$(printf '%s' "$cmd" | jq -R -s '.')" >> "$log"

jq -n --arg d "$decision" --arg r "$reason" \
  '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:$d,permissionDecisionReason:$r}}'
```

```bash
chmod +x /Users/csharpl/Projects/SourceCodeIndexerMCP/bench/hooks/enforce-and-log.sh
```

- [ ] **Step 4: Run the test — passes**

Run: `bash bench/hooks/test-hook.sh`
Expected: `ALL HOOK TESTS PASS`.

- [ ] **Step 5: Commit**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
git add bench/hooks/enforce-and-log.sh bench/hooks/test-hook.sh
git commit -m "bench: PreToolUse enforce-and-log hook + unit test"
```

---

## Task 4: Per-arm settings + MCP config + headless smoke (no-prompt / deny validation)

Validates the spec's "smoke run 0" mechanism on a throwaway prompt **before** any expensive real run.

**Files:** Create `bench/config/settings.semantic.json`, `bench/config/settings.baseline.json`, `bench/config/mcp.semantic.json`.

- [ ] **Step 1: Write the per-arm settings (absolute hook path)**

```bash
BENCH=/Users/csharpl/Projects/SourceCodeIndexerMCP/bench
HOOK="$BENCH/hooks/enforce-and-log.sh"
cat > "$BENCH/config/settings.semantic.json" <<EOF
{
  "permissions": { "deny": ["Grep", "Glob"] },
  "hooks": {
    "PreToolUse": [
      { "matcher": ".*", "hooks": [ { "type": "command", "command": "bash $HOOK" } ] }
    ]
  }
}
EOF
cat > "$BENCH/config/settings.baseline.json" <<EOF
{
  "permissions": { "deny": [] },
  "hooks": {
    "PreToolUse": [
      { "matcher": ".*", "hooks": [ { "type": "command", "command": "bash $HOOK" } ] }
    ]
  }
}
EOF
cat > "$BENCH/config/mcp.semantic.json" <<'EOF'
{ "mcpServers": { "source-code-indexer": {
    "type": "http", "url": "http://localhost:8080/mcp",
    "headers": { "Authorization": "Bearer bench-upload-key" } } } }
EOF
```

- [ ] **Step 2: Smoke the SEMANTIC arm — grep denied, MCP present, no stall, usage emitted**

```bash
BENCH=/Users/csharpl/Projects/SourceCodeIndexerMCP/bench
TMP=$(mktemp -d); AUDIT=$(mktemp)
cd "$TMP"
timeout 180 env BENCH_ARM=semantic BENCH_AUDIT_LOG="$AUDIT" claude \
  -p "Run 'grep -r foo .' to find foo, then tell me what tools you have available. Be brief." \
  --output-format json --permission-mode default \
  --settings "$BENCH/config/settings.semantic.json" \
  --strict-mcp-config --mcp-config "$BENCH/config/mcp.semantic.json" \
  > /tmp/smoke-sem.json 2>/tmp/smoke-sem.err || true
echo "exit ok; is_error=$(jq -r .is_error /tmp/smoke-sem.json) turns=$(jq -r .num_turns /tmp/smoke-sem.json) in=$(jq -r .usage.input_tokens /tmp/smoke-sem.json)"
echo "grep denied in audit? -> $(grep -c '"decision":"deny"' "$AUDIT")"
grep -q '"decision":"deny"' "$AUDIT" && echo "OK: grep was denied (no stall, usage present)"
```

Expected: a result JSON with `is_error=false`, a numeric `in` (usage present → no stall/hang), and ≥1 `deny` in the audit (grep blocked). If the run hung or `usage` is empty, the no-prompt mechanism needs adjustment (try adding `--permission-mode acceptEdits`); iterate here before continuing.

- [ ] **Step 3: Smoke the BASELINE arm — no MCP tools, grep allowed**

```bash
BENCH=/Users/csharpl/Projects/SourceCodeIndexerMCP/bench
TMP=$(mktemp -d); AUDIT=$(mktemp); cd "$TMP"
echo "hello" > marker.txt
timeout 180 env BENCH_ARM=baseline BENCH_AUDIT_LOG="$AUDIT" claude \
  -p "Run 'grep -r hello .' and report the file it matched. Be brief." \
  --output-format json --permission-mode default \
  --settings "$BENCH/config/settings.baseline.json" --strict-mcp-config \
  > /tmp/smoke-base.json 2>/tmp/smoke-base.err || true
echo "is_error=$(jq -r .is_error /tmp/smoke-base.json) turns=$(jq -r .num_turns /tmp/smoke-base.json)"
echo "grep allowed (no deny)? denies=$(grep -c '\"decision\":\"deny\"' "$AUDIT")"
grep -q "marker.txt" /tmp/smoke-base.json && echo "OK: baseline used grep successfully, no MCP"
```

Expected: `is_error=false`, 0 denies, and the result mentions `marker.txt` (grep worked; no MCP needed/available).

- [ ] **Step 4: Commit**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
git add bench/config/settings.semantic.json bench/config/settings.baseline.json bench/config/mcp.semantic.json
git commit -m "bench: per-arm settings + MCP config; validated headless no-prompt + deny"
```

---

## Task 5: Index alignment + preflight

**Files:** Create `bench/setup-index.sh`, `bench/preflight.sh`.

- [ ] **Step 1: Write `setup-index.sh`**

```bash
#!/usr/bin/env bash
# Point the server's hazelcast clone at the base SHA and reindex, so the semantic
# index reflects exactly the code the agent edits. Asserts last_indexed_sha == base.
set -euo pipefail
BASE="b4d75e77eaa1"
CLONE="$HOME/.source-code-indexer/repos/hazelcast"
ADMIN_TOKEN="${ADMIN_TOKEN:-bench-admin}"

git -C "$CLONE" checkout --quiet --detach "$BASE"
echo "clone HEAD now: $(git -C "$CLONE" rev-parse HEAD)"
curl -s -X POST http://localhost:8080/admin/repos/hazelcast/reindex \
  -H "Authorization: Bearer $ADMIN_TOKEN" -o /dev/null -w "reindex HTTP %{http_code}\n"

echo "waiting for full reindex at $BASE ..."
for i in $(seq 1 60); do
  sha=$(docker exec indexer-pg psql -U indexer -d source_code_index -tAc \
    "select last_indexed_sha from repositories where name='hazelcast'")
  case "$BASE" in "$sha"*) echo "OK: indexed at base ($sha)"; exit 0;; esac
  sleep 15
done
echo "TIMEOUT: last_indexed_sha=$sha != $BASE"; exit 1
```

```bash
chmod +x /Users/csharpl/Projects/SourceCodeIndexerMCP/bench/setup-index.sh
```

- [ ] **Step 2: Run it; confirm index == base**

Run: `ADMIN_TOKEN=bench-admin bash bench/setup-index.sh`
Expected: `OK: indexed at base (b4d75e77…)` (takes ~5 min — full reindex of 11k files at the base commit).

- [ ] **Step 3: Write `preflight.sh`**

```bash
#!/usr/bin/env bash
# Assert the environment is ready for a batch: server is the new build (uploads route
# live) and the semantic index is at base.
set -euo pipefail
BASE="b4d75e77eaa1"
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/api/scip/hazelcast/uploads || true)
[[ "$code" == "401" ]] || { echo "PREFLIGHT FAIL: server not the new build (/uploads=$code)"; exit 1; }
sha=$(docker exec indexer-pg psql -U indexer -d source_code_index -tAc \
  "select last_indexed_sha from repositories where name='hazelcast'")
case "$BASE" in "$sha"*) : ;; *) echo "PREFLIGHT FAIL: index at $sha, not base $BASE (run setup-index.sh)"; exit 1;; esac
command -v jq >/dev/null || { echo "PREFLIGHT FAIL: jq required"; exit 1; }
echo "PREFLIGHT OK: server up, index at base, jq present"
```

```bash
chmod +x /Users/csharpl/Projects/SourceCodeIndexerMCP/bench/preflight.sh
bash /Users/csharpl/Projects/SourceCodeIndexerMCP/bench/preflight.sh
```

Expected: `PREFLIGHT OK`.

- [ ] **Step 4: Commit**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
git add bench/setup-index.sh bench/preflight.sh
git commit -m "bench: index-at-base setup + preflight checks"
```

---

## Task 6: `run-one.sh` + one real run per arm (spec smoke run 0)

**Files:** Create `bench/run-one.sh`.

- [ ] **Step 1: Write `run-one.sh`**

```bash
#!/usr/bin/env bash
# One benchmark run. Usage: run-one.sh <semantic|baseline> <run-id>
set -uo pipefail
ARM="$1"; RUN_ID="$2"
BENCH="$(cd "$(dirname "$0")" && pwd)"
HZ="/Users/csharpl/Projects/hazelcast"
BASE="b4d75e77eaa1"
TESTS="InterceptorTest,EntryProcessorInterceptorTest,OffloadableEntryProcessorInterceptorTest"
R="$BENCH/results"; mkdir -p "$R" "$BENCH/worktrees"
WT="$BENCH/worktrees/${ARM}-${RUN_ID}"
AUDIT="$R/audit-${ARM}-${RUN_ID}.jsonl"; : > "$AUDIT"
RES="$R/result-${ARM}-${RUN_ID}.json"

cleanup() { git -C "$HZ" worktree remove --force "$WT" 2>/dev/null || true; }
trap cleanup EXIT

# 1. fresh worktree at base + test_patch
git -C "$HZ" worktree add --force --detach "$WT" "$BASE" >/dev/null 2>&1
git -C "$WT" apply "$BENCH/task/test_patch.diff"

# 2. assert RED at base
if "$BENCH/task/oracle.sh" "$WT" "$TESTS" >/dev/null 2>&1; then
  echo "[$ARM/$RUN_ID] ABORT: oracle GREEN before run (bad worktree)"; exit 3; fi

# 3. arm-specific args
ARGS=( -p "$(cat "$BENCH/task/task-prompt.md")" --output-format json
       --permission-mode default --settings "$BENCH/config/settings.${ARM}.json"
       --strict-mcp-config --add-dir "$WT" )
[[ "$ARM" == "semantic" ]] && ARGS+=( --mcp-config "$BENCH/config/mcp.semantic.json" )

# 4. run the agent (timed; cwd = worktree for hermetic config)
START=$(python3 -c 'import time;print(time.time())')
( cd "$WT" && BENCH_ARM="$ARM" BENCH_AUDIT_LOG="$AUDIT" \
    claude "${ARGS[@]}" ) > "$RES" 2> "$R/stderr-${ARM}-${RUN_ID}.log"
END=$(python3 -c 'import time;print(time.time())')
WALL=$(python3 -c "print(f'{$END-$START:.2f}')")

# 5. oracle: GREEN now?
if "$BENCH/task/oracle.sh" "$WT" "$TESTS" > "$R/oracle-${ARM}-${RUN_ID}.log" 2>&1; then PASS=1; else PASS=0; fi

# 6. metrics
IN=$(jq -r '.usage.input_tokens // 0' "$RES" 2>/dev/null || echo 0)
OUT=$(jq -r '.usage.output_tokens // 0' "$RES" 2>/dev/null || echo 0)
CR=$(jq -r '.usage.cache_read_input_tokens // 0' "$RES" 2>/dev/null || echo 0)
CC=$(jq -r '.usage.cache_creation_input_tokens // 0' "$RES" 2>/dev/null || echo 0)
TURNS=$(jq -r '.num_turns // 0' "$RES" 2>/dev/null || echo 0)
ERR=$(jq -r '.is_error // false' "$RES" 2>/dev/null || echo true)
DENIES=$(grep -c '"decision":"deny"' "$AUDIT" 2>/dev/null || echo 0)

echo "${ARM},${RUN_ID},${PASS},${IN},${OUT},${CR},${CC},${TURNS},${WALL},${DENIES},${ERR}" >> "$R/results.csv"
echo "[$ARM/$RUN_ID] pass=$PASS in=$IN out=$OUT turns=$TURNS wall=${WALL}s denied_attempts=$DENIES is_error=$ERR"
```

```bash
chmod +x /Users/csharpl/Projects/SourceCodeIndexerMCP/bench/run-one.sh
```

- [ ] **Step 2: One real run per arm (the spec's smoke run 0)**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
bash bench/preflight.sh
echo "arm,run_id,pass,in_tokens,out_tokens,cache_read,cache_create,turns,wall_s,denied_attempts,is_error" > bench/results/results.csv
bash bench/run-one.sh semantic 0
bash bench/run-one.sh baseline 0
column -s, -t bench/results/results.csv
```

Expected: two rows with `pass=1` (both arms should solve a real, well-scoped fix), non-zero tokens/turns, and — critically — the **semantic** row shows `denied_attempts ≥ 0` with NO successful grep/find in its transcript, while the **baseline** row shows `denied_attempts=0`. If `pass=0` for both, inspect `bench/results/oracle-*-0.log` (likely a build/JDK issue surfaced earlier in Task 2).

- [ ] **Step 3: Compliance spot-check**

```bash
echo "=== semantic arm: any forbidden tool that EXECUTED? (should be none) ==="
grep '"decision":"allow"' bench/results/audit-semantic-0.jsonl | grep -iE '"tool":"(Grep|Glob)"|grep |find |rg ' || echo "clean: no forbidden tool executed in semantic arm"
```

Expected: `clean: no forbidden tool executed in semantic arm`.

- [ ] **Step 4: Commit**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
git add bench/run-one.sh
git commit -m "bench: single-run harness; validated one real run per arm"
```

---

## Task 7: `run-all.sh` (interleaved batch driver)

**Files:** Create `bench/run-all.sh`.

- [ ] **Step 1: Write `run-all.sh`**

```bash
#!/usr/bin/env bash
# Full benchmark: preflight -> smoke (run 0) -> interleaved N-loop -> analysis.
# Usage: run-all.sh [N]   (default N=10)
set -euo pipefail
BENCH="$(cd "$(dirname "$0")" && pwd)"
N="${1:-10}"
R="$BENCH/results"; mkdir -p "$R"

bash "$BENCH/preflight.sh"
echo "arm,run_id,pass,in_tokens,out_tokens,cache_read,cache_create,turns,wall_s,denied_attempts,is_error" > "$R/results.csv"

echo "== smoke (run 0) =="
for arm in semantic baseline; do bash "$BENCH/run-one.sh" "$arm" 0; done

echo "== interleaved batch (N=$N per arm) =="
for i in $(seq 1 "$N"); do
  for arm in semantic baseline; do
    bash "$BENCH/run-one.sh" "$arm" "$i"
  done
done

python3 "$BENCH/analyze.py" "$R/results.csv" | tee "$R/report.txt"
```

```bash
chmod +x /Users/csharpl/Projects/SourceCodeIndexerMCP/bench/run-all.sh
```

- [ ] **Step 2: Dry-run with N=1 to validate the loop (not the full experiment)**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
bash bench/run-all.sh 1   # smoke + 1 interleaved pair = 4 runs total
wc -l bench/results/results.csv   # expect 5 lines (header + 4 runs)
```

Expected: 4 data rows (semantic/baseline × {0,1}); the script proceeds into `analyze.py` (built next; if absent, this errors at the last line only — acceptable for this dry run, the rows are what we validate).

- [ ] **Step 3: Commit**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
git add bench/run-all.sh
git commit -m "bench: interleaved batch driver"
```

---

## Task 8: `analyze.py` — nonparametric stats + report

**Files:** Create `bench/analyze.py`.

- [ ] **Step 1: Write the failing test (synthetic CSV → report)**

```bash
cat > /tmp/fake-results.csv <<'EOF'
arm,run_id,pass,in_tokens,out_tokens,cache_read,cache_create,turns,wall_s,denied_attempts,is_error
semantic,1,1,1000,200,0,0,8,30.0,0,false
semantic,2,1,1100,210,0,0,9,33.0,0,false
semantic,3,1,1050,205,0,0,8,31.0,0,false
baseline,1,1,4000,260,0,0,18,55.0,0,false
baseline,2,1,4200,270,0,0,19,58.0,0,false
baseline,3,0,3900,250,0,0,17,53.0,0,false
EOF
python3 bench/analyze.py /tmp/fake-results.csv && echo "RAN" || echo "NOT YET"
```

Expected: `NOT YET` (file missing).

- [ ] **Step 2: Write `analyze.py`**

```python
#!/usr/bin/env python3
"""Nonparametric analysis of benchmark results. Usage: analyze.py results.csv"""
import csv, sys, statistics as st, random

random.seed(12345)  # deterministic bootstrap

def load(path):
    rows = []
    with open(path) as f:
        for r in csv.DictReader(f):
            for k in ("pass","in_tokens","out_tokens","cache_read","cache_create","turns","denied_attempts"):
                r[k] = int(float(r[k]))
            r["wall_s"] = float(r["wall_s"])
            r["total_tokens"] = r["in_tokens"] + r["out_tokens"]
            rows.append(r)
    return rows

def med(xs): return st.median(xs) if xs else float("nan")
def iqr(xs):
    if len(xs) < 2: return (float("nan"), float("nan"))
    q = st.quantiles(xs, n=4); return (q[0], q[2])

def boot_ci(a, b, reps=10000):
    # 95% CI on median(b) - median(a)  (baseline - semantic): positive => semantic cheaper
    if not a or not b: return (float("nan"), float("nan"))
    diffs = []
    for _ in range(reps):
        ra = [random.choice(a) for _ in a]
        rb = [random.choice(b) for _ in b]
        diffs.append(st.median(rb) - st.median(ra))
    diffs.sort()
    lo = diffs[int(0.025*reps)]; hi = diffs[int(0.975*reps)]
    return (lo, hi)

def main():
    rows = load(sys.argv[1])
    arms = {"semantic": [r for r in rows if r["arm"]=="semantic"],
            "baseline": [r for r in rows if r["arm"]=="baseline"]}
    print("="*64)
    print("BENCHMARK REPORT — semantic indexer vs grep/find (hazelcast PR #4317)")
    print("="*64)
    for name, rs in arms.items():
        n = len(rs); passed = sum(r["pass"] for r in rs)
        rate = (passed/n*100) if n else float("nan")
        print(f"\n[{name}]  runs={n}  passed={passed}  success_rate={rate:.0f}%")
    metrics = [("total_tokens","tokens"),("in_tokens","input tok"),
               ("out_tokens","output tok"),("turns","turns"),("wall_s","wall s")]
    print("\n--- successful runs only (median [IQR]) ---")
    for key,label in metrics:
        s = [r[key] for r in arms["semantic"] if r["pass"]==1]
        b = [r[key] for r in arms["baseline"] if r["pass"]==1]
        ms, mb = med(s), med(b)
        lo, hi = boot_ci(s, b)
        delta = (mb-ms)
        pct = (delta/mb*100) if mb else float("nan")
        s_iqr, b_iqr = iqr(s), iqr(b)
        print(f"{label:>10}: semantic {ms:>9.1f} [{s_iqr[0]:.0f},{s_iqr[1]:.0f}]  "
              f"baseline {mb:>9.1f} [{b_iqr[0]:.0f},{b_iqr[1]:.0f}]  "
              f"baseline-semantic={delta:>+9.1f} ({pct:+.0f}%)  95%CI[{lo:.0f},{hi:.0f}]")
    # compliance
    bad = [r for r in arms["semantic"] if r["denied_attempts"]>0]
    print(f"\ncompliance: semantic runs with >=1 blocked discovery attempt = {len(bad)} "
          f"(blocked, not executed — expected when the agent reaches for grep)")
    print("\nNote: single-task benchmark — result holds for PR #4317, not as a general law.")

if __name__ == "__main__":
    main()
```

```bash
chmod +x /Users/csharpl/Projects/SourceCodeIndexerMCP/bench/analyze.py
```

- [ ] **Step 3: Run on the synthetic CSV**

Run: `python3 bench/analyze.py /tmp/fake-results.csv`
Expected: a report showing semantic median tokens 1050 vs baseline 4000 with a positive `baseline-semantic` delta and a 95% CI, semantic success_rate=100% vs baseline 67%.

- [ ] **Step 4: Commit**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
git add bench/analyze.py
git commit -m "bench: nonparametric analysis + bootstrap-CI report"
```

---

## Task 9: README + finalize

**Files:** Create `bench/README.md`.

- [ ] **Step 1: Write `bench/README.md`**

```markdown
# Semantic-indexer vs grep/find benchmark

Compares Claude Code implementing hazelcast PR #4317 with the Source Code Indexer MCP
tools (grep/find blocked) vs standard bash discovery (MCP blocked). See the design spec
at `docs/superpowers/specs/2026-05-31-semantic-vs-grep-benchmark-design.md`.

## Prerequisites
- The indexer server running the multi-part build on :8080 (env CI_UPLOAD_KEY=bench-upload-key,
  ADMIN_TOKEN=bench-admin), hazelcast registered.
- Local hazelcast clone at /Users/csharpl/Projects/hazelcast.
- `claude` CLI, `jq`, `python3`, a JDK/Maven that builds hazelcast at the base commit.

## Run
    ADMIN_TOKEN=bench-admin bash bench/setup-index.sh   # index hazelcast at base (~5 min, once)
    bash bench/run-all.sh 10                            # smoke + 10 interleaved pairs

Results land in `bench/results/` (gitignored): `results.csv`, `report.txt`, per-run
`result-*.json` / `audit-*.jsonl` / `oracle-*.log`.

## What each metric means
- tokens / turns: from each run's `--output-format json` usage; the efficiency signal.
- wall_s: agent wall-clock only (oracle verification excluded).
- pass: the PR's own tests go GREEN (correctness gate).
- denied_attempts: forbidden discovery tools the agent reached for (blocked, audited).
```

- [ ] **Step 2: Commit**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
git add bench/README.md
git commit -m "bench: README"
```

---

## Task 10: Execute the experiment (the payload)

This is the expensive run (~20 agent sessions + 20+ hazelcast test builds). It is the *experiment*, not harness construction — run it deliberately.

- [ ] **Step 1: Ensure the environment is aligned**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
ADMIN_TOKEN=bench-admin bash bench/setup-index.sh   # index at base
bash bench/preflight.sh
```

Expected: `OK: indexed at base` then `PREFLIGHT OK`.

- [ ] **Step 2: Run the full benchmark**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
time bash bench/run-all.sh 10
```

Expected: 22 result rows (smoke + 10×2) and a printed report. Interleaving (A,B per i) spreads any API-latency drift across both arms.

- [ ] **Step 3: Sanity-check + archive the report**

```bash
column -s, -t bench/results/results.csv | head
cat bench/results/report.txt
cp bench/results/report.txt "docs/superpowers/benchmark-report-$(git -C /Users/csharpl/Projects/hazelcast rev-parse --short HEAD 2>/dev/null || echo run).txt" 2>/dev/null || true
```

Verify: both arms have a sensible success rate; if either arm is mostly `pass=0`, investigate the oracle/build before trusting the token deltas. Confirm no semantic run executed a forbidden tool (compliance line in the report).

- [ ] **Step 4: Restore the server index (teardown)**

```bash
git -C ~/.source-code-indexer/repos/hazelcast checkout --quiet master 2>/dev/null \
  || git -C ~/.source-code-indexer/repos/hazelcast checkout --quiet --detach 3647cd76f35c
curl -s -X POST http://localhost:8080/admin/repos/hazelcast/reindex \
  -H "Authorization: Bearer bench-admin" -o /dev/null -w "reindex back to master: %{http_code}\n"
```

- [ ] **Step 5: Commit the archived report (results/ itself stays gitignored)**

```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
git add docs/superpowers/benchmark-report-*.txt 2>/dev/null || true
git commit -m "bench: experiment report for PR #4317 (semantic vs grep)" || echo "nothing to commit"
```

---

## Notes / Known risks

- **JDK/Maven for hazelcast at base `b4d75e77eaa1`:** if the oracle won't build (wrong JDK), Task 2 surfaces it immediately — fix the toolchain before any agent run. Hazelcast core + `-am` deps download on the first build (slow, one-time; cached after).
- **No-prompt mechanism:** validated empirically in Task 4. If a future CLI version changes hook semantics and runs stall, add `--permission-mode acceptEdits` (belt) — the hook still enforces deny + logs.
- **Single task:** the result is specific to PR #4317 (stated in the report). Expanding to a suite is a separate effort (spec "Out of Scope").
- **Oracle strength:** `analyze.py` reports success rate; optionally extend `oracle.sh` to also run the module's pre-existing interceptor tests to catch regressions (a passing hack), per the spec.
