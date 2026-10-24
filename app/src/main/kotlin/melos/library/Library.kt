package melos.library

import com.badmanners.murglar.lib.core.model.track.BaseTrack
import com.badmanners.murglar.lib.core.utils.MediaId
import melos.core.Paths
import melos.core.json
import melos.host.Log
import melos.host.Services
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
    val history = SnapList("history", 300)

    fun isFavorite(mediaId: String) = mediaId in favorites

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
