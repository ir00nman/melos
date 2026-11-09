package melos

import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.Modifier
import melos.ui.globalKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import melos.core.Paths
import melos.host.Log
import melos.host.Services
import melos.player.Player
import melos.ui.App
import melos.ui.Nav
import melos.ui.Screen
import java.awt.Dimension

fun main() {
    // Windows — DIRECT3D: OPENGL на старых драйверах Intel и в RDP дарит белое окно
    System.setProperty("skiko.renderApi", System.getProperty("skiko.renderApi")
        ?: when { Paths.isMac -> "METAL"; Paths.isWindows -> "DIRECT3D"; else -> "OPENGL" })
    melos.core.Browse.url = { url -> runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } }
    melos.core.Browse.dir = { dir -> runCatching { java.awt.Desktop.getDesktop().open(dir) } }
    melos.core.Browse.pickDir = { start, onPick ->
        val ch = javax.swing.JFileChooser(start).apply { fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY; dialogTitle = "Куда сохранить" }
        if (ch.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) onPick(ch.selectedFile)
    }
    Thread.setDefaultUncaughtExceptionHandler { t, e -> Log.e("Crash", "thread ${t.name}", e) }
    Services.load()
    // офлайн-Медиатека: собираем играемый трек из скачанного файла
    melos.library.Library.offlineTrackBuilder = { snap: melos.library.Snap ->
        runCatching {
            (Services.byId("local")?.murglar as? melos.builtin.local.LocalMurglar)?.let { m ->
                melos.download.DownloadIndex.get(snap.mediaId)?.let { e ->
                    val f = java.io.File(e.path)
                    m.track(melos.builtin.local.LocalMurglar.Entry(
                        f.path, f.lastModified(), snap.title, snap.artists.ifBlank { "Неизвестный исполнитель" },
                        snap.album, null, null, null, null, snap.durationMs, null, null))
                }
            }
        }.getOrNull()
    }
    Player.init()
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {   // старые загрузки (в подпапках тоже)
        runCatching {                                                               // помечаем «скачано» по совпадению с медиатекой
            delay(5000)
            val local = Services.byId("local")?.murglar as? melos.builtin.local.LocalMurglar ?: return@launch
            val byKey = local.entries().associateBy { melos.library.Library.songKey(it.artist, it.title) }
            (melos.library.Library.offlineSnaps.value + melos.library.Library.favorites.items.value).forEach { snap ->
                if (!melos.download.DownloadIndex.has(snap.mediaId)) byKey[melos.library.Library.songKey(snap.artists, snap.title)]?.let { e ->
                    melos.download.DownloadIndex.add(snap.mediaId, java.io.File(e.path), snap)
                }
            }
            melos.host.Log.i("DownloadIndex", "сидировано из локальных файлов: ${melos.download.DownloadIndex.all().size}")
        }
    }
    melos.library.ServiceLikes.start()
    melos.host.NetStatus.start()           // фоновое отслеживание сети
    melos.host.MacMediaHub.init()          // macOS: Now Playing + медиа-клавиши
    System.getenv("MELOS_VOLUME")?.toIntOrNull()?.let { Player.volume.value = it }
    System.getenv("MELOS_PLAY")?.let(::demoPlay)
    System.getenv("MELOS_WEBTEST")?.let { url ->               // отладка: ловит ли окно входа адрес с токеном
        Thread {
            Thread.sleep(2000)
            var got: String? = null
            val ok = kotlinx.coroutines.runBlocking {
                melos.host.EmbeddedWebView.startWebView(true, null, url, emptyList(), object : com.badmanners.murglar.lib.core.webview.WebViewProvider.UrlLoadPolicyResolver {
                    override fun resolveUrlLoadPolicy(url: String) = if ("access_token=" in url) { got = url.substringAfter("access_token=").substringBefore('&'); com.badmanners.murglar.lib.core.webview.WebViewProvider.UrlLoadPolicy.ALLOW_LOAD_AND_FINISH }
                        else com.badmanners.murglar.lib.core.webview.WebViewProvider.UrlLoadPolicy.ALLOW_LOAD
                    override fun resolveResourceLoadPolicy(url: String) = com.badmanners.murglar.lib.core.webview.WebViewProvider.UrlLoadPolicy.ALLOW_LOAD
                })
            }
            Log.i("WebTest", "finished=$ok token=$got")
        }.start()
    }
    Thread { runCatching { melos.host.Chromium.cleanup() } }.start()   // окна входа, оставшиеся от прошлого запуска
    System.getenv("MELOS_SC_CODE")?.let { code -> Thread {          // отладка: обмен кода SoundCloud (адрес возврата)
        Thread.sleep(3000)
        val r = Services.byId("sc")?.murglar?.loginResolver ?: return@Thread
        kotlinx.coroutines.runBlocking {
            runCatching { r.credentialsLogin("token", mapOf("token" to "https://soundcloud.com/signin/callback?code=$code&state=x")) }
                .onFailure { melos.host.Log.e("Login", "debug exchange", it) }
        }
    }.start() }
    System.getenv("MELOS_LOGIN")?.let { id ->                  // отладка: сразу открыть окно входа сервиса
        Thread {
            Thread.sleep(2500)
            val r = Services.byId(id)?.murglar?.loginResolver ?: return@Thread
            val want = System.getenv("MELOS_LOGIN_VARIANT")
            (r.webLoginVariants.firstOrNull { it.id == want } ?: r.webLoginVariants.firstOrNull())?.let { v ->
                // как кнопка в интерфейсе — из потока интерфейса (Swing)
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    runCatching { r.webLogin(v.id, melos.host.EmbeddedWebView) }.onFailure { melos.host.Log.e("Login", "debug ${v.id}", it) }
                }
            }
        }.start()
    }
    if (System.getenv("MELOS_AUDIOBENCH") == "1") Thread { Thread.sleep(1500); melos.player.AudioBench.run() }.start()
    System.getenv("MELOS_SEEK_TO")?.toLongOrNull()?.let { ms -> Thread {     // отладка: перемотать включённый трек
        for (i in 0 until 60) { if (melos.player.Player.status.value == melos.player.Status.PLAYING) break; Thread.sleep(500) }
        Thread.sleep(1500); melos.player.Player.seek(ms)
    }.start() }
    System.getenv("MELOS_WAVE")?.let { sid -> Thread {                // отладка: включить «Мою волну» сервиса и показать очередь
        Thread.sleep(3500)
        val r = Services.byId(sid)!!.murglar.nodeResolver
        val wave = r.getRootNodes(false).first { it.nodeType == com.badmanners.murglar.lib.core.model.node.NodeType.RADIO }
        val P = melos.player.Player
        P.shuffle.value = true                                          // как у пользователя: «перемешать» включено
        P.playRadio(wave as com.badmanners.murglar.lib.core.model.radio.BaseRadio)
        Thread.sleep(6000)
        val heard = mutableListOf<String>()
        repeat(10) {
            heard += "${P.index.value}:${P.current.value?.title}"
            P.next(); Thread.sleep(2500)
        }
        melos.host.Log.i("WaveTest", "сыграно по очереди: " + heard.joinToString(" | ") + " | в очереди всего ${P.queue.value.size}")
        val ids = heard.map { it.substringAfter(':') }
        melos.host.Log.i("WaveTest", "повторов: ${ids.size - ids.toSet().size}")
    }.start() }
    if (System.getenv("MELOS_SEEKTEST") == "1") Thread {         // отладка: перемотка до запуска трека
        val P = melos.player.Player
        for (i in 0 until 60) { if (P.status.value == melos.player.Status.PLAYING) break; Thread.sleep(500) }
        Thread.sleep(2000); P.stop(); Thread.sleep(1000)
        P.seek(60_000); melos.host.Log.i("SeekTest", "остановлен, перемотал: ${P.time.value} мс"); P.toggle()
        for (i in 0 until 40) { if (P.status.value == melos.player.Status.PLAYING) break; Thread.sleep(250) }
        Thread.sleep(2000); melos.host.Log.i("SeekTest", "после «Плей»: ${P.time.value} мс")
    }.start()
    if (System.getenv("MELOS_EQTIMELINE") == "1") Thread { Thread.sleep(3000); melos.player.EqProbe.timeline() }.start()
    if (System.getenv("MELOS_EQPROBE") == "1") Thread { Thread.sleep(3000); melos.player.EqProbe.run() }.start()
    if (System.getenv("MELOS_EQTEST") == "1") Thread {             // отладка: меняем эквалайзер и смотрим статусы
        Thread.sleep(7000)
        Player.eqOn.value = true
        repeat(15) { k -> Player.eqPreset.value = "custom"; Player.eqAmps.value = List(10) { i -> if (i == k % 10) 6f else 0f }; Thread.sleep(200) }
        Player.eqAmps.value = List(10) { 0f }
    }.start()
    when (val open = System.getenv("MELOS_OPEN")) {          // отладка: сразу на нужный экран
        null -> {}
        "settings" -> Thread { Thread.sleep(3000); Nav.sheet = melos.ui.Sheet.SETTINGS }.start()
        "eq" -> Thread { Thread.sleep(3000); Nav.sheet = melos.ui.Sheet.EQ }.start()
        "lyrics" -> Nav.side = melos.ui.Side.LYRICS
        "queue" -> Nav.side = melos.ui.Side.QUEUE
        "library" -> Nav.go(melos.ui.Screen.Favorites)
        "login:sc", "login:ynd", "login:vk" -> Thread { Thread.sleep(3000); Nav.loginFor = open.removePrefix("login:"); Nav.sheet = melos.ui.Sheet.SETTINGS }.start()
        else -> if (open.startsWith("search:")) Nav.go(Screen.Search(open.removePrefix("search:")))
            else if (open.startsWith("ssearch:")) open.removePrefix("ssearch:").split(':', limit = 2).let { (sid, q) -> Nav.go(Screen.Search(q, sid)) }
            else if (open.startsWith("track:")) Thread {               // track:<сервис>:<запрос> — страница первого найденного трека
                Thread.sleep(2500)
                val (sid, q) = open.removePrefix("track:").split(':', limit = 2)
                val r = Services.byId(sid)?.murglar?.nodeResolver ?: return@Thread
                kotlinx.coroutines.runBlocking {
                    runCatching {
                        val root = r.getRootNodes(true).first { it.nodeParameters.isSearchable }
                        val node = r.specifySearchableNode(root, q)
                        r.getNodeContent(node.nodePath, 0).filterIsInstance<com.badmanners.murglar.lib.core.model.track.BaseTrack>().first()
                    }.onSuccess { Nav.go(Screen.Track(it)) }.onFailure { melos.host.Log.e("Debug", "track", it) }
                }
            }.start()
            else if (open.startsWith("root:")) Thread {
                Thread.sleep(1500)
                val (sid, rid) = open.removePrefix("root:").split('/')
                Services.byId(sid)?.murglar?.nodeResolver?.getRootNodes(false)?.firstOrNull { it.nodeId == rid }?.let { Nav.go(Screen.Open(it)) }
            }.start()
    }
    application {
        // отладка скриншотов узкого окна: MELOS_SIZE=980x640
        val winSize = System.getenv("MELOS_SIZE")?.split('x')?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == 2 && it.all { n -> n >= 200 } }?.let { DpSize(it[0].dp, it[1].dp) } ?: DpSize(1280.dp, 820.dp)
        val state = rememberWindowState(size = winSize)
        Window(
            onCloseRequest = { Player.shutdown(); melos.host.Chromium.shutdown(); exitApplication() },
            state = state,
            title = "Melos",
            onPreviewKeyEvent = ::globalKey,
        ) {
            val mac = Paths.isMac
            LaunchedOnce {
                window.minimumSize = Dimension(980, 640)
                if (!mac) runCatching {                                 // иконка окна и таскбара (на маке — из бандла)
                    val icon = java.awt.Toolkit.getDefaultToolkit().getImage(javaClass.getResource("/melos/icon.png"))
                    java.awt.Taskbar.getTaskbar().setIconImage(icon)     // таскбар Windows/Linux
                    window.iconImage = icon
                }.onFailure { Log.w("Window", "иконка: ${it.message}") }
                if (mac) {
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                }
            }
            Box(Modifier.fillMaxSize()) {
                // сверху полоса со стрелками «назад / вперёд»; на маке она же — за кнопками окна, на Windows — под заголовком
                App(topInset = 22)
                // за эту полосу окно таскается; слева — кнопки окна и стрелки, их не перекрываем
                if (mac) WindowDraggableArea(Modifier.padding(start = 150.dp).fillMaxWidth().height(28.dp)) { Box(Modifier.fillMaxSize()) }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun LaunchedOnce(block: () -> Unit) = androidx.compose.runtime.LaunchedEffect(Unit) { block() }

// Для отладки: MELOS_PLAY="sc:запрос" — найти и сразу включить первый результат.
private fun demoPlay(spec: String) = Thread {
    runCatching {
        Thread.sleep(2500)
        val (service, q) = spec.substringBefore(':') to spec.substringAfter(':')
        val r = Services.byId(service)!!.murglar.nodeResolver
        val root = r.getRootNodes(false).first { it.nodeParameters.isSearchable }
        val tracks = kotlinx.coroutines.runBlocking { r.getNodeContent(r.specifySearchableNode(root, q).nodePath, 0) }
            .filterIsInstance<com.badmanners.murglar.lib.core.model.track.BaseTrack>()
        Player.playList(tracks, 0, "Поиск: $q")
    }.onFailure { Log.e("Demo", "demo", it) }
}.start()
