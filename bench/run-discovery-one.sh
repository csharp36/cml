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
