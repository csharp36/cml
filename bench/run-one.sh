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

git -C "$HZ" worktree add --force --detach "$WT" "$BASE" >/dev/null 2>&1 \
  || { echo "[$ARM/$RUN_ID] ABORT: worktree add failed"; exit 5; }
git -C "$WT" apply "$BENCH/task/test_patch.diff" \
  || { echo "[$ARM/$RUN_ID] ABORT: test_patch apply failed"; exit 4; }

if "$BENCH/task/oracle.sh" "$WT" "$TESTS" >/dev/null 2>&1; then
  echo "[$ARM/$RUN_ID] ABORT: oracle GREEN before run (bad worktree)"; exit 3; fi

# Isolation from the operator's personal Claude env so results are hermetic +
# reproducible (see bench/README): --setting-sources project,local drops the
# global ~/.claude/settings.json SessionStart hooks (gsd, etc.); the worktree has
# no project/local settings, so only the arm's --settings file loads. OAuth/keychain
# auth is preserved (we deliberately do NOT use --bare, which forces ANTHROPIC_API_KEY).
ARGS=( -p "$(cat "$BENCH/task/task-prompt.md")" --output-format json
       --permission-mode default --settings "$BENCH/config/settings.${ARM}.json"
       --setting-sources project,local --disable-slash-commands
       --strict-mcp-config --add-dir "$WT" )
[[ "$ARM" == "semantic" ]] && ARGS+=( --mcp-config "$BENCH/config/mcp.semantic.json" )

# Make the agent's OWN `mvn test` runs deterministic (same flags as oracle.sh).
# Maven 3.9+ prepends MAVEN_ARGS to every invocation, so the agent stops seeing
# intermittent failures and chasing phantom races (the n=1 semantic thrash cause).
# Applied to BOTH arms for fairness.
MVN_DETERMINISM="-DforkCount=1 -DreuseForks=true -Dsurefire.rerunFailingTestsCount=2"
START=$(python3 -c 'import time;print(time.time())')
( cd "$WT" && BENCH_ARM="$ARM" BENCH_AUDIT_LOG="$AUDIT" MAVEN_ARGS="$MVN_DETERMINISM" \
    claude "${ARGS[@]}" ) > "$RES" 2> "$R/stderr-${ARM}-${RUN_ID}.log"
END=$(python3 -c 'import time;print(time.time())')
WALL=$(python3 -c "print(f'{$END-$START:.2f}')")

if "$BENCH/task/oracle.sh" "$WT" "$TESTS" > "$R/oracle-${ARM}-${RUN_ID}.log" 2>&1; then PASS=1; else PASS=0; fi

if jq -e . "$RES" >/dev/null 2>&1; then
  IN=$(jq -r '.usage.input_tokens // 0' "$RES")
  OUT=$(jq -r '.usage.output_tokens // 0' "$RES")
  CR=$(jq -r '.usage.cache_read_input_tokens // 0' "$RES")
  CC=$(jq -r '.usage.cache_creation_input_tokens // 0' "$RES")
  TURNS=$(jq -r '.num_turns // 0' "$RES")
  COST=$(jq -r '.total_cost_usd // 0' "$RES")
  ERR=$(jq -r '.is_error // false' "$RES")
else
  IN=0; OUT=0; CR=0; CC=0; TURNS=0; COST=0; ERR=crashed; PASS=0
fi
DENIES=$(grep -c '"decision":"deny"' "$AUDIT" 2>/dev/null || true); DENIES=${DENIES:-0}

echo "${ARM},${RUN_ID},${PASS},${IN},${OUT},${CR},${CC},${TURNS},${WALL},${COST},${DENIES},${ERR}" >> "$R/results.csv"
echo "[$ARM/$RUN_ID] pass=$PASS in=$IN out=$OUT turns=$TURNS wall=${WALL}s denied_attempts=$DENIES is_error=$ERR"
