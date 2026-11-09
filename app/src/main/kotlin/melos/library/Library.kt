package melos.library

import com.badmanners.murglar.lib.core.model.track.BaseTrack
import com.badmanners.murglar.lib.core.utils.MediaId
import melos.core.Paths
import melos.core.json
import melos.host.Log
import melos.host.Services
import melos.search.TrackMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

// Лёгкий слепок трека: чтобы показать список без сети, а играть — после getTracksByMediaIds у сервиса.
@Serializable
data class Snap(
    val mediaId: String,
    val service: String,
    val title: String,
    val artists: String,
    val album: String? = null,
    val cover: String? = null,
    val durationMs: Long = 0,
    val at: Long = System.currentTimeMillis(),
)

fun BaseTrack.snap(service: String = Services.forTrack(this)?.id ?: MediaId.getServiceId(mediaId)) =
    Snap(mediaId, service, fullTitle, artists, albumName, smallCoverUrl ?: bigCoverUrl, durationMs)

class SnapList(name: String, private val limit: Int) {
    private val file = Paths.file("$name.json")
    private val _items = MutableStateFlow(runCatching {
        json.decodeFromString(ListSerializer(Snap.serializer()), file.readText())
    }.getOrDefault(emptyList()))
    val items: StateFlow<List<Snap>> = _items.asStateFlow()

    operator fun contains(mediaId: String) = _items.value.any { it.mediaId == mediaId }

    fun addFirst(s: Snap) = update { (listOf(s) + it.filter { o -> o.mediaId != s.mediaId }).take(limit) }
    fun remove(mediaId: String) = update { it.filter { o -> o.mediaId != mediaId } }
    fun clear() = update { emptyList() }

    private fun update(f: (List<Snap>) -> List<Snap>) {
        _items.value = f(_items.value)
        runCatching { file.writeText(json.encodeToString(ListSerializer(Snap.serializer()), _items.value)) }
    }
}

object Library {
    val favorites = SnapList("favorites", 5000)

    // последний удачный состав Медиатеки (лайки + избранное) — чтобы без сети показать хоть что-то
    private val offlineFile = Paths.file("library-offline.json")
    val offlineSnaps = MutableStateFlow(runCatching {
        json.decodeFromString(ListSerializer(Snap.serializer()), offlineFile.readText())
    }.getOrDefault(emptyList()))

    fun cacheOffline(snaps: List<Snap>) {
        if (snaps.isEmpty()) return
        offlineSnaps.value = snaps
        runCatching { offlineFile.writeText(json.encodeToString(ListSerializer(Snap.serializer()), snaps)) }
    }

    /** Кто может собрать играемый трек из скачанного файла (LocalMurglar на десктопе). */
    var offlineTrackBuilder: ((Snap) -> BaseTrack?)? = null

    /** Строка Медиатеки отвечает запросу: все слова — в названии, исполнителе или альбоме. */
    fun matches(snap: Snap, query: String): Boolean {
        val words = query.trim().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = TrackMatch.norm("${snap.title} ${snap.artists} ${snap.album ?: ""}")
        return words.all { TrackMatch.norm(it) in hay }
    }
    val history = SnapList("history", 300)
    /** «Не нравится»: такие треки пропускаются в волнах и при переходе по очереди. */
    val dislikes = SnapList("dislikes", 5000)

    fun isFavorite(mediaId: String) = mediaId in favorites

    /** Тот же трек на любой площадке: по исполнителям и названию (дизлайк в VK — и в Яндексе, и в SoundCloud). */
    internal fun songKey(artists: String, title: String) = melos.search.TrackMatch.norm(artists) + "|" + melos.search.TrackMatch.norm(title)

    fun isDisliked(track: BaseTrack): Boolean {
        val list = dislikes.items.value
        if (list.isEmpty()) return false
        val key = songKey(track.artists, track.fullTitle)
        return list.any { it.mediaId == track.mediaId || songKey(it.artists, it.title) == key }
    }

    /** Слепки → живые треки, по сервисам, в исходном порядке. Недоступные молча пропускаются. */
    suspend fun resolve(snaps: List<Snap>): List<BaseTrack> {
        val found = mutableMapOf<String, BaseTrack>()
        snaps.groupBy { it.service }.forEach { (service, list) ->
            val s = Services.byId(service) ?: return@forEach
            runCatching { s.murglar.nodeResolver.getTracksByMediaIds(list.map { it.mediaId }) }
                .onFailure { Log.w("Library", "resolve $service: ${it.message}") }
                .getOrNull()?.forEach { found[it.mediaId] = it }
        }
        return snaps.mapNotNull { found[it.mediaId] }
    }
}
