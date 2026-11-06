#!/usr/bin/env bash
# Usage: tools/ci-gradle.sh <label> <gradle tasks...>
# Runs Gradle, keeps the full log in build-logs/<label>.log and, on failure, emits the
# most relevant error lines as one GitHub annotation.
set -uo pipefail
label="$1"; shift
mkdir -p build-logs
log="build-logs/$label.log"
./gradlew "$@" --stacktrace --console=plain 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
if [ "$status" -ne 0 ]; then
  summary=$(grep -E "^e: |^w: .*error|error:|FAILED|What went wrong|> .*failed|Could not|Unresolved|Caused by|AssertionError|expected:|Exception" "$log" \
    | grep -v "^\s*at " | awk '!seen[$0]++' | head -60)
  [ -z "$summary" ] && summary=$(tail -40 "$log")
  # Annotation messages encode newlines as %0A
  msg=$(printf '%s' "$summary" | sed 's/%/%25/g' | awk '{printf "%s%%0A", $0}')
  echo "::error title=${label} failed::${msg}"
fi
exit "$status"
