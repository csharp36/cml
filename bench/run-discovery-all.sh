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
# Uses a proper MCP Streamable-HTTP handshake (prewarm.py) — a raw tools/call POST is rejected
# by the transport (needs the initialize handshake + mcp-session-id), so the old curl warmed
# nothing. Backward refs (older base shas) can take minutes to build a large overlay.
echo "== pre-warming index overlays =="
while read -r line; do
  base=$(printf '%s' "$line" | python3 -c 'import sys,json;print(json.load(sys.stdin)["base_sha"])')
  python3 "$BENCH/discovery/prewarm.py" "$base" || echo "  WARN: warm failed $base"
done < "$INSTANCES"

echo "arm,instance_id,answered,file_f1,symbol_f1,judge_score,in_tokens,out_tokens,cache_read,cache_create,turns,wall_s,cost_usd,denied_attempts,is_error" > "$CSV"

ids=$(python3 -c 'import sys,json;[print(json.loads(l)["id"]) for l in open(sys.argv[1]) if l.strip()]' "$INSTANCES")
first=$(echo "$ids" | head -1)

# --- spend circuit-breaker -------------------------------------------------
# Hard cap on cumulative agent spend (cost_usd, CSV col 13). Set DISC_BUDGET_USD
# to override (default 40). NOTE: the LLM judge is a separate claude call NOT in
# the CSV, so true spend runs ~$1-2 above this cap across a full batch — pick the
# cap with that headroom in mind.
BUDGET="${DISC_BUDGET_USD:-40}"
spent() { awk -F, 'NR>1{s+=$13} END{printf "%.4f", s+0}' "$CSV"; }
guard() {
  local s; s=$(spent)
  if awk "BEGIN{exit !($s+0 >= $BUDGET+0)}"; then
    echo "!! BUDGET CAP HIT: agent spend \$$s >= cap \$$BUDGET — aborting before next run."
    echo "   (judge calls add extra on top; raise DISC_BUDGET_USD to continue.)"
    python3 "$BENCH/discovery/analyze_discovery.py" "$CSV" | tee "$R/discovery-report.txt" || true
    exit 7
  fi
  echo "   [budget] spent \$$s / cap \$$BUDGET"
}

# one bad instance must not abort the whole unattended batch (set -e)
run() { guard; bash "$BENCH/run-discovery-one.sh" "$1" "$2" || echo "  WARN: run $1/$2 failed (exit $?)"; }

echo "== smoke (first instance, both arms) =="
for arm in semantic baseline; do run "$arm" "$first"; done

# batch covers the REMAINING instances (the smoke already ran $first; don't double-count it)
echo "== batch (remaining instances, both arms) =="
for id in $ids; do
  [[ "$id" == "$first" ]] && continue
  for arm in semantic baseline; do run "$arm" "$id"; done
done

python3 "$BENCH/discovery/analyze_discovery.py" "$CSV" | tee "$R/discovery-report.txt"
