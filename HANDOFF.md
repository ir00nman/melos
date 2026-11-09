# HANDOFF — продолжение работы на другой машине

Снимок сессии ZCode от 2026-11-04 (работа велась на macOS, переносится на Windows).
Прочти это первым делом — дальше контекст не теряется.

## Что это за проект

Melos (бывш. Flayer) — десктопный музыкальный плеер: Compose Desktop + VLC (vlcj) + хост плагинов Murglar
(Яндекс Музыка, SoundCloud, VK встроены; jar-плагины в `%APPDATA%\Melos\plugins`).

## Запуск и сборка (Windows)

- Нужны: JDK 21 (Temurin, `JAVA_HOME`), VLC 3 **x64** для звука.
- Разработка: `gradlew.bat :app:run`
- Портативная сборка уже умеет кросс-собираться с мака (`packaging/windows/build.sh`), артефакты: `dist/Melos-windows-x64.zip` (портативная папка) и `dist/Melos.exe` (warp-однофайловый).
- MSI-установщик собирается только на Windows: `packaging\windows\build-msi.bat` (нужны JDK 21 + WiX Toolset 3.x). Настройки установщика — `app/build.gradle.kts`, блок `nativeDistributions.windows`.

## Что сделано в этой сессии

Всё ниже (и работа 2026-11-04…07: VK-плагин, офлайн-поиск, эквалайзер, обновлялка, тактильный UI,
синк мозга с битом, лого-эмблема, компактные вершины мозга) закоммичено и запушено 2026-11-07
(main@babf6bf). Windows-сборка с этими изменениями: `dist/Melos-windows-x64.zip` + `dist/Melos.exe`.
На Windows-машине: `git pull` → при желании MSI через `packaging\windows\build-msi.bat`.

1. **Фикс «кнопка плей не работает после переключения вкладок»**: `VlcEngine.buffering` больше не затирает статус PAUSED; `Player.toggle()` перезапускает трек, если застряли в OPENING/BUFFERING с мёртвой загрузкой; `setTap` чинит рассинхрон статуса при пересоздании движка (`melos/player/VlcEngine.kt`, `melos/player/Player.kt`).
2. **Windows-порт**: карточка «Melos не нашёл VLC» на главной с кнопками «Найти на ПК» (перепроверка без перезапуска, `Player.recheckVlc()`) и «Установить VLC» (страница по ОС, `Paths.vlcDownloadUrl`); статус VLC в Настройки → Звук; рендер Windows = DIRECT3D (`skiko.renderApi`, Main.kt); иконка окна из `app/src/main/resources/melos/icon.png`; `packaging/windows`: `build-msi.bat`, чек-лист `ПРОВЕРКА.md`.
3. **Прогресс скачивания Chromium** — шкала-оверлей сверху окна (`Chromium.downloadProgress` StateFlow + `ChromiumDownload` в `ui/App.kt`), вместо тостов раз в 10%.
4. **Полоса стрелок навигации** восстановлена: `App(topInset = 22)` на всех ОС (уборка отступа на Windows ломала стрелки).
5. **Звук через Melos на Windows**: 16-битная линия Java Sound пробуется первой; если вывод не поднялся — фолбэк на прямой VLC + тост вместо тишины (`Player.newEngine`).
6. **Обновление по кнопке** (`melos/host/Updater.kt`): при запуске через 5 с сверяет версию с последним релизом GitHub (`ir00nman/melos`), при новой — карточка сверху с «Обновить»/«Позже»; раздел «Обновления» в настройках. Windows-портатива: zip качается, `apply.bat` после выхода Melos подменяет файлы и перезапускает; macOS: DMG в Загрузки + `open`; gradle-запуск: открывает страницу релизов. Версия сборки — `melos/version.txt` из `-PappVersion` (gradle, `app/build.gradle.kts`). **Релизы публикует `packaging/publish.sh <версия>`** (нужен `gh auth login`): собирает zip + dmg и создаёт GitHub Release — без релиза кнопка молчит (404 = тихо).

## Как выпустить новую версию

```bash
./packaging/publish.sh 1.0.1 "что нового"     # zip + dmg → GitHub Releases v1.0.1
```

## Что не проверено / дальше

- **Живой тест на Windows** по чек-листу `packaging/windows/ПРОВЕРКА.md` — главное хвостовое дело.
- Сборка MSI на Windows-машине (`build-msi.bat`).
- Медиа-клавиши/SMTC, трей — сознательно вне объёма, можно следующим шагом.
- **Android (этап 2 готов)**: общий UI десктопа (Screens/Sheets/Kit/…) + мобильная оболочка
  DroidRoot (шторка-меню); проверено на живом Poco — волна играет. Платформенные швы:
  melos.core.Browse, SheetScroll/ScrollList, копии Rows/PlayerBar/Stage. Осталось: веб-вход,
  MediaSession, загрузки, «мозг» (skia).
  Грабли версий: AGP 9.x несовместим с KGP → AGP 8.13.2 + Gradle 9.1 (wrapper переключён!);
  Compose BOM 2026.06.01 / activity 1.11 / okhttp 5.1 (новее требуют compileSdk 37 — его ещё нет).
  Desktop `VlcEngine` и `Player.kt` отвязаны от vlcj-типов через `melos/player/PlayerEqualizer.kt` —
  не сломать эту абстракцию при правках эквалайзера.

## Грабли (важно!)

- **Пока экземпляр Melos запущен — любые gradle-сборки только с `-PdevOut=<папка>`**: пересборка классов под живым JVM ломает его (CNFE на ленивых лямбдах, кнопки умирают молча). Проверка: `tasklist | findstr java` / `pgrep -fl melos.MainKt`.
- Данные приложения на Windows: `%APPDATA%\Melos` (там же скачанный Chromium — при замене папки программы не перекачивается).
- Логи: `%APPDATA%\Melos\logs\melos-<дата>.log`.
- На macOS системной Java нет — `JAVA_HOME=/opt/homebrew/opt/openjdk@21` для gradlew.

## Стиль проекта

- Комментарии в коде — только когда нужно объяснить почему (обход платформы, магическое число, порядок вызовов); по-русски, коротко, одна строка. Плотность — в среднем не чаще одного на ~25 строк, в UI реже.
- Никаких лишних зависимостей; утилиты — в `ui/Kit.kt`, тосты — `melos.host.Toasts`.
- Отладочные крючки через переменные `MELOS_*` (см. `Main.kt`).

## Логотип «мини-мозг» (2026-11-05)

Генератор: `packaging/icons/make_logo.py` (Pillow; узлы+связи в форме мозга, градиент acc→c2, синапсы-вспышки).
Выход: `packaging/icons/melos-brain.png|melos.icns|melos.ico`; ресурс окна `app/src/main/resources/melos/icon.png`.
Gradle: macOS → icns, Windows → ico (packaging/windows/melos.ico тоже обновлён для launch4j).
Перегенерация: `python3 packaging/icons/make_logo.py` + пересборка дистрибутивов.
