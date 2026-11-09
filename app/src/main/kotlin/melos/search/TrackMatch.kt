package melos.search

import com.badmanners.murglar.lib.core.model.track.BaseTrack
import com.badmanners.murglar.lib.core.model.track.source.QualityTier
import com.badmanners.murglar.lib.core.utils.MediaId
import melos.builtin.FTrack
import melos.builtin.yandex.YandexMurglar
import melos.host.Services
import kotlin.math.abs

// Общий поиск: один и тот же трек со всех площадок — в одну строку, играет версия в лучшем качестве.
// Тот же трек — это те же исполнители, то же название и та же длительность (±4 с); «версии» (ремикс, кавер,
// караоке, live, sped up…) — отдельные треки. Фальшивки (перезаливы с похожими названиями, каверы, «в стиле»)
// уходят вниз: им не хватает слов из запроса, у них лишние пометки и их нет на других площадках.
// Что считать оригиналом, решает эталон — открытые каталоги лейблов (Reference: Deezer, iTunes): у оригинала
// те же исполнитель, название и длительность. Без эталона опора — каталог площадки и совпадение на нескольких площадках.
object TrackMatch {

    /** Версия трека на одной площадке и качество, которое она реально отдаст (с учётом подписки). */
    class Version(val track: BaseTrack, val serviceId: String, val kbps: Int, val label: String)

    /** original — совпал с эталоном из каталогов лейблов: это именно тот трек. */
    class Group(val versions: List<Version>, val score: Double, val original: Boolean = false) {
        /** Лучшее качество; при равном — площадка с официальным каталогом. */
        val best: Version = versions.maxWith(compareBy<Version> { it.kbps }.thenBy { -rank(it.serviceId) })
        val services: List<String> = versions.map { it.serviceId }.distinct()
    }


    /** null — трек не играет целиком (отрывок Go+, DRM, недоступен): в общий поиск не берём. */
    fun quality(track: BaseTrack): Version? {
        if ((track as? FTrack)?.extra?.get("partial") != null || track.sources.isEmpty()) return null
        val sid = runCatching { MediaId.getServiceId(track.mediaId) }.getOrNull() ?: return null
        if (sid == "ynd") {
            // Яндекс объявляет FLAC у каждого трека, но отдаёт его только с Плюсом; без Плюса — MP3 192
            val plus = (Services.byId("ynd")?.murglar as? YandexMurglar)?.hasPlus == true
            return if (plus) Version(track, sid, 1411, "FLAC") else Version(track, sid, 192, "MP3 192")
        }
        if (sid == "vk") {
            // VK качество не сообщает — измеряем сами (VkMurglar.measure); пока не измерено — «MP3» без числа, в середине
            val k = (Services.byId("vk")?.murglar as? melos.builtin.vk.VkMurglar)?.measuredKbps(track.id)
            return if (k != null) Version(track, sid, k, "MP3 ${standard(k)}") else Version(track, sid, 200, "MP3")
        }
        val src = track.sources.maxWith(compareBy({ it.qualityTier.ordinal }, { it.bitrate.value ?: 0 }))
        val kbps = when (src.qualityTier) {
            QualityTier.HI_RES -> 2300
            QualityTier.LOSSLESS -> 1411
            else -> src.bitrate.value ?: 128
        }
        val ext = src.extension.value.uppercase().let { if (it == "M4A") "AAC" else it }
        return Version(track, sid, kbps, if (kbps >= 1411) ext else "$ext $kbps")
    }

    /** Средний битрейт VBR (258) — к ближайшему привычному (256). */
    private fun standard(k: Int) = listOf(96, 128, 160, 192, 224, 256, 320).minBy { abs(it - k) }

    /** Площадки с официальным каталогом лейблов — выше перезаливов SoundCloud. */
    private fun rank(serviceId: String) = when (serviceId) { "ynd" -> 0; "vk" -> 1; "sc" -> 3; else -> 2 }

    /**
     * Версия из каталога лейбла, а не загрузка пользователя: в Яндексе — всё; в VK — только трек альбома
     * (пользовательские загрузки VK без альбома и обложки); SoundCloud — сплошь загрузки, не считаем.
     */
    private fun official(v: Version) = v.serviceId == "ynd" || (v.serviceId == "vk" && v.track.albumId != null)


