# Media3 1.11.0 / lmg31 — restoration from lmg30

Original source: `09b577b01a731885f1ee8707f9008de64badf223`.
Google source: `91fd5df7ab6b5c2f1a589fbdee5ee8deeae97f02` (1.11.0).

`AudioFadeControl.java`, `CrossfadeConfig.java`, and `PlayerAudioFadeControl.java`
are copied byte for byte from lmg30, including the original wall-clock throttling.
The original arming conditions, recipe handling, watchdog, seek/reset, release,
loading rules and ordering of fade processing are restored in the playback loop.
Additional cancellation on configuration, timeline, selection, repeat and shuffle
changes and the audio-only eligibility restriction have been removed.

Google 1.11 integration still requires RendererHolder lifecycle calls, per-renderer
queue pointers, audio stream/track-selection routing, the new notification signature,
and forwarding playback-thread volume/audio focus to the original controller.
These integration files are not byte-identical to lmg30. They must not be described
as a literal restoration of the entire old player.

GitHub runs the original curve/configuration tests and the integration tests before
publishing AARs. The integration tests include consecutive overlaps and an 18-second
overlap with a metadata track and a recipe update. They use paced fake renderers so
production timing code remains unchanged. No emulator is used. Successful tests and
an app debug build do not validate audible output on a real device.

This corrects the already published `1.11.0-lmg31`. The release tag and Maven zip
are replaced only after GitHub checks succeed. Consumers must replace their old
local Maven copy; the coordinates alone do not distinguish the corrected binaries.
