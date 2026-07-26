/*
 * LMG-fork (crossfade). Тесты формы кривых свода.
 *
 * Форма кривой дважды была источником регрессий, которые ловились только на слух:
 *  - слишком быстрое затухание — свод на 12 c слышался как 6-7;
 *  - недостижимый порог завершения — провал громкости в начале следующего трека.
 * Оба свойства проверяются здесь, чтобы следующая правка кривых не потребовала
 * прослушивания.
 */
package androidx.media3.exoplayer;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.exoplayer.AudioFadeControl.FadeEffectType;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Тесты кривых громкости кроссфейда. */
@RunWith(AndroidJUnit4.class)
public class PlayerAudioFadeControlCurvesTest {

  /** Коэффициенты соответствуют значениям по умолчанию для соответствующих кривых. */
  private static final double COEFFICIENT_EXPONENTIAL = 10.0d;

  private static final double COEFFICIENT_LOGARITHMIC = 2.0d;

  private static final double COEFFICIENT_NONE = 0.0d;

  /** Нормированное время идёт 1 → 0: на старте свода входящий трек молчит. */
  @Test
  public void fadeInCurve_atStartOfFade_isSilent() {
    for (FadeEffectType type : FadeEffectType.values()) {
      float level = PlayerAudioFadeControl.fadeInCurveLevel(type, /* t= */ 1f, coefficientFor(type));
      assertThat(level).isAtMost(0.05f);
    }
  }

  /** К концу свода входящий трек звучит на полной громкости. */
  @Test
  public void fadeInCurve_atEndOfFade_isFullVolume() {
    for (FadeEffectType type : FadeEffectType.values()) {
      float level = PlayerAudioFadeControl.fadeInCurveLevel(type, /* t= */ 0f, coefficientFor(type));
      assertThat(level).isAtLeast(0.95f);
    }
  }

  /** Громкость меняется монотонно — никаких провалов и рывков по ходу свода. */
  @Test
  public void fadeInCurve_isMonotonic() {
    for (FadeEffectType type : FadeEffectType.values()) {
      float previous = -1f;
      for (int step = 20; step >= 0; step--) {
        float level =
            PlayerAudioFadeControl.fadeInCurveLevel(type, step / 20f, coefficientFor(type));
        assertThat(level).isAtLeast(previous);
        previous = level;
      }
    }
  }

  /** Значение вне диапазона не должно давать NaN или уходить за пределы громкости. */
  @Test
  public void fadeInCurve_outOfRangeInput_staysWithinVolumeRange() {
    for (FadeEffectType type : FadeEffectType.values()) {
      for (float t : new float[] {-5f, 2f, Float.NaN}) {
        float level = PlayerAudioFadeControl.fadeInCurveLevel(type, t, coefficientFor(type));
        assertThat(Float.isNaN(level)).isFalse();
        assertThat(level).isAtLeast(0f);
        assertThat(level).isAtMost(1f);
      }
    }
  }

  /**
   * Кривая равной мощности — та, что стоит по умолчанию: на середине свода оба
   * трека звучат примерно на 0.71, а сумма мощностей постоянна. Именно это делает
   * перекрытие слышимым всю заданную длительность.
   */
  @Test
  public void constantPowerCurve_keepsTotalPowerConstant() {
    for (int step = 0; step <= 20; step++) {
      float t = step / 20f;
      float fadeIn =
          PlayerAudioFadeControl.fadeInCurveLevel(
              FadeEffectType.CONSTANT_POWER, t, COEFFICIENT_NONE);
      // Уходящий трек в тот же момент времени: у равной мощности это sqrt(t).
      float fadeOut = (float) Math.sqrt(t);
      assertThat(fadeIn * fadeIn + fadeOut * fadeOut).isWithin(0.001f).of(1f);
    }
    float middle =
        PlayerAudioFadeControl.fadeInCurveLevel(
            FadeEffectType.CONSTANT_POWER, /* t= */ 0.5f, COEFFICIENT_NONE);
    assertThat(middle).isWithin(0.01f).of(0.707f);
  }

  /**
   * Порог завершения свода («уходящий тише 0.05») достигается у разных кривых в
   * разные моменты: у экспоненты — задолго до конца окна, у равной мощности —
   * практически в самом конце. Из-за этого свод переставал закрываться вовремя, и
   * следующий трек начинался с недокрученной громкостью. Тест фиксирует факт,
   * чтобы завершение свода не опиралось на один лишь уровень громкости.
   */
  @Test
  public void completionThreshold_dependsOnCurveShape() {
    float exponentialAtHalf =
        PlayerAudioFadeControl.fadeInCurveLevel(
            FadeEffectType.EXPONENTIAL, /* t= */ 0.5f, COEFFICIENT_EXPONENTIAL);
    float constantPowerAtHalf =
        PlayerAudioFadeControl.fadeInCurveLevel(
            FadeEffectType.CONSTANT_POWER, /* t= */ 0.5f, COEFFICIENT_NONE);
    // На середине окна экспонента уже почти вывела входящий трек на полную
    // громкость, а равная мощность — только на 0.71.
    assertThat(exponentialAtHalf).isGreaterThan(constantPowerAtHalf);
  }

  private static double coefficientFor(FadeEffectType type) {
    switch (type) {
      case EXPONENTIAL:
      case SIGMOID:
        return COEFFICIENT_EXPONENTIAL;
      case LOGARITHMIC:
        return COEFFICIENT_LOGARITHMIC;
      default:
        return COEFFICIENT_NONE;
    }
  }
}
