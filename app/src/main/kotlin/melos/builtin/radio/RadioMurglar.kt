package melos.builtin.radio

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.login.NoLoginResolver
import com.badmanners.murglar.lib.core.model.node.Directory as DirNode
import com.badmanners.murglar.lib.core.model.node.NodeParameters.PagingType.ENDLESSLY_PAGEABLE
import com.badmanners.murglar.lib.core.model.node.NodeParameters.PagingType.NON_PAGEABLE
import com.badmanners.murglar.lib.core.model.node.NodeType.NODE
import com.badmanners.murglar.lib.core.model.node.NodeType.TRACK
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import com.badmanners.murglar.lib.core.model.track.source.Bitrate
import com.badmanners.murglar.lib.core.model.track.source.Container
import com.badmanners.murglar.lib.core.model.track.source.Extension
import com.badmanners.murglar.lib.core.model.track.source.QualityTier
import com.badmanners.murglar.lib.core.model.track.source.Source
import com.badmanners.murglar.lib.core.model.track.source.SourceFormat
import com.badmanners.murglar.lib.core.network.NetworkMiddleware
import com.badmanners.murglar.lib.core.notification.NotificationMiddleware
import com.badmanners.murglar.lib.core.node.BaseNodeResolver
import com.badmanners.murglar.lib.core.node.Directory
import com.badmanners.murglar.lib.core.node.Root
import com.badmanners.murglar.lib.core.node.Search
import com.badmanners.murglar.lib.core.node.Track
import com.badmanners.murglar.lib.core.preference.PreferenceMiddleware
import com.badmanners.murglar.lib.core.service.BaseMurglar
import com.badmanners.murglar.lib.core.utils.MediaId
import com.badmanners.murglar.lib.core.utils.MurglarLibUtils.urlDecode
import com.badmanners.murglar.lib.core.utils.MurglarLibUtils.urlEncode
import melos.builtin.BuiltinMessages
import melos.builtin.FTrack
import melos.builtin.bitrateOf
import melos.builtin.builtinMessages
import melos.builtin.getJson
import melos.builtin.int
import melos.builtin.objects
import melos.builtin.source
import melos.builtin.str
import kotlinx.serialization.json.JsonObject

// Интернет-радио из открытого каталога radio-browser.info: 50 000+ станций, VLC играет любые потоки.
class RadioMurglar(
    id: String,
    preferences: PreferenceMiddleware,
    network: NetworkMiddleware,
    notifications: NotificationMiddleware,
    logger: LoggerMiddleware,
) : BaseMurglar<FTrack, BuiltinMessages>(id, MESSAGES, preferences, network, notifications, logger) {

    companion object {
        private val MESSAGES = builtinMessages("Internet radio", "Интернет-радио")
        private const val API = "https://all.api.radio-browser.info/json"
        const val PAGE = 60
    }

    override val loginResolver = NoLoginResolver()
    override val nodeResolver = RadioNodeResolver(this, messages)
    override val possibleFormats = listOf(SourceFormat(Extension.MP3, Bitrate.B_128, QualityTier.NORMAL_QUALITY, SourceFormat.SourceFormatAvailability.AVAILABLE))

    private suspend fun api(path: String, params: Map<String, Any?> = emptyMap()) =
        network.getJson(API + path, params, mapOf("User-Agent" to "Melos/1.0"))

    fun station(o: JsonObject): FTrack? {
        val uuid = o.str("stationuuid") ?: return null
        val url = (o.str("url_resolved") ?: o.str("url"))?.takeIf { it.isNotBlank() } ?: return null
        val codec = o.str("codec").orEmpty().lowercase()
        val ext = when { "mp3" in codec -> Extension.MP3; "aac" in codec -> Extension.AAC; "ogg" in codec -> Extension.OGG; "opus" in codec -> Extension.OPUS; else -> Extension.UNKNOWN }
        val container = if (o.int("hls") == 1 || url.contains(".m3u8")) Container.HLS else Container.PROGRESSIVE
        val country = o.str("country").orEmpty()
        val tags = o.str("tags").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3)
        return FTrack(
            id = uuid, title = o.str("name")?.trim() ?: "?",
            artistIds = emptyList(), artistNames = listOf(listOf(country).plus(tags).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Радио" }),
            durationMs = 0, sources = listOf(source("stream", url, ext, bitrateOf(o.int("bitrate")?.takeIf { it > 0 }), container)),
            mediaId = MediaId.build(this.id, uuid),
            smallCoverUrl = o.str("favicon")?.takeIf { it.startsWith("http") }, bigCoverUrl = o.str("favicon")?.takeIf { it.startsWith("http") },
            serviceUrl = o.str("homepage"), genre = tags.firstOrNull(), live = true,
        )
    }

    private fun order() = mapOf("order" to "clickcount", "reverse" to "true", "hidebroken" to "true")

    suspend fun top(by: String, page: Int?) = api("/stations/$by", mapOf("limit" to PAGE, "offset" to (page ?: 0) * PAGE, "hidebroken" to "true")).objects().mapNotNull(::station)
    suspend fun search(q: String, page: Int?) = api("/stations/search", mapOf("name" to q, "limit" to PAGE, "offset" to (page ?: 0) * PAGE) + order()).objects().mapNotNull(::station)
    suspend fun byCountry(code: String, page: Int?) = api("/stations/bycountrycodeexact/$code", mapOf("limit" to PAGE, "offset" to (page ?: 0) * PAGE) + order()).objects().mapNotNull(::station)
    suspend fun byTag(tag: String, page: Int?) = api("/stations/bytagexact/${tag.urlEncode().replace("+", "%20")}", mapOf("limit" to PAGE, "offset" to (page ?: 0) * PAGE) + order()).objects().mapNotNull(::station)

    suspend fun countries(): List<Pair<String, String>> = api("/countries", mapOf("order" to "stationcount", "reverse" to "true", "hidebroken" to "true")).objects()
        .filter { (it.int("stationcount") ?: 0) >= 5 }
        .mapNotNull { c -> c.str("iso_3166_1")?.let { it to "${c.str("name")} · ${c.int("stationcount")}" } }

    suspend fun tags(): List<Pair<String, String>> = api("/tags", mapOf("order" to "stationcount", "reverse" to "true", "limit" to 80, "hidebroken" to "true")).objects()
        .mapNotNull { t -> t.str("name")?.takeIf { it.isNotBlank() && '/' !in it }?.let { it to "$it · ${t.int("stationcount")}" } }

    suspend fun byIds(ids: List<String>) = ids.mapNotNull { uuid -> api("/stations/byuuid/$uuid").objects().firstOrNull()?.let(::station) }

    override suspend fun resolveSourceForUrl(track: FTrack, source: Source) = source
    override suspend fun getTracksByMediaIds(mediaIds: List<String>) = byIds(mediaIds.map { MediaId.getIds(it).first() })
}

