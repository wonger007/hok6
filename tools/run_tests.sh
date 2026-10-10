#!/usr/bin/env bash
# Quick checks that need no device: Kotlin unit tests and the training page's JavaScript tests.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew testDebugUnitTest --console=plain -q
echo "Kotlin unit tests passed (report: app/build/reports/tests/testDebugUnitTest/index.html)"
node --test app/src/test/js/*.test.js
echo "JavaScript tests passed"
