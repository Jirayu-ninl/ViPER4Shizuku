# Device tests

System audio capture depends on how the phone's audio stack behaves, which an emulator doesn't
reproduce (and Shizuku doesn't start on the emulator). These cases are run by hand on a real
Android 16 phone with Shizuku running.

## How to run

**DSP self-test** (any device or emulator, no Shizuku):

```bash
android/tools/device-tests/run_dsp_selftest.sh [serial]
```

**System audio cases** (real device, Shizuku running and authorised):

```bash
android/tools/device-tests/run_capture.sh S-02 [serial]
```

The script installs the probe app, starts a capture and saves `dumpsys` output and the probe's
log to `out/device-tests/<case>/`. That folder is ignored by git. Keep it that way: the full
`dumpsys` output lists the apps installed on the phone and the names of paired Bluetooth
devices.

The debug build of the app can also be driven over adb with its debug-only receiver:

```bash
adb shell am broadcast -n dev.theiceji.viper4shizuku/.debug.EngineTestReceiver -e cmd capture-start
adb shell am broadcast -n dev.theiceji.viper4shizuku/.debug.EngineTestReceiver -e cmd status
adb shell am broadcast -n dev.theiceji.viper4shizuku/.debug.EngineTestReceiver -e cmd master-on
```

## Cases and current results

Results so far come from one Android 16 phone running ColorOS 16, with Spotify from the Play
Store (unpatched).

✅ passed · 🟡 partly checked · ⏳ not run yet

| ID | Case | Kind | Result |
| --- | --- | --- | --- |
| S-01 | Policy registers as uid 2000 | must | ✅ One `LOOP_BACK` mix that matches UNKNOWN/MEDIA/GAME and excludes the app's uid |
| S-02 | Spotify (unpatched) through system audio | must | ✅ 25 min continuous, 0 underruns, ~48k frames/s processed; next/previous/pause/play work. Seek not tried |
| S-03 | Spotify with the screen locked | must | 🟡 No interruption across lock/unlock, but only ~8 min locked; needs 15 min untouched |
| S-04 | Spotify's output flags | info | ✅ Mixed PCM (16-bit 44.1 kHz) on the submix mixer; the submix delivers 16-bit 48 kHz |
| S-05 | Chrome (YouTube on the web) | should | ✅ Captured and processed. Web Audio not tested separately |
| S-06 | Netflix (DRM) | should | ⏳ |
| S-07 | YouTube app / local files in other apps | must | 🟡 YouTube app captured and processed; local files in other apps not tried |
| S-08 | Notifications, ringtones, alarms, calls are not captured | must | 🟡 The mix rule can't match their usages; not checked by ear yet |
| S-09 | No feedback loop | must | ✅ The app's own track never reaches the submix; heard as a single, clean signal |
| S-10 | Output change (speaker, wired, Bluetooth) | should | 🟡 Connecting Bluetooth moves playback to A2DP without stopping capture; disconnecting not tested |
| S-11 | Capture service or Shizuku killed | must | ✅ Mix gone in < 0.3 s, media never stuck silent. Players pause (Android sends "becoming noisy"); play resumes on the speaker |
| S-12 | App force-stopped | must | ✅ Policy unregistered in ≤ 0.15 s; Spotify moves to the speaker within ~0.4 s and keeps playing |
| S-13 | Added latency ≤ 150 ms | must | 🟡 Estimated from AudioFlinger at ~90–150 ms on the speaker; needs a microphone measurement. A/V lag ~0.4 s on Bluetooth |
| S-14 | Apps that use AAudio MMAP | info | ⏳ |
| S-15 | Android 17 background-audio rules | rerun on 17 | ⏳ |

## Automated tests

```bash
cd android
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest :capture-shizuku:connectedDebugAndroidTest
```

| Test | What it proves |
| --- | --- |
| `AudioOutputDetectorTest` (unit) | Device classification ignores our own submix and Bluetooth SCO |
| `ViperParamsLayoutTest` | The Kotlin `ViPERParams` layout matches the native struct, field by field (sends each value as a full blob and as a single parameter, and compares the audio) |
| `BulkLoaderTest` | Convolver kernels and DDC files load into the DSP, including concurrent loads |
| `ViperServiceEngineTest` | The real `ViperService`: attach order, kernel/DDC directories, master switch from the UI and from the QS tile |
| `PipeWriterTest` | The capture pipe's non-blocking writer and stall watchdog |
