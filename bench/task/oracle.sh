#!/usr/bin/env bash
# Run the scoped hazelcast oracle on a worktree. Exit 0 iff all named tests pass.
# Usage: oracle.sh <worktree-dir> <comma-separated-test-classes>
set -euo pipefail
WT="$1"; TESTS="$2"
cd "$WT"
MVN="mvn"; [[ -x ./mvnw ]] && MVN="./mvnw"
timeout 1800 "$MVN" -q -pl hazelcast -am \
  -Dtest="$TESTS" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  test
