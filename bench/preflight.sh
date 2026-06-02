#!/usr/bin/env bash
# Assert the environment is ready for a batch: server is the new build (uploads route
# live) and the semantic index is at base.
set -euo pipefail
# Expected index base SHA prefix. Default = impl-benchmark base; the discovery benchmark
# re-indexes at the 2020-06 median and overrides via INDEX_BASE_SHA.
BASE="${INDEX_BASE_SHA:-b4d75e77eaa1}"
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/api/scip/hazelcast/uploads || true)
[[ "$code" == "401" ]] || { echo "PREFLIGHT FAIL: server not the new build (/uploads=$code)"; exit 1; }
sha=$(docker exec indexer-pg psql -U indexer -d source_code_index -tAc \
  "select last_indexed_sha from repositories where name='hazelcast'")
case "$sha" in "$BASE"*) : ;; *) echo "PREFLIGHT FAIL: index at $sha, not base $BASE (run setup-index.sh)"; exit 1;; esac
command -v jq >/dev/null || { echo "PREFLIGHT FAIL: jq required"; exit 1; }
echo "PREFLIGHT OK: server up, index at base, jq present"
