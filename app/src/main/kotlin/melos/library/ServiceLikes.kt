package melos.library

import com.badmanners.murglar.lib.core.model.node.NodeType
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import melos.host.Log
import melos.host.Service
import melos.host.Services
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

// Медиатека из сервисов, как в Murglar: после входа подтягиваем «Мне нравится» каждого сервиса,
// который умеет лайки (likesMapping[TRACK] — путь к списку лайков). Сердечки в списках показывают и их.
object ServiceLikes {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Liked(val service: Service, val tracks: List<BaseTrack>)

    private val _all = MutableStateFlow<List<Liked>>(emptyList()); val all = _all.asStateFlow()
    private val _ids = MutableStateFlow<Set<String>>(emptySet()); val ids = _ids.asStateFlow()
    private val _loading = MutableStateFlow(false); val loading = _loading.asStateFlow()

    fun start() {
        Services.revision.debounce(600).onEach { refresh() }.launchIn(scope)
    }

    fun refresh() = scope.launch {
        _loading.value = true
        val result = Services.all.value.mapNotNull { s -> runCatching { load(s) }.onFailure { Log.w("Likes", "${s.id}: ${it.message}") }.getOrNull() }
        _all.value = result
        _ids.value = result.flatMap { l -> l.tracks.map { it.mediaId } }.toSet()
        _loading.value = false
    }

    private suspend fun load(s: Service): Liked? {
        val r = s.murglar.nodeResolver
        if (!s.murglar.loginResolver.isLogged || !r.supportsLikes) return null
        val path = r.likesMapping[NodeType.TRACK] ?: return null
        val pageable = runCatching { r.getNodeParameters(path).isPageable }.getOrDefault(false)
        val tracks = mutableListOf<BaseTrack>()
        if (!pageable) tracks += r.getNodeContent(path, null).filterIsInstance<BaseTrack>()
        else for (page in 0 until 20) {                                // до 20 страниц — дальше подгрузит экран раздела
            val chunk = r.getNodeContent(path, page).filterIsInstance<BaseTrack>()
            if (chunk.isEmpty()) break
            tracks += chunk
        }
        Log.i("Likes", "${s.id}: ${tracks.size} liked tracks")
        return Liked(s, tracks)
    }

    fun isLiked(mediaId: String) = mediaId in _ids.value

    /** Лайк поставлен/снят в сервисе — держим локальную копию в актуальном виде без перезагрузки. */
    fun mark(track: BaseTrack, liked: Boolean) {
        _ids.value = if (liked) _ids.value + track.mediaId else _ids.value - track.mediaId
        _all.value = _all.value.map { l ->
            if (Services.forTrack(track)?.id != l.service.id) l
            else l.copy(tracks = if (liked) listOf(track) + l.tracks.filter { it.mediaId != track.mediaId } else l.tracks.filter { it.mediaId != track.mediaId })
        }
    }
}
