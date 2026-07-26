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
   * Экспонента и равная мощность ведут себя противоположно, и это слышно.
   *
   * <p>У экспоненты входящий трек на середине окна звучит всего на 0.24, тогда
   * как уходящий уже упал до тех же 0.24: суммарная мощность в середине свода
   * проваливается примерно до 0.12 вместо единицы — переход звучит как дырка, и
   * заданная длительность воспринимается вдвое короче. У равной мощности сумма
   * мощностей постоянна, поэтому она и стоит по умолчанию.
   */
  @Test
  public void exponentialCurve_dipsInTheMiddle_constantPowerDoesNot() {
    float exponentialIn =
        PlayerAudioFadeControl.fadeInCurveLevel(
            FadeEffectType.EXPONENTIAL, /* t= */ 0.5f, COEFFICIENT_EXPONENTIAL);
    float constantPowerIn =
        PlayerAudioFadeControl.fadeInCurveLevel(
            FadeEffectType.CONSTANT_POWER, /* t= */ 0.5f, COEFFICIENT_NONE);
    assertThat(exponentialIn).isLessThan(constantPowerIn);

    // Уходящий трек в тот же момент: для обеих кривых это зеркальное значение.
    float exponentialOut =
        (float)
            ((Math.pow(COEFFICIENT_EXPONENTIAL, 0.5d) - 1.0d) / (COEFFICIENT_EXPONENTIAL - 1.0d));
    float constantPowerOut = (float) Math.sqrt(0.5d);

    float exponentialPower = exponentialIn * exponentialIn + exponentialOut * exponentialOut;
    float constantPowerTotal =
        constantPowerIn * constantPowerIn + constantPowerOut * constantPowerOut;
    assertThat(exponentialPower).isLessThan(0.3f);
    assertThat(constantPowerTotal).isWithin(0.001f).of(1f);
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
