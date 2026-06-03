#!/usr/bin/env bash
# Usage: (source pin.env) run_index_scip.sh <questions.jsonl> > scip.results.jsonl
set -euo pipefail
Q="$1"; HERE="$(dirname "$0")"; : "${PIN_REF:?source pin.env}"
while IFS= read -r line; do
  id=$(jq -r .id <<<"$line"); name=$(jq -r .type_simple <<<"$line")
  args=$(jq -cn --arg n "$name" --arg ref "$PIN_REF" '{repo:"hazelcast",symbol_name:$n,direction:"down",branch:$ref,depth:25}')
  resp=$(python3 "$HERE/mcp_call.py" get_type_hierarchy "$args")
  simple=$(jq -c '[(.subtypes // []) | .. | objects | select((.file_path//"")|contains("/src/main/")) | .symbol] | map(select(.!=null)) | unique' <<<"$resp")
  fqn=$(jq -c '[(.subtypes // []) | .. | objects | select((.file_path//"")|contains("/src/main/")) | .scip_symbol] | map(select(.!=null)) | unique' <<<"$resp")
  jq -cn --arg id "$id" --argjson s "${simple:-[]}" --argjson q "${fqn:-[]}" '{id:$id, arm:"index_scip", found_simple:$s, found_fqn:$q, calls:1}'
done < "$Q"
