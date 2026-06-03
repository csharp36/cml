#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
source pin.env
if [ ! -d corpus/.git ]; then
  git clone --branch "$CORPUS_BRANCH" "$CORPUS_URL" corpus
fi
SHA=$(git -C corpus rev-parse HEAD)
# Pin the SHA into pin.env (idempotent rewrite of the CORPUS_SHA line).
if grep -q '^CORPUS_SHA=' pin.env; then
  sed -i.bak "s|^CORPUS_SHA=.*|CORPUS_SHA=$SHA|" pin.env && rm -f pin.env.bak
fi
echo "CardDemo pinned at $SHA"
echo "COBOL programs: $(find corpus -iname '*.cbl' | wc -l | tr -d ' ')"
echo "Copybooks:      $(find corpus -iname '*.cpy' | wc -l | tr -d ' ')"
