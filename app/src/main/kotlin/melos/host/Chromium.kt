package melos.host

import com.badmanners.murglar.lib.core.webview.WebViewProvider
import com.badmanners.murglar.lib.core.webview.WebViewProvider.UrlLoadPolicy
import dev.datlag.kcef.KCEF
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import melos.core.Paths
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefRendering
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.net.HttpCookie
import javax.swing.JFrame
import javax.swing.SwingUtilities

// Окно входа на Chromium (CEF через KCEF), как в Murglar: Google, Apple, Facebook и нынешние сайты (SoundCloud)
// не работают во встроенном WebKit из JavaFX. Chromium скачивается один раз, при первом входе, в папку данных Melos.
object Chromium {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir get() = File(Paths.home, "chromium")
    @Volatile private var ready: CompletableDeferred<Boolean>? = null

    /** Поднять Chromium (скачать при первом разе); false — не вышло, вход откроется в старом окне. */
    suspend fun ensure(): Boolean {
        val r = synchronized(this) {
            ready ?: CompletableDeferred<Boolean>().also { d ->
                ready = d
                scope.launch {
                    var shown = -1
                    runCatching {
                        KCEF.init(builder = {
                            installDir(dir)
                            progress {
                                onDownloading { p ->
                                    val step = (p / 10).toInt() * 10                // тост раз в 10 %
                                    if (step != shown) { shown = step; Toasts.shortNotify("Загружаю Chromium для входа: $step %") }
                                }
                                onInitialized { Log.i("Chromium", "готов") }
                            }
                            settings { cachePath = File(Paths.home, "chromium-cache").path }
                        }, onError = { e -> Log.e("Chromium", "init", e); d.complete(false) },
                            onRestartRequired = { Log.w("Chromium", "нужен перезапуск Melos"); Toasts.error("Chromium установлен — перезапустите Melos"); d.complete(false) })
                        d.complete(true)
                    }.onFailure { Log.e("Chromium", "init", it); d.complete(false) }
                }
            }
        }
        return r.await()
    }

    /** То же, что WebViewProvider.startWebView: страница сервиса, решение по адресам, перенос кук названных доменов. */
    suspend fun open(startUrl: String, domains: List<String>, resolver: WebViewProvider.UrlLoadPolicyResolver): Boolean {
        val client = KCEF.newClient()
        val result = CompletableDeferred<Boolean>()
        var frame: JFrame? = null

        fun finish() = scope.launch {
            syncCookies(domains)
            if (result.complete(true)) SwingUtilities.invokeLater { frame?.dispose() }
        }
        /** true — отменить загрузку этого адреса. */
        fun decide(browser: CefBrowser, url: String): Boolean {
            if (result.isCompleted) return true
            return when (runCatching { resolver.resolveUrlLoadPolicy(url) }.getOrDefault(UrlLoadPolicy.ALLOW_LOAD)) {
                UrlLoadPolicy.ALLOW_LOAD -> false
                UrlLoadPolicy.DISCARD_LOAD -> { browser.loadURL(startUrl); true }
                UrlLoadPolicy.ALLOW_LOAD_AND_FINISH -> { Log.i("WebLogin", "finished (Chromium)"); finish(); true }
            }
        }
        client.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(browser: CefBrowser, frame: CefFrame?, request: CefRequest, userGesture: Boolean, isRedirect: Boolean): Boolean {
                if (frame?.isMain == false) return false
                Log.i("WebLogin", "page ${request.url.substringBefore('#').substringBefore('?')}")       // без токенов в логе
                return decide(browser, request.url)
            }
        })
        // одностраничные сайты меняют адрес без перехода — тоже проверяем
        client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onAddressChange(browser: CefBrowser, frame: CefFrame?, url: String) { if (frame?.isMain != false) decide(browser, url) }
        })
        val browser = client.createBrowser(startUrl, CefRendering.DEFAULT, false)
        SwingUtilities.invokeLater {
            frame = JFrame("Вход").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                size = Dimension(520, 760)
                setLocationRelativeTo(null)
                layout = BorderLayout()
                add(browser.uiComponent, BorderLayout.CENTER)
                addWindowListener(object : WindowAdapter() { override fun windowClosed(e: WindowEvent) { result.complete(false) } })
                isVisible = true
            }
        }
        val ok = result.await()
        runCatching { browser.dispose(); client.dispose() }
        return ok
    }

    /** Куки названных доменов из Chromium — в сетевой слой Melos (плагинам, что входят по кукам). */
    private suspend fun syncCookies(domains: List<String>) {
        if (domains.isEmpty()) return
        val got = mutableListOf<CefCookie>()
        val done = CompletableDeferred<Unit>()
        val visited = CefCookieManager.getGlobalManager()?.visitAllCookies { c, count, total, _ ->
            synchronized(got) { got += c }
            if (count >= total - 1) done.complete(Unit)
            true
        } ?: false
        if (visited) withTimeoutOrNull(3000) { done.await() }
        synchronized(got) {
            for (c in got) {
                val d = c.domain.trimStart('.')
                if (domains.none { d == it || d.endsWith(".$it") || it.endsWith(".$d") }) continue
                HttpNetwork.addCookie(HttpCookie(c.name, c.value).apply { domain = c.domain; path = c.path ?: "/"; secure = c.secure })
            }
        }
    }

    fun shutdown() { if (ready?.isCompleted == true) runCatching { KCEF.disposeBlocking() } }
}
