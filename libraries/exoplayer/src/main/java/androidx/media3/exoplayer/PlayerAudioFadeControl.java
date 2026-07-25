/*
 * LMG-fork (crossfade). Реализация AudioFadeControl для media3 — режим MANUAL,
 * портирована 1:1 из Apple Music PlayerAudioFadeControl (декомпиляция, 1087 строк).
 *
 * Портирован MANUAL-путь (transitionDataAvailable == false): позиционная
 * математика doFadeIn/doFadeOut по startPositionUs/durationUs периода и
 * getDurationUs() фейда; кривые calculateFadeInLevel/doFadeOut switch 1:1;
 * doCrossFade — логика доминирующего периода + завершение по уровням/EOS (НЕ
 * wall-clock, НЕ equal-power).
 *
 * НЕ портирован AUTOMATIC-путь (нативный LevelComposer по серверному
 * AudioAnalysis): у нас нет обёртки MediaPlayer/MediaPlayerContext, нативных
 * биндингов renderer.javanative и PlayerMediaItem. Ветки composer сохранены
 * структурно (if getTransitionDataAvailable()), но так как transitionDataAvailable
 * всегда false, они не исполняются; computeTransitionJob/setComposerTransition/
 * prepareFeature/setAutomations/getAudioAnalysis НЕ перенесены (см. TODO ниже).
 *
 * Модель media3: два аудио-рендерера играют одновременно; громкость каждого
 * ставится renderers[idx].handleMessage(Renderer.MSG_SET_VOLUME, level). Индекс
 * рендерера периода берётся из MediaPeriodHolder.getRendererIdx().
 */
package androidx.media3.exoplayer;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.util.Log;
import java.util.HashMap;

