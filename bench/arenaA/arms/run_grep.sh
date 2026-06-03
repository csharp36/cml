#!/usr/bin/env bash
# Usage: run_grep.sh <src_dir> <questions.jsonl> > grep.results.jsonl
set -euo pipefail
SRC="$1"; Q="$2"
while IFS= read -r line; do
  id=$(jq -r .id <<<"$line"); seed=$(jq -r .type_simple <<<"$line")
  declare -A seen=(); frontier=("$seed"); calls=0
  while [ ${#frontier[@]} -gt 0 ]; do
    nxt=(); for name in "${frontier[@]}"; do
      [ -z "$name" ] && continue
      [ -n "${seen[$name]:-}" ] && continue; seen[$name]=1
      calls=$((calls+1))
      while IFS= read -r hit; do
        cls=$(sed -nE 's/.*(class|interface)[[:space:]]+([A-Za-z0-9_]+).*/\2/p' <<<"$hit")
        [ -n "$cls" ] && nxt+=("$cls")
      done < <(grep -rhoE "(class|interface)[[:space:]]+[A-Za-z0-9_]+[^{]*(implements|extends)[^{]*\b${name}\b" "$SRC" --include='*.java' 2>/dev/null || true)
    done; frontier=("${nxt[@]:-}")
  done
  unset "seen[$seed]"
  found=$(printf '%s\n' "${!seen[@]}" | sed '/^$/d' | jq -R . | jq -cs .)
  jq -cn --arg id "$id" --argjson f "$found" --argjson c "$calls" \
     '{id:$id, arm:"grep", found_simple:$f, found_fqn:null, calls:$c}'
done < "$Q"
