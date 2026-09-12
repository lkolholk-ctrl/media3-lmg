/*
 * Copyright 2026 The LMG contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package androidx.media3.exoplayer;

import static com.google.common.base.Preconditions.checkState;

import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.TrackSelectorResult;

/** Keeps Google's stream, configuration and selection slots aligned during LMG overlap. */
/* package */ final class CrossfadeTrackRouting {
  private CrossfadeTrackRouting() {}

  public static TrackSelectorResult moveSelection(
      TrackSelectorResult result, int source, int target) {
    if (source == target) {
      return result;
    }
    checkState(result.rendererConfigurations[target] == null && result.selections[target] == null);
    RendererConfiguration[] configurations = result.rendererConfigurations.clone();
    ExoTrackSelection[] selections = result.selections.clone();
    configurations[target] = configurations[source];
    selections[target] = selections[source];
    configurations[source] = null;
    selections[source] = null;
    return new TrackSelectorResult(configurations, selections, result.tracks, result.info);
  }

  public static void moveStream(SampleStream[] streams, int source, int target) {
    if (source == target) {
      return;
    }
    checkState(streams[target] == null);
    streams[target] = streams[source];
    streams[source] = null;
  }
}
