/* LMG-fork. Licensed under the Apache License, Version 2.0. */
package androidx.media3.exoplayer;

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.MimeTypes;
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
      advance(player).withTimeoutMs(60_000).untilState(Player.STATE_ENDED);

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
  public void eighteenSecondOverlap_withMetadataAndRecipeRefresh_keepsOriginalFade() throws Exception {
    VolumeRenderer first = new VolumeRenderer(clock);
    VolumeRenderer second = new VolumeRenderer(clock);
    FakeRenderer metadataRenderer = new FakeRenderer(C.TRACK_TYPE_METADATA);
    ExoPlayer player = new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext())
        .setClock(clock)
        .setRenderers(first, second, metadataRenderer)
        .build();
    ExoPlayer.CrossfadeConfiguration recipe = new ExoPlayer.CrossfadeConfiguration(
        18_000_000, ExoPlayer.CrossfadeConfiguration.CURVE_DEFAULT, 0);
    player.setCrossfadeConfiguration(recipe);
    try {
      Format metadata = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_ID3).build();
      player.setMediaSources(ImmutableList.of(
          source(40_000_000, ExoPlayerTestRunner.AUDIO_FORMAT, metadata),
          source(40_000_000, ExoPlayerTestRunner.AUDIO_FORMAT, metadata)));
      player.prepare();
      player.play();
      advance(player).withTimeoutMs(60_000)
          .untilPositionDiscontinuityWithReason(Player.DISCONTINUITY_REASON_AUTO_TRANSITION);
      assertThat(player.getCurrentMediaItemIndex()).isEqualTo(1);
      assertThat(second.sawPartialGain).isTrue();
      assertThat(metadataRenderer.enabledCount).isEqualTo(2);
      assertThat((first.audioClock.getPositionUs() - first.streamStartPositionUs) / 1000)
          .isLessThan(39_500L);

      // lmg30 applies a new pair recipe without cancelling the overlap already in progress.
      player.setCrossfadeConfiguration(new ExoPlayer.CrossfadeConfiguration(
          18_000_000, ExoPlayer.CrossfadeConfiguration.CURVE_DEFAULT, 500_000));
      advance(player).withTimeoutMs(60_000).untilState(Player.STATE_ENDED);
      assertThat(player.getPlayerError()).isNull();
      assertThat(first.volume).isEqualTo(1f);
      assertThat(second.volume).isEqualTo(1f);
    } finally {
      player.release();
    }
  }

  @Test
  public void pauseBeforeHandoff_longPause_preservesBothStreams() throws Exception {
    checkPauseDuringOverlap(/* item= */ 0, /* positionMs= */ 17_000);
  }

  @Test
  public void pauseAfterHandoff_longPause_resumesIncomingRenderer() throws Exception {
    checkPauseDuringOverlap(/* item= */ 1, /* positionMs= */ 2_000);
  }

  private void checkPauseDuringOverlap(int item, long positionMs) throws Exception {
    VolumeRenderer first = new VolumeRenderer(clock);
    VolumeRenderer second = new VolumeRenderer(clock);
    ExoPlayer player = createPlayer(first, second);
    try {
      player.setMediaSources(ImmutableList.of(source(), source(), source()));
      player.prepare();
      advance(player).withTimeoutMs(60_000).untilPosition(item, positionMs);
      player.pause();
      advance(player).untilPendingCommandsAreFullyHandled();
      assertThat(first.getState()).isEqualTo(Renderer.STATE_ENABLED);
      assertThat(second.getState()).isEqualTo(Renderer.STATE_ENABLED);
      clock.advanceTime(20_000);
      player.play();
      advance(player).withTimeoutMs(60_000).untilState(Player.STATE_ENDED);
      assertThat(player.getPlayerError()).isNull();
      assertThat(second.sawPartialGain).isTrue();
      assertThat(first.volume).isEqualTo(1f);
      assertThat(second.volume).isEqualTo(1f);
    } finally {
      player.release();
    }
  }

  @Test
  public void disableOverlapBeforeHandoff_rewindsIncomingAndFinishesGapless() throws Exception {
    checkDisableDuringOverlap(/* item= */ 0, /* positionMs= */ 17_000);
  }

  @Test
  public void disableOverlapAfterHandoff_keepsIncomingAndFinishesGapless() throws Exception {
    checkDisableDuringOverlap(/* item= */ 1, /* positionMs= */ 2_000);
  }

  private void checkDisableDuringOverlap(int item, long positionMs) throws Exception {
    VolumeRenderer first = new VolumeRenderer(clock);
    VolumeRenderer second = new VolumeRenderer(clock);
    ExoPlayer player = createPlayer(first, second);
    try {
      player.setMediaSources(ImmutableList.of(source(), source(), source()));
      player.prepare();
      advance(player).withTimeoutMs(60_000).untilPosition(item, positionMs);
      player.setCrossfadeConfiguration(new ExoPlayer.CrossfadeConfiguration(
          0, ExoPlayer.CrossfadeConfiguration.CURVE_DEFAULT, 0));
      advance(player).withTimeoutMs(60_000).untilState(Player.STATE_ENDED);
      assertThat(player.getPlayerError()).isNull();
      assertThat(player.getCurrentMediaItemIndex()).isEqualTo(2);
      assertThat(first.volume).isEqualTo(1f);
      assertThat(second.volume).isEqualTo(1f);
    } finally {
      player.release();
    }
  }

  @Test
  public void steadyPlaybackAfterHandoff_pauseAndSeekKeepSecondRendererAudible() throws Exception {
    VolumeRenderer first = new VolumeRenderer(clock);
    VolumeRenderer second = new VolumeRenderer(clock);
    ExoPlayer player = createPlayer(first, second);
    try {
      player.setMediaSources(ImmutableList.of(
          source(80_000_000, ExoPlayerTestRunner.AUDIO_FORMAT),
          source(80_000_000, ExoPlayerTestRunner.AUDIO_FORMAT)));
      player.prepare();
      advance(player).withTimeoutMs(60_000).untilPosition(1, 50_000);
      player.pause();
      advance(player).untilPendingCommandsAreFullyHandled();
      assertThat(first.getState()).isEqualTo(Renderer.STATE_DISABLED);
      assertThat(second.getState()).isEqualTo(Renderer.STATE_ENABLED);
      assertThat(second.volume).isEqualTo(1f);
      player.seekTo(1, 60_000);
      player.play();
      advance(player).withTimeoutMs(60_000).untilPosition(1, 65_000);
      assertThat(player.getPlayerError()).isNull();
      assertThat(second.getState()).isEqualTo(Renderer.STATE_STARTED);
      assertThat(second.volume).isEqualTo(1f);
      advance(player).withTimeoutMs(60_000).untilState(Player.STATE_ENDED);
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
    return source(TRACK_DURATION_US, ExoPlayerTestRunner.AUDIO_FORMAT);
  }

  private static FakeMediaSource source(long durationUs, Format format, Format... formats) {
    return new FakeMediaSource.Builder()
        .setTimeline(new FakeTimeline(new TimelineWindowDefinition.Builder()
            .setWindowPositionInFirstPeriodUs(0)
            .setDurationUs(durationUs).build()))
        .setFormats(format, formats)
        .setTrackDataFactory(TrackDataFactory.samplesWithRateDurationAndKeyframeInterval(
            0, 20, durationUs, 1))
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
      if (messageType == androidx.media3.exoplayer.audio.LmgTransitionGainSink.MESSAGE_TYPE) {
        androidx.media3.exoplayer.audio.LmgTransitionGainSink.Update gain =
            (androidx.media3.exoplayer.audio.LmgTransitionGainSink.Update) message;
        volume = gain.combined();
        sawPartialGain |= volume > 0f && volume < 1f;
      } else if (messageType == Renderer.MSG_SET_VOLUME) {
        volume = (Float) message;
        sawPartialGain |= volume > 0f && volume < 1f;
      } else {
        super.handleMessage(messageType, message);
      }
    }
  }
}