    /** Пометки «версии»: трек с ними — уже не тот, что искали (если их нет в запросе). */
    private val TAGS: Map<String, List<String>> = mapOf(
        "remix" to listOf("remix", "rmx", "ремикс", "rework", "flip", "bootleg", "mashup", "мэшап", "vip mix", "vip", "edit", "re edit", "rerub"),
        "cover" to listOf("cover", "кавер", "tribute", "in the style of", "originally performed", "made famous", "по мотивам"),
        "karaoke" to listOf("karaoke", "караоке", "instrumental", "инструментал", "минус", "minus", "backing track", "acapella", "a cappella"),
        "live" to listOf("live", "концерт", "unplugged", "live at"),
        "acoustic" to listOf("acoustic", "акустика", "акустическая", "piano version"),
        "speed" to listOf("sped up", "speed up", "speedup", "slowed", "reverb", "nightcore", "daycore", "8d", "bass boosted", "tiktok"),
        "extended" to listOf("extended", "club mix", "dub mix"),
    )
    private val tagRegex = TAGS.mapValues { (_, words) -> Regex("(?U)\\b(" + words.joinToString("|") { Regex.escape(it) } + ")\\b") }

    // скобки, которые к самому треку отношения не имеют
    private val NOISE = Regex("(?U)[(\\[]\\s*(official|lyrics?|lyric video|audio|video|music video|hq|hd|4k|explicit|clean|premiere|original mix|original|remaster(ed)?( \\d{4})?|\\d{4} remaster(ed)?|mono|stereo|free download|radio edit|single version|album version|radio version)[^)\\]]*[)\\]]")
    private val FEAT = Regex("(?U)[(\\[]\\s*(feat|ft|featuring|with|при участии|при уч)\\.?\\s[^)\\]]*[)\\]]|\\s(feat|ft|featuring)\\.?\\s.*$")

    fun norm(s: String): String = s.lowercase().replace('ё', 'е')
        .replace(NOISE, " ").replace(FEAT, " ")
        .replace(Regex("(?U)[^\\p{L}\\p{N}]+"), " ").trim()

    private fun tokens(s: String) = norm(s).split(' ').filter { it.isNotEmpty() }

    // «(Radio Edit)», «(Album Version)» — тот же трек: шум убираем до поиска пометок, иначе «edit» сочтётся ремиксом
    fun tags(s: String): Set<String> { val n = s.lowercase().replace('ё', 'е').replace(NOISE, " "); return tagRegex.filterValues { it.containsMatchIn(n) }.keys }

    /** Название без исполнителя в начале («Daft Punk - One More Time» на SoundCloud), без пометок версии. */
    private val SHORT = Regex("(?U)[(\\[]\\s*[\\p{L}\\p{N}]{1,3}\\s*[)\\]]")      // «[NR]», «(1)» — служебные пометки загрузивших

