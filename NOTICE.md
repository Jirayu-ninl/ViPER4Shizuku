# Notices

Copyright (C) 2026 TheIceJi (https://www.theiceji.com/)

ViPER4Shizuku is licensed under the GNU General Public License v3.0 (see [LICENSE](LICENSE)).
The copyright above covers the changes and the new code in this repository. The parts forked from
ViPER4Android keep their authors' copyright (below). It is built from, and depends on, the
following work.

## ViPER4Android app (forked)

- Source: https://github.com/likelikeslike/ViPER4Android
- Forked from: v2.1.1 (commit `dfcbda7`, 9 October 2026)
- Copyright: likelikeslike and contributors
- License: GPL-3.0

The UI, effect model, presets, per-device profiles, parameter serialisation and engine service
in `android/app/` are a fork of this app, modified by TheIceJi since 18 October 2026. Changes:
the root, driver, HIDL/AIDL and update-checker paths were removed; the transport to the DSP was
replaced with an in-process JNI one; system audio capture through Shizuku and the in-app
player were added.

## ViPERDSP (git submodule, not part of this repository)

- Source: https://github.com/likelikeslike/ViPERDSP (pinned at `third_party/ViPERDSP`)
- Original work: ViPER520 and Zhuhang, ViPER4Android. Copyright (C) 2010-2017 ViPER ACOUSTICS
- Reverse engineering: Martmists, Iscle and likelikeslike
  ([AndroidAudioMods/ViPERFX_RE](https://github.com/AndroidAudioMods/ViPERFX_RE),
  [likelikeslike/ViPERFX_RE](https://github.com/likelikeslike/ViPERFX_RE))
- License: **none**. Its README states that AndroidAudioMods/ViPERFX_RE, the reverse-engineered
  code it is based on, carries no license and is treated as All Rights Reserved, and that
  ViPERDSP is provided for personal use only and must not be used for any commercial purpose.

ViPERDSP is not covered by this repository's GPL-3.0 license and is not redistributed here: the
repository only records which commit of it to fetch. It is compiled unmodified into the app's
native library, which is why built APKs must not be redistributed.

ViPERDSP itself bundles PFFFT by Julien Pommier (FFTPACK-derived, BSD-style license; see the
header of `viper/utils/pffft.c` in the submodule).

## Shizuku API

- Source: https://github.com/RikkaApps/Shizuku-API
- Copyright: RikkaApps
- License: MIT

## Android Open Source Project

`android/hidden-api/` contains compile-only stubs of `@SystemApi` classes from AOSP
(`android.media.audiopolicy`, android-16.0.0_r1). They declare the signatures only and are not
packaged into the app.

- Copyright: The Android Open Source Project
- License: Apache-2.0

## AndroidX, Jetpack Compose, Media3, Room, DataStore, Hilt, Kotlin coroutines

Fetched from Google Maven and Maven Central at build time.

- License: Apache-2.0

## Trademarks

ViPER4Android and ViPER are names of ViPER's Audio (ViPER ACOUSTICS). This project is unofficial
and is not affiliated with or endorsed by them, by likelikeslike or by RikkaApps.
