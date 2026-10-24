package melos.builtin.yandex

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.model.tag.Lyrics
import com.badmanners.murglar.lib.core.model.track.source.Bitrate
import com.badmanners.murglar.lib.core.model.track.source.Extension
import com.badmanners.murglar.lib.core.model.track.source.QualityTier
import com.badmanners.murglar.lib.core.model.track.source.Source
import com.badmanners.murglar.lib.core.model.track.source.SourceFormat
import com.badmanners.murglar.lib.core.model.track.source.SourceFormat.SourceFormatAvailability.AVAILABLE
import com.badmanners.murglar.lib.core.model.track.source.SourceFormat.SourceFormatAvailability.REQUIRES_SERVICE_SUBSCRIPTION
import com.badmanners.murglar.lib.core.network.NetworkMiddleware
import com.badmanners.murglar.lib.core.notification.NotificationMiddleware
import com.badmanners.murglar.lib.core.preference.PreferenceMiddleware
import com.badmanners.murglar.lib.core.service.BaseMurglar
import com.badmanners.murglar.lib.core.utils.MediaId
import melos.builtin.BuiltinMessages
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
import melos.builtin.postJson
import melos.builtin.int
import melos.builtin.long
import melos.builtin.objects
import melos.builtin.source
import melos.builtin.str
import melos.host.Log
import melos.lyrics.Lrc
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Яндекс Музыка через мобильный API (тот же, что у официального приложения), вход по OAuth-токену.
class YandexMurglar(
    id: String,
    preferences: PreferenceMiddleware,
    network: NetworkMiddleware,
    notifications: NotificationMiddleware,
    logger: LoggerMiddleware,
) : BaseMurglar<FTrack, BuiltinMessages>(id, MESSAGES, preferences, network, notifications, logger) {

    companion object {
        private val MESSAGES = builtinMessages("Yandex Music", "Яндекс Музыка")
        private const val API = "https://api.music.yandex.net"
        private const val DOWNLOAD_SALT = "XGRlBW9FXlekgbPrRHuSiA"
        private const val LYRICS_KEY = "p93jhgh689SBReK6ghtw62"
        // get-file-info (FLAC/AAC для Плюса): подпись проверяется вместе с заголовком десктопного клиента (как в yandex-music-api)
        private const val FILE_INFO_KEY = "kzqU4XhfCaY6B6JTHODeq5"
        private const val FILE_INFO_CLIENT = "YandexMusicDesktopAppWindows/5.95.0"
        private val FILE_INFO_CODECS = listOf("flac", "flac-mp4", "aac-mp4", "he-aac-mp4", "aac", "he-aac", "mp3")
        const val PAGE = 30
    }

    override val loginResolver = TokenLoginResolver(
        preferences, "OAuth-токен",
        "Войдите через Яндекс или вставьте OAuth-токен (как его получить — ym.marshal.dev/token)",
        webUrl = "https://oauth.yandex.ru/authorize?response_type=token&client_id=23cabbbdc6cd418abb4b39c32c41195d",
        webLabel = "Войти через Яндекс",
        webLanding = Regex("""^https://(id|passport)\.yandex\.[a-z.]+/(profile/?)?(\?.*)?$"""),
    ) { token ->
        val acc = network.getJson("$API/account/status", headers = headers(token))["result"]["account"]
        (acc.long("uid")?.toString() ?: error("Токен не подошёл")) to (acc.str("displayName") ?: acc.str("login") ?: "")
    }

    override val nodeResolver = YandexNodeResolver(this, messages)

    override val possibleFormats = listOf(
        SourceFormat(Extension.FLAC, Bitrate.B_LOSSLESS, QualityTier.LOSSLESS, REQUIRES_SERVICE_SUBSCRIPTION),
        SourceFormat(Extension.MP3, Bitrate.B_320, QualityTier.HIGH_QUALITY, REQUIRES_SERVICE_SUBSCRIPTION),
        SourceFormat(Extension.MP3, Bitrate.B_192, QualityTier.NORMAL_QUALITY, AVAILABLE),
    )

    private fun headers(token: String? = loginResolver.token) = buildMap {
        put("X-Yandex-Music-Client", "YandexMusicAndroid/24023621")
        put("Accept-Language", "ru")
        put("User-Agent", "Yandex-Music-API")
        token?.let { put("Authorization", "OAuth $it") }
    }

    private suspend fun api(path: String, params: Map<String, Any?> = emptyMap(), method: String = "GET"): JsonElement =
        network.getJson(API + path, params, headers(), method)["result"] ?: error("Пустой ответ Яндекса")

    private fun uid() = loginResolver.uid ?: error("Нужен вход в Яндекс Музыку")

    // ---- маппинг ----

    private fun cover(uri: String?, size: Int) = uri?.let { "https://" + it.replace("%%", "${size}x$size") }

    fun track(o: JsonObject): FTrack? {
        val id = o.str("id") ?: return null
        if (o["available"] != null && !o.bool("available")) return null
        val album = o["albums"].objects().firstOrNull()
        val artists = o["artists"].objects()
        val coverUri = o.str("coverUri") ?: album.str("coverUri")
        return FTrack(
            id = id, title = o.str("title") ?: "?", subtitle = o.str("version"),
            artistIds = artists.mapNotNull { it.str("id") }, artistNames = artists.mapNotNull { it.str("name") },
            durationMs = o.long("durationMs") ?: 0,
            sources = listOf(
                source("lossless", null, Extension.FLAC, Bitrate.B_LOSSLESS),
                source("mp3-320", null, Extension.MP3, Bitrate.B_320),
                source("mp3-192", null, Extension.MP3, Bitrate.B_192),
            ),
            mediaId = MediaId.build(this.id, id),
            albumId = album.str("id"), albumName = album.str("title"),
            smallCoverUrl = cover(coverUri, 200), bigCoverUrl = cover(coverUri, 1000),
            serviceUrl = album.str("id")?.let { "https://music.yandex.ru/album/$it/track/$id" } ?: "https://music.yandex.ru/track/$id",
            genre = album.str("genre"), explicit = o.str("contentWarning") == "explicit",
            indexInAlbum = album["trackPosition"].int("index"),
            releaseDate = album.int("year")?.let { org.threeten.bp.LocalDate.of(it, 1, 1) },
            // громкость по EBU R128 от Яндекса → gain/peak в шкале тегов (те же хелперы, что в ядре Murglar)
            gain = o["r128"].let { r -> r?.let { (it as? JsonObject)?.get("i") }?.let { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }?.let { com.badmanners.murglar.lib.core.model.tag.r128GainToTagsGain(it) } },
            peak = o["r128"].let { r -> r?.let { (it as? JsonObject)?.get("tp") }?.let { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }?.let { com.badmanners.murglar.lib.core.model.tag.r128PeakToTagsPeak(it) } },
            extra = buildMap {
                val info = o["lyricsInfo"]
                if (info.bool("hasAvailableSyncLyrics")) put("lyrics", "sync")
                else if (info.bool("hasAvailableTextLyrics")) put("lyrics", "text")
            },
        )
    }

    fun album(o: JsonObject): FAlbum? {
        val id = o.str("id") ?: return null
        val artists = o["artists"].objects()
        return FAlbum(
            id, o.str("title") ?: "?", artists.mapNotNull { it.str("id") }, artists.mapNotNull { it.str("name") },
            o.int("trackCount") ?: 0, o.int("year")?.let { org.threeten.bp.LocalDate.of(it, 1, 1) },
            cover(o.str("coverUri"), 200), cover(o.str("coverUri"), 1000), "https://music.yandex.ru/album/$id", o.str("genre"),
        )
    }

    fun artist(o: JsonObject): FArtist? {
        val id = o.str("id") ?: return null
        val uri = o["cover"].str("uri")
        return FArtist(id, o.str("name") ?: "?", cover(uri, 200), cover(uri, 1000), "https://music.yandex.ru/artist/$id")
    }

    fun playlist(o: JsonObject): FPlaylist? {
        val owner = o["owner"].str("uid") ?: o.str("uid") ?: return null
        val kind = o.str("kind") ?: return null
        val c = o["cover"]
        val uri = c.str("uri") ?: (c["itemsUri"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: o.str("ogImage")
        return FPlaylist("${owner}_$kind", o.str("title") ?: "?", o["owner"].str("name"), o.int("trackCount") ?: 0,
            cover(uri, 200), cover(uri, 1000), "https://music.yandex.ru/users/$owner/playlists/$kind", owner)
    }

    // ---- запросы ----

    suspend fun tracksByIds(ids: List<String>): List<FTrack> = ids.chunked(200).flatMap { chunk ->
        api("/tracks", mapOf("track-ids" to chunk.joinToString(","), "with-positions" to "true"), "POST").objects().mapNotNull(::track)
    }

    /** Чарт Яндекс Музыки (как вкладка «Чарт» на сайте): 100 треков. */
    suspend fun chart(): List<FTrack> =
        api("/landing3/chart")["chart"]["tracks"].objects().mapNotNull { (it["track"] as? JsonObject)?.let(::track) }

    /** Новые релизы (вкладка на сайте): id альбомов, затем сами альбомы одним запросом. */
    suspend fun newReleases(): List<FAlbum> {
        val ids = api("/landing3/new-releases")["newReleases"].arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (ids.isEmpty()) return emptyList()
        return api("/albums", mapOf("album-ids" to ids.joinToString(","))).objects().mapNotNull(::album)
    }

    suspend fun myTracks(): List<FTrack> {
        val ids = api("/users/${uid()}/likes/tracks")["library"]["tracks"].objects().mapNotNull { t ->
            t.str("id")?.let { id -> t.str("albumId")?.let { "$id:$it" } ?: id }
        }
        return tracksByIds(ids)
    }

    suspend fun myAlbums() = api("/users/${uid()}/likes/albums", mapOf("rich" to "true")).objects()
        .mapNotNull { (it["album"] as? JsonObject ?: it).let(::album) }

    suspend fun myArtists() = api("/users/${uid()}/likes/artists", mapOf("with-timestamps" to "false")).objects()
        .mapNotNull { (it["artist"] as? JsonObject ?: it).let(::artist) }

    suspend fun myPlaylists(): List<FPlaylist> {
        val own = api("/users/${uid()}/playlists/list").objects().mapNotNull(::playlist)
        val liked = runCatching {
            api("/users/${uid()}/likes/playlists").objects().mapNotNull { (it["playlist"] as? JsonObject)?.let(::playlist) }
        }.getOrDefault(emptyList())
        return own + liked
    }

    suspend fun search(q: String, type: String, page: Int?): List<JsonObject> =
        api("/search", mapOf("text" to q, "type" to type, "page" to (page ?: 0), "nocorrect" to "false"))[type + "s"]["results"].objects()

    suspend fun albumWithTracks(id: String): Pair<FAlbum, List<FTrack>> {
        val r = api("/albums/$id/with-tracks") as JsonObject
        val tracks = (r["volumes"] as? JsonArray).orEmpty()             // volumes — массив дисков, в каждом массив треков
            .flatMap { (it as? JsonArray).orEmpty() }.filterIsInstance<JsonObject>()
        return (album(r) ?: error("Альбом $id недоступен")) to tracks.mapNotNull(::track)
    }

    suspend fun playlistTracks(owner: String, kind: String): List<FTrack> {
        val items = api("/users/$owner/playlists/$kind")["tracks"].objects()
        val full = items.mapNotNull { (it["track"] as? JsonObject)?.let(::track) }
        if (full.size >= items.size * 0.9) return full
        return tracksByIds(items.mapNotNull { it.str("id") })
    }

    suspend fun playlistInfo(owner: String, kind: String) =
        playlist(api("/users/$owner/playlists/$kind", mapOf("rich-tracks" to "false")) as JsonObject) ?: error("Плейлист недоступен")

    suspend fun artistInfo(id: String) = api("/artists/$id/brief-info")

    suspend fun artistTracks(id: String, page: Int?) =
        api("/artists/$id/tracks", mapOf("page" to (page ?: 0), "page-size" to PAGE))["tracks"].objects().mapNotNull(::track)

    suspend fun artistAlbums(id: String) =
        api("/artists/$id/direct-albums", mapOf("page-size" to 100, "sort-by" to "year"))["albums"].objects().mapNotNull(::album)

    // ---- волны: сессии ротора, как в нынешних приложениях Яндекса ----
    // старый /rotor/station/…/tracks без обратной связи от плеера крутит почти одни лайки; сессия подбирает и новое
    private val waveQueues = java.util.concurrent.ConcurrentHashMap<String, List<String>>()   // id сессии → последние треки «id:альбом»

    /** Следующая порция волны и курсор для следующего запроса (id сессии); не вышло с сессией — старый способ. */
    suspend fun wave(station: String, cursor: String?): Pair<List<FTrack>, String?> {
        runCatching {
            val r = if (cursor == null || !waveQueues.containsKey(cursor))
                network.postJson("$API/rotor/session/new", """{"seeds":["$station"],"includeTracksInResponse":true}""", headers())["result"]
            else network.postJson("$API/rotor/session/$cursor/tracks",
                """{"queue":[${waveQueues[cursor]!!.joinToString(",") { "\"$it\"" }}]}""", headers())["result"]
            val sid = r.str("radioSessionId") ?: cursor ?: error("нет id сессии")
            val seq = r["sequence"].objects().mapNotNull { it["track"] as? JsonObject }
            val tracks = seq.mapNotNull(::track)
            if (tracks.isEmpty()) error("пустая порция")
            waveQueues[sid] = seq.mapNotNull { t -> t.str("id")?.let { id -> t["albums"].objects().firstOrNull()?.str("id")?.let { "$id:$it" } ?: id } }
            return tracks to sid
        }.onFailure { Log.w("Yandex", "волна $station: сессия не вышла (${it.message}), старый способ") }
        val tracks = stationTracks(station, cursor?.takeIf { !waveQueues.containsKey(it) })
        return tracks to tracks.lastOrNull()?.id
    }

    suspend fun stationTracks(station: String, lastTrack: String?): List<FTrack> =
        api("/rotor/station/$station/tracks", mapOf("settings2" to "true", "queue" to lastTrack))["sequence"].objects()
            .mapNotNull { (it["track"] as? JsonObject)?.let(::track) }

    suspend fun like(trackId: String, albumId: String?, like: Boolean) {
        val action = if (like) "add-multiple" else "remove"
        api("/users/${uid()}/likes/tracks/$action", mapOf("track-ids" to (albumId?.let { "$trackId:$it" } ?: trackId)), "POST")
    }

    /** kind — albums или artists */
    suspend fun likeEntity(kind: String, id: String, like: Boolean) {
        val action = if (like) "add" else "remove"
        api("/users/${uid()}/likes/$kind/$action", mapOf(kind.removeSuffix("s") + "-id" to id), "POST")
    }

    // ---- Murglar ----

    override suspend fun resolveSourceForUrl(track: FTrack, source: Source): Source {
        if (source.id == "lossless") fileInfo(track)?.let { return it }   // не вышло — дальше обычный MP3 320
        val wanted = source.bitrate.value ?: 320
        val infos = api("/tracks/${track.id}/download-info").objects().filter { it.str("codec") == "mp3" }
        val info = infos.filter { (it.int("bitrateInKbps") ?: 0) <= wanted }.maxByOrNull { it.int("bitrateInKbps") ?: 0 }
            ?: infos.maxByOrNull { it.int("bitrateInKbps") ?: 0 } ?: error(messages.sourceUrlUnavailable)
        val xml = network.getText(info.str("downloadInfoUrl")!!, headers())
        fun tag(name: String) = Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1) ?: error("Нет $name в download-info")
        val path = tag("path")
        val sign = MessageDigest.getInstance("MD5").digest((DOWNLOAD_SALT + path.substring(1) + tag("s")).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return source.copyWithNewUrl("https://${tag("host")}/get-mp3/$sign/${tag("ts")}$path")
    }

    /** Новая отдача файлов: FLAC, если он есть у трека, иначе лучший AAC/MP3 на выбор сервера. Только transport=raw — без шифрования. */
    private suspend fun fileInfo(track: FTrack): Source? = runCatching {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val quality = "lossless"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(FILE_INFO_KEY.toByteArray(), "HmacSHA256")) }
        val msg = "$ts${track.id}$quality${FILE_INFO_CODECS.joinToString("")}raw"
        val sign = Base64.getEncoder().encodeToString(mac.doFinal(msg.toByteArray())).dropLast(1)
        val params = mapOf("ts" to ts, "trackId" to track.id, "quality" to quality,
            "codecs" to FILE_INFO_CODECS.joinToString(","), "transports" to "raw", "sign" to sign)
        val info = network.getJson("$API/get-file-info", params, headers() + ("X-Yandex-Music-Client" to FILE_INFO_CLIENT))["result"]["downloadInfo"]
        val url = info.str("url") ?: info["urls"].arr.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: error("нет url в ответе")
        val codec = info.str("codec").orEmpty()
        // расширение — по контейнеру (важно для загрузок и тегов): flac-mp4 / aac-mp4 лежат в MP4
        val ext = when {
            codec.endsWith("-mp4") -> Extension.M4A
            codec == "flac" -> Extension.FLAC
            "aac" in codec -> Extension.AAC
            else -> Extension.MP3
        }
        val bitrate = if (codec.startsWith("flac")) Bitrate.B_LOSSLESS else bitrateOf(info.int("bitrate"))
        Log.i("Yandex", "file-info ${track.id}: $codec ${info.int("bitrate") ?: "?"} kbps")
        source("lossless", url, ext, bitrate)
    }.onFailure { Log.w("Yandex", "file-info ${track.id}: ${it.message}") }.getOrNull()

    override fun hasLyrics(track: FTrack) = track.extra["lyrics"] != null

    override suspend fun getLyrics(track: FTrack): Lyrics {
        val sync = track.extra["lyrics"] == "sync"
        val ts = (System.currentTimeMillis() / 1000).toString()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(LYRICS_KEY.toByteArray(), "HmacSHA256")) }
        val sign = Base64.getEncoder().encodeToString(mac.doFinal("${track.id}$ts".toByteArray()))
        val r = api("/tracks/${track.id}/lyrics", mapOf("format" to if (sync) "LRC" else "TEXT", "timeStamp" to ts, "sign" to sign))
        val text = network.getText(r.str("downloadUrl") ?: error(messages.trackHasNoLyrics))
        return if (sync) Lrc.parse(text, track.artists, track.title) else Lyrics(text)
    }

    override suspend fun getTracksByMediaIds(mediaIds: List<String>) = tracksByIds(mediaIds.map { MediaId.getIds(it).first() })
}
