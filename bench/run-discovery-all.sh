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
