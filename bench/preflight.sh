#!/usr/bin/env bash
# Assert the environment is ready for a batch: server is the new build (uploads route
# live) and the semantic index is at base.
set -euo pipefail
BASE="b4d75e77eaa1"
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/api/scip/hazelcast/uploads || true)
[[ "$code" == "401" ]] || { echo "PREFLIGHT FAIL: server not the new build (/uploads=$code)"; exit 1; }
sha=$(docker exec indexer-pg psql -U indexer -d source_code_index -tAc \
  "select last_indexed_sha from repositories where name='hazelcast'")
case "$BASE" in "$sha"*) : ;; *) echo "PREFLIGHT FAIL: index at $sha, not base $BASE (run setup-index.sh)"; exit 1;; esac
command -v jq >/dev/null || { echo "PREFLIGHT FAIL: jq required"; exit 1; }
echo "PREFLIGHT OK: server up, index at base, jq present"