class RadioNodeResolver(murglar: RadioMurglar, messages: BuiltinMessages) : BaseNodeResolver<RadioMurglar, BuiltinMessages>(murglar, messages) {
    override val configurations = listOf(
        Root("popular", { "Популярные" }, ENDLESSLY_PAGEABLE, false, false, TRACK,
            nodeContentSupplier = { p, page, _ -> murglar.top("topclick", page).convertTracks(p) }),
        Root("loved", { "Любимые слушателями" }, ENDLESSLY_PAGEABLE, false, false, TRACK,
            nodeContentSupplier = { p, page, _ -> murglar.top("topvote", page).convertTracks(p) }),
        Root("countries", { "Страны" }, NON_PAGEABLE, true, false, NODE,
            nodeContentSupplier = { p, _, _ -> murglar.countries().map { (code, name) -> node(DirNode("country-$code", name), p.child("country-$code")) } }),
        Root("genres", { "Жанры" }, NON_PAGEABLE, true, false, NODE,
            nodeContentSupplier = { p, _, _ -> murglar.tags().map { (tag, name) -> val id = "tag-${tag.urlEncode()}"; node(DirNode(id, name), p.child(id)) } }),
        Search("searchStations", { "Поиск станций" }, hasSubdirectories = false, contentNodeType = TRACK,
            nodeContentSupplier = { p, page, params -> murglar.search(params.getQuery(), page).convertTracks(p) }),
        Directory("*/country-<code>", ENDLESSLY_PAGEABLE, false, TRACK,
            nodeContentSupplier = { p, page, params -> murglar.byCountry(params["code"]!!, page).convertTracks(p) }),
        Directory("*/tag-<tag>", ENDLESSLY_PAGEABLE, false, TRACK,
            nodeContentSupplier = { p, page, params -> murglar.byTag(params["tag"]!!.urlDecode(), page).convertTracks(p) }),
        Track("*/track-<id>", nodeSupplier = { p, params -> murglar.byIds(listOf(params["id"]!!)).first().convertTrack(p) }),
    )

    override val isAvailable = true

    @Suppress("UNCHECKED_CAST")
    override suspend fun getTracksByMediaIds(mediaIds: List<String>): List<BaseTrack> =
        murglar.getTracksByMediaIds(mediaIds).convertTracks(unmappedPath()) as List<BaseTrack>
}
