#!/usr/bin/env bash
# Feeds fake PreToolUse payloads to the hook and asserts allow/deny + logging.
set -euo pipefail
HOOK="$(cd "$(dirname "$0")" && pwd)/enforce-and-log.sh"
LOG=$(mktemp)
decision() { BENCH_ARM="$1" BENCH_AUDIT_LOG="$LOG" bash "$HOOK" <<<"$2" | jq -r '.hookSpecificOutput.permissionDecision'; }

[[ $(decision semantic '{"tool_name":"Grep","tool_input":{}}') == deny ]] || { echo FAIL grep-tool; exit 1; }
[[ $(decision semantic '{"tool_name":"Bash","tool_input":{"command":"grep -r Foo ."}}') == deny ]] || { echo FAIL bash-grep; exit 1; }
# hybrid model: grep used to FILTER command output (after a pipe) is ALLOWED
[[ $(decision semantic '{"tool_name":"Bash","tool_input":{"command":"cat x | grep y"}}') == allow ]] || { echo FAIL pipe-grep-allow; exit 1; }
[[ $(decision semantic '{"tool_name":"Bash","tool_input":{"command":"mvn -q test | grep \"Tests run\""}}') == allow ]] || { echo FAIL pipe-mvn-grep; exit 1; }
[[ $(decision semantic '{"tool_name":"Bash","tool_input":{"command":"ls target | grep -i interceptor"}}') == allow ]] || { echo FAIL pipe-ls-grep; exit 1; }
[[ $(decision semantic '{"tool_name":"Bash","tool_input":{"command":"cat x | /usr/bin/grep y"}}') == allow ]] || { echo FAIL pipe-abspath-grep; exit 1; }
# ...but a LEADING grep (even after a pipe to another leading grep, or after ;/&&/||) is discovery -> deny
[[ $(decision semantic '{"tool_name":"Bash","tool_input":{"command":"grep -r Foo . | grep bar"}}') == deny ]] || { echo FAIL leading-grep-pipe; exit 1; }
[[ $(decision semantic '{"tool_name":"Bash","tool_input":{"command":"cd /tmp && grep -r x ."}}') == deny ]] || { echo FAIL andand-grep; exit 1; }
[[ $(decision semantic '{"tool_name":"Bash","tool_input":{"command":"find . -name X"}}') == deny ]] || { echo FAIL find; exit 1; }
[[ $(decision semantic '{"tool_name":"Bash","tool_input":{"command":"mvn test | find . -name X"}}') == deny ]] || { echo FAIL piped-find-still-deny; exit 1; }
[[ $(decision semantic '{"tool_name":"Bash","tool_input":{"command":"/usr/bin/grep -r Foo ."}}') == deny ]] || { echo FAIL abspath-grep; exit 1; }
[[ $(decision semantic '{"tool_name":"Bash","tool_input":{"command":"\\grep Foo ."}}') == deny ]] || { echo FAIL backslash-grep; exit 1; }
[[ $(decision semantic '{"tool_name":"Bash","tool_input":{"command":"(grep Foo .)"}}') == deny ]] || { echo FAIL paren-grep; exit 1; }
[[ $(decision semantic '{"tool_name":"Bash","tool_input":{"command":"./findbugs.sh"}}') == allow ]] || { echo FAIL findbugs-falsepos; exit 1; }
[[ $(decision semantic '{"tool_name":"Bash","tool_input":{"command":"echo grepping"}}') == allow ]] || { echo FAIL echo-falsepos; exit 1; }
[[ $(decision semantic '{"tool_name":"Read","tool_input":{"file_path":"a"}}') == allow ]] || { echo FAIL read; exit 1; }
[[ $(decision semantic '{"tool_name":"mcp__source-code-indexer__search_symbols","tool_input":{}}') == allow ]] || { echo FAIL mcp-allow; exit 1; }
[[ $(decision semantic '{"tool_name":"Bash","tool_input":{"command":"mvn -q test"}}') == allow ]] || { echo FAIL mvn; exit 1; }
[[ $(decision baseline '{"tool_name":"Bash","tool_input":{"command":"grep -r Foo ."}}') == allow ]] || { echo FAIL base-grep; exit 1; }
[[ $(decision baseline '{"tool_name":"mcp__source-code-indexer__search_symbols","tool_input":{}}') == deny ]] || { echo FAIL base-mcp; exit 1; }
[[ $(wc -l < "$LOG") -ge 9 ]] || { echo "FAIL log count $(wc -l < "$LOG")"; exit 1; }
echo "ALL HOOK TESTS PASS"
