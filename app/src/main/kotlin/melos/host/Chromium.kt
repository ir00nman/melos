package melos.host

import com.badmanners.murglar.lib.core.webview.WebViewProvider
import com.badmanners.murglar.lib.core.webview.WebViewProvider.UrlLoadPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import melos.core.Paths
import java.io.File
import java.net.HttpCookie
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// окно входа — настоящий Chromium со своим профилем: WebKit не пускают Google/VK ID, JCEF на OpenJDK падал.
// Melos ведёт его по DevTools, как Puppeteer: видит переходы до загрузки, забирает куки, закрывает окно.
object Chromium {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    private val own get() = File(Paths.home, "chromium")
    private val profile get() = File(Paths.home, "chromium-profile")
    private val installLock = Mutex()
    private val openLock = Mutex()
    @Volatile private var process: Process? = null
    @Volatile private var cookies: List<JsonObject> = emptyList()
    /** Загрузка Chromium идёт: доля 0..1 для шкалы в интерфейсе (null — не качается). */
    val downloadProgress = kotlinx.coroutines.flow.MutableStateFlow<Float?>(null)
    private val rejected = Regex("""^https://accounts\.google\.com/(v3/)?signin/rejected""")

    private enum class Outcome { DONE, CLOSED, REJECTED }

    /** Кука из окна входа на Chromium (для сайтов, что держат вход в куке). */
    fun cookie(domain: String, name: String): String? = cookies.firstOrNull { c ->
        c.str("name") == name && matches(c.str("domain").orEmpty(), domain)
    }?.str("value")?.takeIf { it.isNotBlank() }

    /** То же, что WebViewProvider.startWebView. null — Chromium нет и не скачался: вход откроется в старом окне. */
    suspend fun open(
        enableJS: Boolean, userAgent: String?, startUrl: String, domains: List<String>,
        resolver: WebViewProvider.UrlLoadPolicyResolver, inspector: WebViewProvider.RequestsInspector?,
        finishWhen: (() -> Boolean)?,
    ): Boolean? = openLock.withLock {
        val bin = binary() ?: return null
        suspend fun controlled(): Outcome {
            val cdp = launch(bin)
            return try { session(cdp, enableJS, userAgent, startUrl, domains, resolver, inspector, finishWhen) } finally { close(cdp) }
        }
        when (controlled()) {
            Outcome.DONE -> true
            Outcome.CLOSED -> false
            Outcome.REJECTED -> {
                Log.i("WebLogin", "Google отклонил окно под управлением — открываю обычное")
                Toasts.longNotify("Google не пускает окно, которым управляет программа. Открываю обычное окно Chromium — войдите там, Melos заметит вход сам")
                if (!plain(bin, startUrl, resolver)) return@withLock false
                when (controlled()) {
                    Outcome.DONE -> true
                    Outcome.CLOSED -> false
                    Outcome.REJECTED -> { Toasts.error("Google снова отклонил вход — попробуйте войти по почте"); false }
                }
            }
        }
    }

    fun shutdown() { process?.let { p -> p.descendants().forEach { it.destroy() }; p.destroy() } }

    /** Окна входа, оставшиеся от прошлого запуска Melos (упал или закрыт по ⌘Q), — закрываем; при выходе — тоже. */
    fun cleanup() {
        val mark = "--user-data-dir=${profile.path}"
        ProcessHandle.allProcesses().filter { h -> h.info().arguments().map { args -> mark in args }.orElse(false) }
            .forEach { h -> if (h.pid() != process?.pid()) h.destroy() }
    }

    init { Runtime.getRuntime().addShutdownHook(Thread { runCatching { shutdown(); cleanup() } }) }


