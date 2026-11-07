package melos.host

import com.badmanners.murglar.lib.core.webview.WebViewProvider

// Этап 1 Android: окна входа (WebView в диалоге) не готовы — общий код TokenLogin/VK знает только
// куки окна и условие завершения; отдаём куки из общего сетевого слоя, а сам веб-вход честно отказывает.
object EmbeddedWebView : WebViewProvider {
    @Volatile var finishWhen: (() -> Boolean)? = null

    fun webCookie(domain: String, name: String): String? = HttpNetwork.getCookie(domain, name)?.value

    override suspend fun startWebView(
        enableJS: Boolean,
        userAgent: String?,
        startUrl: String,
        domainsForCookiesSync: List<String>,
        resolver: WebViewProvider.UrlLoadPolicyResolver,
        inspector: WebViewProvider.RequestsInspector?,
    ): Boolean {
        Toasts.error("Вход через веб-окно на Android пока не готов — войдите токеном")
        return false
    }
}