/* package */ final class PlayerAudioFadeControl implements AudioFadeControl {

  private static final String TAG = "PlayerAudioFadeControl";

  // ── Константы Apple 1:1 ──
  private static final long CROSS_POINT_TOLERANCE_US = 100000;
  private static final long MAX_MS_BETWEEN_MESSAGES = 500;
  private static final long MIN_MS_BETWEEN_MESSAGES = 200;
  private static final int NUM_MESSAGES = 20;
  private static final float MAX_VOLUME = 1.0f;
  private static final float MIN_VOLUME = 0.0f;
  private static final long MILLIS_PER_SECOND = 1000L;
  private static final long US_PER_SECOND = 1_000_000L;

  // Состояния кроссфейда: 0=AUTOMATIC, 1=MANUAL, 2=OFF (как у Apple).
  private final Renderer[] renderers;
  private final HashMap<FadeType, AudioFadeTransition> transitionsMap = new HashMap<>();

  private long msBetweenMessages = MAX_MS_BETWEEN_MESSAGES;
  private long secondTrackOffsetUs = Long.MAX_VALUE;
  private boolean paused = false;
  private long lastMsgTs = Long.MAX_VALUE;
  private float fadeInLevel = MIN_VOLUME;
  private float fadeOutLevel = MAX_VOLUME;

  @Nullable private MediaPeriodHolder fadeOutPeriodHolder;
  @Nullable private MediaPeriodHolder fadeInPeriodHolder;

  private boolean isComputeTransitionJobExecuted = false;
  // Всегда false в нашем порте (нативный композер не перенесён).
  private volatile boolean transitionDataAvailable = false;

  private int crossFadeState = 2; // OFF
  private int crossFadeDuration; // секунды
  private FadePhase fadePhase = FadePhase.IDLE;
  private float crossingTimeUs = MIN_VOLUME; // всегда 0 в MANUAL (заполняется только композером)
  @Nullable private Boolean canFadeCached;

  // media3-адаптация: Apple берёт repeatMode из обёртки MediaPlayer.getRepeatMode();
  // у нас его прокидывает ExoPlayerImplInternal через setRepeatMode(int).
  private int repeatMode = Player.REPEAT_MODE_OFF;

  public PlayerAudioFadeControl(Renderer[] renderers) {
    this.renderers = renderers;
    // Дефолт-кривые (как в Apple reset() и по ТЗ): fade-in LOGARITHMIC, fade-out EXPONENTIAL.
    // Примечание: Apple-конструктор кладёт new AudioFadeTransition() (LINEAR) и полагается
    // на композер; у нас композера нет, поэтому сразу ставим LOG/EXP (совпадает с reset()).
    transitionsMap.put(FadeType.FADE_IN, new AudioFadeTransition(FadeEffectType.LOGARITHMIC));
    transitionsMap.put(FadeType.FADE_OUT, new AudioFadeTransition(FadeEffectType.EXPONENTIAL));
  }

  /** media3-адаптация: прокидывается из ExoPlayerImplInternal (замена MediaPlayer.getRepeatMode). */
  public void setRepeatMode(int repeatMode) {
    this.repeatMode = repeatMode;
  }

  // ── Кривая fade-IN (Apple calculateFadeInLevel 1:1) ──
  private float calculateFadeInLevel(float fadeInTimeNormalized) {
    AudioFadeTransition audioFadeTransition = transitionsMap.get(FadeType.FADE_IN);
    if (audioFadeTransition == null) {
      return MIN_VOLUME;
    }
    // Защита: t вне [0,1] (позиция ещё/уже вне окна фейда) давала бы NaN в
    // LOGARITHMIC (log отрицательного) и мусор в остальных кривых. NaN затем
    // проходил бы Math.max/min и уходил в громкость.
    if (Float.isNaN(fadeInTimeNormalized)) {
      return MIN_VOLUME;
    }
    fadeInTimeNormalized = Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, fadeInTimeNormalized));
    float f;
    FadeEffectType effectType = audioFadeTransition.getEffectType();
    switch (effectType) {
      case LINEAR:
        f = 1 - fadeInTimeNormalized;
        break;
      case CUBIC:
        f = (float) Math.pow((double) 1 - (double) fadeInTimeNormalized, 3.0d);
        break;
      case EXPONENTIAL:
        {
          double d = 1;
          f =
              (float)
                  ((Math.pow(audioFadeTransition.getCoefficient(), d - (double) fadeInTimeNormalized)
                          - d)
                      / (audioFadeTransition.getCoefficient() - d));
          break;
        }
      case LOGARITHMIC:
        f =
            (float)
                (Math.log(
                        audioFadeTransition.getCoefficient()
                            + (((double) 1 - audioFadeTransition.getCoefficient())
                                * (double) fadeInTimeNormalized))
                    / Math.log(audioFadeTransition.getCoefficient()));
        break;
      case CONSTANT_POWER:
        f = (float) Math.sqrt((double) 1 - (double) fadeInTimeNormalized);
        break;
      case SIGMOID:
        {
          double d = 1;
          f =
              (float)
                  (d
                      / (Math.exp(
                              ((double) fadeInTimeNormalized - 0.5d)
                                  * audioFadeTransition.getCoefficient())
                          + d));
          break;
        }
      default:
        f = 1.0f;
        break;
    }
    return Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, f));
  }

  // ── doFadeIn (Apple 1:1; обе ветки сохранены, composer-ветка не исполняется) ──
  private float doFadeIn(@Nullable MediaPeriodHolder fadeInPeriodHolder, long rendererPositionUs)
      throws ExoPlaybackException {
    float periodTime;
    AudioFadeTransition audioFadeTransition = transitionsMap.get(FadeType.FADE_IN);
    if (fadeInPeriodHolder == null
        || audioFadeTransition == null
        || this.fadeOutPeriodHolder == null) {
      return MIN_VOLUME;
    }
    MediaPeriodInfo mediaPeriodInfo = fadeInPeriodHolder.info;
    if (getTransitionDataAvailable()) {
      // AUTOMATIC (composer) — не исполняется (transitionDataAvailable == false).
      MediaPeriodHolder mediaPeriodHolder = this.fadeOutPeriodHolder;
      long periodTime2 = mediaPeriodHolder.toPeriodTime(rendererPositionUs);
      if (periodTime2 < audioFadeTransition.getStartUs()) {
        return MIN_VOLUME;
      }
      if (periodTime2 > audioFadeTransition.getDurationUs() + audioFadeTransition.getStartUs()) {
        setVolume(fadeInPeriodHolder.getRendererIdx(), MAX_VOLUME);
        return MAX_VOLUME;
      }
      periodTime =
          ((float) (audioFadeTransition.getStartUs() - periodTime2)
                  / audioFadeTransition.getDurationUs())
              + MAX_VOLUME;
    } else {
      // MANUAL: t = (startPositionUs - позиция_в_периоде) / длительность_фейда.
      periodTime =
          (float) (mediaPeriodInfo.startPositionUs - fadeInPeriodHolder.toPeriodTime(rendererPositionUs))
              / audioFadeTransition.getDurationUs();
    }
    float level = calculateFadeInLevel(periodTime);
    setVolume(fadeInPeriodHolder.getRendererIdx(), level);
    return level;
  }

  // ── doFadeOut (Apple 1:1; обе ветки сохранены, composer-ветка не исполняется) ──
  private float doFadeOut(@Nullable MediaPeriodHolder fadeOutPeriodHolder, long rendererPositionUs)
      throws ExoPlaybackException {
    float numerator;
    long durationDenom;
    AudioFadeTransition audioFadeTransition = transitionsMap.get(FadeType.FADE_OUT);
    if (fadeOutPeriodHolder == null || audioFadeTransition == null) {
      return MIN_VOLUME;
    }
    MediaPeriodInfo mediaPeriodInfo = fadeOutPeriodHolder.info;
    if (getTransitionDataAvailable()) {
      // AUTOMATIC (composer) — не исполняется (transitionDataAvailable == false).
      long periodTime = fadeOutPeriodHolder.toPeriodTime(rendererPositionUs);
      if (periodTime < audioFadeTransition.getStartUs()) {
        return MAX_VOLUME;
      }
      if (periodTime > audioFadeTransition.getDurationUs() + audioFadeTransition.getStartUs()) {
        setVolume(fadeOutPeriodHolder.getRendererIdx(), MIN_VOLUME);
        return MIN_VOLUME;
      }
      numerator = audioFadeTransition.getStartUs() - periodTime;
      durationDenom = audioFadeTransition.getDurationUs();
    } else {
      // MANUAL: фейд начинается за durationUs до конца периода.
      numerator =
          ((mediaPeriodInfo.startPositionUs + mediaPeriodInfo.durationUs)
                  - audioFadeTransition.getDurationUs())
              - fadeOutPeriodHolder.toPeriodTime(rendererPositionUs);
      durationDenom = audioFadeTransition.getDurationUs();
    }
    float fExp = (numerator / durationDenom) + MAX_VOLUME; // x: 1→0 по ходу фейда
    FadeEffectType effectType = audioFadeTransition.getEffectType();
    switch (effectType) {
      case LINEAR:
        break;
      case CUBIC:
        fExp = (float) Math.pow(fExp, 3.0d);
        break;
      case EXPONENTIAL:
        {
          double d = 1;
          fExp =
              (float)
                  ((Math.pow(audioFadeTransition.getCoefficient(), fExp) - d)
                      / (audioFadeTransition.getCoefficient() - d));
          break;
        }
      case LOGARITHMIC:
        {
          double d2 = 1;
          fExp =
              (float)
                  (Math.log(((audioFadeTransition.getCoefficient() - d2) * (double) fExp) + d2)
                      / Math.log(audioFadeTransition.getCoefficient()));
          break;
        }
      case CONSTANT_POWER:
        fExp = (float) Math.sqrt(fExp);
        break;
      case SIGMOID:
        {
          double d3 = 1;
          fExp =
              (float)
                  (d3
                      / (Math.exp(
                              ((double) fExp - 0.5d)
                                  * (audioFadeTransition.getCoefficient() * ((double) (-1))))
                          + d3));
          break;
        }
      default:
        fExp = 1.0f;
        break;
    }
    float level = Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, fExp));
    setVolume(fadeOutPeriodHolder.getRendererIdx(), level);
    return level;
  }

  /** Последний выставленный гейн по индексу рендерера (для инварианта §6). */
  private final java.util.HashMap<Integer, Float> lastVolume = new java.util.HashMap<>();

  private void setVolume(int rendererIdx, float volume) throws ExoPlaybackException {
    if (rendererIdx >= 0 && rendererIdx < renderers.length) {
      renderers[rendererIdx].handleMessage(Renderer.MSG_SET_VOLUME, Float.valueOf(volume));
      lastVolume.put(rendererIdx, volume);
    }
  }

  /**
   * ИНВАРИАНТ: вне активного окна свода ни один аудио-рендерер не должен остаться
   * с гейном < 1.0. Фейд-аут уводит гейн в 0, и если переход оборвался (skip, seek,
   * pause, ошибка декодера, смена маршрута) — трек останется немым. Возвращаем
   * гейн ПРИНУДИТЕЛЬНО, не «плавно», и логируем сам факт: такого состояния быть
   * не должно.
   */
  public void restoreFullGain(String reason) {
    for (int i = 0; i < renderers.length; i++) {
      Float v = lastVolume.get(i);
      if (v != null && v < MAX_VOLUME) {
        try {
          renderers[i].handleMessage(Renderer.MSG_SET_VOLUME, Float.valueOf(MAX_VOLUME));
          lastVolume.put(i, MAX_VOLUME);
          Log.e(TAG, "xfade GAIN RESTORE idx=" + i + " was=" + v + " reason=" + reason);
        } catch (Exception e) {
          Log.e(TAG, "xfade gain restore failed idx=" + i, e);
        }
      }
    }
  }

  /** Есть ли рендерер с приглушённым гейном (для watchdog-лога вне свода). */
  public boolean hasDuckedRenderer() {
    for (Float v : lastVolume.values()) {
      if (v != null && v < MAX_VOLUME) {
        return true;
      }
    }
    return false;
  }

  // ── canFadeBetweenPeriods (Apple 1:1, адаптировано под отсутствие обёртки) ──
  @Override
  public synchronized boolean canFadeBetweenPeriods(
      @Nullable MediaPeriodHolder fadeOutPeriodHolder,
      @Nullable MediaPeriodHolder fadeInPeriodHolder) {
    if (!isCrossFadeEnabled()) {
      return false;
    }
    Boolean bool = this.canFadeCached;
    if (bool != null) {
      return bool.booleanValue();
    }
    if (this.repeatMode == Player.REPEAT_MODE_ONE) { // Apple: mediaPlayer.getRepeatMode() == 1
      return false;
    }
    if (!canMediaPeriodFade(fadeOutPeriodHolder, transitionsMap.get(FadeType.FADE_OUT))
        || !canMediaPeriodFade(fadeInPeriodHolder, transitionsMap.get(FadeType.FADE_IN))
        || areTheSameMediaPeriods(fadeOutPeriodHolder, fadeInPeriodHolder)
        || areSequentialItems(fadeOutPeriodHolder, fadeInPeriodHolder)) {
      return false;
    }
    // Apple: if (!FeatureFlag.l() || !isUnsupportedModel(...)) return true; иначе кэшируем false.
    // media3-адаптация: нет фичефлага C0149j — гейт по модели устройства опущен → return true.
    // (isUnsupportedModel() сохранён как заглушка ниже, но в гейте не участвует.)
    return true;
  }

  private boolean canMediaPeriodFade(
      @Nullable MediaPeriodHolder periodHolder, @Nullable AudioFadeTransition transition) {
    return isCorrectMediaType(periodHolder) && isMediaPeriodReady(periodHolder, transition);
  }

  // Apple 1:1.
  private boolean isMediaPeriodReady(
      @Nullable MediaPeriodHolder periodHolder, @Nullable AudioFadeTransition transition) {
    if (periodHolder == null || !periodHolder.prepared || transition == null) {
      return false;
    }
    // Apple: durationUs >= 2 * fade. Для нашего окна 5–30 c это отсекало бы длинные
    // своды (30 c потребовали бы трек >= 60 c), поэтому достаточно, чтобы фейд
    // помещался в трек с небольшим запасом.
    long minTrackUs = transition.getDurationUs() + 2_000_000L;
    return periodHolder.info.durationUs == C.TIME_UNSET
        || periodHolder.info.durationUs >= minTrackUs
        || getTransitionDataAvailable();
  }

  // Apple сравнивает getMediaPeriodUid (кастит periodUid к Long). В media3 uid — Object,
  // поэтому сравниваем через uid.equals (эквивалентно по смыслу).
  private boolean areTheSameMediaPeriods(
      @Nullable MediaPeriodHolder fadeOutPeriodHolder,
      @Nullable MediaPeriodHolder fadeInPeriodHolder) {
    return fadeOutPeriodHolder != null
        && fadeInPeriodHolder != null
        && fadeOutPeriodHolder.uid.equals(fadeInPeriodHolder.uid);
  }

  // TODO: media-type check недоступен без обёртки MediaPlayer/PlayerMediaItem
  // (Apple: getPlayerMediaItemFromPeriodHolder(...).getType() == 1). Заглушка → true.
  private boolean isCorrectMediaType(@Nullable MediaPeriodHolder periodHolder) {
    return periodHolder != null;
  }

  // TODO: album-check (areSequentialItems по albumSubscriptionStoreId/disc/track) недоступен
  // без обёртки MediaPlayer/PlayerMediaItem. Заглушка → false (не блокирует кроссфейд).
  private boolean areSequentialItems(
      @Nullable MediaPeriodHolder fadeOutPeriodHolder,
      @Nullable MediaPeriodHolder fadeInPeriodHolder) {
    return false;
  }

  // Apple hardcode: return true. Сохранён, но в canFadeBetweenPeriods не используется (см. коммент).
  private boolean isUnsupportedModel(
      @Nullable MediaPeriodHolder fadeOutPeriodHolder,
      @Nullable MediaPeriodHolder fadeInPeriodHolder) {
    return true;
  }

  // ── prepareForCrossFade (Apple 1:1 + необходимая инициализация lastMsgTs) ──
  @Override
  public void prepareForCrossFade(
      @Nullable MediaPeriodHolder fadeOutPeriodHolder,
      @Nullable MediaPeriodHolder fadeInPeriodHolder)
      throws ExoPlaybackException {
    Log.e(TAG, "prepareForCrossFade()");
    if (fadeOutPeriodHolder == null || fadeInPeriodHolder == null) {
      return;
    }
    if (!getTransitionDataAvailable()) {
      setVolume(fadeOutPeriodHolder.getRendererIdx(), MAX_VOLUME);
    }
    setVolume(fadeInPeriodHolder.getRendererIdx(), MIN_VOLUME);
    this.fadeOutPeriodHolder = fadeOutPeriodHolder;
    this.fadeInPeriodHolder = fadeInPeriodHolder;
    this.fadeOutLevel = MAX_VOLUME;
    this.fadeInLevel = MIN_VOLUME;
    this.paused = false;
    // media3-адаптация: хост стартует фейд через prepareForCrossFade (а не setCrossFadeInProgress),
    // поэтому здесь взводим lastMsgTs = now — иначе троттлинг doCrossFade (now - lastMsgTs<step)
    // при lastMsgTs=MAX_VALUE навсегда блокировал бы первый тик. У Apple lastMsgTs ставит
    // setCrossFadeInProgress в момент cross-point.
    this.lastMsgTs = System.currentTimeMillis();
    this.fadePhase = FadePhase.FADE_OUT;
  }

  // ── setCrossFadeInProgress (Apple 1:1) ──
  // Не вызывается хостом media3 (тот стартует через prepareForCrossFade), сохранён по ТЗ.
  private boolean setCrossFadeInProgress(
      @Nullable MediaPeriodHolder fadeOutPeriodHolder,
      @Nullable MediaPeriodHolder fadeInPeriodHolder,
      long rendererPositionUs) {
    if (fadeOutPeriodHolder == null) {
      return false;
    }
    long periodTime = fadeOutPeriodHolder.toPeriodTime(rendererPositionUs) - this.secondTrackOffsetUs;
    if (1 <= periodTime && periodTime < CROSS_POINT_TOLERANCE_US) {
      this.lastMsgTs = System.currentTimeMillis();
      this.fadePhase = FadePhase.FADE_OUT;
    }
    return this.fadePhase != FadePhase.IDLE;
  }

  // ── maybeStartCrossFading (Apple 1:1) ──
  // В MANUAL isComputeTransitionJobExecuted всегда false → путь composer (computeTransitionJob)
  // ничего не делает (композер не перенесён), поэтому метод фактически инертен. Хост media3
  // стартует фейд напрямую через prepareForCrossFade. Сохранён по ТЗ.
  @Override
  public synchronized boolean maybeStartCrossFading(
      @Nullable MediaPeriodHolder fadeOutPeriodHolder,
      @Nullable MediaPeriodHolder fadeInPeriodHolder,
      long rendererPositionUs) {
    if (!isCrossFadeEnabled()) {
      return false;
    }
    if (fadeInPeriodHolder == null || fadeOutPeriodHolder == null) {
      return false;
    }
    if (this.fadePhase != FadePhase.IDLE) {
      return false;
    }
    if (!canFadeBetweenPeriods(fadeOutPeriodHolder, fadeInPeriodHolder)) {
      return false;
    }
    if (this.isComputeTransitionJobExecuted) {
      setCrossFadeInProgress(fadeOutPeriodHolder, fadeInPeriodHolder, rendererPositionUs);
    } else {
      // media3-адаптация: у Apple здесь computeTransitionJob → нативный композер
      // (даже в MANUAL, с fallbackTransitionDuration), который и взводит фазу через
      // setCrossFadeInProgress. Композера у нас НЕТ → взводим MANUAL напрямую, иначе
      // фаза остаётся IDLE и кроссфейд не запускается вообще (треки идут gapless).
      computeTransitionJob(fadeOutPeriodHolder, fadeInPeriodHolder); // no-op
      this.fadePhase = FadePhase.FADE_OUT;
    }
    if (this.fadePhase != FadePhase.IDLE) {
      this.fadePhase = FadePhase.FADE_OUT;
    }
    Log.e(TAG, "maybeStartCrossFading() ARMED phase=" + this.fadePhase);
    return this.fadePhase != FadePhase.IDLE;
  }

  // TODO: AUTOMATIC — нативный LevelComposer (setComposerTransition/prepareFeature/setAutomations)
  // не перенесён. В нашем порте computeTransitionJob — no-op, transitionDataAvailable остаётся false.
  private void computeTransitionJob(
      @Nullable MediaPeriodHolder fadeOutPeriodHolder,
      @Nullable MediaPeriodHolder fadeInPeriodHolder) {
    // no-op (composer omitted)
  }

  // ── doCrossFade (Apple 1:1, MANUAL: crossingTimeUs==0 ветка) ──
  @Override
  public synchronized void doCrossFade(
      @Nullable MediaPeriodHolder fadeOutPeriodHolder,
      @Nullable MediaPeriodHolder fadeInPeriodHolder,
      long rendererPositionUs)
      throws ExoPlaybackException {
    if (fadeInPeriodHolder == null) {
      return;
    }
    if (fadeOutPeriodHolder == null) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now - this.lastMsgTs < this.msBetweenMessages) {
      return;
    }
    int rendererIdx2 = fadeOutPeriodHolder.getRendererIdx();
    if (rendererIdx2 < 0 || rendererIdx2 >= renderers.length) {
      return; // индекс рендерера ещё не назначен — фейд не тикаем (защита от AIOOBE)
    }
    // Apple берёт позицию из аудио-часов fade-out рендерера (getMediaClock().getPositionUs()).
    MediaClock mediaClock = renderers[rendererIdx2].getMediaClock();
    if (mediaClock == null) {
      // Apple здесь ассертит non-null (иначе NPE). Защищаемся: пропускаем тик.
      return;
    }
    long fadeOutPositionUs = mediaClock.getPositionUs();
    this.lastMsgTs = now;
    if (this.fadePhase != FadePhase.IDLE && !this.paused) {
      this.fadeInLevel = doFadeIn(fadeInPeriodHolder, fadeOutPositionUs);
      this.fadeOutLevel = doFadeOut(fadeOutPeriodHolder, fadeOutPositionUs);
      Log.e(
          TAG,
          "doCrossFade() fadeOutLevel: "
              + this.fadeOutLevel
              + " fadeInLevel: "
              + this.fadeInLevel
              + " crossingTime "
              + this.crossingTimeUs);
      if (this.crossingTimeUs == MIN_VOLUME) {
        // MANUAL: доминирующий период по уровням.
        if (this.fadeInLevel < this.fadeOutLevel) {
          if (this.fadePhase != FadePhase.FADE_OUT) {
            this.fadePhase = FadePhase.FADE_OUT;
          }
        } else {
          if (this.fadePhase != FadePhase.FADE_IN) {
            this.fadePhase = FadePhase.FADE_IN;
          }
        }
      } else {
        // TODO: AUTOMATIC — выбор доминирующего периода по crossingTimeUs/duration
        // (Apple использует getPlayerMediaItemFromPeriodHolder().getDuration()).
        // Не перенесён; crossingTimeUs всегда 0 в MANUAL, ветка не исполняется.
      }
      boolean isEnded = renderers[fadeOutPeriodHolder.getRendererIdx()].isEnded();
      boolean hasReadStreamToEnd = renderers[fadeOutPeriodHolder.getRendererIdx()].hasReadStreamToEnd();
      if ((this.fadeOutLevel < 0.05d && this.fadeInLevel > 0.95d)
          || (hasReadStreamToEnd && isEnded)) {
        Log.e(
            TAG,
            "doCrossFade() complete. EOS: " + hasReadStreamToEnd + ", isEnded: " + isEnded);
        this.fadePhase = FadePhase.COMPLETED;
      }
    }
  }

  // ── maybeDoFadeOut (Apple 1:1) ── исполняется только при AUTOMATIC → в MANUAL no-op.
  @Override
  public void maybeDoFadeOut(@Nullable MediaPeriodHolder fadeOutPeriodHolder, long rendererPositionUs)
      throws ExoPlaybackException {
    if (getTransitionDataAvailable()
        && this.fadePhase == FadePhase.IDLE
        && fadeOutPeriodHolder != null) {
      AudioFadeTransition transition = transitionsMap.get(FadeType.FADE_OUT);
      if (transition == null) {
        return;
      }
      long periodTime = fadeOutPeriodHolder.toPeriodTime(rendererPositionUs);
      if (periodTime < transition.getStartUs()) {
        return;
      }
      long now = System.currentTimeMillis();
      if (now - this.lastMsgTs < this.msBetweenMessages) {
        return;
      }
      this.lastMsgTs = now;
      doFadeOut(fadeOutPeriodHolder, rendererPositionUs);
    }
  }

  // ── pauseFadeOut / resumeFadeOut (Apple 1:1) ──
  @Override
  public synchronized void pauseFadeOut() throws ExoPlaybackException {
    if (isCrossFadeEnabled() && this.fadePhase != FadePhase.IDLE) {
      MediaPeriodHolder mediaPeriodHolder = this.fadeOutPeriodHolder;
      if (mediaPeriodHolder == null) {
        return;
      }
      Renderer renderer = renderers[mediaPeriodHolder.getRendererIdx()];
      if (renderer.getState() == Renderer.STATE_STARTED) {
        renderer.stop();
        this.paused = true;
      }
    }
  }

  @Override
  public synchronized void resumeFadeOut() throws ExoPlaybackException {
    if (isCrossFadeEnabled() && this.fadePhase != FadePhase.IDLE) {
      MediaPeriodHolder mediaPeriodHolder = this.fadeOutPeriodHolder;
      if (mediaPeriodHolder == null) {
        return;
      }
      Renderer renderer = renderers[mediaPeriodHolder.getRendererIdx()];
      if (renderer.getState() == Renderer.STATE_ENABLED) {
        renderer.start();
      }
      this.paused = false;
    }
  }

  // ── reset (Apple 1:1 + реинициализация durationUs через crossFadeDuration) ──
  @Override
  public synchronized void reset() throws ExoPlaybackException {
    Log.e(TAG, "reset()");
    try {
      if (this.fadeInPeriodHolder != null) {
        setVolume(this.fadeInPeriodHolder.getRendererIdx(), MAX_VOLUME);
      }
      if (this.fadeOutPeriodHolder != null) {
        setVolume(this.fadeOutPeriodHolder.getRendererIdx(), MAX_VOLUME);
      }
    } catch (Exception e) {
      Log.e(TAG, "reset() exception ex: " + e);
    }
    this.lastMsgTs = Long.MAX_VALUE;
    this.fadeOutPeriodHolder = null;
    this.fadeInPeriodHolder = null;
    this.fadeOutLevel = MAX_VOLUME;
    this.fadeInLevel = MIN_VOLUME;
    this.isComputeTransitionJobExecuted = false;
    this.transitionDataAvailable = false;
    transitionsMap.put(FadeType.FADE_IN, new AudioFadeTransition(FadeEffectType.LOGARITHMIC));
    transitionsMap.put(FadeType.FADE_OUT, new AudioFadeTransition(FadeEffectType.EXPONENTIAL));
    this.fadePhase = FadePhase.IDLE;
    this.crossingTimeUs = MIN_VOLUME;
    this.canFadeCached = null;
    // media3-адаптация: Apple после reset заново заполняет start/durationUs транзишенов через
    // композер (setAutomations). Композера нет → сами восстанавливаем durationUs из
    // crossFadeDuration, иначе default-транзишены имеют durationUs=0 и следующий фейд сломается.
    if (this.crossFadeDuration > 0) {
      applyCrossFadeDurationToTransitions(this.crossFadeDuration);
    }
  }

  // ── Конфиг ──
  // media3-адаптация: Apple setCrossFadeDuration лишь сохраняет поле (durationUs транзишенов
  // задаёт композер). Без композера мы обязаны здесь же залить durationUs в транзишены.
  @Override
  public synchronized void setCrossFadeDuration(int crossFadeDuration) {
    this.crossFadeDuration = crossFadeDuration;
    applyCrossFadeDurationToTransitions(crossFadeDuration);
  }

  private void applyCrossFadeDurationToTransitions(int crossFadeDurationSeconds) {
    long durationUs = (long) crossFadeDurationSeconds * US_PER_SECOND;
    AudioFadeTransition in = transitionsMap.get(FadeType.FADE_IN);
    AudioFadeTransition out = transitionsMap.get(FadeType.FADE_OUT);
    if (in != null) {
      setFadeAudioEffect(
          FadeType.FADE_IN,
          new AudioFadeTransition(in.getEffectType(), durationUs, in.getCoefficient()));
    }
    if (out != null) {
      setFadeAudioEffect(
          FadeType.FADE_OUT,
          new AudioFadeTransition(out.getEffectType(), durationUs, out.getCoefficient()));
    }
  }

  // ── setCrossFadeState (Apple 1:1; MediaPlayer-зависимые части опущены/помечены TODO) ──
  @Override
  public synchronized void setCrossFadeState(int crossFadeState) {
    int old = this.crossFadeState;
    this.crossFadeState = crossFadeState;
    if (crossFadeState == 1) { // MANUAL
      this.secondTrackOffsetUs = Long.MAX_VALUE;
      MediaPeriodHolder mediaPeriodHolder = this.fadeOutPeriodHolder;
      if (mediaPeriodHolder != null) {
        try {
          setVolume(mediaPeriodHolder.getRendererIdx(), MAX_VOLUME);
        } catch (ExoPlaybackException e) {
          Log.e(TAG, "setCrossFadeState() volume reset exception: " + e);
        }
      }
      // TODO: Apple здесь mediaPlayer.setCrossFadeDuration(playerContext.getCrossFadeDuration()) —
      // недоступно без обёртки MediaPlayer/MediaPlayerContext.
    }
    if (old == 2 && (crossFadeState == 1 || crossFadeState == 0)) {
      // TODO: Apple делает seek-nudge (mediaPlayer.seekToPosition(pos+1)) чтобы перезапустить
      // конвейер — недоступно без обёртки MediaPlayer.
    }
  }

  // ── setFadeAudioEffect (Apple 1:1) ──
  @Override
  public synchronized void setFadeAudioEffect(FadeType fadeType, AudioFadeTransition transition) {
    transitionsMap.put(fadeType, transition);
    this.msBetweenMessages =
        Math.max(
            Math.min(
                (transition.getDurationUs() / MILLIS_PER_SECOND) / ((long) NUM_MESSAGES),
                MAX_MS_BETWEEN_MESSAGES),
            MIN_MS_BETWEEN_MESSAGES);
    Log.e(
        TAG,
        "setFadeAudioEffect() new duration: "
            + transition.getDurationUs()
            + " msBetweenMsg: "
            + this.msBetweenMessages);
  }

  // ── Геттеры/состояние (Apple 1:1) ──
  @Override
  public int getCrossFadeDuration() {
    return this.crossFadeDuration;
  }

  @Override
  public int getCrossFadeState() {
    return this.crossFadeState;
  }

  @Override
  public FadePhase getCrossFadePhase() {
    return this.fadePhase;
  }

  @Override
  public boolean isCrossFadeEnabled() {
    int i = this.crossFadeState;
    return i == 0 || i == 1;
  }

  @Override
  public synchronized boolean isCrossFadeInProgress() {
    return this.fadePhase != FadePhase.IDLE;
  }

  @Override
  public int getFadeInRendererIndex() {
    MediaPeriodHolder mediaPeriodHolder = this.fadeInPeriodHolder;
    return mediaPeriodHolder != null ? mediaPeriodHolder.getRendererIdx() : C.INDEX_UNSET;
  }

  @Override
  public int getFadeOutRendererIndex() {
    MediaPeriodHolder mediaPeriodHolder = this.fadeOutPeriodHolder;
    return mediaPeriodHolder != null ? mediaPeriodHolder.getRendererIdx() : C.INDEX_UNSET;
  }

  private boolean getTransitionDataAvailable() {
    return this.transitionDataAvailable;
  }
}
