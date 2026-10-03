package androidx.media3.exoplayer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Playback grant created only by this player's internal playback owner.
 * Constructor is package-private; applications cannot authorize themselves with a boolean.
 * Identity is checked against the actual retained periods and renderer SampleStreams. */
public final class LmgLivePlaybackLease {
  public final long generation, revision;
  public final long outgoingOffsetUs, incomingOffsetUs;
  private final Thread owner;
  private final BooleanSupplier boundToActualPeriods;
  private final Runnable pinExistingOutputClock;
  private final AtomicBoolean revoked = new AtomicBoolean();
  private boolean pinned;

  LmgLivePlaybackLease(long generation, long revision, long outgoingOffsetUs,
      long incomingOffsetUs, BooleanSupplier valid, Runnable pinClock) {
    this.generation = generation; this.revision = revision;
    this.outgoingOffsetUs = outgoingOffsetUs; this.incomingOffsetUs = incomingOffsetUs;
    this.owner = Thread.currentThread();
    this.boundToActualPeriods = valid;
    this.pinExistingOutputClock = pinClock;
  }
  public boolean isCurrent(long expectedGeneration, long expectedRevision) {
    return owner == Thread.currentThread() && !revoked.get()
        && generation == expectedGeneration && revision == expectedRevision
        && boundToActualPeriods.getAsBoolean();
  }
  public void pinClock() {
    if (!isCurrent(generation, revision)) throw new IllegalStateException("Revoked playback lease");
    if (!pinned) { pinExistingOutputClock.run(); pinned = true; }
  }
  public boolean isClockPinned() { return pinned && isCurrent(generation, revision); }
  /** May revoke from another thread. Native cleanup remains on the playback owner. */
  public void revoke() { revoked.set(true); }
}
