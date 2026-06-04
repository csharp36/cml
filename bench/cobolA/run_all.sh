#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
test -d corpus || { echo "corpus missing — run ./clone_corpus.sh first" >&2; exit 1; }
mkdir -p results
echo "== grep arm ==" >&2
python3 arms/run_grep.py corpus questions.jsonl > results/grep.results.jsonl
echo "== proxy arm ==" >&2
python3 arms/run_proxy.py oracle/oracle.json questions.jsonl > results/proxy.results.jsonl
echo "== score ==" >&2
python3 score.py results/grep.results.jsonl results/proxy.results.jsonl
