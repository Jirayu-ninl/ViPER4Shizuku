# builds

Built APKs land here, one folder per version. `./gradlew assembleDebug` / `assembleRelease`
(run in `android/`) copies the APK automatically:

```
builds/
  0.1.0/
    ViPER4Shizuku-0.1.0-debug.apk
    ViPER4Shizuku-0.1.0-release.apk
```

Bump `versionName` (and `versionCode`) in `android/app/build.gradle.kts` before building a new
version, so it lands in its own folder instead of overwriting the last one.

The APKs are ignored by git and must not be shared: they bundle ViPERDSP, which may not be
redistributed (see the [README](../README.md#why-there-is-no-apk-to-download)). Only this README
is tracked.
