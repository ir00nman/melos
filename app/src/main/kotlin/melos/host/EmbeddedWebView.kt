package melos.host

import com.badmanners.murglar.lib.core.webview.WebViewProvider
import com.badmanners.murglar.lib.core.webview.WebViewProvider.UrlLoadPolicy
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import kotlinx.coroutines.CompletableDeferred
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import javax.swing.JFrame
import javax.swing.SwingUtilities

// Окно входа, как в Murglar: страница сервиса во встроенном WebView (JavaFX). Пользователь входит сам,
// а плагин по адресам страниц решает, когда вход завершён (UrlLoadPolicyResolver), — тогда окно закрывается.
// Если плагину нужны куки, переносим куки только тех доменов, что он назвал (domainsForCookiesSync).
object EmbeddedWebView : WebViewProvider {
    private const val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"

    // у WebView своё хранилище кук, отдельное от сетевого слоя Melos
    private val webCookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    /** Кука из хранилища окна входа (после входа сайт сам её выставил). */
    fun webCookie(domain: String, name: String): String? = Chromium.cookie(domain, name) ?: webCookies.cookieStore.cookies.firstOrNull { c ->
        val d = c.domain?.trimStart('.')?.lowercase() ?: return@firstOrNull false
        c.name == name && (domain == d || domain.endsWith(".$d") || d.endsWith(".$domain"))
    }?.value?.takeIf { it.isNotBlank() }

    /** Для сайтов-одностраничников: окно закрывается, как только условие выполнилось (например, появилась кука входа). */
    @Volatile var finishWhen: (() -> Boolean)? = null

