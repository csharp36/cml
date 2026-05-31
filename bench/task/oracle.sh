#!/usr/bin/env bash
# Run the scoped hazelcast oracle on a worktree. Exit 0 iff all named tests pass.
# Usage: oracle.sh <worktree-dir> <comma-separated-test-classes>
set -euo pipefail
WT="$1"; TESTS="$2"
cd "$WT"
MVN="mvn"; [[ -x ./mvnw ]] && MVN="./mvnw"
# Portable timeout: use timeout/gtimeout if present (Linux/coreutils), else run uncapped (macOS).
# Plain string (not an array) so it expands to nothing safely under `set -u` on bash 3.2.
TO=""
if command -v timeout >/dev/null 2>&1; then TO="timeout 1800"
elif command -v gtimeout >/dev/null 2>&1; then TO="gtimeout 1800"; fi
$TO "$MVN" -q -pl hazelcast -am \
  -Dtest="$TESTS" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  test
