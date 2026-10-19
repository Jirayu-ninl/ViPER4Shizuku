# Architecture

ViPER4Android normally needs root because its DSP is an audio-effect library loaded by
`audioserver`. An ordinary app can't install such a library, and on Android 15+ the old effect
plugin interface is gone altogether. ViPER4Shizuku keeps the DSP and the app but moves the DSP
into the app process. It then needs a way to get other apps' audio in and the processed audio
out. Shizuku gives it that: a process running as the shell user (uid 2000), which may register
an audio policy that reroutes media.

## Data flow

```
                    ┌──────────── Shizuku process (uid 2000) ────────────┐
other apps' media ──► AudioPolicy (LOOP_BACK mix) ─► remote submix ─► AudioRecord
                    └───────────────────────────────────────┬─────────────┘
                                                             │ PCM over a pipe
                    ┌──────────── app process ───────────────▼─────────────┐
 System audio       │ LoopbackRenderer ─► NativeDsp (ViPERDSP) ─► AudioTrack ─► speaker / BT
 In-app player      │ ExoPlayer ─► DspAudioProcessor ─► NativeDsp ─► AudioTrack
                    │                                  ▲
 UI ─► EffectState ─► ViperParamsSerializer ─► LocalTransport ─► JNI ─► ViPER::DispatchParamValue
                    └──────────────────────────────────────────────────────┘
```

## System audio through Shizuku

`capture-shizuku/` holds the privileged side. `CaptureUserService` is a Shizuku `UserService`,
so it runs in a separate process as uid 2000.

- **Policy.** It builds an `AudioMixingRule` that matches the usages `UNKNOWN`, `MEDIA` and
  `GAME`, and excludes the app's own uid plus the apps the user excluded. It wraps the rule in an
  `AudioMix` with `ROUTE_FLAG_LOOP_BACK` and registers an `AudioPolicy`.
  `registerAudioPolicy` is `@SystemApi`, so it is called by reflection. The other classes
  compile against the stubs in `hidden-api/`. Calls, notifications, alarms and ringtones use
  other usages, so the mix can't match them.
- **Sink.** It records the mix's remote-submix input with an `AudioRecord`. On Android 12+ the
  record is built with `AudioRecord.Builder` and a `ContextWrapper` whose `AttributionSource` is
  `com.android.shell`. Otherwise AudioFlinger rejects the pair (uid 2000, app package) as an
  invalid attribution.
- **Caller identity.** Entry points run under `Binder.clearCallingIdentity()`. In-process
  permission checks such as `MODIFY_AUDIO_ROUTING` then see uid 2000, not the calling app.
- **Transport.** PCM goes to the app through a pipe (`ParcelFileDescriptor`). `PipeWriter` polls
  instead of blocking, so a stuck reader can be detected.
- **Playback.** `LoopbackRenderer` (in `engine/`) runs in the app process. It reads the pipe,
  runs the DSP and plays the result with a low-latency `USAGE_MEDIA` `AudioTrack`, which
  disallows capture so it can't loop back into the mix. Playing from the app process, inside a
  `mediaPlayback` foreground service started from the UI, keeps it within Android 17's
  while-in-use rules.

### Fail-safes

If rerouting fails, all media on the phone could go silent, so every exit path drops the
policy:

- The service links to a binder token of the app. If the app dies, the policy is unregistered.
- If anything fails after `registerAudioPolicy` succeeds, the policy is unregistered before the
  error is returned.
- A watchdog drops the policy if the app leaves the pipe full for `readerStallTimeoutMs`
  (1500 ms by default).
- If the Shizuku process dies, AudioService drops the policy itself. Android then broadcasts
  `ACTION_AUDIO_BECOMING_NOISY`, so most music players pause.

Shizuku reuses a running `UserService` when its version is unchanged, even after a new APK is
installed. Bump `ShizukuCapture.SERVICE_VERSION` whenever `CaptureUserService` changes.

### Output device and profiles

`AudioOutputDetector` picks the per-device profile (speaker, wired, Bluetooth…). During capture
the policy adds a remote-submix output of its own. That output is recognised by its registration
id and ignored, so it isn't taken for Android Auto. Bluetooth SCO (the call channel) is skipped
in favour of A2DP.

## In-app player

`InAppPlayer` decodes a local file with Media3 ExoPlayer. `DspAudioProcessor` is a Media3
`AudioProcessor` that runs the DSP on the decoded PCM before ExoPlayer's `AudioTrack`.

## The DSP and its parameters

`engine/` builds `third_party/ViPERDSP` with the NDK into `libviper4shizuku_dsp.so`, behind a
small JNI layer (`viper4shizuku_jni.cpp`, `NativeDsp`). ViPERDSP isn't modified.

- The app's effect state is encoded by `ViperParamsSerializer`. It can either send one parameter
  at a time (`DispatchParamValue`), or the full `ViPERParams` struct (1156 bytes,
  `kParamSetFullParams`). `ViperParamsLayout.kt` mirrors the struct and is generated from the
  ViPERDSP header with its `tools/viper_layout_gen.py`. Regenerate it whenever the submodule
  moves. `ViperParamsLayoutTest` checks the offsets field by field against the native engine.
- `LocalTransport` takes the place of the original app's HIDL/AIDL transports. It sends each
  parameter to every live DSP instance (capture and the in-app player).
- Convolver kernels and DDC files are streamed into the DSP by `BulkLoader`, off the main thread,
  one load at a time (the DSP has a single staging buffer).
- The DSP runs at 44.1 or 48 kHz, the rates ViPER-DDC has coefficients for.

## Module map

| Module | Role |
| --- | --- |
| `app/` | UI, effect state, presets, per-device profiles (Room, DataStore), `ViperService` (foreground service that owns both engines), QS tile |
| `engine/` | JNI + ViPERDSP, `DspAudioProcessor`, `InAppPlayer`, `LoopbackRenderer` |
| `capture-shizuku/` | `ShizukuCapture` (app-side controller) and `CaptureUserService` (uid 2000), AIDL |
| `hidden-api/` | Compile-only stubs of `android.media.audiopolicy` |
| `tools/probe/` | Test app that drives capture and the in-app player in isolation |

## Known gaps

See the limitations in the [README](../README.md#limitations) and the open device tests in
[device-tests.md](device-tests.md).
