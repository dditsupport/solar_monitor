#!/usr/bin/env bash
# App sync tests on the JVM. Needs a JDK and Gradle (8.x) on PATH; downloads
# Kotlin and the two kotlinx libraries from Maven Central on first run.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
gradle -p "$HERE" test --console=plain -q && echo "== android: ALL PASSED"
