package androidx.media3.exoplayer.audio;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.source.MediaSource;

/**
 * Optional metadata-only hook for the LMG single-player AutoMix boundary.
 * Invoked immediately before AudioSink.handleBuffer on the playback thread.
 * A token identifies the CURRENT OUTPUT queue entry, not the renderer input stream.
 * Implementations may not call Player, retain/read/write sample bytes, block, change
 * volumes or return a consumption result. Ordinary sinks require no implementation.
 */
@UnstableApi
public interface LmgPcmBoundaryListener {
  static int protocolVersion() { return 1; }

  void onLmgPcmOutputBoundary(
      Object outputStreamToken,
      Timeline outputTimeline,
      @Nullable MediaSource.MediaPeriodId outputMediaPeriodId,
      @Nullable Format decodedFormat,
      long outputStreamOffsetUs,
      long rendererPositionUs,
      boolean identityChannelMapping,
      boolean tunneling);
}
