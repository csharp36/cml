#!/usr/bin/env bash
# PreToolUse hook: allow-by-default (suppresses prompts), deny the arm's forbidden
# discovery tools, log every call to $BENCH_AUDIT_LOG. Arm via $BENCH_ARM.
set -euo pipefail
input=$(cat)
tool=$(printf '%s' "$input" | jq -r '.tool_name // ""')
cmd=$(printf '%s'  "$input" | jq -r '.tool_input.command // ""')
arm="${BENCH_ARM:-unknown}"
log="${BENCH_AUDIT_LOG:-/dev/null}"
ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)

decision="allow"; reason="permitted"
if [[ "$arm" == "semantic" ]]; then
  # Hybrid model: the indexer answers "which file?" (code discovery); shell grep is
  # still allowed for FILTERING command output (e.g. `mvn test | grep "Tests run"`).
  # So we block code DISCOVERY only, not output filtering.
  if [[ "$tool" == "Grep" || "$tool" == "Glob" ]]; then
    decision="deny"; reason="discovery tool '$tool' forbidden in semantic arm (use the indexer)"
  elif [[ "$tool" == "Bash" ]] \
        && printf '%s' "$cmd" | grep -qE '(^|[;&|`(]|[[:space:]])(\\)?([^|;&[:space:]]*/)?(find|fd|locate)([[:space:]]|$)'; then
    # filesystem discovery — never a legitimate output filter; deny anywhere (path/backslash-prefixed too)
    decision="deny"; reason="file-discovery command (find/fd/locate) forbidden in semantic arm"
  elif [[ "$tool" == "Bash" ]] \
        && printf '%s' "$cmd" | grep -qE '(^|[;&`(]|\|\|)[[:space:]]*(\\)?([^|;&[:space:]]*/)?(grep|egrep|fgrep|rg|ag|ack)([[:space:]]|$)'; then
    # a LEADING text-search command = code discovery (e.g. `grep -r X .`, `/usr/bin/grep ...`, `\grep ...`); deny.
    # grep AFTER a single pipe (`cmd | grep X`) is NOT leading -> allowed (output filtering).
    decision="deny"; reason="leading code-search command forbidden in semantic arm (piping output through grep is fine)"
  fi
elif [[ "$arm" == "baseline" ]]; then
  if [[ "$tool" == mcp__* ]]; then
    decision="deny"; reason="MCP tools forbidden in baseline arm"
  fi
fi

printf '{"ts":"%s","arm":"%s","tool":"%s","decision":"%s","cmd":%s}\n' \
  "$ts" "$arm" "$tool" "$decision" "$(printf '%s' "$cmd" | jq -R -s '.')" >> "$log"

jq -n --arg d "$decision" --arg r "$reason" \
  '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:$d,permissionDecisionReason:$r}}'
