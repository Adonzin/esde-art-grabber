#!/bin/bash
# Runs the engine tests on the desktop JVM (no device needed).
# Needs: a JDK and Android's org.json (Ubuntu: apt install libandroid-json-java).
set -euo pipefail
cd "$(dirname "$0")/.."
JSON=/usr/share/java/com.android.json.jar
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/out" "$WORK/lib" "$WORK/lib2"
javac -nowarn -encoding UTF-8 -d "$WORK/out" -cp "$JSON" \
  src/com/esde/artgrabber/{Names,Sgdb,Engine}.java tests/EngineTest.java tests/com/esde/artgrabber/HttpTest.java
echo "== Engine tests"
java -Dfile.encoding=UTF-8 -cp "$WORK/out:$JSON" EngineTest "$WORK/lib" | grep -E "^  ok|ALL OK"
echo "== HTTP tests (local fake SteamGridDB server)"
env -u HTTPS_PROXY -u HTTP_PROXY -u https_proxy -u http_proxy \
  java -Dfile.encoding=UTF-8 -cp "$WORK/out:$JSON" com.esde.artgrabber.HttpTest "$WORK/lib2" | grep -E "^  ok|FAIL|TESTS OK"
