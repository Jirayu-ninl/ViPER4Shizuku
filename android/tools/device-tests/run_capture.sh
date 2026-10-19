#!/usr/bin/env bash
# System audio capture test. REQUIRES a real device (Android 16) with Shizuku running and
# authorised. Walks the operator through one case of docs/device-tests.md, collecting the AOSP
# dumpsys output. This script does not decide pass/fail; it gathers evidence.
#
# Evidence goes to out/device-tests/<case>/ (ignored by git). The full dumpsys output lists
# the apps installed on the device and the names of paired Bluetooth devices: do not commit it.
#
# Usage: android/tools/device-tests/run_capture.sh <case-id> [serial]
#   e.g. android/tools/device-tests/run_capture.sh S-02
set -euo pipefail

CASE="${1:?usage: run_capture.sh <case-id> [serial]}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"  # the Gradle project (android/)
REPO="$(cd "$ROOT/.." && pwd)"
ADB=(adb)
[ $# -ge 2 ] && ADB=(adb -s "$2")

PKG=dev.theiceji.viper4shizuku.probe
APK="$ROOT/tools/probe/build/outputs/apk/debug/probe-debug.apk"
OUT="$REPO/out/device-tests/$CASE"
mkdir -p "$OUT"

echo "== Building + installing probe"
( cd "$ROOT" && ./gradlew :tools:probe:assembleDebug -q )
"${ADB[@]}" install -r -g "$APK"

echo
echo "Case $CASE. Make sure Shizuku is running and the probe has Shizuku permission"
echo "(open the probe once, tap 'Grant Shizuku permission')."
read -r -p "Press Enter when the source app (e.g. Spotify) is PLAYING..."

"${ADB[@]}" logcat -c
echo "== Starting capture (float 48k)"
"${ADB[@]}" shell am start -n "$PKG/.ProbeActivity" -e cmd capture --ez float true --ei sr 48000 >/dev/null
sleep 3
"${ADB[@]}" shell am start -n "$PKG/.ProbeActivity" -e cmd status >/dev/null
sleep 1

echo "== Capturing dumpsys evidence"
"${ADB[@]}" shell dumpsys audio                 > "$OUT/dumpsys_audio.txt" 2>&1 || true
"${ADB[@]}" shell dumpsys media.audio_policy     > "$OUT/dumpsys_audio_policy.txt" 2>&1 || true
"${ADB[@]}" shell dumpsys media.audio_flinger    > "$OUT/dumpsys_audio_flinger.txt" 2>&1 || true

echo
echo "LISTEN NOW: original app audio should be silent on the speaker, processed audio should"
echo "play through the probe. Let it run, then note dropouts."
read -r -p "Press Enter to stop capture and collect the log..."

"${ADB[@]}" shell am start -n "$PKG/.ProbeActivity" -e cmd stop >/dev/null
sleep 1
"${ADB[@]}" logcat -d -s ViPER4ShizukuProbe > "$OUT/probe_log.txt"

echo "== Probe results"
sed -n 's/.*ViPER4ShizukuProbe: //p' "$OUT/probe_log.txt" | grep -E '^RESULT' || true
echo
echo "Evidence saved under: $OUT"
echo "Compare it with the pass criteria for $CASE in docs/device-tests.md"
