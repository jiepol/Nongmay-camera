#!/usr/bin/env bash
# Runs the JVM unit tests for every Android-free class (DSP, voice matching, crop/tone/WB math,
# JSON, ICC, worksheet). The same tests run under Gradle (`./gradlew testDebugUnitTest`).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT="$ROOT/build/unit"
JUNIT="${JUNIT_JAR:-/usr/share/java/junit4.jar}"
HAMCREST="${HAMCREST_JAR:-/usr/share/java/hamcrest-core.jar}"
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/test-classes"
# main sources that do not touch the Android SDK
{ grep -rL --include="*.java" -E "^import (android|dalvik)\." app/src/main/java || true; } | sort > "$OUT/pure.txt"
find app/src/test/java -name '*.java' | sort > "$OUT/tests.txt"
javac -encoding UTF-8 -nowarn -d "$OUT/classes" @"$OUT/pure.txt" 2>&1 | grep -v "^Picked up" || true
javac -encoding UTF-8 -nowarn -cp "$OUT/classes:$JUNIT" -d "$OUT/test-classes" @"$OUT/tests.txt" 2>&1 | grep -v "^Picked up" || true
CLASSES=$(cd "$OUT/test-classes" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
echo "pure main classes: $(wc -l < "$OUT/pure.txt")  test classes: $(echo "$CLASSES" | wc -w)"
java -cp "$OUT/classes:$OUT/test-classes:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore $CLASSES 2>&1 \
  | grep -v "^Picked up" | tee "$OUT/results.txt"
grep -q "^OK (" "$OUT/results.txt"
