package androidx.media3.exoplayer.audio;

/** Actual optional protocol tested without Android classes. This does not execute a Renderer. */
public final class LmgTransitionGainProtocolScenarios {
  private static int checks;
  private static void check(boolean value) { checks++; if (!value) throw new AssertionError("gain protocol"); }
  public static void protocolKnown() {
    check(LmgTransitionGainSink.protocolVersion() == 1);
    check(LmgTransitionGainSink.MESSAGE_TYPE == 0x4c4d4701);
  }
  public static void combinedExactAcrossGrid() {
    for (int a = 0; a <= 32; a++) for (int b = 0; b <= 32; b++) {
      float fade = a / 32f, master = b / 32f;
      LmgTransitionGainSink.Update u = new LmgTransitionGainSink.Update(fade, master);
      check(Float.floatToRawIntBits(u.combined()) == Float.floatToRawIntBits(fade * master));
    }
  }
  public static void zeroMaster() {
    for (float fade : new float[]{0f, .1f, .5f, 1f})
      check(new LmgTransitionGainSink.Update(fade, 0f).combined() == 0f);
  }
  public static void signedZero() {
    check(Float.floatToRawIntBits(new LmgTransitionGainSink.Update(-0f, .5f).combined()) == 0x80000000);
    check(Float.floatToRawIntBits(new LmgTransitionGainSink.Update(1f, -0f).combined()) == 0x80000000);
  }
  public static void invalidComponentsFail() {
    for (float v : new float[]{Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -.1f, 1.1f}) {
      boolean a = false, b = false;
      try { new LmgTransitionGainSink.Update(v, 1f); } catch (IllegalArgumentException expected) { a = true; }
      try { new LmgTransitionGainSink.Update(1f, v); } catch (IllegalArgumentException expected) { b = true; }
      check(a && b);
    }
  }
  public static void extensionReceivesSeparateNotLegacy() {
    float[] actual = {-1f, -1f};
    LmgTransitionGainSink sink = new LmgTransitionGainSink() {
      public void onLmgTransitionGain(float a, float b) { actual[0] = a; actual[1] = b; }
      public void onLmgPlayerGain(float b) { throw new AssertionError("wrong branch"); }
    };
    LmgTransitionGainSink.dispatch(sink, new LmgTransitionGainSink.Update(.2f, .7f),
        value -> { throw new AssertionError("legacy path invoked"); });
    check(actual[0] == .2f && actual[1] == .7f);
  }
  public static void legacyGetsSameProduct() {
    float[] actual = {-1f};
    LmgTransitionGainSink.dispatch(new Object(), new LmgTransitionGainSink.Update(.2f, .7f), value -> actual[0] = value);
    check(Float.floatToRawIntBits(actual[0]) == Float.floatToRawIntBits(.2f * .7f));
  }
  public static void originalExceptionPropagates() {
    RuntimeException original = new IllegalStateException("test-only sink");
    try {
      LmgTransitionGainSink.dispatch(new LmgTransitionGainSink() {
            public void onLmgTransitionGain(float a, float b) { throw original; }
            public void onLmgPlayerGain(float b) { throw original; }
          },
          new LmgTransitionGainSink.Update(1f,1f), value -> {});
      throw new AssertionError("missing failure");
    } catch (RuntimeException e) { check(e == original); }
  }
  public static void normalPlayerMessageIsTyped() {
    float[] actual = {-1f};
    LmgTransitionGainSink sink = new LmgTransitionGainSink() {
      public void onLmgTransitionGain(float a, float b) { throw new AssertionError("wrong branch"); }
      public void onLmgPlayerGain(float b) { actual[0] = b; }
    };
    LmgTransitionGainSink.dispatchPlayer(sink, .3f, value -> { throw new AssertionError("wrong fallback"); });
    check(actual[0] == .3f);
  }
  public static void normalPlayerFallbackPreservesValue() {
    float[] actual = {-1f};
    LmgTransitionGainSink.dispatchPlayer(new Object(), .3f, value -> actual[0] = value);
    check(actual[0] == .3f);
  }
  public static void main(String[] args) {
    protocolKnown();combinedExactAcrossGrid();zeroMaster();signedZero();invalidComponentsFail();
    extensionReceivesSeparateNotLegacy();legacyGetsSameProduct();originalExceptionPropagates();normalPlayerMessageIsTyped();normalPlayerFallbackPreservesValue();
    System.out.println("GAIN_PROTOCOL_HOST_VERIFIED: 10 groups, " + checks + " checks; no Renderer/device execution");
  }
}
