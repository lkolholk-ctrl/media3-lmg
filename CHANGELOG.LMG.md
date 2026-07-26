# Fork version history

*[Русская версия](CHANGELOG.LMG.ru.md)*

Version format: `1.5.1-lmgN`, where `1.5.1` is the upstream base version.
Current: **1.5.1-lmg28**.

## 1.5.1-lmg28
- Podcasts and audiobooks are no longer crossfaded: overlapping speech turns it
  into mush. The type is taken from the item metadata; when no type is declared,
  behaviour is unchanged.
- Added tests for curve shapes and crossfade settings. Curve shape caused two
  regressions that could only be caught by ear (fade shorter than configured, and
  a volume dip at the start of the next track) — those properties are now checked
  by the build. Tests run in CI before publishing.

## 1.5.1-lmg27
- Crossfade settings moved from a static holder onto the player itself:
  `ExoPlayer.setCrossfadeConfiguration(...)`. They used to be process-wide, so two
  players in one app shared the same values. `CrossfadeConfig` is now only the
  verbose logging toggle.
- Fade duration is stored in microseconds instead of whole seconds: 7500 ms used
  to collapse to 7 s, losing fractional values from recipes.

## 1.5.1-lmg26
Verbose crossfade logging is off by default and enabled via
`CrossfadeConfig.setDebugLogging(true)`. The engine used to log volume levels on
every tick — several lines per second during normal playback. Only anomalies are
always logged: the stuck-fade watchdog and errors. The `Log.e` level is kept
deliberately — R8 strips `Log.d`/`Log.v` in release builds.

## 1.5.1-lmg25
The fade now also completes when the fade window elapses, not only by volume
level. The completion threshold ("outgoing quieter than 0.05") depends on the
curve: with an exponential curve it was reached by 93% of the window — well
before the end of the track — while with constant power (lmg23) only by 99.75%,
so the fade phase never closed. After the playing period advanced, the incoming
track was left at a reduced volume and the sound dipped at the start of the song.
The incoming renderer is now brought to full volume immediately on completion,
without waiting for the outgoing period to be released.

## 1.5.1-lmg24
Artifacts are published under the `com.liquidmusicglass.media3` group instead of
`androidx.media3`. Gradle can no longer resolve the original in place of the fork.
Java packages are unchanged — see `NOTICE.md`.

## 1.5.1-lmg23
The default volume curve is **constant power** in both directions. The previous
logarithmic/exponential pair dropped the outgoing track to 41% of its volume by
the middle of the window and to 23% by 70%, so a 12 s fade was heard as 6–7: the
duration was honest, the audible overlap was half of it. With constant power both
tracks sit at 71% at the midpoint and total loudness never dips.

## 1.5.1-lmg22
- Consecutive tracks of the same album are no longer crossfaded: same album title
  and disc number with the track number following means the transition is meant to
  be gapless.
- The entry offset into the next track (`entryOffsetMs`) is finally honoured by
  the engine: the incoming track can start from an offset. It only applies on a
  normal track start; seeking positions are left alone.

## 1.5.1-lmg21
The fade was compared against the reference behaviour and aligned with it:
- reverted an incorrect change to the time normalisation of the fade-in: with it,
  the incoming track played at zero volume for the whole fade;
- the curve coefficient is taken per curve type instead of one value for all;
- a pair is eligible only if the track is at least twice the fade duration; tracks
  with unknown duration no longer qualify — the fade-out maths falls apart on them;
- duration is compared in consistent units and is no longer reapplied every tick.

## 1.5.1-lmg19
Housekeeping release. The artifact version was bumped in `constants.gradle` but
not the release name in the workflow: release `lmg18` ended up overwritten with
`lmg19` artifacts and consumers broke. Both must be bumped.

## 1.5.1-lmg18
The fade phase no longer rolls back after the crossing point. The rollback muted
the incoming track at its own beginning — "the music fades out and disappears".

## 1.5.1-lmg17
Gain invariant: outside a fade every renderer returns to full volume. A ducked
renderer could otherwise stay that way and the track played silent.

## 1.5.1-lmg16
Removed the fixed 6 s fallback fade: without configured parameters no fade happens
at all.

## 1.5.1-lmg15
Fixed silence after pause and seek: after a fade the track plays on the second
renderer, which starting the renderers did not account for.

## 1.5.1-lmg14
Meaningful mapping from transition type to volume curve (the transition type is
semantics, not a curve index).

## 1.5.1-lmg13
An application-to-engine bridge (`CrossfadeConfig`) instead of a hardcoded 6
seconds.

## 1.5.1-lmg12
Fixed a crash on playback handoff: the position discontinuity event must always be
reported, not only for the last period.

## 1.5.1-lmg11
Found the cause of playback freezing a few seconds before the end of a track: the
player clock treated enabling a second audio renderer as an error and threw.

## 1.5.1-lmg10
Fixed the missing overlap: the incoming period started after the outgoing one had
ended. Added the stuck-fade watchdog.

## 1.5.1-lmg9
Unconditional crossfade diagnostics in the log.

## 1.5.1-lmg8
Fall back to a normal transition if the second renderer did not enable.

## 1.5.1-lmg7
Fade arming and the positional gate: the fade starts N seconds before the end of
the track.

## 1.5.1-lmg5 … lmg6
Fade control implementation and the first time the fade was switched on.

## 1.5.1-lmg2 … lmg4
Scaffolding: the fade control contract, the renderer index on a period, wiring
into the player core. Crossfade off by default.

## 1.5.1-lmg1
Fork baseline: building and publishing AARs, a unique version, without demo apps
and heavy test assets.
