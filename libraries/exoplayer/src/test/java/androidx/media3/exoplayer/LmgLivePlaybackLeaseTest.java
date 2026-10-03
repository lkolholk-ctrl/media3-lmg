package androidx.media3.exoplayer;

import static org.junit.Assert.*;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Tests the real lease class. Period guard is deliberately a test fixture. */
public class LmgLivePlaybackLeaseTest {
  @Test public void exactGenerationAndRevisionRequired() {
    LmgLivePlaybackLease l = new LmgLivePlaybackLease(3,4,0,100,()->true,()->{});
    assertTrue(l.isCurrent(3,4)); assertFalse(l.isCurrent(2,4)); assertFalse(l.isCurrent(3,5));
  }
  @Test public void actualPeriodGuardIsRechecked() {
    AtomicBoolean valid = new AtomicBoolean(true);
    LmgLivePlaybackLease l = new LmgLivePlaybackLease(1,1,0,100,valid::get,()->{});
    assertTrue(l.isCurrent(1,1));valid.set(false);assertFalse(l.isCurrent(1,1));
  }
  @Test public void wrongThreadCannotUseLease() throws Exception {
    LmgLivePlaybackLease l = new LmgLivePlaybackLease(1,1,0,100,()->true,()->{});
    AtomicBoolean accepted = new AtomicBoolean(true);
    Thread t = new Thread(()->accepted.set(l.isCurrent(1,1)));t.start();t.join();assertFalse(accepted.get());
  }
  @Test public void crossThreadRevocationIsVisible() throws Exception {
    LmgLivePlaybackLease l = new LmgLivePlaybackLease(1,1,0,100,()->true,()->{});
    Thread t = new Thread(l::revoke);t.start();t.join();assertFalse(l.isCurrent(1,1));
  }
  @Test public void clockPinnedExactlyOnce() {
    AtomicInteger calls = new AtomicInteger();
    LmgLivePlaybackLease l = new LmgLivePlaybackLease(1,1,0,100,()->true,calls::incrementAndGet);
    l.pinClock();l.pinClock();assertEquals(1,calls.get());assertTrue(l.isClockPinned());
  }
  @Test public void invalidPeriodsCannotPinClock() {
    AtomicInteger calls = new AtomicInteger();
    LmgLivePlaybackLease l = new LmgLivePlaybackLease(1,1,0,100,()->false,calls::incrementAndGet);
    try {l.pinClock();fail();}catch(IllegalStateException expected){}assertEquals(0,calls.get());
  }
  @Test public void revokedPinnedLeaseIsNotCurrent() {
    LmgLivePlaybackLease l = new LmgLivePlaybackLease(1,1,0,100,()->true,()->{});
    l.pinClock();l.revoke();assertFalse(l.isClockPinned());
  }
  @Test public void applicationCannotConstructPublicGrant() {
    assertEquals(0,LmgLivePlaybackLease.class.getConstructors().length);
    assertTrue(Modifier.isFinal(LmgLivePlaybackLease.class.getModifiers()));
  }
}
