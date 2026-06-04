#!/usr/bin/env bash
# Independent normalization pass for the GnuCOBOL cross-check oracle.
#
# Uses GnuCOBOL's preprocessor (`cobc -E`) as a DIFFERENT-LINEAGE front end
# (C compiler vs ProLeap's ANTLR/Java) to do fixed-format column handling,
# continuation-line joining, and COPY expansion. The resulting normalized text
# is consumed by extract_edges.py. This is the layer where both audited oracle
# bugs lived (the PROGRAM '(' continuation/space regex miss; copybook-resolved
# menu literals), so normalizing it with an independent compiler is the point.
#
# Missing IBM system copybooks (DFHAID/DFHBMSCA/CMQ*) are stubbed in stubs/ —
# they define BMS screen attributes / MQ structures and carry no program
# dispatch targets, so stubbing them cannot change the control-flow graph.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CORPUS="$HERE/../corpus"
OUT="$HERE/normalized"
mkdir -p "$OUT"

export PATH="$(brew --prefix gnucobol)/bin:$PATH"

# All copybook directories in the corpus + our system stubs.
INCS=(
  -I "$CORPUS/app/cpy"
  -I "$CORPUS/app/cpy-bms"
  -I "$CORPUS/app/app-transaction-type-db2/cpy"
  -I "$CORPUS/app/app-transaction-type-db2/cpy-bms"
  -I "$CORPUS/app/app-authorization-ims-db2-mq/cpy"
  -I "$CORPUS/app/app-authorization-ims-db2-mq/cpy-bms"
  -I "$HERE/stubs"
)

n=0; missing_log="$OUT/_missing_copybooks.log"; : > "$missing_log"
while IFS= read -r src; do
  base="$(basename "$src")"; prog="${base%.*}"
  # cobc -E emits normalized output even when some COPY targets are unresolved;
  # capture stderr to a per-corpus missing-copybook log for auditability.
  cobc -E -fformat=fixed "${INCS[@]}" "$src" > "$OUT/${prog}.cob" 2>>"$missing_log"
  lines=$(wc -l < "$OUT/${prog}.cob")
  printf '%-12s -> %5s lines\n' "$prog" "$lines"
  n=$((n+1))
done < <(find "$CORPUS/app" -type f \( -iname '*.cbl' \) | sort)

echo "---"
echo "preprocessed $n programs -> $OUT"
echo "unresolved COPY names (system copybooks expected):"
grep -oE "error: [A-Z0-9]+: No such file" "$missing_log" | sort -u || echo "  (none)"
