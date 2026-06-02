#!/usr/bin/env bash
# Sample merged PRs from the hazelcast clone into instances.jsonl.
# Usage: sample-prs.sh <hazelcast-clone> <out.jsonl> [max_instances] [since] [branch]
# Filters: 2-parent merge commits (PRs); >=2 non-test source files changed; total <= MAX_FILES;
#          all touched source files resolve at base; has a usable subject line; truth has >=2 files.
set -euo pipefail
REPO="${1:?clone path}"; OUT="${2:?out jsonl}"; MAX="${3:-30}"; SINCE="${4:-2024-01-01}"; BRANCH="${5:-master}"
HERE="$(cd "$(dirname "$0")" && pwd)"
MAX_FILES=20
: > "$OUT"
count=0
git -C "$REPO" log --merges --since="$SINCE" --pretty='%H %P' "$BRANCH" 2>/dev/null \
  | while read -r merge p1 p2; do
      [[ -n "${p2:-}" ]] || continue            # need a real 2-parent merge
      base="$p1"                                 # first parent = mainline before the PR
      changed=()
      while IFS= read -r f; do [[ -n "$f" ]] && changed+=("$f"); done < <(
        git -C "$REPO" diff --name-only "$base..$merge" -- '*.java' \
          | grep -E '/src/main/' | grep -vE '(^|/)src/test/|Test\.java$|IT\.java$' || true)
      (( ${#changed[@]} >= 2 )) || continue
      (( ${#changed[@]} <= MAX_FILES )) || continue
      ok=1; for f in "${changed[@]}"; do
        git -C "$REPO" cat-file -e "$base:$f" 2>/dev/null || { ok=0; break; }; done
      (( ok == 1 )) || continue
      subj=$(git -C "$REPO" log -1 --pretty='%s' "$merge")
      body=$(git -C "$REPO" log -1 --pretty='%b' "$merge")
      [[ -n "$subj" ]] || continue
      truth=$(python3 "$HERE/extract_truth.py" "$REPO" "$base" "$merge")
      tf=$(printf '%s' "$truth" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["files"]))')
      (( tf >= 2 )) || continue
      # sanitize the task via sanitize_task.sanitize (strips merge/PR#/branch + paths/FQNs,
      # answer-leak guard). Emits nothing + exits 1 when no usable task remains -> skip instance.
      if PYTHONPATH="$HERE" python3 - "$merge" "$base" "$subj" "$body" "$truth" >> "$OUT" <<'PY'
import sys, json
import sanitize_task
merge, base, subj, body, truth = sys.argv[1:6]
task = sanitize_task.sanitize(subj + "\n" + body)
if not task:
    sys.exit(1)  # pure merge-noise / no description -> skip
t = json.loads(truth)
print(json.dumps({"id": merge[:12], "merge_sha": merge, "base_sha": base,
                  "title": subj, "task": task,
                  "truth_files": t["files"], "truth_symbols": t["symbols"]}))
PY
      then
        count=$((count+1))
        [[ "$count" -ge "$MAX" ]] && break
      fi
    done
echo "wrote $(wc -l < "$OUT") instances to $OUT"
