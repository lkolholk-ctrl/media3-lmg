/*
 * LMG-fork (crossfade). Тесты параметров свода.
 */
package androidx.media3.exoplayer;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.exoplayer.ExoPlayer.CrossfadeConfiguration;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Тесты {@link CrossfadeConfiguration}. */
@RunWith(AndroidJUnit4.class)
public class CrossfadeConfigurationTest {

  @Test
  public void defaultConfiguration_disablesCrossfade() {
    assertThat(CrossfadeConfiguration.DEFAULT.isEnabled()).isFalse();
    assertThat(CrossfadeConfiguration.DEFAULT.durationUs).isEqualTo(0L);
  }

  @Test
  public void zeroDuration_disablesCrossfade() {
    CrossfadeConfiguration configuration =
        new CrossfadeConfiguration(
            /* durationUs= */ 0L,
            CrossfadeConfiguration.CURVE_DEFAULT,
            /* entryOffsetUs= */ 0L);
    assertThat(configuration.isEnabled()).isFalse();
  }

  @Test
  public void positiveDuration_enablesCrossfade() {
    CrossfadeConfiguration configuration =
        new CrossfadeConfiguration(
            /* durationUs= */ 9_000_000L,
            CrossfadeConfiguration.CURVE_CONSTANT_POWER,
            /* entryOffsetUs= */ 0L);
    assertThat(configuration.isEnabled()).isTrue();
    assertThat(configuration.durationUs).isEqualTo(9_000_000L);
  }

  /** Отрицательные значения не должны утекать в движок: там они дают мусорные уровни. */
  @Test
  public void negativeValues_areClampedToZero() {
    CrossfadeConfiguration configuration =
        new CrossfadeConfiguration(
            /* durationUs= */ -1_000L,
            CrossfadeConfiguration.CURVE_DEFAULT,
            /* entryOffsetUs= */ -500L);
    assertThat(configuration.durationUs).isEqualTo(0L);
    assertThat(configuration.entryOffsetUs).isEqualTo(0L);
  }

  /** Дробные секунды больше не теряются: длительность хранится в микросекундах. */
  @Test
  public void subSecondDuration_isPreserved() {
    CrossfadeConfiguration configuration =
        new CrossfadeConfiguration(
            /* durationUs= */ 7_500_000L,
            CrossfadeConfiguration.CURVE_DEFAULT,
            /* entryOffsetUs= */ 0L);
    assertThat(configuration.durationUs).isEqualTo(7_500_000L);
  }

  @Test
  public void equalConfigurations_areEqual() {
    CrossfadeConfiguration first =
        new CrossfadeConfiguration(9_000_000L, CrossfadeConfiguration.CURVE_LINEAR, 1_000L);
    CrossfadeConfiguration second =
        new CrossfadeConfiguration(9_000_000L, CrossfadeConfiguration.CURVE_LINEAR, 1_000L);
    assertThat(first).isEqualTo(second);
    assertThat(first.hashCode()).isEqualTo(second.hashCode());
  }

  @Test
  public void differentDuration_isNotEqual() {
    CrossfadeConfiguration first =
        new CrossfadeConfiguration(9_000_000L, CrossfadeConfiguration.CURVE_DEFAULT, 0L);
    CrossfadeConfiguration second =
        new CrossfadeConfiguration(4_000_000L, CrossfadeConfiguration.CURVE_DEFAULT, 0L);
    assertThat(first).isNotEqualTo(second);
  }
}
