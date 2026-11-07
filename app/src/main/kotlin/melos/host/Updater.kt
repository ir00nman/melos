package melos.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import melos.core.Paths
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

// Обновление по кнопке: сверяем версию с последним релизом на GitHub, качаем и подменяем установку.
//   Windows, портативная папка (exe + app\melos.jar + jre): качаем zip, распаковываем рядом и оставляем
//   apply.bat — он дождётся выхода Melos, заменит файлы и запустит Melos.exe снова (кнопка = обновился).
//   macOS, Melos.app: качаем свежий DMG в Загрузки и открываем — осталось перетащить в Программы.
//   Запуск из gradle (разработка): просто открываем страницу релизов.
// Релизы публикует packaging/publish.sh <версия> (zip + dmg одним тегом vX.Y.Z).
object Updater {
    private const val REPO = "ir00nman/melos"
    val releasesPage = "https://github.com/$REPO/releases/latest"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data class Available(val version: String, val assetUrl: String) : State()
        data class Downloading(val frac: Float) : State()
        data object Restarting : State()            // замена пошла — сейчас приложение само перезапустится
    }

    val state = MutableStateFlow<State>(State.Idle)
    private var dismissed: String? = null           // «Позже» — больше не напоминаем до перезапуска

    /** Версия этой сборки — из ресурсов (melos/version.txt, пишет gradle из -PappVersion). */
    val current: String by lazy {
        runCatching {
            javaClass.getResourceAsStream("/melos/version.txt")?.bufferedReader()?.readText()
                ?.trim()?.substringAfter('=', "")?.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0"
    }

    fun startCheck() {
        if (state.value is State.Checking) return
        state.value = State.Checking
        scope.launch {
            runCatching { check() }.onFailure {
                state.value = State.Idle
                Toasts.error("Обновления: ${it.message ?: "не удалось проверить"}")
            }
        }
    }

    /** Пользователь нажал «Обновить». */
    fun update() {
        val st = state.value as? State.Available ?: return
        scope.launch {
            runCatching { apply(st) }.onFailure {
                state.value = State.Idle
                Toasts.error("Обновление не получилось: ${it.message}")
                Log.e("Update", "apply", it)
            }
        }
    }

    fun dismiss() {
        (state.value as? State.Available)?.let { dismissed = it.version }
        state.value = State.Idle
    }

    // ---- проверка ----

    private suspend fun check() {
        val req = HttpRequest.newBuilder(URI("https://api.github.com/repos/$REPO/releases/latest"))
            .header("Accept", "application/vnd.github+json")
            .timeout(Duration.ofSeconds(20)).build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() == 404) { state.value = State.Idle; return }      // релизов ещё не было — не назойлив
        check(resp.statusCode() == 200) { "GitHub: HTTP ${resp.statusCode()}" }
        val o = json.parseToJsonElement(resp.body()).jsonObject
        val tag = o.str("tag_name").orEmpty().removePrefix("v")
        val asset = assetUrl(o) ?: error("в релизе нет файла для этой платформы")
        state.value = if (isNewer(tag, current) && tag != dismissed) State.Available(tag, asset) else State.Idle
    }

    /** Файл релиза под текущую ОС: Windows — *windows-x64.zip, macOS — *.dmg, Linux — *.AppImage. */
    private fun assetUrl(release: JsonObject): String? {
        val assets = release["assets"]?.jsonArray ?: return null
        fun match(pred: (String) -> Boolean) = assets.mapNotNull { a ->
            (a as? JsonObject)?.let { it.str("browser_download_url") }
        }.firstOrNull { pred(it.substringAfterLast('/').lowercase()) }
        return when {
            Paths.isWindows -> match { it.endsWith(".zip") && "windows" in it }
            Paths.isMac -> match { it.endsWith(".dmg") }
            else -> match { it.endsWith(".appimage") }
        }
    }

    private fun isNewer(tag: String, cur: String): Boolean {
        fun parts(s: String) = s.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(tag); val b = parts(cur)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // ---- применение ----

    private suspend fun apply(a: State.Available) {
        val portable = portableDir()
        when {
            Paths.isWindows && portable != null -> {
                val tmp = File.createTempFile("melos-update", "").let { f -> f.delete(); File(f.path).apply { mkdirs() } }
                val zip = File(tmp, "update.zip")
                download(a.assetUrl, zip)
                val new = File(tmp, "new")
                unzip(zip, new)                                             // внутри — new\Melos\{Melos.exe, app, jre}
                check(File(new, "Melos/Melos.exe").isFile) { "в архиве нет Melos.exe" }
                // пути в bat — только ASCII (кодировка cmd): пользовательские папки с кириллицей не проходят,
                // поэтому temp берём системный, а папку установки передаём аргументом
                val bat = File(tmp, "apply.bat")
                bat.writeText("""
                    @echo off
                    rem Melos: подмена файлов после закрытия приложения
                    cd /d "%~dp0"
                    :wait
                    ping -n 4 127.0.0.1 >nul
                    xcopy /E /Y /I /Q "new\Melos" "%~1"
                    if errorlevel 1 goto wait
                    start "" /D "%~1" "%~1\Melos.exe"
                    rd /S /Q "%~dp0"
                """.trimIndent())
                state.value = State.Restarting
                ProcessBuilder("cmd", "/c", "start", "", "/min", bat.path, portable.path).start()
                delay(1200)                                                 // дать батнику уйти в цикл ожидания
                quit()
            }
            Paths.isMac && runningInAppBundle() -> {
                val dmg = File(System.getProperty("user.home"), "Downloads/${a.assetUrl.substringAfterLast('/')}")
                download(a.assetUrl, dmg)
                state.value = State.Idle
                ProcessBuilder("open", dmg.path).start()
                Toasts.longNotify("Melos ${a.version} скачан — в открывшемся окне перетащите Melos в Программы")
            }
            else -> {                                                       // gradle-запуск или портатив без exe — до релизов в браузере
                state.value = State.Idle
                runCatching { java.awt.Desktop.getDesktop().browse(URI(releasesPage)) }
                Toasts.shortNotify("Страница релизов открыта в браузере")
            }
        }
    }

    /** Папка портативной установки (где Melos.exe рядом с app\melos.jar) — только там сам себя подменяем. */
    private fun portableDir(): File? {
        val jar = runCatching {
            File(Updater::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull() ?: return null
        val app = jar.parentFile?.parentFile ?: return null                 // <установка>\app\melos.jar
        return app.takeIf { File(it, "Melos.exe").isFile }
    }

    private fun runningInAppBundle(): Boolean = runCatching {
        File(Updater::class.java.protectionDomain.codeSource.location.toURI()).path.contains(".app/Contents/")
    }.getOrDefault(false)

    private suspend fun download(url: String, to: File) {
        state.value = State.Downloading(0f)
        withContext(Dispatchers.IO) {
            val resp = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofInputStream())
            check(resp.statusCode() == 200) { "HTTP ${resp.statusCode()}" }
            val total = resp.headers().firstValueAsLong("content-length").orElse(-1)
            var got = 0L
            resp.body().use { input -> to.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); got += n
                    if (total > 0) state.value = State.Downloading(got.toFloat() / total)
                }
            } }
            check(total <= 0 || got == total) { "загрузка оборвалась (${got / 1_048_576} из ${total / 1_048_576} МБ)" }
        }
    }

    private fun unzip(zip: File, to: File) {
        java.util.zip.ZipFile(zip).use { z ->
            z.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
                val f = File(to, e.name).apply { parentFile.mkdirs() }
                check(f.canonicalPath.startsWith(to.canonicalPath)) { "плохой архив" }
                z.getInputStream(e).use { i -> f.outputStream().use { i.copyTo(it) } }
            }
        }
        zip.delete()
    }

    /** Честный выход: сохраняем очередь и закрываем Chromium, как при закрытии окна, затем — процесс. */
    private fun quit() {
        runCatching { melos.player.Player.shutdown() }
        runCatching { Chromium.shutdown() }
        exitProcess(0)
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
