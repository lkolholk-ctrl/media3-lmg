package androidx.media3.exoplayer.audio;
import androidx.annotation.Nullable;
import androidx.media3.exoplayer.LmgLivePlaybackClient;
public interface LmgLivePlaybackSink {
  @Nullable LmgLivePlaybackClient getLmgLivePlaybackClient();
  static int protocolVersion() { return 1; }
}
