#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
set -a; source pin.env; set +a
mkdir -p results
arms/run_index_cte.sh questions.jsonl  > results/cte.results.jsonl
arms/run_index_scip.sh questions.jsonl > results/scip.results.jsonl
python3 arms/run_grep.py "$HZ_WORKTREE" questions.jsonl > results/grep.results.jsonl
python3 score.py results/grep.results.jsonl results/cte.results.jsonl results/scip.results.jsonl
