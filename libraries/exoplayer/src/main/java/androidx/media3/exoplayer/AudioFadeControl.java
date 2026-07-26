/*
 * LMG-fork addition (crossfade). Портирует контракт AudioFadeControl из
 * Apple Music в неймспейс media3. Интерфейс package-private: реализация
 * (PlayerAudioFadeControl) живёт в этом же пакете и имеет доступ к
 * package-private внутренностям (MediaPeriodHolder и т.п.), поэтому публичный
 * API media3 не расширяется.
 *
 * Дёргается из playback-loop (ExoPlayerImplInternal) на границах media-периодов.
 * Модель: два одновременных аудио-рендерера — уходящий трек на renderers[
 * fadeOut.getRendererIdx()], входящий на renderers[fadeIn.getRendererIdx()];
 * громкость каждого ramp'ится независимо (renderer.handleMessage(MSG_SET_VOLUME)).
 */
package androidx.media3.exoplayer;

/** Кроссфейд-контроллер (форк LMG). См. {@link PlayerAudioFadeControl}. */
/* package */ interface AudioFadeControl {

  /** Какой из двух треков фейдим. */
  enum FadeType {
    FADE_IN,
    FADE_OUT
  }

  /**
   * Форма кривой громкости. Порядок ordinal важен (завязан на switch в
   * реализации). По умолчанию: fade-in = LOGARITHMIC, fade-out = EXPONENTIAL.
   */
  enum FadeEffectType {
    LINEAR,
    CUBIC,
    EXPONENTIAL,
    LOGARITHMIC,
    CONSTANT_POWER,
    SIGMOID
  }

  /** Фаза перехода: IDLE → FADE_OUT/FADE_IN → COMPLETED. */
  enum FadePhase {
    IDLE,
    FADE_OUT,
    FADE_IN,
    COMPLETED
  }

  /**
   * Описание одного фейда: форма кривой + окно во времени (start/duration в
   * микросекундах внутри периода) + коэффициент кривизны для EXP/LOG/SIGMOID.
   */
  final class AudioFadeTransition {

    /** Длительность по умолчанию, как в оригинале. */
    private static final long DEFAULT_DURATION_US = 12_000_000L;

    /** Коэффициент кривизны зависит от типа кривой (в оригинале — так же). */
    private static double defaultCoefficient(FadeEffectType effectType) {
      switch (effectType) {
        case EXPONENTIAL:
          return 10.0d;
        case LOGARITHMIC:
          return 2.0d;
        case SIGMOID:
          return 10.0d;
        default:
          return 0.0d;
      }
    }

    private final FadeEffectType effectType;
    private final long startUs;
    private final long durationUs;
    private final double coefficient;

    public AudioFadeTransition() {
      this(FadeEffectType.LINEAR, 0L, DEFAULT_DURATION_US, defaultCoefficient(FadeEffectType.LINEAR));
    }

    public AudioFadeTransition(FadeEffectType effectType) {
      this(effectType, 0L, DEFAULT_DURATION_US, defaultCoefficient(effectType));
    }

    /** (тип, startUs, durationUs) — используется при заливке composer-кривых. */
    public AudioFadeTransition(FadeEffectType effectType, long startUs, long durationUs) {
      this(effectType, startUs, durationUs, defaultCoefficient(effectType));
    }

    /** (тип, durationUs, coefficient) — используется при задании длительности из UI. */
    public AudioFadeTransition(FadeEffectType effectType, long durationUs, double coefficient) {
      this(effectType, 0L, durationUs, coefficient);
    }

    public AudioFadeTransition(
        FadeEffectType effectType, long startUs, long durationUs, double coefficient) {
      this.effectType = effectType;
      this.startUs = startUs;
      this.durationUs = durationUs;
      this.coefficient = coefficient;
    }

    public FadeEffectType getEffectType() {
      return effectType;
    }

    public long getStartUs() {
      return startUs;
    }

    public long getDurationUs() {
      return durationUs;
    }

    public double getCoefficient() {
      return coefficient;
    }
  }

  // ── Конфигурация (из UI/сервиса) ──
  void setCrossFadeState(int crossFadeState); // 0=AUTOMATIC, 1=MANUAL, 2=OFF
  /**
   * Длительность свода в микросекундах.
   *
   * Раньше принимались целые секунды, из-за чего 7500 мс превращались в 7 c —
   * дробные значения из рецептов терялись.
   */
  void setCrossFadeDurationUs(long crossFadeDurationUs);

  void setFadeAudioEffect(FadeType fadeType, AudioFadeTransition transition);

  int getCrossFadeState();

  long getCrossFadeDurationUs();

  boolean isCrossFadeEnabled();

  // ── Состояние ──
  FadePhase getCrossFadePhase();

  boolean isCrossFadeInProgress();

  int getFadeInRendererIndex();

  int getFadeOutRendererIndex();

  // ── Хуки из playback-loop (ExoPlayerImplInternal) ──
  boolean canFadeBetweenPeriods(MediaPeriodHolder fadeOut, MediaPeriodHolder fadeIn);

  void prepareForCrossFade(MediaPeriodHolder fadeOut, MediaPeriodHolder fadeIn)
      throws ExoPlaybackException;

  boolean maybeStartCrossFading(
      MediaPeriodHolder fadeOut, MediaPeriodHolder fadeIn, long rendererPositionUs);

  void doCrossFade(MediaPeriodHolder fadeOut, MediaPeriodHolder fadeIn, long rendererPositionUs)
      throws ExoPlaybackException;

  void maybeDoFadeOut(MediaPeriodHolder fadeOut, long rendererPositionUs) throws ExoPlaybackException;

  void pauseFadeOut() throws ExoPlaybackException;

  void resumeFadeOut() throws ExoPlaybackException;

  void reset() throws ExoPlaybackException;
}
