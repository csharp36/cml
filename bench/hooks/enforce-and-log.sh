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
  if [[ "$tool" == "Grep" || "$tool" == "Glob" ]]; then
    decision="deny"; reason="search tool '$tool' forbidden in semantic arm"
  elif [[ "$tool" == "Bash" ]] && printf '%s' "$cmd" \
        | grep -qE '(^|[;&|`(/\\]|[[:space:]])(grep|egrep|fgrep|rg|ag|ack|find|fd|locate)([[:space:]]|$)'; then
    decision="deny"; reason="shell search command forbidden in semantic arm"
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
