package androidx.media3.exoplayer.audio;

/** Optional sink extension. It distinguishes the transition multiplier from Player/focus volume.
 * It neither creates an audio output nor grants MediaClock/period ownership.
 */
public interface LmgTransitionGainSink {
  // Fork-private renderer extension, not an upstream Renderer.MessageType assignment.
  int MESSAGE_TYPE = 0x4c4d4701;
  static int protocolVersion() { return 1; }

  void onLmgTransitionGain(float transitionGain, float playerGain);
  void onLmgPlayerGain(float playerGain);

  /** Compatibility path for sinks that do not implement the optional split-gain hook. */
  interface LegacyVolume { void set(float value); }

  static void dispatch(Object target, Update gain, LegacyVolume legacy) {
    if (gain == null || legacy == null) throw new IllegalArgumentException("Missing gain dispatch input");
    if (target instanceof LmgTransitionGainSink) {
      ((LmgTransitionGainSink) target).onLmgTransitionGain(gain.transitionGain, gain.playerGain);
    } else {
      legacy.set(gain.combined());
    }
  }

  static void dispatchPlayer(Object target, float playerGain, LegacyVolume legacy) {
    if (Float.isNaN(playerGain) || Float.isInfinite(playerGain) || playerGain < 0f || playerGain > 1f || legacy == null)
      throw new IllegalArgumentException("Invalid player gain");
    if (target instanceof LmgTransitionGainSink) {
      ((LmgTransitionGainSink) target).onLmgPlayerGain(playerGain);
    } else {
      legacy.set(playerGain);
    }
  }

  final class Update {
    public final float transitionGain;
    public final float playerGain;
    public Update(float transitionGain, float playerGain) {
      if (Float.isNaN(transitionGain) || Float.isInfinite(transitionGain)
          || Float.isNaN(playerGain) || Float.isInfinite(playerGain)
          || transitionGain < 0f || transitionGain > 1f || playerGain < 0f || playerGain > 1f) {
        throw new IllegalArgumentException("Invalid LMG gain components");
      }
      this.transitionGain = transitionGain;
      this.playerGain = playerGain;
    }
    public float combined() { return transitionGain * playerGain; }
  }
}