    private fun core(title: String, artists: List<String>): String {
        var t = norm(title.replace(SHORT, " "))
        for (a in artists.map(::norm).filter { it.isNotEmpty() }) if (t.startsWith("$a ")) t = t.removePrefix("$a ").trim()
        for (words in TAGS.values) for (w in words) t = t.replace(Regex("(?U)\\b${Regex.escape(w)}\\b"), " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private fun artists(names: List<String>): Set<String> = names
        .flatMap { it.split(Regex("(?iU)\\s*(,|&|/|\\bx\\b|×|\\bfeat\\.?|\\bft\\.?|\\band\\b|\\bи\\b)\\s*")) }
        .map(::norm).filter { it.isNotEmpty() }.toSet()

    /** Тот же исполнитель — и с опечаткой загрузившего («Allan Walker», «Alan Walke»). */
    private fun sameArtists(a: Set<String>, b: Set<String>) =
        a.isEmpty() || b.isEmpty() || a.any { x -> b.any { y -> x == y || (x.length >= 4 && y.length >= 4 && (x.contains(y) || y.contains(x))) || typo(x, y) } }

    private fun typo(x: String, y: String): Boolean {
        val n = minOf(x.length, y.length)
        if (n < 5 || abs(x.length - y.length) > 2) return false
        val d = Array(x.length + 1) { IntArray(y.length + 1) }
        for (i in 0..x.length) d[i][0] = i
        for (j in 0..y.length) d[0][j] = j
        for (i in 1..x.length) for (j in 1..y.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1)
        return d[x.length][y.length] <= if (n >= 9) 2 else 1
    }

    private fun sameLength(a: Long, b: Long) = a <= 0 || b <= 0 || abs(a - b) <= maxOf(4000L, minOf(a, b) * 3 / 100)


    /**
     * Ввели только название — исполнители песен с таким названием из каталогов лейблов (без каверов и караоке),
     * чтобы уточнить запрос и не попасть на подделку. Исполнитель уже есть в запросе — подсказывать нечего.
     */
    fun authors(query: String, refs: List<Reference.Ref>): List<Pair<String, String?>> {
        val q = norm(query)
        if (q.length < 2) return emptyList()
        val qTokens = q.split(' ').toSet()
        val songs = refs.filter { r ->
            val c = core(r.title, r.artists)
            (c == q || (q.length >= 3 && c.startsWith("$q "))) && (tags(r.title) - tags(query)).isEmpty()
        }
        if (songs.any { r -> artists(r.artists).any { a -> a.split(' ').all { it in qTokens } } }) return emptyList()
        return songs.sortedBy { it.rank }.distinctBy { r -> artists(r.artists).firstOrNull() ?: "" }
            .map { r -> r.artists.joinToString(", ") to r.picture }.take(8)
    }

    /**
     * Ввели только название: самый популярный в каталогах исполнитель этой песни («faded» → Alan Walker).
     * Площадки по одному названию оригинал часто не находят — по «исполнитель + название» ищем ещё раз.
     */
    fun likelyAuthor(query: String, refs: List<Reference.Ref>): String? = authors(query, refs).firstOrNull()?.first

    /**
     * Самый популярный в каталогах трек под запрос (та версия, что ищут), которого нет ни в одной строке, — или null.
     * Тогда честно пишем, что оригинала на площадках нет, а не выдаём за него похожую подделку.
     */
    fun missingOriginal(query: String, refs: List<Reference.Ref>, groups: List<Group>): Reference.Ref? {
        val q = tokens(query).toSet()
        val qTags = tags(query)
        val want = refs.sortedBy { it.rank }.firstOrNull { r ->
            tags(r.title) == qTags && core(r.title, r.artists).split(' ').filter { it.isNotEmpty() }.all { it in q } &&
                (artists(r.artists).isEmpty() || artists(r.artists).any { a -> a.split(' ').all { it in q } } || authors(query, refs).isNotEmpty())
        } ?: return null
        val w = item(null, want.title, null, want.artists, want.durationMs)
        return want.takeIf { groups.none { g -> g.versions.any { v -> sameTrack(w, item(null, v.track.title, v.track.subtitle, v.track.artistNames, v.track.durationMs)) } } }
    }


    private class Item(val v: Version?, val core: String, val tags: Set<String>, val artists: Set<String>, val durationMs: Long)

    private fun item(v: Version?, title: String, version: String?, artistNames: List<String>, durationMs: Long) =
        Item(v, core("$title ${version ?: ""}", artistNames), tags("$title ${version ?: ""}"), artists(artistNames), durationMs)

    private fun sameTrack(a: Item, b: Item) =
        a.core == b.core && a.tags == b.tags && sameArtists(a.artists, b.artists) && sameLength(a.durationMs, b.durationMs)

    /** Треки с площадок → строки общего поиска по убыванию «это тот трек»; внутри строки — версии по качеству. */
    fun group(query: String, found: List<BaseTrack>, refs: List<Reference.Ref> = emptyList()): List<Group> {
        val items = found.mapNotNull { t -> quality(t)?.let { v -> item(v, t.title, t.subtitle, t.artistNames, t.durationMs) } }
        val reference = refs.map { r -> item(null, r.title, null, r.artists, r.durationMs) }
        // совпавшие с одним эталоном — одна строка, даже если загрузки расходятся между собой на 5–6 с
        fun refOf(x: Item) = reference.indexOfFirst { r -> sameTrack(r, x) }
        val buckets = mutableListOf<MutableList<Item>>()
        for (it in items) {
            val ref = refOf(it)
            val home = buckets.firstOrNull { b -> if (ref >= 0) refOf(b.first()) == ref || sameTrack(b.first(), it) else sameTrack(b.first(), it) }
            if (home != null) home += it else buckets += mutableListOf(it)
        }
        // одна и та же площадка дважды в строке — оставляем лучшую версию
        val dedup = buckets.map { b -> b.groupBy { it.v!!.serviceId }.values.map { same -> same.maxBy { it.v!!.kbps } } }

        // эталон: тот же трек — оригинал; то же название у того же исполнителя, но другая длина — подделка или обрезка
        fun original(b: List<Item>) = reference.any { r -> sameTrack(r, b.first()) }
        // сам оригинал — самая популярная в каждом каталоге версия песни из запроса (без пометок, которых не просили);
        // остальные совпадения с каталогом (feat.-версии, другие песни с тем же названием) — настоящие, но не «тот самый»
        val qSet = tokens(query).toSet()
        val wanted = refs.filter { r -> tags(r.title) == tags(query) && core(r.title, r.artists).split(' ').filter { it.isNotEmpty() }.all { it in qSet } }
        val named = wanted.filter { r -> artists(r.artists).any { a -> a.split(' ').all { it in qSet } } }
        val pool = named.ifEmpty { wanted }
        val canonical = pool.filter { r -> r.rank == pool.filter { it.source == r.source }.minOf { it.rank } }
            .map { r -> item(null, r.title, null, r.artists, r.durationMs) }
        val canonicalArtists = canonical.flatMap { it.artists }.toSet()
        fun isCanonical(b: List<Item>) = canonical.any { sameTrack(it, b.first()) }
        // чем выше в каталоге совпавший эталон, тем вероятнее это тот трек, что ищут по одному названию
        fun popularity(b: List<Item>) = refs.indices.filter { sameTrack(reference[it], b.first()) }.minOfOrNull { refs[it].rank }
            ?.let { 0.4 * (1 - minOf(it, 8) / 8.0) } ?: 0.0
        fun contradicts(b: List<Item>) = !original(b) && reference.any { r -> r.core == b.first().core && r.tags == b.first().tags && sameArtists(r.artists, b.first().artists) }
        fun confirmed(b: List<Item>) = original(b) ||
            (!contradicts(b) && (b.any { official(it.v!!) } || b.map { it.v!!.serviceId }.distinct().size > 1))

        val q = tokens(query)
        val qTags = tags(query)
        fun hit(token: String, pool: List<String>) = pool.any { it == token || (token.length >= 3 && it.startsWith(token)) || (it.length >= 3 && token.startsWith(it)) }
        // какая доля запроса — имя исполнителя: у трека другого исполнителя (кавер, колыбельная «в стиле») она меньше
        fun artistShare(artists: Set<String>) = if (q.isEmpty()) 0.0 else q.count { tok -> hit(tok, artists.flatMap { a -> a.split(' ') }) }.toDouble() / q.size
        // планку задают эталон и подтверждённые версии: у загрузок SoundCloud исполнитель и название бывают переставлены
        val topArtistShare = (reference.map { it.artists } + dedup.filter(::confirmed).map { b -> b.flatMap { it.artists }.toSet() })
            .ifEmpty { dedup.map { b -> b.flatMap { it.artists }.toSet() } }.maxOfOrNull(::artistShare) ?: 0.0
        val anyConfirmed = dedup.any(::confirmed)
        return dedup.map { b ->
            val h = b.first()
            val t = h.v!!.track
            val cand = tokens(t.artistNames.joinToString(" ") + " " + t.title + " " + (t.subtitle ?: ""))
            val coverage = if (q.isEmpty()) 0.0 else q.count { hit(it, cand) }.toDouble() / q.size
            val otherArtist = if (topArtistShare > 0 && artistShare(b.flatMap { it.artists }.toSet()) < topArtistShare * 0.75) 0.5 else 0.0
            // лишние слова в названии, которых нет в запросе («One More Time (prod. кто-то) [cover]»)
            val title = h.core.split(' ').filter { it.isNotEmpty() }
            val precision = if (title.isEmpty()) 0.0 else title.count { hit(it, q) || q.isEmpty() }.toDouble() / title.size
            // пометки версии, которых не просили (кавер, sped up…), и запрошенные, которых нет (искали ремикс — оригинал ниже)
            val wrongVersion = ((h.tags - qTags).size + (qTags - h.tags).size) * 0.45
            val services = b.map { it.v!!.serviceId }.distinct()
            val isOriginal = original(b)
            val ok = confirmed(b)
            val support = (services.size - 1) * 0.15 + (if (isOriginal) 0.6 + popularity(b) else 0.0) +
                (if (!contradicts(b) && b.any { official(it.v!!) }) 0.2 else 0.0)
            // подделка или обрезка: эталон или подтверждённый трек с той же подписью — другой длительности
            val fake = !ok && (contradicts(b) || dedup.any { ob -> ob !== b && confirmed(ob) && ob.first().core == h.core && ob.first().tags == h.tags && sameArtists(ob.first().artists, h.artists) })
            // ничем не подтверждённая загрузка, когда подтверждённые версии есть, — чуть ниже
            val unverified = !ok && anyConfirmed
            // не тот исполнитель, что у оригинала (другая песня с этим названием), или лишние, которых не просили (feat.-версия)
            val notCanonical = when {
                canonicalArtists.isEmpty() -> 0.0
                !sameArtists(h.artists, canonicalArtists) -> 0.4
                h.artists.any { a -> !sameArtists(setOf(a), canonicalArtists) && !a.split(' ').all { it in qSet } } -> 0.3
                else -> 0.0
            }
            val score = coverage + precision * 0.4 + support - wrongVersion - otherArtist - notCanonical -
                (if (fake) 0.6 else 0.0) - (if (unverified) 0.15 else 0.0) + (if (isCanonical(b)) 0.3 else 0.0)
            // «оригинал» — только сам оригинал: самая популярная версия песни, без пометок, которых не просили
            Group(b.map { it.v!! }, score, isCanonical(b) && h.tags == qTags)
        }.sortedWith(compareByDescending<Group> { it.score }.thenByDescending { it.best.kbps })
    }
}
