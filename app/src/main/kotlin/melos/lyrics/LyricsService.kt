package melos.lyrics

import com.badmanners.murglar.lib.core.model.tag.Lyrics
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import melos.host.Log
import melos.host.Services

// Текст: сначала у самого сервиса (как в Murglar), затем у плагинов-поставщиков текстов, первым — LRCLIB.
object LyricsService {
    private val cache = object : LinkedHashMap<String, Lyrics?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics?>?) = size > 64
    }

    suspend fun find(track: BaseTrack): Lyrics? {
        synchronized(cache) { if (cache.containsKey(track.mediaId)) return cache[track.mediaId] }
        val murglar = Services.forTrack(track)?.murglar
        val own = murglar?.takeIf { runCatching { it.hasLyrics(track) }.getOrDefault(false) }
            ?.let { m -> runCatching { m.getLyrics(track) }.onFailure { Log.d("Lyrics", "${m.id}: ${it.message}") }.getOrNull() }
        val result = own?.takeIf { it.hasSynced } ?: Services.lyricsProviders.value.firstNotNullOfOrNull { p ->
            runCatching { p.searchLyrics(track)?.lyrics }.onFailure { Log.d("Lyrics", "${p.id}: ${it.message}") }.getOrNull()
        }?.takeIf { it.hasSynced || own == null } ?: own
        synchronized(cache) { cache[track.mediaId] = result }
        return result
    }
}
