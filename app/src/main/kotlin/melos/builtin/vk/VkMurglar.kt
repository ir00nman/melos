package melos.builtin.vk

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.login.CaptchaRequiredStep
import com.badmanners.murglar.lib.core.login.CredentialLoginStep
import com.badmanners.murglar.lib.core.login.CredentialsLoginVariant
import com.badmanners.murglar.lib.core.login.LoginResolver
import com.badmanners.murglar.lib.core.login.SuccessfulLogin
import com.badmanners.murglar.lib.core.login.TwoFARequiredStep
import com.badmanners.murglar.lib.core.login.WebLoginVariant
import com.badmanners.murglar.lib.core.model.album.AlbumType
import com.badmanners.murglar.lib.core.model.track.source.Bitrate
import com.badmanners.murglar.lib.core.model.track.source.Container
import com.badmanners.murglar.lib.core.model.track.source.Extension
import com.badmanners.murglar.lib.core.model.track.source.QualityTier
import com.badmanners.murglar.lib.core.model.track.source.Source
import com.badmanners.murglar.lib.core.model.track.source.SourceFormat
import com.badmanners.murglar.lib.core.model.track.source.SourceFormat.SourceFormatAvailability.AVAILABLE
import com.badmanners.murglar.lib.core.network.NetworkMiddleware
import com.badmanners.murglar.lib.core.network.NetworkRequest
import com.badmanners.murglar.lib.core.network.ResponseConverters
import com.badmanners.murglar.lib.core.notification.NotificationMiddleware
import com.badmanners.murglar.lib.core.preference.PreferenceMiddleware
import com.badmanners.murglar.lib.core.service.BaseMurglar
import com.badmanners.murglar.lib.core.utils.MediaId
import com.badmanners.murglar.lib.core.webview.WebViewProvider
import melos.builtin.BuiltinMessages
import melos.builtin.FAlbum
import melos.builtin.FArtist
import melos.builtin.FPlaylist
import melos.builtin.FTrack
import melos.builtin.arr
import melos.builtin.builtinMessages
import melos.builtin.get
import melos.builtin.int
import melos.builtin.long
import melos.builtin.objects
import melos.builtin.source
import melos.builtin.str
import melos.core.json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.threeten.bp.Instant
import org.threeten.bp.ZoneId

