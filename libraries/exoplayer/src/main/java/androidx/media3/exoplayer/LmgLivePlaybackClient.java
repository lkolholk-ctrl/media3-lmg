package androidx.media3.exoplayer;

import androidx.annotation.Nullable;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;

/** Optional single-player, same-output experimental client. Invoked on playback looper.
 * This protocol does not create a renderer, player, clock thread or audio device. */
public interface LmgLivePlaybackClient {
  final class Request {
    public final long generation, revision, outgoingCueUs, incomingCueUs;
    public Request(long generation, long revision, long outgoingCueUs, long incomingCueUs) {
      if (generation <= 0 || revision < 0 || outgoingCueUs < 0 || incomingCueUs < 0) {
        throw new IllegalArgumentException("Invalid live request");
      }
      this.generation = generation; this.revision = revision;
      this.outgoingCueUs = outgoingCueUs; this.incomingCueUs = incomingCueUs;
    }
  }
  @Nullable Request pendingRequest();
  boolean matches(Timeline timeline, MediaPeriodId periodId, int side);
  void onLease(LmgLivePlaybackLease lease);
  /** Bounded work, no sleeping. Source starvation must not advance a wall-clock substitute. */
  void onWork();
  /** Source media position derived from the actual output sink, or C.TIME_UNSET. */
  long sourcePositionUs(int side);
  boolean transitionReachedOutput();
  boolean outputFullyEnded();
  void beforeMetadataSwitch();
  /** Revocation + terminal admission cleanup. Originals cannot be replayed after claim. */
  void onCancelled();
  void onCompleted();
}
