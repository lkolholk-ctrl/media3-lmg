# media3 — LMG fork (crossfade)

Форк `androidx/media` (тег **1.5.1**) для LiquidMusicGlass. Цель — добавить
кроссфейд стриминга по образцу Apple Music (см. `PlayerAudioFadeControl`),
чего сток media3 не умеет.

## Что изменено относительно upstream 1.5.1
- `settings.gradle`: удалены demo-приложения, testapp и тест-онли модули —
  форк собирает только библиотечные AAR.
- `libraries/test_data`: вырезаны тяжёлые медиа-ассеты (тесты не гоняем).
- `publish.gradle`: публикация больше НЕ зависит от `lint`/`test`.
- **(в работе)** патч кроссфейда: интерфейс `AudioFadeControl` в ядре
  ExoPlayer, второй одновременный аудио-рендерер, хуки в playback-loop,
  `MediaPeriodHolder.getRendererIdx()`.

## Сборка
CI (`.github/workflows/build-aars.yml`) публикует 11 модулей в maven-репозиторий
и выкладывает его zip-ом в GitHub Release `v<version>`:

    media3-common, media3-common-ktx, media3-container, media3-database,
    media3-datasource, media3-decoder, media3-extractor, media3-exoplayer,
    media3-exoplayer-hls, media3-session, media3-ui

groupId остаётся `androidx.media3`, версия — `RELEASE_VERSION` (напр. `1.5.1-lmg1`).

## Потребление в приложении LMG
CI приложения скачивает zip релиза, распаковывает в `./media3-m2` и добавляет:

    repositories { maven { url = uri("media3-m2") } }
    // зависимости: androidx.media3:media3-*:1.5.1-lmg1

Так основной CI остаётся быстрым — media3 пересобирается только здесь, при
изменении патча (бампаем `RELEASE_VERSION`).
