# NOTICE

*[Русская версия](NOTICE.ru.md)*

This product is a **modified version of AndroidX Media3** (project `androidx/media`,
tag `1.5.1`).

Original code: Copyright (C) The Android Open Source Project, licensed under the
Apache License 2.0 — full text in [`LICENSE`](LICENSE).
Original project: https://github.com/androidx/media

Modifications: Copyright (C) 2026 LiquidMusicGlass. Distributed under the same
Apache License 2.0 terms.

## Statement of changes

As required by section 4(b) of the Apache License 2.0, the files below were
modified relative to upstream `1.5.1`. All changes are marked with comments in
the code.

### Player code

| File | Nature of changes |
|---|---|
| `libraries/exoplayer/src/main/java/androidx/media3/exoplayer/AudioFadeControl.java` | **New file.** Fade control contract: curve types, crossfade phases, transition description. |
| `.../exoplayer/PlayerAudioFadeControl.java` | **New file.** Implementation: volume levels per curve, crossfade tick, eligibility rules for a track pair, gain restoration. |
| `.../exoplayer/CrossfadeConfig.java` | **New file.** Verbose crossfade logging toggle. |
| `.../exoplayer/ExoPlayer.java` | Added `CrossfadeConfiguration` plus `setCrossfadeConfiguration`/`getCrossfadeConfiguration`. |
| `.../exoplayer/ExoPlayerImpl.java` | Stores crossfade settings and forwards them to the playback loop. |
| `.../exoplayer/SimpleExoPlayer.java` | Delegates the new crossfade methods. |
| `.../exoplayer/ExoPlayerImplInternal.java` | Crossfade hooks in the playback loop: arming, per-tick updates, releasing the outgoing period, renderer assignment, stuck-fade watchdog, skipping the fade between consecutive album tracks and for non-music content. |
| `.../exoplayer/MediaPeriodHolder.java` | Index of the renderer a period plays on, plus a link to the previous period. |
| `.../exoplayer/MediaPeriodQueue.java` | Advancing the playing period without releasing the previous one (required for overlap) and the entry offset into the next track. |
| `.../exoplayer/DefaultMediaClock.java` | Enabling a second audio renderer is no longer treated as an error: during a crossfade two audio renderers run at once. |
| `.../exoplayer/DefaultRenderersFactory.java` | Creates the second audio renderer. |

### Tests

| File | Nature of changes |
|---|---|
| `libraries/exoplayer/src/test/java/androidx/media3/exoplayer/PlayerAudioFadeControlCurvesTest.java` | **New file.** Curve shape tests. |
| `.../exoplayer/CrossfadeConfigurationTest.java` | **New file.** Crossfade settings tests. |

### Build and publishing

| File | Nature of changes |
|---|---|
| `build.gradle`, `common_library_config.gradle`, `publish.gradle` | Artifact coordinates changed from `androidx.media3` to `com.liquidmusicglass.media3`; publishing no longer depends on `lint`/`test` tasks. |
| `missing_aar_type_workaround.gradle` | Accounts for the new artifact group. |
| `constants.gradle` | Fork release version (`1.5.1-lmgN`). |
| `settings.gradle` | Demo apps, testapp and test-only modules excluded: the fork builds library AARs only. |
| `libraries/test_data` | Heavy test media assets removed. |
| `.github/workflows/build-aars.yml` | **New file.** Builds AARs, runs the crossfade unit tests and publishes a maven repository to a GitHub Release. |

## Origin of the crossfade implementation

The crossfade logic in this fork reproduces the behaviour of the **Apple Music
for Android** player, studied from the shipped application: the order of
transition phases, the use of two simultaneous audio renderers, the eligibility
rules for a track pair, the volume curve formulas and the fade completion
condition.

Apple does not publish this implementation — it is available neither in ExoPlayer
nor in any SDK. Copyright in the original implementation belongs to Apple Inc.

This project is **not affiliated with, endorsed by, or supported by Apple Inc.**
Apple Music is a trademark of Apple Inc.

This notice is not, and does not substitute for, permission from the rights
holder: it records the origin so that nobody mistakes this implementation for
original work by the fork's authors or for part of AndroidX Media3.

## Note on Java packages

Class packages remain `androidx.media3.*` — only the artifact coordinates
changed. This means the fork and the original `androidx.media3` **cannot coexist**
in one build: pulling in both fails with duplicate classes. That is intentional,
so that substituting one for the other is visible rather than silent.
