# Media3 1.11.1 / lmg32

Google source: tag `1.11.1`, commit `915b73c447aed6471aa33380a95126121a79375d`.
LMG source: the installed `1.5.1-lmg30-boundary4-silence1` tree, including
`491a7f6640` and its working-tree lifecycle fixes, carried forward on the existing
1.11 migration (`877d68b985`). This is a source migration, not a version-string change.

All upstream library files are present: 6,245 are byte-identical to Google 1.11.1;
13 existing files contain the LMG integration. New LMG interfaces/controllers and
tests are listed separately in `UPSTREAM-AUDIT.json`. Symlinks are compared as links.

## Preserved LMG behavior

- The original public crossfade configuration, curve formulas, entry offset,
  album gapless rules, recipe updates and two audio renderers.
- Per-sink factory customization and player/focus gain independent of transition
  gain. Typed gain messages retain the fallback for ordinary sinks.
- Decoded-output identity, PCM boundary and channel mapping callbacks. Pending
  output keeps its own timeline/period identity instead of borrowing the newest
  configured stream's identity.
- Opt-in live playback leases, existing-output ownership, clock handoff and
  cancellation. These contracts do not create a third player or audio output.
- Monotonic, injected fade timing; pausing freezes the transition/watchdog.
  Cancellation disables discarded streams before resetting gain/releasing periods,
  preserves the current period, and rewinds an unplayed incoming item.

## Integration with 1.11.1

Renderer start/stop/enable/reset goes through `RendererHolder`. Stream arrays and
track selections move together to the actual audio renderer; old logical-slot
mapping helpers must not be applied again over these already-routed arrays.
Routing invalidates the affected renderer-state confirmations. Metadata/text
handoff from the earlier 1.11 migration remains intact.

The new `AudioSinkConfig` path and `codec.useBuffer` semantics remain intact.
OutputStreamInfo retains Google's duration/flags plus the captured LMG identity.
Google's seek behavior adopts pending output information before clearing it.
The complete 1.11.1 changes, including secondary-renderer and audio-output fixes,
remain in the fork.

Consumers with Kotlin `AudioSink by delegate` wrappers must override
`configure(AudioSinkConfig)` explicitly. Only overriding the deprecated
three-argument method can silently bypass wrapper lifecycle bookkeeping.
Forward the original config object so timeline, period and channel mapping survive.
All consumer modules must use `com.liquidmusicglass.media3:*:1.11.1-lmg32` together.

## Validation and publication

174 selected JVM tests passed, with no failures, errors or skips: queue/routing,
clock, gain dispatch, decoded identity, live leases and real ExoPlayer integration
with fake renderers. Integration coverage includes consecutive overlaps, metadata,
an 18-second overlap, long pauses before/after handoff, disabling overlap on either
side of handoff, and pause/seek during steady playback on the second renderer.

11 core AARs plus sources and Maven metadata were built with JDK 21, Gradle 9.1,
AGP 9.0.1 and compileSdk 36. `-PlmgCoreOnly=true` limits configuration to the core
publication/test dependency graph; all upstream library modules remain available
in the normal full build. CI repeats the selected tests before publication.
Published version assets are immutable; the old lmg31 replacement exception is removed.

These tests do not reproduce HONOR firmware scheduling or establish its cause.
The consuming app can use the new AudioOutputProvider API to retain compatible
PCM outputs; that device workaround is separate from this library migration.
Actual audible output, routing and background behavior require device validation.
