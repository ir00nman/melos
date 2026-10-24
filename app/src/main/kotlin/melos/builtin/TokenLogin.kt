package melos.builtin

import com.badmanners.murglar.lib.core.login.CredentialLoginStep
import com.badmanners.murglar.lib.core.login.CredentialsLoginVariant
import com.badmanners.murglar.lib.core.login.LoginResolver
import com.badmanners.murglar.lib.core.login.SuccessfulLogin
import com.badmanners.murglar.lib.core.login.WebLoginVariant
import com.badmanners.murglar.lib.core.preference.PreferenceMiddleware
import com.badmanners.murglar.lib.core.webview.WebViewProvider

// Вход по токену и, если задан webUrl, — через веб, как в Murglar: страница входа сервиса во встроенном окне,
// токен забирается из адреса, на который сервис перенаправляет после входа (OAuth implicit: #access_token=…),
// или — для сайтов, что держат вход в куке (SoundCloud: oauth_token), — из куки, которую сайт выставил сам.
class TokenLoginResolver(
    private val preferences: PreferenceMiddleware,
    private val label: String,
    private val hint: String,
    private val webUrl: String? = null,
    private val webLabel: String = "Войти через веб",
    private val webLanding: Regex? = null,                                 // страницы «вы вошли» без токена
    private val webCookie: Pair<String, String>? = null,                  // (домен, имя) — токен лежит в куке сайта, а не в адресе
    private val webFlow: (suspend (WebViewProvider) -> String?)? = null,   // свой сценарий входа (OAuth с кодом и т. п.) → токен
    private val browserFlow: (suspend () -> Unit)? = null,                 // вход в обычном браузере (Google/Apple не пускают во встроенные окна)
    private val browserLabel: String = "Войти в браузере",
    private val readInput: (suspend (String) -> String)? = null,           // что вставили в поле (адрес из браузера, токен) → токен
    private val verify: suspend (token: String) -> Pair<String, String>,   // → (uid, имя)
) : LoginResolver {
    companion object {
        const val VARIANT = "token"
        const val BROWSER = "browser"
        const val TOKEN = "token"
        private const val K_TOKEN = "auth.token"
        private const val K_UID = "auth.uid"
        private const val K_NAME = "auth.name"
    }

    val token: String? get() = preferences.getString(K_TOKEN, null)
    val uid: String? get() = preferences.getString(K_UID, null)

    override val isLogged get() = !token.isNullOrEmpty()
    override val loginInfo get() = if (isLogged) "Вход выполнен: ${preferences.getString(K_NAME, "")}" else hint

    override val webLoginVariants = buildList {
        if (webUrl != null || webFlow != null) add(WebLoginVariant("web") { webLabel })
        if (browserFlow != null) add(WebLoginVariant(BROWSER) { browserLabel })
    }

    /** Сервис обновил токен сам (refresh) — сохраняем, вход остаётся. */
    fun updateToken(token: String) = preferences.setString(K_TOKEN, token)

    override suspend fun webLogin(loginVariantId: String, webViewProvider: WebViewProvider): Boolean {
        if (loginVariantId == BROWSER) { browserFlow?.invoke(); return false }   // дальше — вставить адрес из браузера в поле
        webFlow?.let { flow ->
            val t = flow(webViewProvider) ?: run { melos.host.Log.w("Login", "web login closed without token"); return false }
            credentialsLogin(VARIANT, mapOf(TOKEN to t))
            melos.host.Log.i("Login", "web login ok, logged=$isLogged")
            return true
        }
        val start = webUrl ?: return false
        var token: String? = null
        // вход по куке сайта (как в Murglar для SoundCloud): пользователь входит на самом сайте, сайт выставляет куку с токеном
        val cookie = webCookie
        val web = webViewProvider as? melos.host.EmbeddedWebView
        if (cookie != null) web?.finishWhen = { melos.host.EmbeddedWebView.webCookie(cookie.first, cookie.second)?.also { token = it } != null }
        val done = try { webViewProvider.startWebView(true, null, start, cookie?.let { listOf(it.first) } ?: emptyList(), object : WebViewProvider.UrlLoadPolicyResolver {
            override fun resolveUrlLoadPolicy(url: String): WebViewProvider.UrlLoadPolicy {
                if (cookie != null) {
                    token = melos.host.EmbeddedWebView.webCookie(cookie.first, cookie.second)
                    return if (token != null) WebViewProvider.UrlLoadPolicy.ALLOW_LOAD_AND_FINISH else WebViewProvider.UrlLoadPolicy.ALLOW_LOAD
                }
                // вход прошёл, но сайт оставил на странице профиля — возвращаемся к выдаче токена (startUrl)
                if (webLanding?.containsMatchIn(url) == true) return WebViewProvider.UrlLoadPolicy.DISCARD_LOAD
                val t = Regex("access_token=([^&#]+)").find(url)?.groupValues?.get(1) ?: return WebViewProvider.UrlLoadPolicy.ALLOW_LOAD
                token = t
                return WebViewProvider.UrlLoadPolicy.ALLOW_LOAD_AND_FINISH
            }
            override fun resolveResourceLoadPolicy(url: String) = WebViewProvider.UrlLoadPolicy.ALLOW_LOAD
        }) } finally { web?.finishWhen = null }
        val t = token ?: run { melos.host.Log.w("Login", "web login closed without token"); return false }
        if (!done) return false
        runCatching { credentialsLogin(VARIANT, mapOf(TOKEN to t)) }
            .onFailure { melos.host.Log.e("Login", "token check failed", it); throw it }
        melos.host.Log.i("Login", "web login ok, logged=$isLogged")
        return true
    }

    override val credentialsLoginVariants = listOf(
        CredentialsLoginVariant(VARIANT, { label }, listOf(CredentialsLoginVariant.Credential(TOKEN, { label }, secure = true)))
    )

    override suspend fun credentialsLogin(loginVariantId: String, args: Map<String, String>): CredentialLoginStep {
        val raw = args[TOKEN]?.trim().orEmpty()
        require(raw.isNotEmpty()) { "Пустой токен" }
        val token = readInput?.invoke(raw)
            ?: Regex("access_token=([^&]+)").find(raw)?.groupValues?.get(1) ?: raw.removePrefix("OAuth ").trim()
        val (uid, name) = verify(token)
        preferences.setString(K_TOKEN, token)
        preferences.setString(K_UID, uid)
        preferences.setString(K_NAME, name)
        return SuccessfulLogin
    }

    override fun logout() {
        preferences.remove(K_TOKEN); preferences.remove(K_UID); preferences.remove(K_NAME)
    }
}
