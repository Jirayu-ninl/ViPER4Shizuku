# ViPER4Shizuku

ViPER4Android without root. ViPER4Shizuku runs the ViPER DSP **inside the app** instead of as
an audio-HAL driver, and uses [Shizuku](https://shizuku.rikka.app/) to route other apps' media
through it:

- **System audio:** a Shizuku service registers a loopback audio policy, captures the
  media of other apps (Spotify, YouTube, Chrome…), and hands it to the app. The app processes it
  with ViPERDSP and plays it back.
- **Local file:** the in-app player plays an audio file through the same DSP. Needs no
  Shizuku.

The UI, effects, presets and per-device profiles come from
[ViPER4Android by likelikeslike](https://github.com/likelikeslike/ViPER4Android). The root,
driver, HIDL and AIDL paths are removed. The DSP is
[ViPERDSP](https://github.com/likelikeslike/ViPERDSP), built unmodified from a git submodule.

> **Unofficial, early, personal use only.** Not affiliated with ViPER's Audio (ViPER ACOUSTICS)
> or with likelikeslike. Tested on Android 16 only. Uses hidden Android APIs that can break with
> any system update.

## Why there is no APK to download

ViPERDSP is reverse-engineered from the original ViPER4Android driver and has no license: its
README allows personal use only and forbids commercial use. The app is GPL-3.0, and a built APK
links the two together in one library. Such an APK cannot be distributed under the GPL, so
**this repository ships source only**. Build it yourself, for yourself, and please don't share
the APK.

## Build

You need:

- Android SDK with NDK `30.0.16248370` and CMake `3.22.1` (install both from the Android Studio
  SDK Manager)
- JDK 17 or newer (the JBR bundled with Android Studio works)
- Git

```bash
git clone --recurse-submodules https://github.com/Jirayu-ninl/ViPER4Shizuku.git
```

If you already cloned without `--recurse-submodules`, fetch ViPERDSP with:

```bash
git submodule update --init --recursive
```

Create `android/local.properties` with the path to your SDK (Android Studio does this when you
open the `android/` folder):

```properties
sdk.dir=/path/to/Android/Sdk
```

Then build from `android/`:

```bash
cd android && ./gradlew assembleDebug
```

The APK is copied to `builds/<versionName>/ViPER4Shizuku-<versionName>-debug.apk`. Install it
with `adb install` or open it on the phone.

### Release build (optional)

`./gradlew assembleRelease` builds a minified APK. To sign it, add your own key to
`android/local.properties` (this file is ignored by git):

```properties
KEYSTORE_FILE=/path/to/your.jks
KEYSTORE_PASSWORD=...
KEY_ALIAS=...
KEY_PASSWORD=...
```

Without these, the release APK is unsigned.

## Set up on the phone

1. Install [Shizuku](https://shizuku.rikka.app/) and start it with wireless debugging (see
   Shizuku's guide). Shizuku stops on every reboot, so start it again after each one.
2. Open ViPER4Shizuku and tap **Audio source** (the waveform icon in the top bar).
3. Under **System audio (Shizuku)**, tap **Grant permission**, then **Start capture**.
4. Play music in any app, turn on the power button (bottom right) and tune the effects.

Apps on the excluded list (Settings) play directly, without processing.

On ColorOS, Shizuku may report "permission limited". Turning on **Developer options → Disable
system optimization** fixed it on one ColorOS 16 phone.

## Limitations

- **Shizuku after every reboot.** Without it, only the in-app player works.
- **Latency.** Captured audio is delayed by an estimated 90–150 ms on the speaker. Video apps
  can't compensate for it, so with Bluetooth headphones the sound can trail the picture by about
  0.4 s.
- **Media only.** Calls, notifications, alarms and ringtones are never captured. Apps that play
  through AAudio MMAP may bypass the capture (not tested yet).
- **Capture-process death pauses players.** If Shizuku or the capture service dies, Android
  treats it like unplugging headphones, so music apps pause. Press play and they continue on the
  speaker. Force-stopping ViPER4Shizuku doesn't pause them.
- **Aggressive OEM task killers.** On ColorOS, swiping the app away from Recents stops capture.
- **Sample rate.** The DSP runs at 44.1 or 48 kHz (what ViPER-DDC supports).
- **ABIs.** arm64-v8a and x86_64 only.

[docs/device-tests.md](docs/device-tests.md) lists what has and hasn't been verified on a real
device.

## How it works

See [docs/architecture.md](docs/architecture.md).

## Project layout

```
ViPER4Shizuku/
  android/              the Gradle project (open this folder in Android Studio)
  third_party/ViPERDSP  ViPERDSP, a pinned git submodule, built unmodified
  builds/               where built APKs are copied (never committed)
  docs/                 architecture and device tests
```

| Module (in `android/`) | What it is |
| --- | --- |
| `app/` | The app: UI, effects, presets, per-device profiles, the engine service. Forked from ViPER4Android. |
| `engine/` | Builds ViPERDSP with the NDK behind a JNI layer (`NativeDsp`), plus the media3 `DspAudioProcessor`, the in-app player and the loopback renderer (app side of system audio capture). |
| `capture-shizuku/` | Privileged side of system audio capture: a Shizuku `UserService` (uid 2000) that registers the loopback `AudioPolicy`, captures the matched apps' PCM and pipes it to the app, with fail-safe teardown. |
| `hidden-api/` | `compileOnly` stubs for the `@SystemApi` audio-policy classes the public SDK leaves out. |
| `tools/probe/` | Standalone test harness (not shipped) that drives capture and the in-app player over adb. |
| `tools/device-tests/` | Scripts for the device tests. |

## Tests

```bash
cd android
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest :capture-shizuku:connectedDebugAndroidTest
```

The instrumented tests run on an emulator or a phone. They check the DSP parameter layout field
by field against the native engine, kernel/DDC loading and the engine service. Capture itself
needs Shizuku on a real device: see [docs/device-tests.md](docs/device-tests.md).

## Credits

- **ViPER520 and Zhuhang** ([ViPER's Audio](https://github.com/vipersaudio), ViPER ACOUSTICS)
  created ViPER4Android and its DSP.
- **[likelikeslike](https://github.com/likelikeslike)** made the
  [ViPER4Android app](https://github.com/likelikeslike/ViPER4Android) this one is forked from,
  [ViPERDSP](https://github.com/likelikeslike/ViPERDSP) and
  [ViPERFX_RE](https://github.com/likelikeslike/ViPERFX_RE).
- **[Martmists](https://github.com/Martmists-GH) and [Iscle](https://github.com/iscle)**
  reverse-engineered the driver in [AndroidAudioMods/ViPERFX_RE](https://github.com/AndroidAudioMods/ViPERFX_RE).
- **[RikkaApps](https://github.com/RikkaApps)** for [Shizuku](https://github.com/RikkaApps/Shizuku-API).

## License

Copyright (C) 2026 [TheIceJi](https://www.theiceji.com/). The parts forked from ViPER4Android
keep their authors' copyright.

The code in this repository is licensed under [GPL-3.0](LICENSE). ViPERDSP is not part of this
repository and is not covered by that license: it is fetched as a git submodule and keeps its own
terms (personal use only, no commercial use). Third-party notices are in [NOTICE.md](NOTICE.md).
