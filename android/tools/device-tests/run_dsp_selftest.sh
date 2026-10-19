#!/usr/bin/env bash
# DSP self-test: build + install the probe and run the DSP self-test on whatever
# device/emulator `adb` points at. Proves ViPERDSP loads, processes audio and sustains
# real-time throughput. Works on an emulator (no Shizuku needed). See docs/device-tests.md.
#
# Usage: android/tools/device-tests/run_dsp_selftest.sh [serial]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"  # the Gradle project (android/)
ADB=(adb)
[ $# -ge 1 ] && ADB=(adb -s "$1")

PKG=dev.theiceji.viper4shizuku.probe
APK="$ROOT/tools/probe/build/outputs/apk/debug/probe-debug.apk"

echo "== Building probe"
( cd "$ROOT" && ./gradlew :tools:probe:assembleDebug -q )

echo "== Installing"
"${ADB[@]}" install -r -g "$APK"

echo "== Clearing log"
"${ADB[@]}" logcat -c

echo "== Running DSP self-test"
"${ADB[@]}" shell am start -n "$PKG/.ProbeActivity" -e cmd dsp >/dev/null

echo "== Waiting for result (max 40s)"
deadline=$(( $(date +%s) + 40 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
    if "${ADB[@]}" logcat -d -s ViPER4ShizukuProbe | grep -q "RESULT dsp.selftest done"; then
        break
    fi
    sleep 1
done

echo "== Results"
"${ADB[@]}" logcat -d -s ViPER4ShizukuProbe | sed -n 's/.*ViPER4ShizukuProbe: //p'
