#!/usr/bin/env bash
# Point the server's hazelcast clone at the base SHA and reindex, so the semantic
# index reflects exactly the code the agent edits. Asserts last_indexed_sha == base.
set -euo pipefail
BASE="b4d75e77eaa1"
CLONE="$HOME/.source-code-indexer/repos/hazelcast"
ADMIN_TOKEN="${ADMIN_TOKEN:-bench-admin}"

git -C "$CLONE" checkout --quiet --detach "$BASE"
echo "clone HEAD now: $(git -C "$CLONE" rev-parse HEAD)"
curl -s -X POST http://localhost:8080/admin/repos/hazelcast/reindex \
  -H "Authorization: Bearer $ADMIN_TOKEN" -o /dev/null -w "reindex HTTP %{http_code}\n"

echo "waiting for full reindex at $BASE ..."
for i in $(seq 1 60); do
  sha=$(docker exec indexer-pg psql -U indexer -d source_code_index -tAc \
    "select last_indexed_sha from repositories where name='hazelcast'")
  case "$BASE" in "$sha"*) echo "OK: indexed at base ($sha)"; exit 0;; esac
  sleep 15
done
echo "TIMEOUT: last_indexed_sha=$sha != $BASE"; exit 1
