/*
 * LMG-fork (crossfade). Тумблер отладочного лога свода.
 *
 * Параметры свода (длительность, кривая, точка входа) задаются на плеере через
 * ExoPlayer.setCrossfadeConfiguration — они у каждого плеера свои. Здесь остался
 * только флаг подробного логирования: он глобален по смыслу (включается на время
 * разбора проблемы) и нужен коду, у которого нет ссылки на плеер.
 */
package androidx.media3.exoplayer;

/** Подробный лог кроссфейда: по умолчанию выключен. */
public final class CrossfadeConfig {

  private static volatile boolean debugLogging = false;

  private CrossfadeConfig() {}

  /**
   * Включает подробный лог свода: уровни громкости на каждом тике, взвод,
   * завершение.
   *
   * <p>Логи идут через {@code Log.e} намеренно: R8 в релизной сборке вырезает
   * {@code Log.d}/{@code Log.v}, и диагностика исчезала бы именно там, где нужна.
   * Поэтому громкость лога регулируется этим флагом, а не уровнем.
   */
  public static void setDebugLogging(boolean value) {
    debugLogging = value;
  }

  public static boolean isDebugLogging() {
    return debugLogging;
  }
}
