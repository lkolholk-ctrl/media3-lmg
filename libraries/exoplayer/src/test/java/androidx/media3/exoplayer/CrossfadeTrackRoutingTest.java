/* LMG-fork. Licensed under the Apache License, Version 2.0. */
package androidx.media3.exoplayer;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.TrackSelectorResult;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CrossfadeTrackRoutingTest {
  @Test
  public void routeToSecondDeck_preservesStreamIdentityMetadataAndOriginalSelection() {
    ExoTrackSelection selection = mock(ExoTrackSelection.class);
    SampleStream stream = mock(SampleStream.class);
    Object info = new Object();
    TrackSelectorResult original =
        new TrackSelectorResult(
            new RendererConfiguration[] {RendererConfiguration.DEFAULT, null},
            new ExoTrackSelection[] {selection, null}, Tracks.EMPTY, info);
    SampleStream[] streams = {stream, null};

    TrackSelectorResult routed = CrossfadeTrackRouting.moveSelection(original, 0, 1);
    CrossfadeTrackRouting.moveStream(streams, 0, 1);

    assertThat(original.isRendererEnabled(0)).isTrue();
    assertThat(original.isRendererEnabled(1)).isFalse();
    assertThat(routed.isRendererEnabled(0)).isFalse();
    assertThat(routed.isRendererEnabled(1)).isTrue();
    assertThat(routed.selections[1]).isSameInstanceAs(selection);
    assertThat(routed.tracks).isSameInstanceAs(original.tracks);
    assertThat(routed.info).isSameInstanceAs(info);
    assertThat(streams[0]).isNull();
    assertThat(streams[1]).isSameInstanceAs(stream);

    TrackSelectorResult returned = CrossfadeTrackRouting.moveSelection(routed, 1, 0);
    CrossfadeTrackRouting.moveStream(streams, 1, 0);
    assertThat(returned.isEquivalent(original)).isTrue();
    assertThat(streams[0]).isSameInstanceAs(stream);
    assertThat(streams[1]).isNull();
  }

  @Test
  public void occupiedTarget_isRejectedWithoutChangingSource() {
    SampleStream first = mock(SampleStream.class);
    SampleStream second = mock(SampleStream.class);
    SampleStream[] streams = {first, second};
    TrackSelectorResult selection =
        new TrackSelectorResult(
            new RendererConfiguration[] {RendererConfiguration.DEFAULT, RendererConfiguration.DEFAULT},
            new ExoTrackSelection[] {mock(ExoTrackSelection.class), mock(ExoTrackSelection.class)},
            Tracks.EMPTY, null);

    assertThrows(IllegalStateException.class,
        () -> CrossfadeTrackRouting.moveSelection(selection, 0, 1));
    assertThrows(IllegalStateException.class,
        () -> CrossfadeTrackRouting.moveStream(streams, 0, 1));
    assertThat(streams[0]).isSameInstanceAs(first);
    assertThat(streams[1]).isSameInstanceAs(second);
  }
}
