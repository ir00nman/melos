package melos.search

import melos.builtin.get
import melos.builtin.getJson
import melos.builtin.long
import melos.builtin.objects
import melos.builtin.str
import melos.host.HttpNetwork
import melos.host.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

// Эталон для общего поиска: открытые каталоги лейблов (Deezer, iTunes — без ключей и входа) знают, какой трек
// настоящий и сколько он длится. Наши площадки бывают без лицензии (тогда там только перезаливы) — эталон
// помогает отличить оригинал от подделки с той же подписью.
object Reference {
    /** rank — место в выдаче каталога: каталоги ставят выше самое популярное — обычно это и есть оригинал. */
    class Ref(val artists: List<String>, val title: String, val durationMs: Long, val picture: String? = null, val rank: Int = 0, val source: String = "")

    private val cache = ConcurrentHashMap<String, List<Ref>>()

    suspend fun lookup(query: String): List<Ref> {
        val q = query.trim().lowercase()
        cache[q]?.let { return it }
        val refs = coroutineScope {
            val deezer = async { runCatching { deezer(q) }.onFailure { Log.w("Reference", "deezer: ${it.message}") }.getOrDefault(emptyList()) }
            val itunes = async { runCatching { itunes(q) }.onFailure { Log.w("Reference", "itunes: ${it.message}") }.getOrDefault(emptyList()) }
            deezer.await() + itunes.await()
        }
        if (refs.isNotEmpty()) cache[q] = refs
        return refs
    }

    private suspend fun deezer(q: String) = HttpNetwork.getJson("https://api.deezer.com/search", mapOf("q" to q, "limit" to 15))["data"].objects()
        .mapIndexedNotNull { i, o -> Ref(listOfNotNull(o["artist"].str("name")), o.str("title") ?: return@mapIndexedNotNull null, (o.long("duration") ?: 0) * 1000, o["artist"].str("picture_medium"), i, "deezer") }

    private suspend fun itunes(q: String) = HttpNetwork.getJson("https://itunes.apple.com/search", mapOf("term" to q, "entity" to "song", "limit" to 15))["results"].objects()
        .mapIndexedNotNull { i, o -> Ref(listOfNotNull(o.str("artistName")), o.str("trackName") ?: return@mapIndexedNotNull null, o.long("trackTimeMillis") ?: 0, o.str("artworkUrl100"), i, "itunes") }
}