    private suspend fun session(
        cdp: Cdp, enableJS: Boolean, userAgent: String?, startUrl: String, domains: List<String>,
        resolver: WebViewProvider.UrlLoadPolicyResolver, inspector: WebViewProvider.RequestsInspector?,
        finishWhen: (() -> Boolean)?,
    ): Outcome {
        val result = CompletableDeferred<Outcome>()
        val pages = ConcurrentHashMap<String, String>()          // сессия DevTools → вкладка
        val seen = ConcurrentHashMap<String, String>()           // вкладка → последний проверенный адрес
        val inspecting = ConcurrentHashMap<String, Triple<String, Map<String, String>, String?>>()
        val main = CompletableDeferred<String>()                 // сессия первого окна

        suspend fun finish() {
            if (result.isCompleted) return
            Log.i("WebLogin", "finished (Chromium)")
            runCatching { syncCookies(cdp, domains) }.onFailure { Log.w("WebLogin", "cookies: ${it.message}") }
            result.complete(Outcome.DONE)
        }
        fun policy(url: String): UrlLoadPolicy =
            if (result.isCompleted) UrlLoadPolicy.DISCARD_LOAD
            else runCatching { resolver.resolveUrlLoadPolicy(url) }.getOrDefault(UrlLoadPolicy.ALLOW_LOAD)
        suspend fun restart(sid: String) { cdp.call("Page.navigate", buildJsonObject { put("url", startUrl) }, sid) }

        /** Переход главной страницы окна: решает плагин; отменённый адрес сайт не получит (код OAuth останется нашим). */
        suspend fun paused(p: JsonObject, sid: String) {
            val id = p.str("requestId") ?: return
            val req = p["request"]!!.jsonObject
            val url = req.str("url").orEmpty() + req.str("urlFragment").orEmpty()
            fun fail(reason: String = "Aborted") = buildJsonObject { put("requestId", id); put("errorReason", reason) }
            if ("responseStatusCode" in p || "responseErrorReason" in p) {         // ответ, который плагин просил показать
                val (u, h, b) = inspecting.remove(id) ?: return cdp.continueRequest(id, sid)
                val body = runCatching {
                    val r = cdp.call("Fetch.getResponseBody", buildJsonObject { put("requestId", id) }, sid)
                    val raw = r.str("body").orEmpty()
                    if (r["base64Encoded"]?.jsonPrimitive?.contentOrNull == "true") String(java.util.Base64.getDecoder().decode(raw)) else raw
                }.getOrNull()
                val headers = p["responseHeaders"]?.jsonArray.orEmpty().associate { it.jsonObject.str("name").orEmpty() to it.jsonObject.str("value").orEmpty() }
                runCatching { inspector?.inspect(u, h, b, headers, body) }.onFailure { Log.w("WebLogin", "inspect: ${it.message}") }
                return cdp.continueRequest(id, sid)
            }
            val mainFrame = p.str("resourceType") == "Document" && p.str("frameId") == pages[sid]
            if (mainFrame && rejected.containsMatchIn(url)) result.complete(Outcome.REJECTED)
            if (mainFrame) {
                Log.i("WebLogin", "page ${url.substringBefore('#').substringBefore('?')}${if ('#' in url) " #…" else ""}")   // без токенов в логе
                seen[pages[sid]!!] = url
                when (policy(url)) {
                    UrlLoadPolicy.ALLOW_LOAD -> {}
                    UrlLoadPolicy.DISCARD_LOAD -> { cdp.call("Fetch.failRequest", fail(), sid); if (!result.isCompleted) restart(sid); return }
                    UrlLoadPolicy.ALLOW_LOAD_AND_FINISH -> { cdp.call("Fetch.failRequest", fail(), sid); finish(); return }
                }
            } else if (inspector != null && p.str("resourceType") != "Document" &&
                runCatching { resolver.resolveResourceLoadPolicy(url) }.getOrNull() == UrlLoadPolicy.DISCARD_LOAD) {
                cdp.call("Fetch.failRequest", fail("BlockedByClient"), sid); return
            }
            val headers = req["headers"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
            val body = req.str("postData")
            val inspect = inspector != null && runCatching { inspector.shouldInspect(url, headers, body) }.getOrDefault(false)
            if (inspect) inspecting[id] = Triple(url, headers, body)
            cdp.continueRequest(id, sid, inspect)
        }

        cdp.onEvent = { method, p, _ ->
            when (method) {
                "Target.attachedToTarget" -> {
                    val sid = p.str("sessionId")!!
                    val info = p["targetInfo"]!!.jsonObject
                    runCatching {
                        if (info.str("type") == "page") {
                            pages[sid] = info.str("targetId")!!
                            userAgent?.let { ua -> cdp.call("Emulation.setUserAgentOverride", buildJsonObject { put("userAgent", ua) }, sid) }
                            if (!enableJS) cdp.call("Emulation.setScriptExecutionDisabled", buildJsonObject { put("value", true) }, sid)
                            // без инспектора ловим только страницы; с ним — все запросы (плагин читает ответы сайта)
                            cdp.call("Fetch.enable", buildJsonObject {
                                put("patterns", buildJsonArray {
                                    add(buildJsonObject { put("urlPattern", "*"); if (inspector == null) put("resourceType", "Document") })
                                })
                            }, sid)
                            main.complete(sid)
                        }
                    }.onFailure { Log.w("WebLogin", "attach: ${it.message}") }
                    runCatching { cdp.call("Runtime.runIfWaitingForDebugger", session = sid) }
                }
                "Target.detachedFromTarget" -> {
                    // закрыли последнее окно — вход отменён (на маке Chromium при этом сам не выходит)
                    if (pages.remove(p.str("sessionId")) != null && pages.isEmpty()) result.complete(Outcome.CLOSED)
                }
                "Target.targetInfoChanged" -> {
                    // одностраничники меняют адрес без перехода — тоже спрашиваем плагин
                    val info = p["targetInfo"]!!.jsonObject
                    val target = info.str("targetId")
                    val url = info.str("url").orEmpty()
                    val sid = pages.entries.firstOrNull { it.value == target }?.key
                    if (rejected.containsMatchIn(url)) result.complete(Outcome.REJECTED)     // отказ Google показывает без перехода
                    if (info.str("type") == "page" && sid != null && url.startsWith("http") && seen.put(target!!, url) != url) {
                        when (policy(url)) {
                            UrlLoadPolicy.ALLOW_LOAD -> {}
                            UrlLoadPolicy.DISCARD_LOAD -> if (!result.isCompleted) restart(sid)
                            UrlLoadPolicy.ALLOW_LOAD_AND_FINISH -> finish()
                        }
                    }
                }
                "Fetch.requestPaused" -> {
                    val sid = p.str("sessionId") ?: ""
                    val rid = p.str("requestId")
                    // запрос нельзя оставить на паузе: что бы ни случилось — отпускаем, иначе окно зависнет
                    runCatching { paused(p, sid) }.onFailure { e ->
                        Log.w("WebLogin", "request: ${e.message}")
                        rid?.let { runCatching { cdp.continueRequest(it, sid) } }
                    }
                }
            }
        }
        cdp.closed.invokeOnCompletion { result.complete(Outcome.CLOSED) }
        process?.onExit()?.thenRun { result.complete(Outcome.CLOSED) }

        cdp.call("Target.setDiscoverTargets", buildJsonObject { put("discover", true) })
        cdp.call("Target.setAutoAttach", buildJsonObject { put("autoAttach", true); put("waitForDebuggerOnStart", true); put("flatten", true) })
        // окно открылось пустым: перехват уже включён, теперь — страница сервиса (вход в профиле мог сохраниться — редирект поймаем)
        restart(withTimeout(15_000) { main.await() })

        finishWhen?.let { probe ->
            scope.launch {
                while (!result.isCompleted) {
                    delay(700)
                    runCatching { cookies = allCookies(cdp) }
                    if (runCatching(probe).getOrDefault(false)) { Log.i("WebLogin", "finished (условие входа)"); finish() }
                }
            }
        }
        return result.await()
    }

    /** Куки названных доменов — в сетевой слой Melos (плагинам, что входят по кукам). */
    private suspend fun syncCookies(cdp: Cdp, domains: List<String>) {
        cookies = allCookies(cdp)
        for (c in cookies) {
            val d = c.str("domain") ?: continue
            if (domains.none { matches(d, it) }) continue
            HttpNetwork.addCookie(HttpCookie(c.str("name"), c.str("value")).apply {
                domain = d; path = c.str("path") ?: "/"; secure = c.str("secure") == "true"
            })
        }
    }

    private suspend fun allCookies(cdp: Cdp) = cdp.call("Storage.getCookies")["cookies"]?.jsonArray.orEmpty().map { it.jsonObject }

    private fun matches(cookieDomain: String, domain: String): Boolean {
        val d = cookieDomain.trimStart('.').lowercase()
        return domain == d || domain.endsWith(".$d") || d.endsWith(".$domain")
    }


    private suspend fun launch(bin: File): Cdp = withContext(Dispatchers.IO) {
        val port = File(profile.apply { mkdirs() }, "DevToolsActivePort").apply { delete() }
        val p = start(bin, debug = true, url = "about:blank")
        val until = System.currentTimeMillis() + 20_000
        while (!port.exists() || port.readLines().size < 2) {
            check(p.isAlive) { "Chromium закрылся при запуске (код ${p.exitValue()})" }
            check(System.currentTimeMillis() < until) { "Chromium не ответил" }
            delay(100)
        }
        val (num, path) = port.readLines()
        Cdp.connect(http, "ws://127.0.0.1:$num$path")
    }

    private fun start(bin: File, debug: Boolean, url: String): Process {
        shutdown()                                              // прошлое окно входа (тот же профиль второй раз не запустится)
        process?.waitFor(3, TimeUnit.SECONDS)
        val screen = runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()
        val args = buildList {
            add(bin.path)
            add("--user-data-dir=${profile.path}")
            if (debug) add("--remote-debugging-port=0")         // порт Chromium выберет сам и запишет в DevToolsActivePort
            add("--no-first-run"); add("--no-default-browser-check"); add("--disable-default-apps")
            add("--window-size=520,760")
            screen?.let { add("--window-position=${it.x + (it.width - 520) / 2},${it.y + (it.height - 760) / 2}") }
            if (Paths.isMac) add("--use-mock-keychain")         // без вопроса про связку ключей
            add("--disable-session-crashed-bubble"); add("--hide-crash-restore-bubble")   // без «Восстановить страницы?»
            add("--app=$url")                                   // окно без вкладок и адресной строки
        }
        Log.i("Chromium", "запуск ${bin.name}${if (debug) "" else " (без отладки)"}")
        return ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            // у сборок Chromium нет ключей Google API — без этого он показывает об этом плашку (вход на сайты работает и так)
            .apply { environment().putAll(mapOf("GOOGLE_API_KEY" to "no", "GOOGLE_DEFAULT_CLIENT_ID" to "no", "GOOGLE_DEFAULT_CLIENT_SECRET" to "no")) }
            .start().also { process = it }
    }

    /**
     * Обычное окно Chrome без отладки — туда Google пускает. Конец входа видно по истории профиля (Chrome пишет её раз
     * в ~10 с, вместе с #…): плагин узнал адрес — окно закрываем. На Windows без sqlite3 ждём, пока окно закроют.
     */
    private suspend fun plain(bin: File, startUrl: String, resolver: WebViewProvider.UrlLoadPolicyResolver): Boolean = withContext(Dispatchers.IO) {
        val p = start(bin, debug = false, url = startUrl)
        val since = (System.currentTimeMillis() + 11_644_473_600_000L) * 1000     // время Chrome — микросекунды с 1601 года
        val sqlite = listOf("/usr/bin/sqlite3", "/opt/homebrew/bin/sqlite3", "/usr/local/bin/sqlite3").map(::File).firstOrNull { it.canExecute() }
        val history = File(profile, "Default/History")
        val copy = File.createTempFile("melos-history", ".db")
        val checked = HashSet<String>()
        val until = System.currentTimeMillis() + 15 * 60_000
        try {
            while (p.isAlive && System.currentTimeMillis() < until) {
                delay(1500)
                if (sqlite == null || !history.exists()) continue
                val urls = runCatching {
                    history.copyTo(copy, overwrite = true)               // файл занят Chrome — читаем копию
                    val q = ProcessBuilder(sqlite.path, copy.path,
                        "select url from urls where last_visit_time > $since order by last_visit_time").start()
                    q.inputStream.bufferedReader().readLines().also { q.waitFor(5, TimeUnit.SECONDS) }
                }.getOrDefault(emptyList())
                val done = urls.filter { checked.add(it) }.any { url ->
                    runCatching { resolver.resolveUrlLoadPolicy(url) }.getOrNull().let { it == UrlLoadPolicy.ALLOW_LOAD_AND_FINISH || it == UrlLoadPolicy.DISCARD_LOAD }
                }
                if (done) { Log.i("WebLogin", "вход в обычном окне завершён"); return@withContext true }
            }
            !p.isAlive                                                    // окно закрыли сами — проверит окно под управлением
        } finally {
            copy.delete(); shutdown(); p.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private suspend fun close(cdp: Cdp) {
        runCatching { withTimeout(3000) { cdp.call("Browser.close") } }
        cdp.ws.abort()
        withContext(Dispatchers.IO) { process?.let { if (!it.waitFor(5, TimeUnit.SECONDS)) shutdown() } }
    }

    /** Свой Chromium (при первом входе — скачиваем); не скачался — установленный браузер на Chromium. */
    private suspend fun binary(): File? = installLock.withLock {
        ownBinary() ?: runCatching { download() }.onFailure {
            Log.e("Chromium", "загрузка", it); Toasts.error("Не удалось скачать Chromium для входа: ${it.message}")
        }.getOrNull() ?: installed()
    }

    private fun ownBinary(): File? {
        val names = setOf("Chromium", "chrome.exe", "chrome")               // Chrome for Testing из прошлых версий Melos не берём — скачаем Chromium
        return own.takeIf { it.isDirectory }?.walkTopDown()?.maxDepth(6)
            ?.firstOrNull { it.isFile && it.name in names && it.canExecute() && (!Paths.isMac || it.parentFile.name == "MacOS") }
    }

    private fun installed(): File? {
        val home = System.getProperty("user.home")
        val candidates = when {
            Paths.isMac -> listOf("Google Chrome", "Chromium", "Microsoft Edge", "Brave Browser").flatMap { app ->
                listOf("/Applications", "$home/Applications").map { File("$it/$app.app/Contents/MacOS/$app") }
            }
            Paths.isWindows -> listOfNotNull(System.getenv("PROGRAMFILES"), System.getenv("PROGRAMFILES(X86)"), System.getenv("LOCALAPPDATA")).flatMap {
                listOf(File(it, "Google/Chrome/Application/chrome.exe"), File(it, "Microsoft/Edge/Application/msedge.exe"),
                    File(it, "Chromium/Application/chrome.exe"), File(it, "BraveSoftware/Brave-Browser/Application/brave.exe"))
            }
            else -> listOf("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge", "brave-browser")
                .flatMap { listOf(File("/usr/bin/$it"), File("/snap/bin/$it")) }
        }
        return candidates.firstOrNull { it.isFile && it.canExecute() }
    }

    /**
     * Официальная сборка Chromium из хранилища снимков проекта (chromium-browser-snapshots) — в папку данных Melos, один раз.
     * Берём снимок у позиции текущего стабильного релиза (по chromiumdash), а не самый свежий ночной: тот бывает сырым.
     */
    private suspend fun download(): File = withContext(Dispatchers.IO) {
        val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
        val (dir, file, dash) = when {
            Paths.isMac -> Triple(if (arm) "Mac_Arm" else "Mac", "chrome-mac.zip", "Mac")
            Paths.isWindows -> Triple("Win_x64", "chrome-win.zip", "Windows")
            else -> Triple("Linux_x64", "chrome-linux.zip", "Linux")
        }
        val store = "https://commondatastorage.googleapis.com/chromium-browser-snapshots"
        fun get(url: String) = http.send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofString()).body()
        fun exists(url: String) = http.send(HttpRequest.newBuilder(URI(url)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.discarding()).statusCode() == 200
        val stable = runCatching {
            val pos = json.parseToJsonElement(get("https://chromiumdash.appspot.com/fetch_releases?channel=Stable&platform=$dash&num=1"))
                .jsonArray.first().jsonObject.str("chromium_main_branch_position")!!
            // снимки есть не у каждой позиции — первый не раньше нужной
            json.parseToJsonElement(get("https://www.googleapis.com/storage/v1/b/chromium-browser-snapshots/o?prefix=$dir/&delimiter=/&startOffset=$dir/$pos&maxResults=1&fields=prefixes"))
                .jsonObject["prefixes"]!!.jsonArray.first().jsonPrimitive.content.removePrefix("$dir/").trimEnd('/')
        }.getOrNull()?.takeIf { exists("$store/$dir/$it/$file") }
        val rev = stable ?: get("$store/$dir/LAST_CHANGE").trim()
        val url = "$store/$dir/$rev/$file"
        // качаем и распаковываем рядом, на место — только целиком: оборванная загрузка не оставит полу-Chromium
        val tmp = File(Paths.home, "chromium.download").apply { deleteRecursively(); mkdirs() }
        val zip = File(tmp, "chrome.zip")
        val resp = http.send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofInputStream())
        check(resp.statusCode() == 200) { "HTTP ${resp.statusCode()}" }
        val total = resp.headers().firstValueAsLong("content-length").orElse(-1)
        var got = 0L
        try {
            downloadProgress.value = 0f
            resp.body().use { input -> zip.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); got += n
                    if (total > 0) downloadProgress.value = got.toFloat() / total
                }
            } }
        } finally { downloadProgress.value = null }
        check(total <= 0 || got == total) { "загрузка оборвалась (${got / 1_048_576} из ${total / 1_048_576} МБ)" }
        // на маке и в Linux — системной распаковкой: внутри приложения символьные ссылки и права на запуск
        val unpack = when { Paths.isMac -> listOf("ditto", "-x", "-k", zip.path, tmp.path); Paths.isWindows -> null; else -> listOf("unzip", "-q", "-o", zip.path, "-d", tmp.path) }
        if (unpack != null) check(ProcessBuilder(unpack).redirectErrorStream(true).start().waitFor() == 0) { "не распаковался" }
        else java.util.zip.ZipFile(zip).use { z -> z.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
            val f = File(tmp, e.name).apply { parentFile.mkdirs() }
            check(f.canonicalPath.startsWith(tmp.canonicalPath)) { "плохой архив" }
            z.getInputStream(e).use { i -> f.outputStream().use { i.copyTo(it) } }
        } }
        zip.delete()
        own.deleteRecursively()
        check(tmp.renameTo(own)) { "не удалось положить Chromium в ${own.path}" }
        Log.i("Chromium", "скачан: $url")
        ownBinary() ?: error("в архиве нет Chromium")
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull


