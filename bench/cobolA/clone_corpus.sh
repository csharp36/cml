#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
source pin.env
if [ ! -d corpus/.git ]; then
  git clone --branch "$CORPUS_BRANCH" "$CORPUS_URL" corpus
fi
SHA=$(git -C corpus rev-parse HEAD)
if [[ ! "$SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "ERROR: unexpected SHA format: $SHA" >&2; exit 1
fi
# Pin the resolved SHA into pin.env (idempotent rewrite of the CORPUS_SHA line).
grep -q '^CORPUS_SHA=' pin.env || { echo "ERROR: CORPUS_SHA line missing from pin.env" >&2; exit 1; }
sed -i.bak "s|^CORPUS_SHA=.*|CORPUS_SHA=$SHA|" pin.env && rm -f pin.env.bak
CBL=$(find corpus -iname '*.cbl' | wc -l | tr -d ' ')
CPY=$(find corpus -iname '*.cpy' | wc -l | tr -d ' ')
[ "$CBL" -gt 0 ] || { echo "ERROR: no .cbl files found — clone may be broken" >&2; exit 1; }
echo "CardDemo pinned at $SHA"
echo "COBOL programs: $CBL"
echo "Copybooks:      $CPY"
