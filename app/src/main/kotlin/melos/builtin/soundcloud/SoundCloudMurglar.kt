package melos.builtin.soundcloud

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.model.album.AlbumType
import com.badmanners.murglar.lib.core.model.track.source.Bitrate
import com.badmanners.murglar.lib.core.model.track.source.Container
import com.badmanners.murglar.lib.core.model.track.source.Extension
import com.badmanners.murglar.lib.core.model.track.source.Source
import com.badmanners.murglar.lib.core.model.track.source.SourceFormat
import com.badmanners.murglar.lib.core.model.track.source.SourceFormat.SourceFormatAvailability.AVAILABLE
import com.badmanners.murglar.lib.core.model.track.source.QualityTier
import com.badmanners.murglar.lib.core.network.NetworkMiddleware
import com.badmanners.murglar.lib.core.notification.NotificationMiddleware
import com.badmanners.murglar.lib.core.preference.ActionPreference
import com.badmanners.murglar.lib.core.preference.PreferenceMiddleware
import com.badmanners.murglar.lib.core.service.BaseMurglar
import com.badmanners.murglar.lib.core.utils.MediaId
import melos.builtin.BuiltinMessages
import melos.builtin.bitrateOf
import melos.builtin.FAlbum
import melos.builtin.FArtist
import melos.builtin.FPlaylist
import melos.builtin.FTrack
import melos.builtin.TokenLoginResolver
import melos.builtin.arr
import melos.builtin.bitrateOf
import melos.builtin.bool
import melos.builtin.builtinMessages
import melos.builtin.get
import melos.builtin.getJson
import melos.builtin.getText
import melos.builtin.long
import melos.builtin.objects
import melos.builtin.parseDate
import melos.builtin.source
import melos.builtin.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// SoundCloud через публичный api-v2: client_id достаём из бандла сайта, как это делает сам сайт.
class SoundCloudMurglar(
    id: String,
    preferences: PreferenceMiddleware,
    network: NetworkMiddleware,
    notifications: NotificationMiddleware,
    logger: LoggerMiddleware,
) : BaseMurglar<FTrack, BuiltinMessages>(id, MESSAGES, preferences, network, notifications, logger) {

    companion object {
        private val MESSAGES = builtinMessages("SoundCloud")
        private const val API = "https://api-v2.soundcloud.com"
        private const val K_CLIENT = "client_id.v2"                  // v2: старый ключ хранил запасной id из скрипта — с ним не работал вход
        private const val K_DEVICE = "device_id"
        private const val APP_ID = 46941                                // client_application_id сайта (web-auth передаёт его как app_id)
        private const val K_REFRESH = "auth.refresh"
        private const val K_PKCE = "auth.pkce"
        private const val SECURE = "https://secure.soundcloud.com"
        private const val REDIRECT = "https://soundcloud.com/signin/callback"
        const val PAGE = 30
    }

    override val loginResolver = TokenLoginResolver(
        preferences, "Адрес страницы из браузера (или токен)",
        "Поиск работает без входа. Вход — в окне Chromium: по почте, через Google, Apple или Facebook",
        webLabel = "Войти",
        webFlow = { web -> webAuth(web) },
        readInput = { raw -> codeFromInput(raw) },
    ) { token ->
        val me = network.getJson("$API/me", mapOf("client_id" to clientId()), mapOf("Authorization" to "OAuth $token"))
        (me.long("id")?.toString() ?: error("Токен не подошёл")) to (me.str("username") ?: "")
    }

    override val nodeResolver = SoundCloudNodeResolver(this, messages)

    override val possibleFormats = listOf(
        SourceFormat(Extension.MP3, Bitrate.B_128, QualityTier.NORMAL_QUALITY, AVAILABLE),
        SourceFormat(Extension.OPUS, Bitrate.B_64, QualityTier.LOW_QUALITY, AVAILABLE),
    )

    override val murglarPreferences = listOf(
        ActionPreference("sc.reset", "Обновить client_id", "Если поиск перестал работать", { preferences.remove(K_CLIENT) }, false)
    )

    private var cachedClient: String? = null

    suspend fun clientId(force: Boolean = false): String {
        if (!force) (cachedClient ?: preferences.getString(K_CLIENT, null))?.let { cachedClient = it; return it }
        val home = network.getText("https://soundcloud.com/")
        // действующий id сайт отдаёт в разметке (apiClient, «isExpiring» — его меняют); в скриптах — старый запасной
        Regex(""""apiClient":\{"id":"([a-zA-Z0-9]{32})"""").find(home)?.groupValues?.get(1)?.let {
            preferences.setString(K_CLIENT, it); cachedClient = it; return it
        }
        val scripts = Regex("""<script[^>]+src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js)"""").findAll(home).map { it.groupValues[1] }.toList()
        for (src in scripts.reversed()) {
            val js = runCatching { network.getText(src) }.getOrNull() ?: continue
            Regex("""client_id\s*[:=]\s*"([a-zA-Z0-9]{32})"""").find(js)?.groupValues?.get(1)?.let {
                preferences.setString(K_CLIENT, it); cachedClient = it; return it
            }
        }
        error("Не удалось получить client_id SoundCloud")
    }

    // (главный сайт показывает эту же страницу во фрейме, а встроенный WebKit фрейм входа не открывает)
    /** Новая пара PKCE и адрес страницы входа; проверочное слово запоминаем до обмена кода. */
    private suspend fun authUrl(): String {
        val rnd = java.security.SecureRandom()
        fun b64url(b: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        val verifier = b64url(ByteArray(48).also(rnd::nextBytes))
        val challenge = b64url(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val nonce = b64url(ByteArray(24).also(rnd::nextBytes))
        val cid = clientId()
        val state = java.util.Base64.getEncoder().encodeToString("""{"client_id":"$cid","nonce":"$nonce"}""".toByteArray())
        fun enc(v: String) = java.net.URLEncoder.encode(v, Charsets.UTF_8)
        preferences.setString(K_PKCE, verifier)
        // как у сайта: device_id — анонимный id браузера (у сайта — кука sc_anonymous_id), app_id — приложение «веб»
        val device = preferences.getString<String?>(K_DEVICE, null)
            ?: List(4) { rnd.nextInt(1_000_000).toString().padStart(6, '0') }.joinToString("-").also { preferences.setString(K_DEVICE, it) }
        return "$SECURE/web-auth?client_id=$cid&device_id=$device&theme=dark&ui_evo=true&app_id=$APP_ID&tracking=local" +
            "&redirect_uri=${enc(REDIRECT)}&state=${enc(state)}&code_challenge=${enc(challenge)}&code_challenge_method=S256"
    }

    /** Код из адреса soundcloud.com/signin/callback?code=… → токен (обмен как у сайта). */
    private suspend fun exchange(code: String): String {
        // тип — явно: getString обобщённый, с «null ?: error()» Kotlin выводит Nothing? (Void) и строка из настроек падает
        val verifier: String = preferences.getString<String?>(K_PKCE, null) ?: error("Начните вход заново: нажмите «Войти»")
        val r = network.getJson("$SECURE/oauth/token?grant_type=authorization_code",
            mapOf("grant_type" to "authorization_code", "code" to code, "code_verifier" to verifier, "redirect_uri" to REDIRECT, "client_id" to clientId()),
            method = "POST")
        r.str("refresh_token")?.let { preferences.setString(K_REFRESH, it) }
        preferences.remove(K_PKCE)
        return r.str("access_token") ?: error("SoundCloud не выдал токен")
    }

    private fun codeOf(url: String) = Regex("[?&]code=([^&#\\s]+)").find(url)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }

    /** Окно входа SoundCloud (на Chromium — с Google, Apple, Facebook); адрес возврата с кодом до сайта не доходит — код меняем сами. */
    private suspend fun webAuth(web: com.badmanners.murglar.lib.core.webview.WebViewProvider): String? {
        var code: String? = null
        val done = web.startWebView(true, null, authUrl(), emptyList(), object : com.badmanners.murglar.lib.core.webview.WebViewProvider.UrlLoadPolicyResolver {
            override fun resolveUrlLoadPolicy(url: String): com.badmanners.murglar.lib.core.webview.WebViewProvider.UrlLoadPolicy {
                if (!url.startsWith(REDIRECT)) return com.badmanners.murglar.lib.core.webview.WebViewProvider.UrlLoadPolicy.ALLOW_LOAD
                code = codeOf(url)
                return com.badmanners.murglar.lib.core.webview.WebViewProvider.UrlLoadPolicy.ALLOW_LOAD_AND_FINISH
            }
            override fun resolveResourceLoadPolicy(url: String) = com.badmanners.murglar.lib.core.webview.WebViewProvider.UrlLoadPolicy.ALLOW_LOAD
        })
        val c = code ?: return null
        if (!done) return null
        return exchange(c)
    }

    /** В поле вставили адрес из браузера — меняем код на токен; иначе это сам токен. */
    private suspend fun codeFromInput(raw: String): String = codeOf(raw)?.let { exchange(it) } ?: raw.removePrefix("OAuth ").trim()

    /** Токен с сайта живёт недолго — обновляем по refresh_token, как это делает сам сайт. */
    private suspend fun refreshToken(): Boolean = runCatching {
        val refresh: String = preferences.getString<String?>(K_REFRESH, null) ?: return false
        val r = network.getJson("$SECURE/oauth/token?grant_type=refresh_token",
            mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId()), method = "POST")
        val t = r.str("access_token") ?: return false
        loginResolver.updateToken(t)
        r.str("refresh_token")?.let { preferences.setString(K_REFRESH, it) }
        true
    }.onFailure { melos.host.Log.w("SoundCloud", "refresh: ${it.message}") }.getOrDefault(false)

    private fun auth() = loginResolver.token?.let { mapOf("Authorization" to "OAuth $it") } ?: emptyMap()

    suspend fun api(path: String, params: Map<String, Any?> = emptyMap()): JsonElement {
        val url = if (path.startsWith("http")) path else API + path
        return runCatching { network.getJson(url, params + ("client_id" to clientId()), auth()) }
            .recoverCatching { e ->
                if (e.message?.contains("HTTP 401") == true && loginResolver.isLogged && refreshToken())
                    network.getJson(url, params + ("client_id" to clientId()), auth())          // токен истёк — обновили
                else if (e.message?.contains("HTTP 401") == true || e.message?.contains("HTTP 403") == true)
                    network.getJson(url, params + ("client_id" to clientId(force = true)), auth())
                else throw e
            }.getOrThrow()
    }


    private fun art(url: String?, size: String) = url?.replace("-large.", "-$size.")

    fun track(o: JsonObject): FTrack? {
        val id = o.long("id")?.toString() ?: return null
        var title = o.str("title") ?: return null   // заглушка {id} из плейлиста — догружаем отдельно
        val user = o["user"]
        var artist = o["publisher_metadata"].str("artist")?.takeIf { it.isNotBlank() }
        if (artist == null && " - " in title) {            // «Исполнитель - Название» в заголовке — частый случай на SoundCloud
            val left = title.substringBefore(" - ").trim(); val right = title.substringAfter(" - ").trim()
            val uploader = user.str("username").orEmpty()
            // «Название - Исполнитель», если справа — сам загрузивший
            if (uploader.isNotEmpty() && right.equals(uploader, ignoreCase = true)) { artist = right; title = left }
            else { artist = left; title = right }
        }
        artist = artist ?: user.str("username") ?: "?"
        val auth = o.str("track_authorization")
        // потоки с DRM (cbc/ctr-encrypted-hls) не трогаем; полный поток — раньше 30-секундного отрывка (snipped)
        val plain = o["media"]["transcodings"].objects().filter { t -> t["format"].str("protocol")?.contains("encrypted") == false }
        val full = plain.filter { !it.bool("snipped") }
        val preview = full.isEmpty()
        val sources = (if (preview) plain else full).mapNotNull { t ->
            val url = t.str("url") ?: return@mapNotNull null
            val protocol = t["format"].str("protocol") ?: return@mapNotNull null
            val mime = t["format"].str("mime_type").orEmpty()
            val ext = when { "mpeg" in mime -> Extension.MP3; "opus" in mime -> Extension.OPUS; "mp4" in mime -> Extension.M4A; else -> Extension.UNKNOWN }
            // настоящий битрейт — в имени пресета: aac_160k / aac_96k / mp3_1_0 (128) / opus_0_0 (64)
            val kbps = Regex("_(\\d+)k").find(t.str("preset").orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            val bitrate = bitrateOf(kbps ?: if (ext == Extension.OPUS) 64 else 128)
            val container = if (protocol == "hls") Container.HLS else Container.PROGRESSIVE
            source("${protocol}_${ext.value}", url + (auth?.let { "?track_authorization=$it" } ?: ""), ext, bitrate, container)
        }.sortedBy { if (it.container == Container.PROGRESSIVE) 0 else if (it.extension == Extension.MP3) 1 else 2 }
        val artwork = o.str("artwork_url") ?: user.str("avatar_url")
        val snipped = o.str("policy") == "SNIP" || (preview && sources.isNotEmpty())
        return FTrack(
            id = id, title = title, artistIds = listOfNotNull(user.long("id")?.toString()), artistNames = listOf(artist),
            durationMs = if (snipped) o.long("full_duration") ?: 0 else o.long("duration") ?: 0,
            sources = sources, mediaId = MediaId.build(this.id, id),
            subtitle = if (sources.isEmpty()) "защищён DRM — не играет" else if (snipped) "отрывок 30 с — целиком по подписке Go+" else null,
            extra = if (sources.isEmpty() || snipped) mapOf("partial" to "1") else emptyMap(),
            smallCoverUrl = art(artwork, "t200x200"), bigCoverUrl = art(artwork, "t500x500"),
            serviceUrl = o.str("permalink_url"), genre = o.str("genre")?.takeIf { it.isNotBlank() },
            releaseDate = parseDate(o.str("release_date") ?: o.str("created_at")),
        )
    }

    fun playlist(o: JsonObject): Any? {
        val id = o.long("id")?.toString() ?: return null
        val user = o["user"]
        val artwork = o.str("artwork_url") ?: o["tracks"].objects().firstOrNull()?.str("artwork_url") ?: user.str("avatar_url")
        val count = o.long("track_count")?.toInt() ?: 0
        return if (o.bool("is_album")) FAlbum(
            id, o.str("title") ?: "?", listOfNotNull(user.long("id")?.toString()), listOfNotNull(user.str("username")), count,
            parseDate(o.str("release_date") ?: o.str("published_at")), art(artwork, "t200x200"), art(artwork, "t500x500"),
            o.str("permalink_url"), type = when (o.str("set_type")) { "ep" -> AlbumType.EP; "single" -> AlbumType.SINGLE; "compilation" -> AlbumType.COMPILATION; else -> AlbumType.ALBUM }
        ) else FPlaylist(
            id, o.str("title") ?: "?", user.str("username"), count, art(artwork, "t200x200"), art(artwork, "t500x500"),
            o.str("permalink_url"), user.long("id")?.toString() ?: ""
        )
    }

    fun artist(o: JsonObject): FArtist? {
        val id = o.long("id")?.toString() ?: return null
        val avatar = o.str("avatar_url")
        return FArtist(id, o.str("username") ?: "?", art(avatar, "t200x200"), art(avatar, "t500x500"), o.str("permalink_url"))
    }


    private fun page(page: Int?) = mapOf("limit" to PAGE, "offset" to (page ?: 0) * PAGE, "linked_partitioning" to 1)

    /** Поиск — только треки, которые играют целиком: без отрывков Go+ и без DRM. */
    suspend fun searchTracks(q: String, page: Int?) = api("/search/tracks", mapOf("q" to q) + page(page))["collection"].objects()
        .mapNotNull(::track).filter { it.extra["partial"] == null }
    suspend fun searchArtists(q: String, page: Int?) = api("/search/users", mapOf("q" to q) + page(page))["collection"].objects().mapNotNull(::artist)
    suspend fun searchAlbums(q: String, page: Int?) = api("/search/albums", mapOf("q" to q) + page(page))["collection"].objects().mapNotNull { playlist(it) as? FAlbum }
    suspend fun searchPlaylists(q: String, page: Int?) =
        api("/search/playlists_without_albums", mapOf("q" to q) + page(page))["collection"].objects().mapNotNull { playlist(it) as? FPlaylist }

    suspend fun getTrack(id: String) = track(api("/tracks/$id") as JsonObject) ?: error("Трек $id недоступен")

    suspend fun getTracks(ids: List<String>): List<FTrack> = ids.chunked(50).flatMap { chunk ->
        val byId = api("/tracks", mapOf("ids" to chunk.joinToString(","))).objects().mapNotNull(::track).associateBy { it.id }
        chunk.mapNotNull(byId::get)
    }

    suspend fun getPlaylistRaw(id: String) = api("/playlists/$id", mapOf("representation" to "full")) as JsonObject

    suspend fun getPlaylistTracks(id: String): List<FTrack> {
        val raw = getPlaylistRaw(id)["tracks"].objects()
        val full = raw.mapNotNull(::track).associateBy { it.id }
        val missing = raw.mapNotNull { it.long("id")?.toString() }.filter { it !in full }
        val loaded = if (missing.isEmpty()) emptyMap() else getTracks(missing).associateBy { it.id }
        return raw.mapNotNull { it.long("id")?.toString()?.let { id -> full[id] ?: loaded[id] } }
    }

    suspend fun getArtist(id: String) = artist(api("/users/$id") as JsonObject) ?: error("Нет пользователя $id")
    suspend fun artistTracks(id: String, page: Int?) = api("/users/$id/tracks", page(page))["collection"].objects().mapNotNull(::track)
    suspend fun artistPopular(id: String) = api("/users/$id/toptracks", mapOf("limit" to 50))["collection"].objects().mapNotNull(::track)
    suspend fun artistAlbums(id: String) = api("/users/$id/albums", mapOf("limit" to 50))["collection"].objects().mapNotNull { playlist(it) as? FAlbum }
    suspend fun artistPlaylists(id: String) = api("/users/$id/playlists_without_albums", mapOf("limit" to 50))["collection"].objects().mapNotNull { playlist(it) as? FPlaylist }

    suspend fun related(trackId: String) = api("/tracks/$trackId/related", mapOf("limit" to 25))["collection"].objects().mapNotNull(::track)

    private fun me() = loginResolver.uid ?: error("Нужен вход в SoundCloud")
    /** Лайк трека, как на сайте: PUT/DELETE /users/{я}/track_likes/{трек}. */
    suspend fun likeTrack(trackId: String, like: Boolean) {
        val req = com.badmanners.murglar.lib.core.network.NetworkRequest.Builder("$API/users/${me()}/track_likes/$trackId", if (like) "PUT" else "DELETE").apply {
            addParameter("client_id", clientId())
            auth().forEach { (k, v) -> addHeader(k, v) }
        }.build()
        val resp = network.execute(req, com.badmanners.murglar.lib.core.network.ResponseConverters.asString())
        check(resp.isSuccessful) { "SoundCloud: лайк не сохранился (HTTP ${resp.statusCode})" }
    }

    suspend fun myLikes() = api("/users/${me()}/track_likes", mapOf("limit" to 200))["collection"].objects().mapNotNull { it["track"] as? JsonObject }.mapNotNull(::track)
    suspend fun myPlaylists() = api("/users/${me()}/playlist_likes", mapOf("limit" to 200))["collection"].objects()
        .mapNotNull { it["playlist"] as? JsonObject }.mapNotNull { playlist(it) }
    suspend fun myStream() = api("/stream", mapOf("limit" to 50))["collection"].objects().mapNotNull { it["track"] as? JsonObject }.mapNotNull(::track)

    suspend fun resolve(url: String) = api("/resolve", mapOf("url" to url)) as JsonObject


    override suspend fun resolveSourceForUrl(track: FTrack, source: Source): Source {
        val url = source.url ?: error("Нет ссылки на поток")
        val stream = api(url).str("url") ?: error("SoundCloud не отдал поток")
        return source.copyWithNewUrl(stream)
    }

    override suspend fun getTracksByMediaIds(mediaIds: List<String>) = getTracks(mediaIds.map { MediaId.getIds(it).first() })

    fun hasArr(e: JsonElement?) = e.arr.isNotEmpty()
}
