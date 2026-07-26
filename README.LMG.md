# media3 — LiquidMusicGlass fork (crossfade)

*[Русская версия](README.LMG.ru.md)*

A fork of [`androidx/media`](https://github.com/androidx/media) (tag **1.5.1**)
that adds crossfading between tracks — overlapping the outgoing and incoming
track with a smooth volume transition. Stock media3 cannot do this: it switches
tracks back to back (gapless) and never plays two audio streams at once.

The crossfade logic reproduces the behaviour of the Apple Music for Android
player (Apple does not publish that implementation). This project is not
affiliated with or endorsed by Apple Inc. — see [`NOTICE.md`](NOTICE.md).

Legal information and the full list of changes: [`NOTICE.md`](NOTICE.md).
Version history: [`CHANGELOG.LMG.md`](CHANGELOG.LMG.md).

---

## Setup

Artifacts are published under their own group, `com.liquidmusicglass.media3`, so
that Gradle cannot confuse them with the original and silently resolve stock
media3 instead of the fork.

**1. Download the maven repository from a release** (a separate CI step):

```bash
VER=1.5.1-lmg29
curl -sSL -o media3-m2.zip \
  "https://github.com/lkolholk-ctrl/media3-lmg/releases/download/v${VER}/media3-${VER}-m2.zip"
mkdir -p media3-m2 && unzip -q media3-m2.zip -d media3-m2
```

**2. Register the repository** (`settings.gradle.kts`):

```kotlin
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("media3-m2")
            content { includeGroup("com.liquidmusicglass.media3") }
        }
        google()
        mavenCentral()
    }
}
```

**3. Dependencies** (`app/build.gradle.kts`):

```kotlin
configurations.configureEach {
    // The fork lives in the same Java packages as the original: two copies of the
    // classes will not build. Exclude stock media3 in case a transitive
    // dependency pulls it in.
    exclude(group = "androidx.media3")
}

dependencies {
    implementation("com.liquidmusicglass.media3:media3-common:1.5.1-lmg29")
    implementation("com.liquidmusicglass.media3:media3-exoplayer:1.5.1-lmg29")
    implementation("com.liquidmusicglass.media3:media3-session:1.5.1-lmg29")
    implementation("com.liquidmusicglass.media3:media3-ui:1.5.1-lmg29")
    // if needed: media3-extractor, media3-exoplayer-hls, media3-common-ktx,
    // media3-datasource, media3-decoder, media3-container, media3-database
}
```

Imports in your code **do not change** — classes are still `androidx.media3.*`.

---

## Using the crossfade

Crossfade settings belong to the player instance:

```kotlin
import androidx.media3.exoplayer.ExoPlayer

player.setCrossfadeConfiguration(
    ExoPlayer.CrossfadeConfiguration(
        /* durationUs    = */ 9_000_000L,  // fade length; 0 disables the crossfade
        /* curveType     = */ ExoPlayer.CrossfadeConfiguration.CURVE_DEFAULT,
        /* entryOffsetUs = */ 0L           // where the next track starts from
    )
)
```

The setting takes effect immediately, including for the track already playing:
the fade is armed during the last `durationUs` microseconds of the track. To turn
crossfading off, pass `durationUs = 0` or `CrossfadeConfiguration.DEFAULT`.

`curveType` selects the volume curve:

| Constant | Curve | When it fits |
|---|---|---|
| `CURVE_DEFAULT` | logarithmic in / exponential out | audible transition: the outgoing track drops while the incoming one rises |
| `CURVE_CONSTANT_POWER` | constant power | even overlap: total power stays constant, but the transition is heard as both tracks playing at once |
| `CURVE_EXPONENTIAL` | exponential | the outgoing track holds longer, the incoming one enters sharply |
| `CURVE_LINEAR` | linear | keeps the beat steady |

### Why these curves

The default is a logarithmic fade-in with an exponential fade-out — the same pair
the reference player uses. It produces an *audible transition*: the outgoing track
noticeably drops while the incoming one rises.

The obvious alternative, constant power, was the default in `lmg23`–`lmg28`. It is
mathematically nicer: both tracks sit at 0.71 at the midpoint, so total power stays
constant and loudness never dips. In practice listeners described it as "both
tracks just play at full volume" — the crossfade is heard as an overlap rather than
as a transition.

The trade-off is real and worth stating plainly: with the default curves total
power in the middle of the fade drops to roughly 0.12 instead of 1.0, i.e. the mix
gets quieter halfway through. That dip is what made a 12-second fade feel like 6–7
seconds. It is compensated by fade length rather than by curve shape — which is why
the duration range goes up to 18 seconds. If you prefer the even overlap, pass
`CURVE_CONSTANT_POWER` explicitly.

### When no crossfade happens

- crossfading is disabled (`durationUs = 0`);
- the next track is not prepared yet — the fade starts late and ends up shorter
  than configured; to avoid this, enable preloading:
  `player.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(30_000_000L))`;
- the track is shorter than twice the fade duration;
- the track duration is unknown;
- the next track is the consecutive track of the same album (same album title and
  disc number, track number follows): such transitions are meant to be gapless;
- the item is declared as a podcast or an audiobook: crossfading only makes sense
  for music;
- repeat-one mode is on.

### Diagnostics

By default the engine stays quiet: only anomalies are logged (the stuck-fade
watchdog and errors). Verbose logging is a toggle:

```kotlin
CrossfadeConfig.setDebugLogging(true)   // per-tick volume levels, arming, completion
```

Logs go through `Log.e` deliberately: R8 strips `Log.d`/`Log.v` in release builds,
which is exactly where the diagnostics are needed. Volume of logging is therefore
controlled by this flag rather than by log level.

Filter by `xfade`:

- `xfade ARM: remainingUs=… nextPrepared=…` — the fade was armed: shows how long
  before the end of the track and whether the next period was ready;
- `setFadeAudioEffect() new duration: …` — the duration that reached the engine (µs);
- `doCrossFade() fadeOutLevel: … fadeInLevel: …` — current volume levels;
- `xfade SKIP: …` — the fade was skipped on purpose (album gapless, non-music);
- `xfade WATCHDOG: …` — the fade got stuck and was force-reset.

---

## How it works

Stock media3 keeps a single audio renderer and switches periods back to back.
Overlapping requires two simultaneously sounding streams, therefore:

1. `DefaultRenderersFactory` creates a **second audio renderer**;
2. `DefaultMediaClock` no longer treats enabling a second audio renderer as an
   error;
3. the playback loop (`ExoPlayerImplInternal`) gains crossfade phases: arming N
   seconds before the end of the track, a per-tick volume update, advancing the
   playing period **without releasing** the previous one, and releasing the
   outgoing period once the fade completes;
4. `PlayerAudioFadeControl` computes both volumes per tick from the selected
   curve and applies them to the renderers via `MSG_SET_VOLUME`.

The fade start is positional: the last N seconds of the track, where N is the
configured duration.

---

## Building the fork

CI (`.github/workflows/build-aars.yml`) runs the crossfade unit tests, builds 11
modules into a maven repository and publishes it as a zip archive attached to the
GitHub Release `v<version>`:

```
media3-common, media3-common-ktx, media3-container, media3-database,
media3-datasource, media3-decoder, media3-extractor, media3-exoplayer,
media3-exoplayer-hls, media3-session, media3-ui
```

The version is defined **in two places, and they must match**:

- `constants.gradle` → `releaseVersion` — the artifact version;
- `.github/workflows/build-aars.yml` → `RELEASE_VERSION` — the release and
  archive name.

Bumping only one of them produces a release named after the old version holding
artifacts of the new one, and consumers fail with "dependency not found". This
already happened once — see `lmg19` in the version history.

Local build:

```bash
./gradlew publishAllPublicationsToLocalRepository
```

Running the tests:

```bash
./gradlew :lib-exoplayer:testReleaseUnitTest \
  --tests 'androidx.media3.exoplayer.PlayerAudioFadeControlCurvesTest' \
  --tests 'androidx.media3.exoplayer.CrossfadeConfigurationTest'
```