    override suspend fun startWebView(
        enableJS: Boolean,
        userAgent: String?,
        startUrl: String,
        domainsForCookiesSync: List<String>,
        resolver: WebViewProvider.UrlLoadPolicyResolver,
        inspector: WebViewProvider.RequestsInspector?,
    ): Boolean {
        // как в Murglar — на Chromium; его нет и не скачался — старое окно на WebKit
        if (System.getenv("MELOS_NO_CHROMIUM") != "1") {
            runCatching { Chromium.open(enableJS, userAgent, startUrl, domainsForCookiesSync, resolver, inspector, finishWhen) }
                .onSuccess { ok -> if (ok != null) return ok }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.e("WebLogin", "Chromium", it); Toasts.error("Chromium не запустился: ${it.message} — открываю простое окно входа") }
        }
        val result = CompletableDeferred<Boolean>()
        SwingUtilities.invokeLater {
            val frame = JFrame("Вход").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                size = Dimension(520, 760)
                setLocationRelativeTo(null)
                layout = BorderLayout()
            }
            val panel = JFXPanel()                              // поднимает JavaFX внутри Swing
            frame.add(panel, BorderLayout.CENTER)
            frame.addWindowListener(object : WindowAdapter() {
                override fun windowClosed(e: WindowEvent) { result.complete(false) }
            })
            frame.isVisible = true
            Platform.setImplicitExit(false)
            Platform.runLater {
                CookieHandler.setDefault(webCookies)
                val view = WebView()
                val engine = view.engine
                engine.isJavaScriptEnabled = enableJS
                // ошибки страницы — в лог (без содержимого форм): видно, почему сайт не показал вход
                com.sun.javafx.webkit.WebConsoleListener.setDefaultListener { _, message, line, source ->
                    Log.w("WebLogin", "js: ${message?.take(200)} (${source?.substringBefore('?')?.takeLast(80)}:$line)")
                }
                engine.loadWorker.exceptionProperty().addListener { _, _, ex -> if (ex != null) Log.w("WebLogin", "load: ${ex.message}") }
                System.getenv("MELOS_WEBCLICK")?.let { label -> javax.swing.Timer(7000) {    // отладка: нажать кнопку по тексту
                    Platform.runLater { runCatching { engine.executeScript("[...document.querySelectorAll('button')].find(b=>b.innerText.includes('$label'))?.click()") } }
                }.apply { isRepeats = false; start() } }
                if (System.getenv("MELOS_WEBDEBUG") == "1") javax.swing.Timer(8000) {     // отладка: что на странице (фреймы, формы)
                    Platform.runLater { runCatching {
                        Log.i("WebLogin", "dom: " + engine.executeScript("""JSON.stringify({inputs:[...document.querySelectorAll('input')].map(i=>i.type+':'+i.name).slice(0,10),buttons:[...document.querySelectorAll('button')].map(b=>b.innerText.trim()).filter(t=>t).slice(0,10),title:document.title})"""))
                    }.onFailure { Log.w("WebLogin", "dom: ${it.message}") } }
                }.apply { isRepeats = false; start() }
                engine.userAgent = userAgent ?: UA
                // вход через Google/Facebook/Apple и другие шаги, которые сайт открывает в новом окне, — отдельным окошком:
                // страница входа остаётся на месте и получает ответ провайдера (раньше окно затиралось — белый экран)
                engine.setCreatePopupHandler { popup(frame) }
                engine.locationProperty().addListener { _, _, url ->
                    if (url.isNullOrEmpty() || result.isCompleted) return@addListener
                    Log.i("WebLogin", "page ${url.substringBefore('#').substringBefore('?')}${if ('#' in url) " #…" else ""}")   // без токенов в логе
                    when (runCatching { resolver.resolveUrlLoadPolicy(url) }.getOrDefault(UrlLoadPolicy.ALLOW_LOAD)) {
                        UrlLoadPolicy.ALLOW_LOAD -> {}
                        UrlLoadPolicy.DISCARD_LOAD -> Platform.runLater { engine.load(startUrl) }
                        UrlLoadPolicy.ALLOW_LOAD_AND_FINISH -> {
                            Log.i("WebLogin", "finished")
                            syncCookies(domainsForCookiesSync)
                            result.complete(true)
                            SwingUtilities.invokeLater { frame.dispose() }
                        }
                    }
                }
                panel.scene = Scene(view)
                engine.load(startUrl)
                // адрес у одностраничника после входа может не смениться — проверяем условие раз в полсекунды
                finishWhen?.let { probe ->
                    val timer = javax.swing.Timer(500, null)
                    timer.addActionListener {
                        if (result.isCompleted) { timer.stop(); return@addActionListener }
                        if (runCatching(probe).getOrDefault(false)) {
                            timer.stop(); Log.i("WebLogin", "finished (условие входа)")
                            syncCookies(domainsForCookiesSync); result.complete(true); frame.dispose()
                        }
                    }
                    timer.start()
                }
            }
        }
        return result.await()
    }

    /** Всплывающее окно сайта (window.open): свой WebView в своём окне; сайт закрыл его (window.close) — окно уходит. */
    private fun popup(owner: JFrame): javafx.scene.web.WebEngine {
        val view = WebView()
        val engine = view.engine
        engine.userAgent = UA
        engine.isJavaScriptEnabled = true
        engine.setCreatePopupHandler { popup(owner) }
        engine.locationProperty().addListener { _, _, url -> if (!url.isNullOrEmpty()) Log.i("WebLogin", "окно: ${url.substringBefore('#').substringBefore('?')}") }
        SwingUtilities.invokeLater {
            val win = JFrame("Вход").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                size = Dimension(520, 700)
                setLocation(owner.x + 40, owner.y + 40)
                layout = BorderLayout()
            }
            val panel = JFXPanel()
            win.add(panel, BorderLayout.CENTER)
            win.isVisible = true
            Platform.runLater {
                panel.scene = Scene(view)
                engine.setOnVisibilityChanged { e -> if (!e.data) SwingUtilities.invokeLater { win.dispose() } }   // window.close()
            }
            owner.addWindowListener(object : WindowAdapter() { override fun windowClosed(e: WindowEvent) { win.dispose() } })
        }
        return engine
    }

    private fun syncCookies(domains: List<String>) {
        if (domains.isEmpty()) return
        for (d in domains) webCookies.cookieStore.get(URI("https://$d/")).forEach { c ->
            if (c.domain.isNullOrEmpty()) c.domain = d                  // куки только для хоста — привязываем к нему
            HttpNetwork.addCookie(c)
        }
    }
}
