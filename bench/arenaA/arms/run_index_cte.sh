#!/usr/bin/env bash
# Usage: (source pin.env) run_index_cte.sh <questions.jsonl> > cte.results.jsonl
set -euo pipefail
Q="$1"; HERE="$(dirname "$0")"; : "${PIN_REF:?source pin.env}"
while IFS= read -r line; do
  id=$(jq -r .id <<<"$line"); name=$(jq -r .type_simple <<<"$line")
  args=$(jq -cn --arg n "$name" --arg ref "$PIN_REF" '{type_name:$n,repo:"hazelcast",branch:$ref,transitive:true}')
  resp=$(python3 "$HERE/mcp_call.py" find_implementations "$args")
  found=$(jq -c '[(.results // .) | .[]? | select((.file_path//"")|contains("/src/main/")) | .class_name] | unique' <<<"$resp")
  jq -cn --arg id "$id" --argjson f "${found:-[]}" '{id:$id, arm:"index_cte", found_simple:$f, found_fqn:null, calls:1}'
done < "$Q"