// VK Музыка через api.vk.com. Два входа:
// • через веб — как сам vk.com: вход на сайте в окне Chromium, затем по кукам сессии берём токен веб-клиента
//   (login.vk.com/?act=web_token); он живёт недолго — обновляем так же, по кукам;
// • паролем — как официальное Android-приложение VK (его токену VK отдаёт аудио; через браузер такой токен не выдаётся).
class VkMurglar(
    id: String,
    preferences: PreferenceMiddleware,
    network: NetworkMiddleware,
    notifications: NotificationMiddleware,
    logger: LoggerMiddleware,
) : BaseMurglar<FTrack, BuiltinMessages>(id, MESSAGES, preferences, network, notifications, logger) {

    companion object {
        private val MESSAGES = builtinMessages("VK Music", "VK Музыка")
        private const val API = "https://api.vk.com/method/"
        private const val VERSION = "5.131"
        private const val WEB_VERSION = "5.289"                        // как у страницы vk.ru
        private const val CLIENT_ID = "2274003"                         // официальное приложение VK для Android
        private const val CLIENT_SECRET = "hHbZxrka2uZ6jB1inYsH"
        // токен привязан к клиенту: и вход, и все запросы — с заголовком его приложения
        const val UA = "VKAndroidApp/5.52-4543 (Android 5.1.1; SDK 22; x86_64; unknown Android SDK built for x86_64; en; 320x240)"
        private const val WEB_APP_ID = "6287487"                       // веб-клиент vk.com
        private val SESSION_COOKIES = listOf("remixnsid", "remixsid")  // появляются только после входа
        private val DOMAINS = listOf("vk.ru", "vk.com")                 // vk.com теперь переводит на vk.ru — куки сессии там
        const val PAGE = 50
    }

    override val loginResolver = VkLoginResolver()

    override val nodeResolver = VkNodeResolver(this, messages)

    override val possibleFormats = listOf(SourceFormat(Extension.MP3, Bitrate.B_320, QualityTier.HIGH_QUALITY, AVAILABLE))


    private suspend fun raw(url: String, params: Map<String, Any?>, method: String = "GET", web: Boolean = loginResolver.web): JsonElement {
        val req = NetworkRequest.Builder(url, method).apply {
            params.forEach { (k, v) -> if (v != null) addParameter(k, v) }
            // веб-токен — как у страницы vk.com, токен приложения — с заголовком приложения
            if (web) { val d = loginResolver.domain; addHeader("Origin", "https://$d"); addHeader("Referer", "https://$d/") } else addHeader("User-Agent", UA)
        }.build()
        // VK отвечает ошибкой с JSON и кодом 4xx — разбираем тело в любом случае
        return json.parseToJsonElement(network.execute(req, ResponseConverters.asString()).result)
    }

    suspend fun api(method: String, params: Map<String, Any?> = emptyMap(), retry: Boolean = true): JsonElement {
        if (loginResolver.web && loginResolver.expired) loginResolver.refreshWeb()
        val token = loginResolver.token ?: error("Нужен вход в VK")
        val web = loginResolver.web
        val host = if (web) "https://web.api.${loginResolver.domain}/method/" else API
        val r = raw(host + method, params + mapOf("access_token" to token, "v" to if (web) WEB_VERSION else VERSION, "lang" to "ru", "https" to 1), method = "POST")
        r["error"]?.let { e ->
            val code = e.int("error_code")
            // веб-токен истёк раньше срока — берём новый по кукам сайта и повторяем
            if (code == 5 && retry && loginResolver.web && runCatching { loginResolver.refreshWeb() }.isSuccess) return api(method, params, retry = false)
            if (code == 5) error("VK: вход устарел — войдите заново")
            error("VK: ${e.str("error_msg") ?: "ошибка $code"}")
        }
        return r["response"] ?: error("VK: пустой ответ")
    }

    private fun me() = loginResolver.uid ?: error("Нужен вход в VK")
    private fun page(page: Int?) = mapOf("count" to PAGE, "offset" to (page ?: 0) * PAGE)


    /** Обложка: у трека — от альбома, у альбома — photo или первая из thumbs. */
    private fun thumb(o: JsonElement?, size: Int) = listOf(size, 600, 300, 270, 135).firstNotNullOfOrNull { o.str("photo_$it") }

    fun track(o: JsonObject): FTrack? {
        val owner = o.long("owner_id") ?: return null
        val aid = o.long("id") ?: return null
        val key = o.str("access_key")?.takeIf { it.isNotBlank() }
        val id = "${owner}_$aid" + (key?.let { "_$it" } ?: "")       // с ключом — чтобы потом достать трек заново (audio.getById)
        val url = o.str("url")?.takeIf { it.startsWith("http") && "audio_api_unavailable" !in it }
        val artists = o["main_artists"].objects()
        val album = o["album"]
        val thumbs = album["thumb"]
        val sources = listOfNotNull(url?.let {
            source("mp3", it, Extension.MP3, Bitrate.B_320, if (".m3u8" in it) Container.HLS else Container.PROGRESSIVE)
        })
        return FTrack(
            id = id, title = o.str("title") ?: return null,
            artistIds = artists.mapNotNull { it.str("id") }, artistNames = artists.mapNotNull { it.str("name") }.ifEmpty { listOfNotNull(o.str("artist")) },
            durationMs = (o.long("duration") ?: 0) * 1000, sources = sources, mediaId = MediaId.build(this.id, id),
            albumId = album?.let { a -> a.long("owner_id")?.let { "${it}_${a.long("id")}" + (a.str("access_key")?.let { k -> "_$k" } ?: "") } },
            albumName = album.str("title"),
            subtitle = o.str("subtitle")?.takeIf { it.isNotBlank() } ?: if (sources.isEmpty()) "недоступен в VK" else null,
            smallCoverUrl = thumb(thumbs, 300), bigCoverUrl = thumb(thumbs, 1200),
            serviceUrl = "https://vk.com/audio${owner}_$aid", explicit = o.str("is_explicit") == "true",
            releaseDate = o.long("date")?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate() },
            extra = if (sources.isEmpty()) mapOf("partial" to "1") else emptyMap(),
        )
    }

    /** Плейлист VK; с исполнителями — это альбом. */
    fun playlist(o: JsonObject): Any? {
        val owner = o.long("owner_id") ?: return null
        val pid = o.long("id") ?: return null
        val id = "${owner}_$pid" + (o.str("access_key")?.takeIf { it.isNotBlank() }?.let { "_$it" } ?: "")
        val photo = o["photo"] ?: o["thumbs"].objects().firstOrNull()
        val artists = o["main_artists"].objects()
        val count = o.int("count") ?: 0
        val url = "https://vk.com/music/playlist/${owner}_$pid"
        return if (artists.isNotEmpty()) FAlbum(
            id, o.str("title") ?: "?", artists.mapNotNull { it.str("id") }, artists.mapNotNull { it.str("name") }, count,
            o.int("year")?.let { runCatching { org.threeten.bp.LocalDate.of(it, 1, 1) }.getOrNull() },
            thumb(photo, 300), thumb(photo, 1200), url,
            type = if (count == 1) AlbumType.SINGLE else AlbumType.ALBUM,
        ) else FPlaylist(id, o.str("title") ?: "?", o.str("description")?.takeIf { it.isNotBlank() }, count, thumb(photo, 300), thumb(photo, 1200), url, owner.toString())
    }

    fun artist(o: JsonObject): FArtist? {
        val id = o.str("id") ?: return null
        val photos = o["photo"].objects().sortedBy { it.int("width") ?: 0 }
        return FArtist(id, o.str("name") ?: "?", photos.firstOrNull { (it.int("width") ?: 0) >= 300 }?.str("url") ?: photos.lastOrNull()?.str("url"),
            photos.lastOrNull()?.str("url"), o.str("domain")?.let { "https://vk.com/artist/$it" })
    }

    private fun tracks(r: JsonElement) = (r["items"] ?: r).objects().mapNotNull(::track)
    private fun playlists(r: JsonElement) = (r["items"] ?: r).objects().mapNotNull(::playlist)

    /** «owner_id_id_ключ» → параметры запроса. */
    private fun ids(id: String): Triple<String, String, String?> {
        val parts = id.split('_')
        return Triple(parts[0], parts.getOrElse(1) { "" }, parts.getOrNull(2))
    }


    private val cursors = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()   // список → (блок, next_from)

    /** Раздел «Музыки» по адресу: section=all — моя музыка, section=general — главная (рекомендации). */
    private suspend fun section(kind: String): JsonElement {
        val sections = api("catalog.getAudio", mapOf("owner_id" to me(), "need_blocks" to 0))["catalog"]["sections"].objects()
        val id = sections.firstOrNull { it.str("url")?.contains("section=$kind") == true }?.str("id") ?: error("VK: нет раздела $kind")
        return api("catalog.getSection", mapOf("section_id" to id))
    }

    /** Треки из блоков каталога по порядку; дальше — по next_from того блока, где он есть. */
    private suspend fun catalogTracks(key: String, page: Int?, first: suspend () -> JsonElement): List<FTrack> {
        val (r, blocks) = if ((page ?: 0) == 0) {
            val r = first()
            r to (r["section"]["blocks"] ?: r["catalog"]["sections"].objects().firstOrNull()?.get("blocks")).objects()
        } else {
            val (block, from) = cursors[key] ?: return emptyList()
            val r = api("catalog.getBlockItems", mapOf("block_id" to block, "start_from" to from))
            r to listOfNotNull(r["block"] as? JsonObject)
        }
        val lists = blocks.filter { it.str("data_type") == "music_audios" }
        lists.firstOrNull { !it.str("next_from").isNullOrEmpty() }
            ?.let { b -> cursors[key] = b.str("id")!! to b.str("next_from")!! } ?: cursors.remove(key)
        val byId = r["audios"].objects().associateBy { "${it.long("owner_id")}_${it.long("id")}" }
        val order = lists.flatMap { b -> b["audios_ids"].arr.mapNotNull { e -> (e as? kotlinx.serialization.json.JsonPrimitive)?.content?.split('_')?.take(2)?.joinToString("_") } }
        return (if (order.isEmpty()) byId.values.toList() else order.distinct().mapNotNull(byId::get)).mapNotNull(::track)
    }

    /** «Моя музыка»: audio.get отдаёт треки в порядке добавления (лайков) — каталог же сортирует по прослушиваниям. */
    suspend fun myTracks(page: Int?): List<FTrack> {
        val direct = runCatching { tracks(api("audio.get", mapOf("owner_id" to me()) + page(page))) }.getOrNull()
        if (direct != null) return direct
        // каталог как запасной путь (если audio.get закрыли для этого типа токена)
        return catalogTracks("my", page) { section("all") }
    }
    suspend fun recommendations(page: Int?) = catalogTracks("general", page) { section("general") }
    suspend fun myPlaylists() = playlists(section("all")["playlists"] ?: kotlinx.serialization.json.JsonArray(emptyList()))

    suspend fun searchTracks(q: String, page: Int?) =
        catalogTracks("search:$q", page) { api("catalog.getAudioSearch", mapOf("query" to q, "need_blocks" to 1)) }.filter { it.extra["partial"] == null }
    suspend fun searchAlbums(q: String, page: Int?) = playlists(api("audio.searchAlbums", mapOf("q" to q) + page(page))).filterIsInstance<FAlbum>()
    suspend fun searchPlaylists(q: String, page: Int?) = playlists(api("audio.searchPlaylists", mapOf("q" to q) + page(page))).filterIsInstance<FPlaylist>()
    suspend fun searchArtists(q: String, page: Int?) = (api("audio.searchArtists", mapOf("q" to q) + page(page))["items"]).objects().mapNotNull(::artist)

    suspend fun similar(trackId: String): List<FTrack> {
        val (owner, aid, _) = ids(trackId)
        return tracks(api("audio.getRecommendations", mapOf("target_audio" to "${owner}_$aid", "count" to 50)))
    }

    suspend fun playlistInfo(id: String): Any {
        val (owner, pid, key) = ids(id)
        val o = api("audio.getPlaylistById", mapOf("owner_id" to owner, "playlist_id" to pid, "access_key" to key)) as? JsonObject
        return o?.let(::playlist) ?: error("Плейлист недоступен")
    }

    suspend fun playlistTracks(id: String): List<FTrack> {
        val (owner, pid, key) = ids(id)
        return tracks(api("audio.get", mapOf("owner_id" to owner, "playlist_id" to pid, "access_key" to key, "count" to 500)))
    }

    private suspend fun artistPage(id: String) = api("catalog.getAudioArtist", mapOf("artist_id" to id, "need_blocks" to 1))
    suspend fun artistInfo(id: String) = artistPage(id)["artists"].objects().firstOrNull()?.let(::artist) ?: error("Нет исполнителя")
    suspend fun artistTracks(id: String, page: Int?) = tracks(api("audio.getAudiosByArtist", mapOf("artist_id" to id) + page(page)))
    suspend fun artistAlbums(id: String) = playlists(artistPage(id)["playlists"] ?: kotlinx.serialization.json.JsonArray(emptyList())).filterIsInstance<FAlbum>()

    suspend fun tracksByIds(ids: List<String>): List<FTrack> = ids.chunked(100).flatMap { chunk ->
        val byId = tracks(api("audio.getById", mapOf("audios" to chunk.joinToString(",")))).associateBy { it.id }
        chunk.mapNotNull(byId::get)
    }

    /** «Добавить к себе», как в VK; убрать можно трек из своей музыки. */
    suspend fun like(trackId: String, like: Boolean) {
        val (owner, aid, key) = ids(trackId)
        if (like) api("audio.add", mapOf("owner_id" to owner, "audio_id" to aid, "access_key" to key))
        else {
            check(owner == me()) { "Уберите трек в «Моей музыке» — здесь лежит чужая копия" }
            api("audio.delete", mapOf("owner_id" to owner, "audio_id" to aid))
        }
    }


    private val measured = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Уже измеренный битрейт трека (кбит/с) или null. */
    fun measuredKbps(trackId: String): Int? = measured[trackId]

    /**
     * Битрейт файла по первым 16 КБ: средний — по полному размеру (Content-Range) и длительности (учитывает VBR),
     * иначе — из заголовка первого MP3-кадра. Сервер не ответил — null (качество останется неизвестным).
     */
    suspend fun measure(t: FTrack): Int? {
        measured[t.id]?.let { return it }
        val url = t.sources.firstOrNull()?.url?.takeIf { ".m3u8" !in it } ?: return null
        val resp = runCatching {
            network.execute(NetworkRequest.Builder(url).apply { addHeader("Range", "bytes=0-16383") }.build(), ResponseConverters.asByteArray())
        }.getOrNull()?.takeIf { it.statusCode in 200..299 } ?: return null
        val total = resp.headers.firstOrNull { it.first.equals("content-range", true) }?.second?.substringAfterLast('/')?.toLongOrNull()
        val avg = total?.takeIf { t.durationMs > 0 }?.let { (it * 8 / t.durationMs).toInt() }?.takeIf { it in 32..400 }   // бит/мс = кбит/с
        val kbps = avg ?: frameKbps(resp.result) ?: return null
        measured[t.id] = kbps
        return kbps
    }

    private fun frameKbps(b: ByteArray): Int? {
        var i = 0
        if (b.size > 10 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte())      // пропускаем тег ID3v2
            i = 10 + ((b[6].toInt() and 0x7f) shl 21 or (b[7].toInt() and 0x7f shl 14) or (b[8].toInt() and 0x7f shl 7) or (b[9].toInt() and 0x7f))
        val table = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)   // MPEG-1 Layer III
        while (i + 3 < b.size) {
            if (b[i].toInt() and 0xff == 0xff && b[i + 1].toInt() and 0xe0 == 0xe0) return table[(b[i + 2].toInt() and 0xff) shr 4].takeIf { it > 0 }
            i++
        }
        return null
    }


    override suspend fun resolveSourceForUrl(track: FTrack, source: Source): Source = source   // ссылка уже готова (живёт сутки)

    override suspend fun getTracksByMediaIds(mediaIds: List<String>) = tracksByIds(mediaIds.map { MediaId.getIds(it).first() })


    inner class VkLoginResolver : LoginResolver {
        private val kToken = "auth.token"
        private val kUid = "auth.uid"
        private val kName = "auth.name"
        private val kDevice = "auth.device"
        private val kWeb = "auth.web"
        private val kExpires = "auth.expires"
        private val kDomain = "auth.domain"

        val token: String? get() = preferences.getString<String?>(kToken, null)
        val uid: String? get() = preferences.getString<String?>(kUid, null)
        val web: Boolean get() = preferences.getString<String?>(kWeb, null) == "1"
        /** Домен сайта, где вошли через веб: vk.ru или vk.com. */
        val domain: String get() = preferences.getString<String?>(kDomain, null) ?: DOMAINS.first()
        val expired: Boolean get() = (preferences.getString<String?>(kExpires, null)?.toLongOrNull() ?: Long.MAX_VALUE) < System.currentTimeMillis() / 1000 + 60

        override val isLogged get() = !token.isNullOrEmpty()
        override val loginInfo get() = if (isLogged) "Вход выполнен: ${preferences.getString(kName, "")}" else "Войдите через VK или телефоном и паролем"

        override val webLoginVariants = listOf(WebLoginVariant("web") { "Войти через VK" })

        /** Вход на vk.com в окне Chromium; сайт поставил куку сессии — окно закрывается, куки уходят в сетевой слой Melos. */
        override suspend fun webLogin(loginVariantId: String, webViewProvider: WebViewProvider): Boolean {
            fun sessionDomain() = DOMAINS.firstOrNull { d -> SESSION_COOKIES.any { melos.host.EmbeddedWebView.webCookie(d, it) != null } }
            val embedded = webViewProvider as? melos.host.EmbeddedWebView
            embedded?.finishWhen = { sessionDomain() != null }
            val done = try {
                webViewProvider.startWebView(true, null, "https://vk.com/", DOMAINS, object : WebViewProvider.UrlLoadPolicyResolver {
                    override fun resolveUrlLoadPolicy(url: String) =
                        if (sessionDomain() != null) WebViewProvider.UrlLoadPolicy.ALLOW_LOAD_AND_FINISH else WebViewProvider.UrlLoadPolicy.ALLOW_LOAD
                    override fun resolveResourceLoadPolicy(url: String) = WebViewProvider.UrlLoadPolicy.ALLOW_LOAD
                })
            } finally { embedded?.finishWhen = null }
            if (!done) return false
            preferences.setString(kDomain, sessionDomain() ?: DOMAINS.first())
            refreshWeb(newLogin = true)
            return true
        }

        /** Токен веб-клиента по кукам сессии vk.com — как его берёт сама страница сайта. */
        suspend fun refreshWeb(newLogin: Boolean = false) {
            val r = raw("https://login.$domain/?act=web_token", mapOf("version" to 1, "app_id" to WEB_APP_ID), "POST", web = true)
            val data = r["data"]
            val t = data.str("access_token") ?: error(if (r.str("error_info") == "unauthorized") "VK: сессия сайта закончилась — войдите заново" else "VK не выдал токен: ${r.str("error_info") ?: r}")
            preferences.setString(kWeb, "1")
            data.long("expires")?.let { preferences.setString(kExpires, it.toString()) }
            if (newLogin || uid == null) save(t, web = true) else preferences.setString(kToken, t)
        }

        override val credentialsLoginVariants = listOf(
            CredentialsLoginVariant("password", { "Пароль" }, listOf(
                CredentialsLoginVariant.Credential("login", { "Телефон или почта" }),
                CredentialsLoginVariant.Credential("password", { "Пароль" }, secure = true),
            )),
            CredentialsLoginVariant("token", { "Токен" }, listOf(
                CredentialsLoginVariant.Credential("token", { "Токен VK (официального приложения)" }, secure = true),
            )),
        )

        override suspend fun credentialsLogin(loginVariantId: String, args: Map<String, String>): CredentialLoginStep {
            if (loginVariantId == "token") return save(args["token"]?.trim()?.removePrefix("access_token=").orEmpty().ifEmpty { error("Пустой токен") }, web = false)
            val login = args["login"]?.trim().orEmpty()
            val password = args["password"].orEmpty()
            require(login.isNotEmpty() && password.isNotEmpty()) { "Введите телефон или почту и пароль" }
            val device = preferences.getString<String?>(kDevice, null)
                ?: List(16) { "0123456789abcdef".random() }.joinToString("").also { preferences.setString(kDevice, it) }
            val r = raw("https://oauth.vk.com/token", mapOf(
                "grant_type" to "password", "client_id" to CLIENT_ID, "client_secret" to CLIENT_SECRET,
                "username" to login, "password" to password, "scope" to "all", "v" to VERSION, "lang" to "ru",
                "device_id" to device, "2fa_supported" to 1,
                "code" to args[TwoFARequiredStep.TWO_F_A_VALUE]?.trim(),
                "captcha_sid" to args["captcha_sid"], "captcha_key" to args[CaptchaRequiredStep.CAPTCHA_VALUE]?.trim(),
            ), web = false)
            r.str("access_token")?.let { return save(it, web = false) }
            val keep = mapOf("login" to login, "password" to password)
            return when (r.str("error")) {
                "need_validation" -> {
                    val sms = r.str("validation_type") == "2fa_sms"
                    // для SMS код надо запросить; приложение-генератор присылает его само
                    if (sms) r.str("validation_sid")?.let { sid -> runCatching { raw(API + "auth.validatePhone", mapOf("sid" to sid, "api_id" to CLIENT_ID, "v" to VERSION), web = false) } }
                    TwoFARequiredStep(if (sms) "Код из SMS на ${r.str("phone_mask") ?: "телефон"}" else "Код из приложения-генератора или из сообщения VK", keep)
                }
                "need_captcha" -> CaptchaRequiredStep(r.str("captcha_img") ?: error("VK просит капчу"), keep + ("captcha_sid" to r.str("captcha_sid").orEmpty()))
                else -> error(r.str("error_description") ?: r.str("error") ?: "VK не пустил")
            }
        }

        /** Проверяем токен и запоминаем, кто вошёл. */
        private suspend fun save(token: String, web: Boolean): CredentialLoginStep {
            val r = raw((if (web) "https://api.$domain/method/" else API) + "users.get", mapOf("access_token" to token, "v" to VERSION, "lang" to "ru"), web = web)
            r["error"]?.let { error("VK: ${it.str("error_msg") ?: "токен не подошёл"}") }
            val u = r["response"].objects().firstOrNull() ?: error("VK: токен не подошёл")
            preferences.setString(kToken, token)
            preferences.setString(kUid, u.long("id")?.toString() ?: error("VK: нет id"))
            preferences.setString(kName, listOfNotNull(u.str("first_name"), u.str("last_name")).joinToString(" "))
            if (web) preferences.setString(kWeb, "1") else { preferences.remove(kWeb); preferences.remove(kExpires) }
            return SuccessfulLogin
        }

        override fun logout() { listOf(kToken, kUid, kName, kWeb, kExpires, kDomain).forEach(preferences::remove) }
    }
}
