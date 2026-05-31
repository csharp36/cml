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
