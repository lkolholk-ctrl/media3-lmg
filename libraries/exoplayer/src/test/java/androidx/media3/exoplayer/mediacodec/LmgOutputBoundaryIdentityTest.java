package androidx.media3.exoplayer.mediacodec;

import static org.junit.Assert.*;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.audio.LmgPcmBoundaryListener;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;

/** Production OutputStreamInfo constructors. Full decoder-drain/device tests are separate gates. */
@RunWith(AndroidJUnit4.class)
public final class LmgOutputBoundaryIdentityTest {
  private static Class<?> entryClass() throws Exception {
    return Class.forName("androidx.media3.exoplayer.mediacodec.MediaCodecRenderer$OutputStreamInfo");
  }
  private static Object field(Object object, String name) throws Exception {
    Field f=entryClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
  }
  private static Object entry(MediaPeriodId id, long offset) throws Exception {
    for(Constructor<?> c:entryClass().getDeclaredConstructors()) {
      Class<?>[] p=c.getParameterTypes();
      if(p.length>=2 && p[p.length-2]==Timeline.class && p[p.length-1]==MediaPeriodId.class) {
        c.setAccessible(true);
        if(p.length==5)return c.newInstance(11L,22L,offset,Timeline.EMPTY,id);
        if(p.length==7)return c.newInstance(11L,22L,offset,44L,0,Timeline.EMPTY,id);
      }
    }
    throw new AssertionError("No output-bound constructor in patched fork");
  }
  @Test public void protocol() { assertEquals(1,LmgPcmBoundaryListener.protocolVersion()); }
  @Test public void capturedIdNotMutableInputId() throws Exception {
    MediaPeriodId a=new MediaPeriodId("a");MediaPeriodId b=new MediaPeriodId("b");
    Object old=entry(a,100);Object next=entry(b,200);
    assertSame(a,field(old,"lmgOutputMediaPeriodId"));assertSame(b,field(next,"lmgOutputMediaPeriodId"));
  }
  @Test public void capturedTimeline() throws Exception {
    assertSame(Timeline.EMPTY,field(entry(new MediaPeriodId("a"),10),"lmgOutputTimeline"));
  }
  @Test public void originalOffsetUnchanged() throws Exception {
    Object e=entry(new MediaPeriodId("a"),123);
    assertEquals(123L,field(e,"streamOffsetUs"));
    assertEquals(123L,field(e,"lmgOutputOffsetUs"));
  }
  @Test public void originalBoundaryUnchanged() throws Exception {
    Object e=entry(new MediaPeriodId("a"),100);
    assertEquals(11L,field(e,"previousStreamLastBufferTimeUs"));assertEquals(22L,field(e,"startPositionUs"));
  }
  @Test public void differentOccurrencesHaveDifferentTokens() throws Exception {
    MediaPeriodId id=new MediaPeriodId("same");assertNotSame(entry(id,100),entry(id,100));
  }
  @Test public void unsetDoesNotInventAnId() throws Exception {
    Field f=entryClass().getDeclaredField("UNSET");f.setAccessible(true);
    assertNull(field(f.get(null),"lmgOutputMediaPeriodId"));
  }
  @Test public void capturedEntryInitiallyValid() throws Exception {
    assertEquals(true,field(entry(new MediaPeriodId("a"),10),"lmgOutputIdentityValid"));
  }
}
