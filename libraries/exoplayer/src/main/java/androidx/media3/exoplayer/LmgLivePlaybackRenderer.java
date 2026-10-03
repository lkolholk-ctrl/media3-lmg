package androidx.media3.exoplayer;
import androidx.annotation.Nullable;
/** Existing renderer exposing its optional existing sink's client; no global registry. */
public interface LmgLivePlaybackRenderer {
  @Nullable LmgLivePlaybackClient getLmgLivePlaybackClient();
}
