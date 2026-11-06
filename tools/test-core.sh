#!/usr/bin/env bash
# Compiles and tests :ai-brain with a bare kotlinc (no Gradle / Maven needed).
# Use this where dependency repositories are unreachable; otherwise prefer
#   ./gradlew :ai-brain:test
set -euo pipefail
cd "$(dirname "$0")/.."
KOTLIN_HOME="${KOTLIN_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v kotlinc)")")")}"
LIB="$KOTLIN_HOME/lib"
CP="$LIB/kotlinx-coroutines-core-jvm.jar:$LIB/kotlin-test.jar"
OUT=build/local-test
rm -rf "$OUT" && mkdir -p "$OUT/main" "$OUT/test"

echo "== compiling ai-brain/src/main"
kotlinc ai-brain/src/main/kotlin -cp "$CP" -d "$OUT/main" -jvm-target 17 -nowarn 2>&1 | grep -v "^warning" || true
test -d "$OUT/main/ai" || { echo "main compilation failed"; exit 1; }

echo "== compiling ai-brain/src/test (+ JUnit shim)"
kotlinc ai-brain/src/test/kotlin tools/junit-shim -cp "$CP:$OUT/main" -d "$OUT/test" -jvm-target 17 -nowarn 2>&1 | grep -v "^warning" || true
test -d "$OUT/test/ai" || { echo "test compilation failed"; exit 1; }

echo "== running tests"
classes=$(cd "$OUT/test" && find ai -name '*Test.class' ! -name '*$*' | sed 's/\.class$//; s#/#.#g')
kotlin -cp "$CP:$OUT/main:$OUT/test" eve.testrunner.RunnerKt $classes
