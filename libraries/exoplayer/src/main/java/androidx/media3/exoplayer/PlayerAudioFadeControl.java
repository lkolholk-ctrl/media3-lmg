/*
 * LMG-fork (crossfade). Реализация AudioFadeControl для media3 — режим MANUAL
 * (симметричный фейд фиксированной длительности 1..12 c). Портировано из
 * Apple Music PlayerAudioFadeControl; нативный AUTOMATIC-композер (seamless по
 * AudioAnalysis) выкинут — у нас нет серверного анализа трека.
 *
 * Модель: два аудио-рендерера играют одновременно; громкость каждого ставится
 * renderer.handleMessage(Renderer.MSG_SET_VOLUME). Кривые и тайминг — как у Apple:
 * fade-in логарифмическая, fade-out экспоненциальная; шаг = clamp(dur/20,200,500)мс;
 * привязка к аудио-часам рендерера (MediaClock.getPositionUs).
 */
package androidx.media3.exoplayer;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import java.util.HashMap;

/* package */ final class PlayerAudioFadeControl implements AudioFadeControl {

  private static final float MAX_VOLUME = 1.0f;
  private static final float MIN_VOLUME = 0.0f;
  private static final int NUM_MESSAGES = 20;
  private static final long MAX_MS_BETWEEN_MESSAGES = 500;
  private static final long MIN_MS_BETWEEN_MESSAGES = 200;
  private static final long US_PER_SECOND = 1_000_000L;

  // Состояния кроссфейда.
  private static final int STATE_AUTOMATIC = 0;
  private static final int STATE_MANUAL = 1;
  private static final int STATE_OFF = 2;

  private final Renderer[] renderers;
  private final HashMap<FadeType, AudioFadeTransition> transitionsMap = new HashMap<>();

  private long msBetweenMessages = MAX_MS_BETWEEN_MESSAGES;
  private boolean paused = false;
  private long lastMsgTs = Long.MAX_VALUE;
  private float fadeInLevel = MIN_VOLUME;
  private float fadeOutLevel = MAX_VOLUME;
  // Wall-clock старта фейда — по нему детерминированно завершаем ровно за
  // crossFadeDuration секунд (гейт продвижения периода гарантированно снимается).
  private long fadeStartWallMs = 0L;

  @Nullable private MediaPeriodHolder fadeOutPeriodHolder;
  @Nullable private MediaPeriodHolder fadeInPeriodHolder;

  private int crossFadeState = STATE_OFF;
  private int crossFadeDuration; // секунды
  private int repeatMode = Player.REPEAT_MODE_OFF;
  private FadePhase fadePhase = FadePhase.IDLE;

  public PlayerAudioFadeControl(Renderer[] renderers) {
    this.renderers = renderers;
    // Дефолт-кривые: вход логарифмическая, выход экспоненциальная.
    transitionsMap.put(FadeType.FADE_IN, new AudioFadeTransition(FadeEffectType.LOGARITHMIC));
    transitionsMap.put(FadeType.FADE_OUT, new AudioFadeTransition(FadeEffectType.EXPONENTIAL));
  }

  /** Обновляется из ExoPlayerImplInternal — кроссфейд не запускаем при REPEAT_ONE. */
  public void setRepeatMode(int repeatMode) {
    this.repeatMode = repeatMode;
  }

  // ── Кривая fade-IN (уровень входящего по нормализованному t∈[0,1]) ──
  private float calculateFadeInLevel(float t) {
    AudioFadeTransition tr = transitionsMap.get(FadeType.FADE_IN);
    if (tr == null) {
      return MIN_VOLUME;
    }
    double c = tr.getCoefficient();
    float f;
    switch (tr.getEffectType()) {
      case LINEAR:
        f = 1 - t;
        break;
      case CUBIC:
        f = (float) Math.pow(1 - (double) t, 3.0);
        break;
      case EXPONENTIAL:
        f = (float) ((Math.pow(c, 1 - (double) t) - 1) / (c - 1));
        break;
      case LOGARITHMIC:
        f = (float) (Math.log(c + (1 - c) * (double) t) / Math.log(c));
        break;
      case CONSTANT_POWER:
        f = (float) Math.sqrt(1 - (double) t);
        break;
      case SIGMOID:
        f = (float) (1.0 / (Math.exp(((double) t - 0.5) * c) + 1));
        break;
      default:
        f = 1.0f;
        break;
    }
    return Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, f));
  }

  private float doFadeIn(@Nullable MediaPeriodHolder fadeIn, long rendererPositionUs)
      throws ExoPlaybackException {
    AudioFadeTransition tr = transitionsMap.get(FadeType.FADE_IN);
    if (fadeIn == null || tr == null || fadeOutPeriodHolder == null || tr.getDurationUs() <= 0) {
      return MIN_VOLUME;
    }
    MediaPeriodInfo info = fadeIn.info;
    // MANUAL: t = (startPositionUs - позиция_в_периоде) / длительность_фейда.
    float t =
        (info.startPositionUs - fadeIn.toPeriodTime(rendererPositionUs)) / (float) tr.getDurationUs();
    float level = calculateFadeInLevel(t);
    setVolume(fadeIn.getRendererIdx(), level);
    return level;
  }

  private float doFadeOut(@Nullable MediaPeriodHolder fadeOut, long rendererPositionUs)
      throws ExoPlaybackException {
    AudioFadeTransition tr = transitionsMap.get(FadeType.FADE_OUT);
    if (fadeOut == null || tr == null || tr.getDurationUs() <= 0) {
      return MIN_VOLUME;
    }
    MediaPeriodInfo info = fadeOut.info;
    // MANUAL: фейд начинается за durationUs до конца периода.
    float num =
        ((info.startPositionUs + info.durationUs) - tr.getDurationUs())
            - fadeOut.toPeriodTime(rendererPositionUs);
    float x = (num / (float) tr.getDurationUs()) + MAX_VOLUME; // x: 1→0 по ходу фейда
    double c = tr.getCoefficient();
    float f;
    switch (tr.getEffectType()) {
      case LINEAR:
        f = x;
        break;
      case CUBIC:
        f = (float) Math.pow(x, 3.0);
        break;
      case EXPONENTIAL:
        f = (float) ((Math.pow(c, x) - 1) / (c - 1));
        break;
      case LOGARITHMIC:
        f = (float) (Math.log((c - 1) * (double) x + 1) / Math.log(c));
        break;
      case CONSTANT_POWER:
        f = (float) Math.sqrt(x);
        break;
      case SIGMOID:
        f = (float) (1.0 / (Math.exp(((double) x - 0.5) * (-c)) + 1));
        break;
      default:
        f = 1.0f;
        break;
    }
    float level = Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, f));
    setVolume(fadeOut.getRendererIdx(), level);
    return level;
  }

  private void setVolume(int rendererIdx, float volume) throws ExoPlaybackException {
    if (rendererIdx >= 0 && rendererIdx < renderers.length) {
      renderers[rendererIdx].handleMessage(Renderer.MSG_SET_VOLUME, volume);
    }
  }

  @Override
  public synchronized boolean canFadeBetweenPeriods(
      @Nullable MediaPeriodHolder fadeOut, @Nullable MediaPeriodHolder fadeIn) {
    if (!isCrossFadeEnabled() || repeatMode == Player.REPEAT_MODE_ONE) {
      return false;
    }
    if (!isMediaPeriodReady(fadeOut, transitionsMap.get(FadeType.FADE_OUT))
        || !isMediaPeriodReady(fadeIn, transitionsMap.get(FadeType.FADE_IN))
        || areTheSameMediaPeriods(fadeOut, fadeIn)) {
      return false;
    }
    return true;
  }

  private boolean isMediaPeriodReady(
      @Nullable MediaPeriodHolder holder, @Nullable AudioFadeTransition tr) {
    if (holder == null || !holder.prepared || tr == null) {
      return false;
    }
    // Трек должен быть минимум вдвое длиннее фейда.
    return holder.info.durationUs == C.TIME_UNSET
        || holder.info.durationUs >= tr.getDurationUs() * 2;
  }

  private boolean areTheSameMediaPeriods(
      @Nullable MediaPeriodHolder a, @Nullable MediaPeriodHolder b) {
    return a != null && b != null && a.uid.equals(b.uid);
  }

  // Разовая подготовка: стартовые громкости + запоминаем холдеры.
  @Override
  public void prepareForCrossFade(MediaPeriodHolder fadeOut, MediaPeriodHolder fadeIn)
      throws ExoPlaybackException {
    setVolume(fadeOut.getRendererIdx(), MAX_VOLUME);
    setVolume(fadeIn.getRendererIdx(), MIN_VOLUME);
    this.fadeOutPeriodHolder = fadeOut;
    this.fadeInPeriodHolder = fadeIn;
    this.fadeOutLevel = MAX_VOLUME;
    this.fadeInLevel = MIN_VOLUME;
    this.paused = false;
    this.lastMsgTs = Long.MAX_VALUE;
    this.fadeStartWallMs = 0L; // старт wall-clock проставится на первом doCrossFade
    this.fadePhase = FadePhase.FADE_OUT;
  }

  @Override
  public synchronized boolean maybeStartCrossFading(
      MediaPeriodHolder fadeOut, MediaPeriodHolder fadeIn, long rendererPositionUs) {
    if (fadePhase != FadePhase.IDLE || !canFadeBetweenPeriods(fadeOut, fadeIn)) {
      return false;
    }
    fadePhase = FadePhase.FADE_OUT;
    return true;
  }

  // ГЛАВНЫЙ tick: вызывается каждым проходом плеера во время перехода.
  // Прогресс t считаем по wall-clock от старта фейда — детерминированно и без
  // зависимости от аудио-часов (которые во время гейта могут встать). Ровно за
  // crossFadeDuration секунд t достигает 1 → COMPLETED → гейт снимается, media3
  // штатно переходит на следующий трек. Кривая — equal-power (√), без провала.
  @Override
  public synchronized void doCrossFade(
      MediaPeriodHolder fadeOut, MediaPeriodHolder fadeIn, long rendererPositionUs)
      throws ExoPlaybackException {
    if (fadeIn == null || fadeOut == null || fadePhase == FadePhase.IDLE || paused) {
      return;
    }
    long now = System.currentTimeMillis();
    if (fadeStartWallMs == 0L) {
      fadeStartWallMs = now;
    }
    long fadeMs = (long) crossFadeDuration * 1000L;
    if (now - lastMsgTs < msBetweenMessages && (now - fadeStartWallMs) < fadeMs) {
      return; // троттлинг, но финальный тик (t>=1) не пропускаем
    }
    lastMsgTs = now;

    float t = fadeMs > 0 ? (now - fadeStartWallMs) / (float) fadeMs : 1f;
    if (t < 0f) {
      t = 0f;
    }
    if (t > 1f) {
      t = 1f;
    }
    fadeOutLevel = (float) Math.sqrt(1f - t);
    fadeInLevel = (float) Math.sqrt(t);
    setVolume(fadeOut.getRendererIdx(), fadeOutLevel);
    setVolume(fadeIn.getRendererIdx(), fadeInLevel);

    fadePhase = (fadeInLevel < fadeOutLevel) ? FadePhase.FADE_OUT : FadePhase.FADE_IN;
    if (t >= 1f) {
      fadePhase = FadePhase.COMPLETED;
    }
  }

  @Override
  public void maybeDoFadeOut(MediaPeriodHolder fadeOut, long rendererPositionUs) {
    // Только для AUTOMATIC (composer) — в MANUAL не используется.
  }

  @Override
  public synchronized void pauseFadeOut() throws ExoPlaybackException {
    if (isCrossFadeEnabled() && fadePhase != FadePhase.IDLE && fadeOutPeriodHolder != null) {
      int idx = fadeOutPeriodHolder.getRendererIdx();
      if (idx >= 0 && renderers[idx].getState() == Renderer.STATE_STARTED) {
        renderers[idx].stop();
        paused = true;
      }
    }
  }

  @Override
  public synchronized void resumeFadeOut() throws ExoPlaybackException {
    if (isCrossFadeEnabled() && fadePhase != FadePhase.IDLE && fadeOutPeriodHolder != null) {
      int idx = fadeOutPeriodHolder.getRendererIdx();
      if (idx >= 0 && renderers[idx].getState() == Renderer.STATE_ENABLED) {
        renderers[idx].start();
      }
      paused = false;
    }
  }

  @Override
  public synchronized void reset() throws ExoPlaybackException {
    try {
      if (fadeInPeriodHolder != null) {
        setVolume(fadeInPeriodHolder.getRendererIdx(), MAX_VOLUME);
      }
      if (fadeOutPeriodHolder != null) {
        setVolume(fadeOutPeriodHolder.getRendererIdx(), MAX_VOLUME);
      }
    } catch (Exception e) {
      // ignore
    }
    lastMsgTs = Long.MAX_VALUE;
    fadeStartWallMs = 0L;
    fadeOutPeriodHolder = null;
    fadeInPeriodHolder = null;
    fadeOutLevel = MAX_VOLUME;
    fadeInLevel = MIN_VOLUME;
    paused = false;
    fadePhase = FadePhase.IDLE;
  }

  // ── Конфиг ──
  @Override
  public synchronized void setCrossFadeDuration(int crossFadeDurationSeconds) {
    this.crossFadeDuration = crossFadeDurationSeconds;
    long durationUs = (long) crossFadeDurationSeconds * US_PER_SECOND;
    setFadeAudioEffect(
        FadeType.FADE_IN,
        new AudioFadeTransition(
            transitionsMap.get(FadeType.FADE_IN).getEffectType(),
            durationUs,
            transitionsMap.get(FadeType.FADE_IN).getCoefficient()));
    setFadeAudioEffect(
        FadeType.FADE_OUT,
        new AudioFadeTransition(
            transitionsMap.get(FadeType.FADE_OUT).getEffectType(),
            durationUs,
            transitionsMap.get(FadeType.FADE_OUT).getCoefficient()));
  }

  @Override
  public synchronized void setCrossFadeState(int crossFadeState) {
    this.crossFadeState = crossFadeState;
  }

  @Override
  public synchronized void setFadeAudioEffect(FadeType fadeType, AudioFadeTransition transition) {
    transitionsMap.put(fadeType, transition);
    long stepMs = (transition.getDurationUs() / US_PER_SECOND * 1000) / NUM_MESSAGES;
    msBetweenMessages = Math.max(Math.min(stepMs, MAX_MS_BETWEEN_MESSAGES), MIN_MS_BETWEEN_MESSAGES);
  }

  // ── Геттеры/состояние ──
  @Override
  public int getCrossFadeDuration() {
    return crossFadeDuration;
  }

  @Override
  public int getCrossFadeState() {
    return crossFadeState;
  }

  @Override
  public FadePhase getCrossFadePhase() {
    return fadePhase;
  }

  @Override
  public boolean isCrossFadeEnabled() {
    return crossFadeState == STATE_AUTOMATIC || crossFadeState == STATE_MANUAL;
  }

  @Override
  public synchronized boolean isCrossFadeInProgress() {
    return fadePhase != FadePhase.IDLE;
  }

  @Override
  public int getFadeInRendererIndex() {
    return fadeInPeriodHolder != null ? fadeInPeriodHolder.getRendererIdx() : C.INDEX_UNSET;
  }

  @Override
  public int getFadeOutRendererIndex() {
    return fadeOutPeriodHolder != null ? fadeOutPeriodHolder.getRendererIdx() : C.INDEX_UNSET;
  }
}
