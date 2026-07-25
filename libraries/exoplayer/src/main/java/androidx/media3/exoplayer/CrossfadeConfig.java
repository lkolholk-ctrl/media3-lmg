/*
 * LMG-fork (crossfade). Мостик «приложение → движок кроссфейда».
 *
 * Зачем статический холдер, а не метод на ExoPlayer: движок кроссфейда живёт в
 * package-private ExoPlayerImplInternal, а расширять публичный API media3
 * (интерфейс ExoPlayer/ExoPlayerImpl) ради этого не хочется — при обновлении
 * upstream такой патч мержить больнее. Холдер читается плеером в момент взвода
 * фейда, поэтому рецепт можно класть в любой момент до перехода.
 *
 * Модель (AutoMix) выдаёт рецепт на ПАРУ треков: длительность свода, тип кривой
 * и точку входа в следующий трек. Момент старта берём НЕ из модели: её
 * transitionStartMs ненадёжен (так же поступает оффлайн JUCE-движок) — свод
 * якорим к концу трека, remaining <= xfade.
 */
package androidx.media3.exoplayer;

/** Конфиг кроссфейда: тумблер + рецепт от ML-модели (AutoMix). */
public final class CrossfadeConfig {

  /** Окно длительности свода, которое отдаёт модель (как в оффлайн-движке). */
  public static final long MIN_XFADE_MS = 5_000L;

  public static final long MAX_XFADE_MS = 30_000L;

  private static volatile boolean enabled = true;
  private static volatile long xfadeMs = MIN_XFADE_MS;
  private static volatile long entryOffsetMs = 0L;
  private static volatile int curveType = -1; // -1 = кривая по умолчанию (log-in/exp-out)
  private static volatile boolean fromModel = false;

  private CrossfadeConfig() {}

  /** Общий тумблер кроссфейда (PlayerSettings.autoMix со стороны приложения). */
  public static void setEnabled(boolean value) {
    enabled = value;
  }

  public static boolean isEnabled() {
    return enabled;
  }

  /**
   * Кладёт рецепт модели для следующего перехода.
   *
   * @param crossfadeDurationMs длительность свода; зажимается в [{@link #MIN_XFADE_MS},
   *     {@link #MAX_XFADE_MS}].
   * @param transitionType индекс кривой из модели; вне диапазона [0,6) — кривая по умолчанию.
   * @param entryOffsetMsValue точка входа в следующий трек (мс от его начала); 0 = с начала.
   */
  public static void applyRecipe(
      long crossfadeDurationMs, int transitionType, long entryOffsetMsValue) {
    xfadeMs = Math.max(MIN_XFADE_MS, Math.min(MAX_XFADE_MS, crossfadeDurationMs));
    curveType = transitionType;
    entryOffsetMs = Math.max(0L, entryOffsetMsValue);
    fromModel = true;
  }

  /**
   * Сбрасывает рецепт. Без рецепта свода НЕ БУДЕТ вовсе (как в оффлайн-движке:
   * нет плана модели → обычное переключение треков), фиксированного фолбэка нет.
   */
  public static void clearRecipe() {
    xfadeMs = MIN_XFADE_MS;
    curveType = -1;
    entryOffsetMs = 0L;
    fromModel = false;
  }

  public static long getXfadeMs() {
    return xfadeMs;
  }

  public static long getEntryOffsetMs() {
    return entryOffsetMs;
  }

  public static int getCurveType() {
    return curveType;
  }

  /** true, если текущие параметры пришли от модели (а не фолбэк). */
  public static boolean isFromModel() {
    return fromModel;
  }
}
