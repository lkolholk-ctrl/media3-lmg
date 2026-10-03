package androidx.media3.exoplayer.audio;

import org.junit.Test;

public final class LmgTransitionGainMessageTest {
  @Test public void protocolKnown() { LmgTransitionGainProtocolScenarios.protocolKnown(); }
  @Test public void combinedExactAcrossGrid() { LmgTransitionGainProtocolScenarios.combinedExactAcrossGrid(); }
  @Test public void zeroMaster() { LmgTransitionGainProtocolScenarios.zeroMaster(); }
  @Test public void signedZero() { LmgTransitionGainProtocolScenarios.signedZero(); }
  @Test public void invalidComponentsFail() { LmgTransitionGainProtocolScenarios.invalidComponentsFail(); }
  @Test public void extensionReceivesSeparateNotLegacy() { LmgTransitionGainProtocolScenarios.extensionReceivesSeparateNotLegacy(); }
  @Test public void legacyGetsSameProduct() { LmgTransitionGainProtocolScenarios.legacyGetsSameProduct(); }
  @Test public void originalExceptionPropagates() { LmgTransitionGainProtocolScenarios.originalExceptionPropagates(); }
  @Test public void normalPlayerMessageIsTyped() { LmgTransitionGainProtocolScenarios.normalPlayerMessageIsTyped(); }
  @Test public void normalPlayerFallbackPreservesValue() { LmgTransitionGainProtocolScenarios.normalPlayerFallbackPreservesValue(); }
}
