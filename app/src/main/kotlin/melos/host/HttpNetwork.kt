package melos.host

import com.badmanners.murglar.lib.core.network.NetworkMiddleware
import com.badmanners.murglar.lib.core.network.NetworkRequest
import com.badmanners.murglar.lib.core.network.NetworkResponse
import com.badmanners.murglar.lib.core.network.ResponseConversionException
import com.badmanners.murglar.lib.core.network.ResponseConverter
import melos.core.Paths
import melos.core.json
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.ByteArrayInputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.time.Duration
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

// NetworkMiddleware на java.net.http: без OkHttp/Ktor, куки общие для всех плагинов и переживают перезапуск.
object HttpNetwork : NetworkMiddleware {
    const val DEFAULT_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    private val cookieFile = Paths.file("cookies.json")
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL).also { restore(it) }

    private fun client(follow: Boolean) = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(if (follow) HttpClient.Redirect.NORMAL else HttpClient.Redirect.NEVER)
        .cookieHandler(cookies)
        .build()

    private val following = client(true)
    private val direct = client(false)

    private val restricted = setOf("host", "connection", "content-length", "expect", "upgrade")

    override suspend fun <T> execute(request: NetworkRequest, converter: ResponseConverter<T>): NetworkResponse<T> {
        val (uri, body, contentType) = prepare(request)
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
        request.headers.forEach { (k, v) -> if (k.lowercase() !in restricted) builder.header(k, v) }
        if (request.headers.keys.none { it.equals("user-agent", true) })
            builder.header("User-Agent", request.userAgent?.takeIf { it.isNotEmpty() } ?: DEFAULT_UA)
        contentType?.let { builder.header("Content-Type", it) }
        builder.method(request.method.uppercase(),
            body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())

        val client = if (request.followRedirects) following else direct
        val response = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray()).await()
        persist()

        val headers = response.headers().map().flatMap { (k, vs) -> vs.map { k to it } }
        val status = response.statusCode()
        val result = if (converter.dropBody || response.body().isEmpty()) converter.emptyResult else {
            val raw = response.body()
            val stream = when (response.headers().firstValue("content-encoding").orElse("").lowercase()) {
                "gzip" -> GZIPInputStream(ByteArrayInputStream(raw))
                "deflate" -> InflaterInputStream(ByteArrayInputStream(raw))
                else -> ByteArrayInputStream(raw)
            }
            try {
                converter.convert(stream, charsetOf(response) ?: Charsets.UTF_8)
            } catch (e: Exception) {
                throw ResponseConversionException(request, e, headers, status, reason(status))
            }
        }
        return NetworkResponse(request, result, headers, status, reason(status))
    }

    private data class Prepared(val uri: URI, val body: ByteArray?, val contentType: String?)

    private fun prepare(request: NetworkRequest): Prepared {
        var url = request.url
        // user:password@host → Basic-авторизация (как в эталонном KtorNetworkMiddleware)
        val parsed = URI(url)
        var auth: String? = null
        parsed.rawUserInfo?.let {
            auth = it
            url = URI(parsed.scheme, null, parsed.host, parsed.port, parsed.rawPath?.let(::decodePath), parsed.rawQuery?.let(::decodePath), null).toASCIIString()
        }
        val method = request.method.uppercase()
        val params = request.parameters.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        var body: ByteArray? = null
        var type: String? = null
        when {
            request.hasBody -> {
                val ct = request.contentType!!
                body = request.body!!.toByteArray(ct.charset)
                type = "${ct.mimeType}; charset=${ct.charset.name()}"
                if (params.isNotEmpty()) url += (if ('?' in url) "&" else "?") + params
            }
            method == "GET" || method == "PUT" || method == "DELETE" || method == "HEAD" ->
                if (params.isNotEmpty()) url += (if ('?' in url) "&" else "?") + params
            else -> {
                body = params.toByteArray()
                type = "application/x-www-form-urlencoded"
            }
        }
        val uri = URI(url.replace(" ", "%20"))
        if (auth != null && request.headers.keys.none { it.equals("authorization", true) }) {
            val headers = request.headers + ("Authorization" to "Basic " + Base64.getEncoder().encodeToString(decodePath(auth!!).toByteArray()))
            return prepare(request.copy(url = uri.toString(), headers = headers))
        }
        return Prepared(uri, body, type)
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    private fun decodePath(s: String) = java.net.URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8)

    private fun charsetOf(r: HttpResponse<*>): Charset? = r.headers().firstValue("content-type").orElse(null)
        ?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("charset=", true) }
        ?.substringAfter('=')?.trim('"')?.let { runCatching { Charset.forName(it) }.getOrNull() }

    private fun reason(code: Int) = when (code) {
        in 200..299 -> "OK"; 301, 302, 303, 307, 308 -> "Redirect"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 429 -> "Too Many Requests"; in 500..599 -> "Server Error"; else -> "HTTP $code"
    }

    // ---- куки ----

    private fun matches(cookie: HttpCookie, domain: String): Boolean {
        val d = cookie.domain?.trimStart('.')?.lowercase() ?: return false
        val q = domain.trimStart('.').lowercase()
        return q == d || q.endsWith(".$d")
    }

    override fun getCookies(domain: String): List<HttpCookie> = cookies.cookieStore.cookies.filter { matches(it, domain) }
    override fun getCookie(domain: String, name: String) = getCookies(domain).firstOrNull { it.name == name }

    override fun addCookie(cookie: HttpCookie) {
        val host = cookie.domain.trimStart('.')
        if (cookie.path == null) cookie.path = "/"
        // HttpCookie из кода по умолчанию версии 1 — Java шлёт её как name="value"; сайты так куку не узнают
        cookie.version = 0
        cookies.cookieStore.add(URI("https://$host/"), cookie)
        persist()
    }

    override fun clearCookiesForDomains(vararg domains: String) {
        val store = cookies.cookieStore
        store.cookies.filter { c -> domains.any { matches(c, it) } }.forEach { c -> store.getURIs().forEach { u -> store.remove(u, c) }; store.remove(null, c) }
        persist()
    }

    override fun clearAllCookies() { cookies.cookieStore.removeAll(); persist() }

    @Serializable
    private data class StoredCookie(
        val name: String, val value: String, val domain: String?, val path: String?,
        val maxAge: Long, val secure: Boolean, val httpOnly: Boolean, val savedAt: Long
    )

    @Synchronized
    private fun persist() {
        val now = System.currentTimeMillis()
        val list = cookies.cookieStore.cookies.filter { !it.hasExpired() && it.domain != null }.map {
            StoredCookie(it.name, it.value, it.domain, it.path, it.maxAge, it.secure, it.isHttpOnly, now)
        }
        runCatching { cookieFile.writeText(json.encodeToString(ListSerializer(StoredCookie.serializer()), list)) }
    }

    private fun restore(manager: CookieManager) {
        val list = runCatching {
            json.decodeFromString(ListSerializer(StoredCookie.serializer()), cookieFile.readText())
        }.getOrElse { return }
        val now = System.currentTimeMillis()
        list.forEach { s ->
            val left = if (s.maxAge < 0) -1 else s.maxAge - (now - s.savedAt) / 1000
            if (s.maxAge >= 0 && left <= 0) return@forEach
            val c = HttpCookie(s.name, s.value).apply {
                domain = s.domain; path = s.path ?: "/"; maxAge = left; secure = s.secure; isHttpOnly = s.httpOnly; version = 0
            }
            manager.cookieStore.add(URI("https://${s.domain!!.trimStart('.')}/"), c)
        }
    }
}
