package androidx.media3.exoplayer;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import androidx.media3.exoplayer.audio.LmgTransitionGainSink;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

/** Real patched fade-control class with recording renderer mocks, not an AudioTrack test. */
@RunWith(AndroidJUnit4.class)
public class LmgLiveGainReservationTest {
  @Test public void actualMasterAndDuckNotReplacedByUnity() throws Exception {
    Renderer a = mock(Renderer.class), b = mock(Renderer.class);
    PlayerAudioFadeControl control = new PlayerAudioFadeControl(new Renderer[]{a,b});
    control.setPlayerVolume(.125f);
    assertTrue(control.prepareLmgLiveUnityGains(0,1));
    ArgumentCaptor<Object> ca = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<Object> cb = ArgumentCaptor.forClass(Object.class);
    verify(a).handleMessage(eq(LmgTransitionGainSink.MESSAGE_TYPE), ca.capture());
    verify(b).handleMessage(eq(LmgTransitionGainSink.MESSAGE_TYPE), cb.capture());
    for (Object value : new Object[]{ca.getValue(), cb.getValue()}) {
      LmgTransitionGainSink.Update gain = (LmgTransitionGainSink.Update)value;
      assertEquals(1f, gain.transitionGain, 0f);
      assertEquals(.125f, gain.playerGain, 0f);
    }
  }
  @Test public void attenuatedOutgoingRejectsBeforeWrites() throws Exception {
    Renderer a = mock(Renderer.class), b = mock(Renderer.class);
    PlayerAudioFadeControl control = new PlayerAudioFadeControl(new Renderer[]{a,b});
    Method setter = PlayerAudioFadeControl.class.getDeclaredMethod("setVolume", int.class, float.class);
    setter.setAccessible(true); setter.invoke(control,0,.5f);
    clearInvocations(a,b);
    assertFalse(control.prepareLmgLiveUnityGains(0,1));
    verifyNoMoreInteractions(a,b);
  }
  @Test public void freshProofDoesNotEnterManualFade() throws Exception {
    Renderer a = mock(Renderer.class), b = mock(Renderer.class);
    PlayerAudioFadeControl control = new PlayerAudioFadeControl(new Renderer[]{a,b});
    control.setPlayerVolume(0f);
    assertTrue(control.prepareLmgLiveUnityGains(0,1));
    assertFalse(control.isCrossFadeInProgress());
    ArgumentCaptor<Object> gain = ArgumentCaptor.forClass(Object.class);
    verify(a).handleMessage(eq(LmgTransitionGainSink.MESSAGE_TYPE),gain.capture());
    assertEquals(0f, ((LmgTransitionGainSink.Update)gain.getValue()).playerGain,0f);
  }
}
