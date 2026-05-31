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

git -C "$HZ" worktree add --force --detach "$WT" "$BASE" >/dev/null 2>&1
git -C "$WT" apply "$BENCH/task/test_patch.diff"

if "$BENCH/task/oracle.sh" "$WT" "$TESTS" >/dev/null 2>&1; then
  echo "[$ARM/$RUN_ID] ABORT: oracle GREEN before run (bad worktree)"; exit 3; fi

ARGS=( -p "$(cat "$BENCH/task/task-prompt.md")" --output-format json
       --permission-mode default --settings "$BENCH/config/settings.${ARM}.json"
       --strict-mcp-config --add-dir "$WT" )
[[ "$ARM" == "semantic" ]] && ARGS+=( --mcp-config "$BENCH/config/mcp.semantic.json" )

START=$(python3 -c 'import time;print(time.time())')
( cd "$WT" && BENCH_ARM="$ARM" BENCH_AUDIT_LOG="$AUDIT" \
    claude "${ARGS[@]}" ) > "$RES" 2> "$R/stderr-${ARM}-${RUN_ID}.log"
END=$(python3 -c 'import time;print(time.time())')
WALL=$(python3 -c "print(f'{$END-$START:.2f}')")

if "$BENCH/task/oracle.sh" "$WT" "$TESTS" > "$R/oracle-${ARM}-${RUN_ID}.log" 2>&1; then PASS=1; else PASS=0; fi

IN=$(jq -r '.usage.input_tokens // 0' "$RES" 2>/dev/null || echo 0)
OUT=$(jq -r '.usage.output_tokens // 0' "$RES" 2>/dev/null || echo 0)
CR=$(jq -r '.usage.cache_read_input_tokens // 0' "$RES" 2>/dev/null || echo 0)
CC=$(jq -r '.usage.cache_creation_input_tokens // 0' "$RES" 2>/dev/null || echo 0)
TURNS=$(jq -r '.num_turns // 0' "$RES" 2>/dev/null || echo 0)
ERR=$(jq -r '.is_error // false' "$RES" 2>/dev/null || echo true)
DENIES=$(grep -c '"decision":"deny"' "$AUDIT" 2>/dev/null || echo 0)

echo "${ARM},${RUN_ID},${PASS},${IN},${OUT},${CR},${CC},${TURNS},${WALL},${DENIES},${ERR}" >> "$R/results.csv"
echo "[$ARM/$RUN_ID] pass=$PASS in=$IN out=$OUT turns=$TURNS wall=${WALL}s denied_attempts=$DENIES is_error=$ERR"
