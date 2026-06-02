#!/usr/bin/env bash
# Full benchmark: preflight -> smoke (run 0) -> interleaved N-loop -> analysis.
# Usage: run-all.sh [N]   (default N=10)
set -euo pipefail
BENCH="$(cd "$(dirname "$0")" && pwd)"
N="${1:-10}"
R="$BENCH/results"; mkdir -p "$R"

bash "$BENCH/preflight.sh"
echo "arm,run_id,pass,in_tokens,out_tokens,cache_read,cache_create,turns,wall_s,cost_usd,denied_attempts,is_error" > "$R/results.csv"

echo "== smoke (run 0) =="
for arm in semantic baseline; do bash "$BENCH/run-one.sh" "$arm" 0; done

echo "== interleaved batch (N=$N per arm) =="
for i in $(seq 1 "$N"); do
  for arm in semantic baseline; do
    bash "$BENCH/run-one.sh" "$arm" "$i"
  done
done

python3 "$BENCH/analyze.py" "$R/results.csv" | tee "$R/report.txt"
