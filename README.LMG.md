# media3 — форк LiquidMusicGlass (кроссфейд)

Форк [`androidx/media`](https://github.com/androidx/media) (тег **1.5.1**).
Добавляет кроссфейд между треками — наложение уходящего и входящего трека с
плавным переходом громкости, чего сток media3 не умеет: он переключает треки
встык (gapless) и не играет два аудиопотока одновременно.

Логика свода воспроизводит поведение кроссфейда плеера Apple Music для Android
(Apple эту реализацию не публикует). Проект не аффилирован с Apple Inc. и не
одобрен ею — подробнее в [`NOTICE.md`](NOTICE.md).

Юридическая часть и полный перечень изменений — в [`NOTICE.md`](NOTICE.md).
История версий — в [`CHANGELOG.LMG.md`](CHANGELOG.LMG.md).

---

## Подключение

Артефакты публикуются под собственной группой `com.liquidmusicglass.media3`,
чтобы Gradle не мог перепутать их с оригиналом и подставить сток вместо форка.

**1. Скачать maven-репозиторий из релиза** (в CI — отдельным шагом):

```bash
VER=1.5.1-lmg25
curl -sSL -o media3-m2.zip \
  "https://github.com/lkolholk-ctrl/media3-lmg/releases/download/v${VER}/media3-${VER}-m2.zip"
mkdir -p media3-m2 && unzip -q media3-m2.zip -d media3-m2
```

**2. Подключить репозиторий** (`settings.gradle.kts`):

```kotlin
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("media3-m2")
            content { includeGroup("com.liquidmusicglass.media3") }
        }
        google()
        mavenCentral()
    }
}
```

**3. Зависимости** (`app/build.gradle.kts`):

```kotlin
configurations.configureEach {
    // Форк живёт в тех же Java-пакетах, что и оригинал: две копии классов
    // сборку не соберут. Отсекаем сток, если его притащит транзитивная зависимость.
    exclude(group = "androidx.media3")
}

dependencies {
    implementation("com.liquidmusicglass.media3:media3-common:1.5.1-lmg25")
    implementation("com.liquidmusicglass.media3:media3-exoplayer:1.5.1-lmg25")
    implementation("com.liquidmusicglass.media3:media3-session:1.5.1-lmg25")
    implementation("com.liquidmusicglass.media3:media3-ui:1.5.1-lmg25")
    // при необходимости: media3-extractor, media3-exoplayer-hls,
    // media3-common-ktx, media3-datasource, media3-decoder,
    // media3-container, media3-database
}
```

Импорты в коде **не меняются** — классы остались `androidx.media3.*`.

---

## Использование кроссфейда

Свод управляется статическим конфигом `androidx.media3.exoplayer.CrossfadeConfig`.
Плеер читает его в момент взвода свода, поэтому параметры можно менять в любой
момент до перехода.

```kotlin
import androidx.media3.exoplayer.CrossfadeConfig

// Включить/выключить свод целиком.
CrossfadeConfig.setEnabled(true)

// Задать параметры перехода.
CrossfadeConfig.applyRecipe(
    /* crossfadeDurationMs = */ 9_000L,  // зажимается в [MIN_XFADE_MS, MAX_XFADE_MS] = [1 c, 12 c]
    /* transitionType      = */ -1,      // -1 = кривая по умолчанию (равная мощность)
    /* entryOffsetMsValue  = */ 0L       // с какой позиции начинать следующий трек
)

// Убрать параметры: без них свода не будет — треки переключатся обычным образом.
CrossfadeConfig.clearRecipe()
```

`transitionType` задаёт форму кривой громкости:

| Значение | Кривая | Когда уместна |
|---|---|---|
| `-1` | равная мощность (по умолчанию) | ровный свод: суммарная громкость не проваливается, перекрытие слышно всю длительность |
| `0` | равная мощность | то же, явным указанием |
| `1` | экспонента | уходящий держится дольше, входящий вступает резче |
| `2` | линейная | ритм не «плывёт» |
| прочие | по умолчанию | — |

### Когда свода НЕ будет

- параметры не заданы (`clearRecipe`) или свод выключен;
- следующий трек ещё не подготовлен — свод начнётся позже и выйдет короче
  заданного; чтобы этого избежать, включите предзагрузку:
  `player.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(30_000_000L))`;
- трек короче удвоенной длительности свода;
- длительность трека неизвестна;
- следующий трек — соседний трек того же альбома (совпадает альбом и номер
  диска, номер трека идёт следующим): такие переходы задуманы встык;
- включён повтор одного трека.

### Диагностика

Движок пишет ключевые события через `Log.e` — они переживают вырезание логов в
релизной сборке. Фильтр — `xfade`:

- `xfade ARM: remainingUs=… nextPrepared=…` — свод взведён: видно, за сколько до
  конца трека это случилось и был ли готов следующий период;
- `setFadeAudioEffect() new duration: …` — длительность, дошедшая до движка (мкс);
- `doCrossFade() fadeOutLevel: … fadeInLevel: …` — текущие уровни громкости;
- `xfade SKIP: соседние треки альбома` — свод намеренно пропущен;
- `xfade WATCHDOG: …` — свод завис и был принудительно сброшен.

---

## Как это устроено

Сток media3 держит один аудио-рендерер и переключает периоды встык. Для
наложения нужны два одновременно звучащих потока, поэтому:

1. `DefaultRenderersFactory` создаёт **второй аудио-рендерер**;
2. `DefaultMediaClock` перестаёт считать ошибкой включение второго
   аудио-рендерера;
3. в цикле воспроизведения (`ExoPlayerImplInternal`) появляются фазы свода:
   взвод за N секунд до конца трека, тик с пересчётом громкостей, смена
   играющего периода **без освобождения** предыдущего, освобождение уходящего
   периода после завершения;
4. `PlayerAudioFadeControl` на каждом тике считает громкость обоих треков по
   выбранной кривой и выставляет её рендерерам сообщением `MSG_SET_VOLUME`.

Момент старта свода — позиционный: последние N секунд трека, где N — заданная
длительность.

---

## Сборка форка

CI (`.github/workflows/build-aars.yml`) собирает 11 модулей, складывает их в
maven-репозиторий и публикует его zip-архивом в GitHub Release `v<version>`:

```
media3-common, media3-common-ktx, media3-container, media3-database,
media3-datasource, media3-decoder, media3-extractor, media3-exoplayer,
media3-exoplayer-hls, media3-session, media3-ui
```

Версия задаётся **в двух местах, и они обязаны совпадать**:

- `constants.gradle` → `releaseVersion` — версия артефактов;
- `.github/workflows/build-aars.yml` → `RELEASE_VERSION` — имя релиза и архива.

Если поднять только одну, релиз соберётся под именем старой версии, а артефакты
внутри будут новой — подключение сломается с ошибкой «зависимость не найдена».
Так уже случалось, см. `lmg19` в истории версий.

Локальная сборка:

```bash
./gradlew publishAllPublicationsToLocalRepository
```
