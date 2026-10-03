package androidx.media3.exoplayer;
import static org.junit.Assert.*;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.test.utils.FakeClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LmgLiveMediaClockTest {
  private static final class OutputClock implements MediaClock {
    long position=2000;
    public long getPositionUs(){return position;}
    public boolean hasSkippedSilenceSinceLastCall(){return false;}
    public PlaybackParameters getPlaybackParameters(){return PlaybackParameters.DEFAULT;}
    public void setPlaybackParameters(PlaybackParameters p){if(!p.equals(PlaybackParameters.DEFAULT))throw new IllegalStateException();}
  }
  @Test public void unavailablePcmDoesNotAdvanceStandaloneTime() {
    FakeClock clock=new FakeClock(0);DefaultMediaClock d=new DefaultMediaClock(p->{},clock);
    OutputClock output=new OutputClock();d.setLmgOwnedClock(output);d.start();clock.advanceTime(5000);
    assertEquals(2000,d.syncAndGetPositionUs(true));assertEquals(2000,d.getPositionUs());
    output.position=3500;assertEquals(3500,d.syncAndGetPositionUs(false));
  }
  @Test public void explicitReleaseAnchorsNormalClock() {
    DefaultMediaClock d=new DefaultMediaClock(p->{},new FakeClock(0));d.setLmgOwnedClock(new OutputClock());
    d.clearLmgOwnedClock(12345);assertEquals(12345,d.syncAndGetPositionUs(false));
  }
  @Test public void liveClockRejectsForeignSpeed() {
    DefaultMediaClock d=new DefaultMediaClock(p->{},new FakeClock(0));d.setLmgOwnedClock(new OutputClock());
    try{d.setPlaybackParameters(new PlaybackParameters(2));fail();}catch(IllegalStateException expected){}
    assertEquals(PlaybackParameters.DEFAULT,d.getPlaybackParameters());
  }
  @Test public void resetCannotSilentlyDropLease() {
    DefaultMediaClock d=new DefaultMediaClock(p->{},new FakeClock(0));d.setLmgOwnedClock(new OutputClock());
    try{d.resetPosition(500);fail();}catch(IllegalStateException expected){}
    try{d.setLmgOwnedClock(new OutputClock());fail();}catch(IllegalStateException expected){}
    assertEquals(2000,d.getPositionUs());
  }
}
