/* LMG-fork. Licensed under the Apache License, Version 2.0. */
package androidx.media3.exoplayer;

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.util.Clock;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.test.utils.ExoPlayerTestRunner;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.FakeMediaPeriod.TrackDataFactory;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeRenderer;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowLog;

/** Playback-thread regression tests with independent audio clocks and generated sample streams. */
@RunWith(AndroidJUnit4.class)
public final class ExoPlayerCrossfadeTest {
  private static final long TRACK_DURATION_US = 20_000_000;
  private final FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);

  @Before
  public void enableDebugLogs() {
    CrossfadeConfig.setDebugLogging(true);
    ShadowLog.stream = System.out;
  }

  @After
  public void disableDebugLogs() {
    CrossfadeConfig.setDebugLogging(false);
    ShadowLog.stream = null;
  }

  @Test
  public void consecutiveOverlaps_reuseBothDecksAndFinishPlaylist() throws Exception {
    VolumeRenderer first = new VolumeRenderer(clock);
    VolumeRenderer second = new VolumeRenderer(clock);
    ExoPlayer player = createPlayer(first, second);
    List<Long> transitionPositions = new ArrayList<>();
    player.addListener(new Player.Listener() {
      @Override
      public void onPositionDiscontinuity(
          Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
          // Google reports the old item's duration for AUTO_TRANSITION, even during overlap.
          // Measure the still-running outgoing audio clock to verify an actual early handoff.
          VolumeRenderer outgoing = oldPosition.mediaItemIndex == 0 ? first : second;
          transitionPositions.add(
              (outgoing.audioClock.getPositionUs() - outgoing.streamStartPositionUs) / 1000);
        }
      }
    });
    try {
      player.setMediaSources(ImmutableList.of(source(), source(), source()));
      player.prepare();
      player.play();
      advance(player).untilState(Player.STATE_ENDED);

      assertThat(player.getPlayerError()).isNull();
      assertThat(transitionPositions).hasSize(2);
      for (long position : transitionPositions) {
        assertThat(position).isLessThan(19_500L);
      }
      assertThat(first.enabledCount).isAtLeast(2);
      assertThat(second.enabledCount).isAtLeast(1);
      assertThat(first.sawPartialGain).isTrue();
      assertThat(second.sawPartialGain).isTrue();
      assertThat(first.volume).isEqualTo(1f);
      assertThat(second.volume).isEqualTo(1f);
    } finally {
      player.release();
    }
  }

  @Test
  public void disableAfterMetadataHandoff_preservesIncomingDeckAndPlayerVolume() throws Exception {
    VolumeRenderer first = new VolumeRenderer(clock);
    VolumeRenderer second = new VolumeRenderer(clock);
    ExoPlayer player = createPlayer(first, second);
    try {
      player.setMediaSources(ImmutableList.of(source(), source()));
      player.prepare();
      player.play();
      advance(player)
          .untilPositionDiscontinuityWithReason(Player.DISCONTINUITY_REASON_AUTO_TRANSITION);
      assertThat(player.getCurrentMediaItemIndex()).isEqualTo(1);
      assertThat(second.sawPartialGain).isTrue();

      player.setVolume(0.3f);
      player.setCrossfadeConfiguration(ExoPlayer.CrossfadeConfiguration.DEFAULT);
      advance(player).untilState(Player.STATE_ENDED);

      assertThat(player.getPlayerError()).isNull();
      assertThat(second.volume).isEqualTo(0.3f);
      assertThat(second.sampleBufferReadCount).isGreaterThan(300);
    } finally {
      player.release();
    }
  }

  private ExoPlayer createPlayer(VolumeRenderer first, VolumeRenderer second) {
    ExoPlayer player = new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext())
        .setClock(clock)
        .setRenderers(first, second)
        .setPreloadConfiguration(new ExoPlayer.PreloadConfiguration(30_000_000))
        .build();
    player.setCrossfadeConfiguration(new ExoPlayer.CrossfadeConfiguration(
        4_000_000, ExoPlayer.CrossfadeConfiguration.CURVE_DEFAULT, 0));
    return player;
  }

  private static FakeMediaSource source() {
    return new FakeMediaSource.Builder()
        .setTimeline(new FakeTimeline(new TimelineWindowDefinition.Builder()
            .setWindowPositionInFirstPeriodUs(0)
            .setDurationUs(TRACK_DURATION_US).build()))
        .setFormats(ExoPlayerTestRunner.AUDIO_FORMAT)
        .setTrackDataFactory(TrackDataFactory.samplesWithRateDurationAndKeyframeInterval(
            0, 20, TRACK_DURATION_US, 1))
        .build();
  }

  private static final class VolumeRenderer extends FakeRenderer {
    private final StandaloneMediaClock audioClock;
    private long streamStartPositionUs;
    public volatile float volume = 1f;
    public volatile boolean sawPartialGain;

    public VolumeRenderer(Clock clock) {
      super(C.TRACK_TYPE_AUDIO);
      audioClock = new StandaloneMediaClock(clock);
    }

    @Override
    public MediaClock getMediaClock() {
      return audioClock;
    }

    @Override
    protected void onStreamChanged(
        Format[] formats, long startPositionUs, long offsetUs, MediaPeriodId mediaPeriodId) {
      streamStartPositionUs = startPositionUs;
    }

    @Override
    protected void onPositionReset(long positionUs, boolean joining, boolean resetToKeyFrame)
        throws ExoPlaybackException {
      super.onPositionReset(positionUs, joining, resetToKeyFrame);
      // AudioSink starts the incoming stream immediately, ahead of the outgoing master clock.
      audioClock.resetPosition(Math.max(positionUs, streamStartPositionUs));
    }

    @Override
    protected void onStarted() {
      audioClock.start();
    }

    @Override
    protected void onStopped() {
      audioClock.stop();
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
      // Each sink continues on its own clock when the player's master changes at the handoff.
      super.render(audioClock.getPositionUs(), elapsedRealtimeUs);
    }

    @Override
    public void handleMessage(int messageType, Object message) throws ExoPlaybackException {
      if (messageType == Renderer.MSG_SET_VOLUME) {
        volume = (Float) message;
        sawPartialGain |= volume > 0f && volume < 1f;
      } else {
        super.handleMessage(messageType, message);
      }
    }
  }
}
