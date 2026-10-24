package melos.builtin

import com.badmanners.murglar.lib.core.localization.DefaultMessages
import com.badmanners.murglar.lib.core.localization.Messages
import com.badmanners.murglar.lib.core.localization.RussianMessages
import com.badmanners.murglar.lib.core.model.album.AlbumType
import com.badmanners.murglar.lib.core.model.album.BaseAlbum
import com.badmanners.murglar.lib.core.model.artist.BaseArtist
import com.badmanners.murglar.lib.core.model.playlist.BasePlaylist
import com.badmanners.murglar.lib.core.model.radio.BaseRadio
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import com.badmanners.murglar.lib.core.model.track.source.Bitrate
import com.badmanners.murglar.lib.core.model.track.source.Container
import com.badmanners.murglar.lib.core.model.track.source.Extension
import com.badmanners.murglar.lib.core.model.track.source.QualityTier
import com.badmanners.murglar.lib.core.model.track.source.Source
import com.badmanners.murglar.lib.core.network.NetworkMiddleware
import com.badmanners.murglar.lib.core.network.NetworkRequest
import com.badmanners.murglar.lib.core.network.ResponseConverters
import melos.core.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.threeten.bp.LocalDate
import java.util.Locale

// Сообщения встроенных сервисов: интерфейс нужен BaseMurglar (он делает из него Proxy под текущую локаль).
interface BuiltinMessages : Messages

fun builtinMessages(name: String, nameRu: String = name): Map<Locale, BuiltinMessages> = mapOf(
    Locale.ENGLISH to object : DefaultMessages(), BuiltinMessages { override val serviceName = name },
    RussianMessages.RUSSIAN to object : RussianMessages(), BuiltinMessages { override val serviceName = nameRu },
)

// ---- модели: общие для всех встроенных сервисов ----

class FTrack(
    id: String, title: String, artistIds: List<String>, artistNames: List<String>, durationMs: Long,
    sources: List<Source>, mediaId: String,
    albumId: String? = null, albumName: String? = null, subtitle: String? = null,
    smallCoverUrl: String? = null, bigCoverUrl: String? = null, serviceUrl: String? = null,
    genre: String? = null, explicit: Boolean = false, indexInAlbum: Int? = null, releaseDate: LocalDate? = null,
    val live: Boolean = false,
    val extra: Map<String, String> = emptyMap(),
    gain: String? = null, peak: Double? = null,                     // выравнивание громкости (как в API Murglar)
) : BaseTrack(
    id = id, title = title, subtitle = subtitle, artistIds = artistIds, artistNames = artistNames,
    albumId = albumId, albumName = albumName, albumReleaseDate = releaseDate, indexInAlbum = indexInAlbum,
    durationMs = durationMs, genre = genre, explicit = explicit, gain = gain, peak = peak, sources = sources, mediaId = mediaId,
    smallCoverUrl = smallCoverUrl, bigCoverUrl = bigCoverUrl, serviceUrl = serviceUrl
)

class FAlbum(
    id: String, title: String, artistIds: List<String>, artistNames: List<String>, tracksCount: Int,
    releaseDate: LocalDate? = null, smallCoverUrl: String? = null, bigCoverUrl: String? = null,
    serviceUrl: String? = null, genre: String? = null, type: AlbumType = AlbumType.ALBUM,
) : BaseAlbum(id, title, null, type, artistIds, artistNames, tracksCount, releaseDate, genre, false,
    smallCoverUrl, bigCoverUrl, serviceUrl)

class FArtist(id: String, name: String, smallCoverUrl: String? = null, bigCoverUrl: String? = null, serviceUrl: String? = null) :
    BaseArtist(id, name, smallCoverUrl, bigCoverUrl, serviceUrl)

class FPlaylist(
    id: String, title: String, description: String?, tracksCount: Int,
    smallCoverUrl: String? = null, bigCoverUrl: String? = null, serviceUrl: String? = null,
    val ownerId: String = "",
) : BasePlaylist(id, title, description, tracksCount, false, smallCoverUrl, bigCoverUrl, serviceUrl)

class FRadio(id: String, title: String, description: String? = null, smallCoverUrl: String? = null, bigCoverUrl: String? = null,
             val cursor: String? = null) :
    BaseRadio(id, title, description, emptyList(), smallCoverUrl, bigCoverUrl, null)

fun source(id: String, url: String?, ext: Extension, bitrate: Bitrate, container: Container = Container.PROGRESSIVE): Source {
    val tier = when (bitrate) {
        Bitrate.B_HI_RES -> QualityTier.HI_RES
        Bitrate.B_LOSSLESS -> QualityTier.LOSSLESS
        Bitrate.B_256, Bitrate.B_320 -> QualityTier.HIGH_QUALITY
        Bitrate.B_32, Bitrate.B_64, Bitrate.B_96 -> QualityTier.LOW_QUALITY
        else -> QualityTier.NORMAL_QUALITY
    }
    return Source(id, url, "${ext.value}-${bitrate.value ?: bitrate.name.lowercase()}", ext, container, bitrate, tier)
}

fun bitrateOf(kbps: Int?): Bitrate = when {
    kbps == null -> Bitrate.B_UNKNOWN
    kbps >= 320 -> Bitrate.B_320; kbps >= 256 -> Bitrate.B_256; kbps >= 192 -> Bitrate.B_192
    kbps >= 160 -> Bitrate.B_160; kbps >= 128 -> Bitrate.B_128; kbps >= 96 -> Bitrate.B_96
    kbps >= 64 -> Bitrate.B_64; else -> Bitrate.B_32
}

// ---- JSON и сеть ----

suspend fun NetworkMiddleware.getJson(
    url: String,
    params: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    method: String = "GET",
): JsonElement {
    val req = NetworkRequest.Builder(url, method).apply {
        params.forEach { (k, v) -> if (v != null) addParameter(k, v) }
        headers.forEach { (k, v) -> addHeader(k, v) }
    }.build()
    val resp = execute(req, ResponseConverters.asString())
    check(resp.isSuccessful) { "HTTP ${resp.statusCode}: ${url.substringBefore('?')}" }
    return json.parseToJsonElement(resp.result)
}

suspend fun NetworkMiddleware.postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): JsonElement {
    val req = NetworkRequest.Builder(url, "POST").apply {
        body(body, NetworkRequest.APPLICATION_JSON)
        headers.forEach { (k, v) -> addHeader(k, v) }
    }.build()
    val resp = execute(req, ResponseConverters.asString())
    check(resp.isSuccessful) { "HTTP ${resp.statusCode}: ${url.substringBefore('?')}" }
    return json.parseToJsonElement(resp.result)
}

suspend fun NetworkMiddleware.getText(url: String, headers: Map<String, String> = emptyMap()): String {
    val req = NetworkRequest.Builder(url).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()
    val resp = execute(req, ResponseConverters.asString())
    check(resp.isSuccessful) { "HTTP ${resp.statusCode}: ${url.substringBefore('?')}" }
    return resp.result
}

val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.arr: JsonArray get() = this as? JsonArray ?: JsonArray(emptyList())
fun JsonElement?.str(key: String): String? = (this as? JsonObject)?.get(key)?.let { if (it is JsonPrimitive) it.contentOrNull else null }
fun JsonElement?.long(key: String): Long? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
fun JsonElement?.int(key: String): Int? = long(key)?.toInt()
fun JsonElement?.bool(key: String): Boolean = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull == "true"
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)?.takeIf { it !is JsonNull }
fun JsonElement?.objects(): List<JsonObject> = arr.filterIsInstance<JsonObject>()

fun parseDate(s: String?): LocalDate? = s?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
