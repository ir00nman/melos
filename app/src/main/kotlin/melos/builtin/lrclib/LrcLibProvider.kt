package melos.builtin.lrclib

import com.badmanners.murglar.lib.core.model.tag.Lyrics
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import com.badmanners.murglar.lib.core.network.NetworkMiddleware
import com.badmanners.murglar.lib.core.provider.lyrics.LyricsProvider
import com.badmanners.murglar.lib.core.provider.lyrics.LyricsSearchResult
import com.badmanners.murglar.lib.core.utils.RateLimit
import melos.builtin.getJson
import melos.builtin.long
import melos.builtin.objects
import melos.builtin.str
import melos.lyrics.Lrc
import kotlin.math.abs

// Открытая база синхронизированных текстов lrclib.net — запасной источник для любого сервиса.
class LrcLibProvider(private val network: NetworkMiddleware) : LyricsProvider {
    override val id = "lrclib"
    override val name = "LRCLIB"
    override val rateLimits = emptyList<RateLimit>()

    override suspend fun searchLyrics(track: BaseTrack): LyricsSearchResult? {
        val ua = mapOf("User-Agent" to "Melos/1.0 (https://github.com/melos)")
        val title = track.title.replace(Regex("""\s*[\[(].*?[])]"""), "").replace(Regex("""\s+(feat|ft)\..*$""", RegexOption.IGNORE_CASE), "").trim()
        val artist = (track.artistName ?: "").split(',', '&').first().trim()
        val found = network.getJson("https://lrclib.net/api/search", mapOf("track_name" to title, "artist_name" to artist), ua).objects()
            .ifEmpty { network.getJson("https://lrclib.net/api/search", mapOf("q" to "$artist $title"), ua).objects() }
        val sec = track.durationMs / 1000
        val best = found.sortedBy { o -> (o.long("duration")?.let { abs(it - sec) } ?: 99) + if (o.str("syncedLyrics") == null) 30 else 0 }
            .firstOrNull { o -> sec == 0L || (o.long("duration")?.let { abs(it - sec) <= 4 } ?: false) } ?: return null
        val lyrics = best.str("syncedLyrics")?.let { Lrc.parse(it, track.artists, track.title) }
            ?: best.str("plainLyrics")?.let { Lyrics(it) } ?: return null
        return LyricsSearchResult(name, lyrics)
    }
}
