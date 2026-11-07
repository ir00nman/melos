# Melos для Android (этап 1)

Модуль `:android` собирает APK из **общего кода десктопа** (`app/src/main/kotlin`, копируется задачей
`syncSharedSources` минус десктоп-файлы) + своих реализаций тех же пакетов в `android/src/main/kotlin`:

| Пакет | Десктоп | Android |
|---|---|---|
| `melos.player.VlcEngine` | vlcj + javax.sound (тап) | **libVLC** (`org.videolan.android:libvlc-all`), прямой вывод |
| `melos.host.HttpNetwork` | java.net.http | **OkHttp** (та же логика запросов и кук) |
| `melos.host.Plugins`/Services | встроенные + jar-плагины | только встроенные (URLClassLoader на ART нет) |
| `melos.host.EmbeddedWebView` | Chromium/JavaFX | заглушка (веб-вход — следующий этап; вход токеном работает) |
| `melos.core.Paths` | user.home | `Paths.init(context)` → files приложения |
| `melos.player.TrackAnalysis` | карта трека из PCM | no-op |

`Player.kt`, очередь, волны, лайки, история, настройки — **общие без изменений**.

## Сборка

```bash
JAVA_HOME=<jdk21> ./gradlew :android:assembleDebug
# → android/build/outputs/apk/debug/android-debug.apk
```

Нужен Android SDK (`local.properties` → `sdk.dir`), платформа android-36. Версии подобраны под
Gradle 9.1: **AGP 8.13.2** (AGP 9.x не совместим с KGP — только встроенный Kotlin), Compose BOM
2026.06.01, activity 1.11, okhttp 5.1 (более новые требуют compileSdk 37, которого ещё нет).

## Запуск на телефоне

1. Установить APK (214 МБ — libVLC под все архитектуры; позже можно резать по ABI).
2. Вставить OAuth-токен Яндекс Музыки (ym.marshal.dev/token) → «Войти».
3. «Моя волна» → играть; транспорт, перемотка ±10 с, тычок по треку очереди.

Данные — в приватной папке приложения; куки и токен переживают перезапуск.

## Что дальше (этапы)

- Веб-вход в сервисах (android.webkit WebView за тем же `WebViewProvider`).
- Foreground-service + MediaSession (кнопки в шторке, работа при свернутом окне).
- Настоящий UI: переработка визуала под телефон (жесты, без hover), затем тексты/загрузки.

## Грабли, на которые наступили (не наступить снова)

- `LibVLC(context, options)` **дописывает свои опции в переданный список** — список обязан быть
  мутируемым (`arrayListOf`, не `listOf`), иначе `UnsupportedOperationException` на старте.
- `Media(lib, String)` на Android — это **локальный путь**: https-URL он заворачивает в `file://...`.
  Сетевые потоки — только `Media(lib, Uri.parse(url))`.
- HyperOS не даёт `adb shell input tap` без «Отладка по USB (безопасность)» — для автотестов у
  MainActivity есть intent-экстра: `am start -n app.melos.droid/melos.droid.MainActivity --ez wave true`.
- Токен/настройки можно переносить с десктопа: `adb push` в `/data/local/tmp`, затем
  `adb shell run-as app.melos.droid cp /data/local/tmp/ynd.json files/data/prefs/ynd.json`.

## Этап 2 (2026-11-05): настоящий UI, общий с десктопом

`melos.ui` теперь почти целиком общий: Screens/SearchHome/Sheets/SidePanel/Kit/Theme/Logos/Menus
компилируются на обеих платформах. Оболочка на Android — `melos/ui/DroidRoot.kt`
(шторка-меню вместо сайдбара, очередь/текст — панель поверх сцены, «назад» — BackHandler).
Платформенные копии (те же пакеты, android/src): Rows/PlayerBar/Stage/ScrollList/SheetScroll/Images/
Brain(заглушка до skia-этапа). Системные действия — через `melos.core.Browse` (url/dir/pickDir),
его реализацию платформа присваивает при старте (Main.kt / MainActivity).

Что уже работает: главная, поиск (+ «со всех площадок»), страницы узлов/треков, медиатека,
история, волна Яндекса, лайки, эквалайзер, настройки, шторки; токен-вход Яндекса.
Что ещё заглушено: «мозг» (skia-рисование), загрузки, выбор папки, веб-вход сервисов.