    private class Cdp : WebSocket.Listener {
        lateinit var ws: WebSocket
        var onEvent: suspend (method: String, params: JsonObject, session: String?) -> Unit = { _, _, _ -> }
        val closed = CompletableDeferred<Unit>()
        private val ids = AtomicInteger()
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
        private val sendLock = Mutex()
        private val buf = StringBuilder()

        companion object {
            suspend fun connect(http: HttpClient, url: String): Cdp {
                val cdp = Cdp()
                cdp.ws = http.newWebSocketBuilder().buildAsync(URI(url), cdp).await()
                return cdp
            }
        }

        suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), session: String? = null): JsonObject {
            val id = ids.incrementAndGet()
            val reply = CompletableDeferred<JsonObject>().also { pending[id] = it }
            val msg = buildJsonObject {
                put("id", id); put("method", method); put("params", params)
                session?.let { put("sessionId", it) }
            }
            sendLock.withLock { ws.sendText(msg.toString(), true).await() }
            val r = try { withTimeout(15_000) { reply.await() } } finally { pending.remove(id) }
            (r["error"] as? JsonObject)?.let { error("$method: ${(it["message"] as? JsonPrimitive)?.contentOrNull}") }
            return r["result"] as? JsonObject ?: JsonObject(emptyMap())
        }

        suspend fun continueRequest(id: String, session: String, interceptResponse: Boolean = false) {
            call("Fetch.continueRequest", buildJsonObject { put("requestId", id); if (interceptResponse) put("interceptResponse", true) }, session)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buf.append(data)
            if (last) {
                val text = buf.toString(); buf.setLength(0)
                runCatching { handle(Json.parseToJsonElement(text).jsonObject) }
            }
            webSocket.request(1)
            return null
        }

        private fun handle(m: JsonObject) {
            val id = (m["id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            if (id != null) { pending[id]?.complete(m); return }
            val method = (m["method"] as? JsonPrimitive)?.contentOrNull ?: return
            val params = m["params"] as? JsonObject ?: JsonObject(emptyMap())
            val session = (m["sessionId"] as? JsonPrimitive)?.contentOrNull
            // запрос на паузе несёт свою сессию в параметрах — так обработчику проще
            val p = if (session != null && method.startsWith("Fetch.")) JsonObject(params + ("sessionId" to JsonPrimitive(session))) else params
            scope.launch { runCatching { onEvent(method, p, session) }.onFailure { Log.w("WebLogin", "$method: ${it.message}") } }
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>? { closed.complete(Unit); return null }
        override fun onError(webSocket: WebSocket, error: Throwable) { closed.complete(Unit) }
    }
}
