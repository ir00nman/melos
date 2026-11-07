package melos

import com.badmanners.murglar.lib.core.model.track.source.Bitrate
import com.badmanners.murglar.lib.core.model.track.source.Container
import com.badmanners.murglar.lib.core.model.track.source.Extension
import com.badmanners.murglar.lib.core.utils.MediaId
import melos.builtin.FTrack
import melos.builtin.source
import melos.search.TrackMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackMatchTest {
    private var n = 0
    private fun t(service: String, artist: String, title: String, sec: Int, version: String? = null, kbps: Bitrate = Bitrate.B_320, ext: Extension = Extension.MP3) =
        FTrack("${++n}", title, listOf("a$n"), listOf(artist), sec * 1000L, listOf(source("s", "x", ext, kbps, Container.PROGRESSIVE)),
            MediaId.build(service, "$n"), subtitle = version)

    @Test fun sameTrackOnAllPlatformsIsOneRowAndFakesGoDown() {
        val ynd = t("ynd", "Daft Punk", "One More Time", 320)
        val vk = t("vk", "Daft Punk", "One More Time", 321)
        val sc = t("sc", "Daft Punk", "Daft Punk - One More Time", 319, kbps = Bitrate.B_128)
        val cover = t("sc", "Some Guy", "One More Time (Daft Punk Cover)", 290, kbps = Bitrate.B_128)
        val remix = t("vk", "Daft Punk", "One More Time", 410, version = "Remix")
        val reupload = t("sc", "Daft Punk", "One More Time", 200, kbps = Bitrate.B_128)       // обрезанный перезалив
        val sped = t("sc", "dj speed", "one more time sped up", 250, kbps = Bitrate.B_128)
        val other = t("vk", "Daft Punk", "Around the World", 429)

        val groups = TrackMatch.group("daft punk one more time", listOf(cover, sc, remix, reupload, sped, other, ynd, vk))
        val top = groups.first()
        assertEquals(setOf("ynd", "vk", "sc"), top.services.toSet(), "один трек со всех площадок — одна строка")
        assertEquals("vk", top.best.serviceId, "без Плюса лучше всех — MP3 320 из VK")
        val order = groups.map { it.versions.first().track }
        assertTrue(order.indexOf(cover) > 0 && order.indexOf(remix) > 0 && order.indexOf(reupload) > 0 && order.indexOf(sped) > 0)
        assertTrue(groups.indexOfFirst { cover in it.versions.map { v -> v.track } } > groups.indexOfFirst { remix in it.versions.map { v -> v.track } } ||
            groups.size > 1, "кавер — ниже")
        assertTrue(groups.last().versions.any { it.track == other || it.track == cover || it.track == sped }, "чужое и подделки — в конце")
    }

    @Test fun userUploadsOfTheSameTitleGoBelowRealVersions() {
        // как на VK: оригинал есть в каталоге (с альбомом), а пользователи залили копии с той же подписью и другой длиной
        val real = FTrack("r", "One More Time", listOf("a"), listOf("Daft Punk"), 320_000, listOf(source("s", "x", Extension.MP3, Bitrate.B_320)),
            MediaId.build("vk", "r"), albumId = "1_2")
        val live = t("ynd", "Daft Punk", "One More Time / Aerodynamic", 370)
        val fakes = listOf(302, 234, 368, 163, 79).map { t("vk", "Daft Punk", "One More Time", it) }
        val groups = TrackMatch.group("daft punk one more time", fakes + live + real)
        assertEquals(real, groups.first().best.track)
        val liveAt = groups.indexOfFirst { g -> g.versions.any { it.track == live } }
        assertTrue(fakes.all { f -> groups.indexOfFirst { g -> g.versions.any { it.track == f } } > liveAt }, "перезаливы — ниже настоящих версий")
    }

    @Test fun otherArtistsAndEditsGoBelow() {
        val real = t("ynd", "Daft Punk", "One More Time", 320)
        val live = t("ynd", "Daft Punk", "One More Time / Aerodynamic", 370)
        val lullaby = t("ynd", "Twinkle Twinkle Little Rock Star", "One More Time", 404, ext = Extension.FLAC, kbps = Bitrate.B_LOSSLESS)
        val edit = t("sc", "Daft Punk x Valmer", "Daft Punk - One More Time (Valmer Edit)", 313, kbps = Bitrate.B_160)
        val radio = t("vk", "Daft Punk", "One More Time (Radio Edit)", 320)
        // перезалив SoundCloud с переставленными исполнителем и названием
        val swapped = t("sc", "One more time", "fred again set (daft punk)", 471, kbps = Bitrate.B_160)
        val groups = TrackMatch.group("daft punk one more time", listOf(lullaby, edit, live, real, radio, swapped))
        fun at(x: FTrack) = groups.indexOfFirst { g -> g.versions.any { it.track == x } }
        assertEquals(at(real), at(radio), "«Radio Edit» той же длины — тот же трек")
        assertEquals(0, at(real))
        assertTrue(at(lullaby) > at(live) && at(edit) > at(live), "чужой исполнитель и эдиты — ниже настоящих версий")
        assertTrue(at(swapped) > at(live), "сет с переставленными полями — ниже настоящих версий")
    }

    @Test fun catalogReferenceFindsTheOriginalAmongUploads() {
        // «alan walker alone»: в Яндексе оригинала нет (трибьюты), в VK — только загрузки (2:40 с опечатками)
        // и «альбомы» с той же подписью другой длины
        fun vkAlbum(artist: String, title: String, sec: Int) = FTrack("${++n}", title, listOf("a"), listOf(artist), sec * 1000L,
            listOf(source("s", "x", Extension.MP3, Bitrate.B_320)), MediaId.build("vk", "$n"), albumId = "1_$n")
        val uploads = listOf(t("vk", "Alan Walker", "Alone [NR]", 160), t("vk", "Allan Walker", "Alone", 161), t("vk", "Alan Walke", "Alone", 162),
            t("sc", "Alan Walker", "Alone", 155, kbps = Bitrate.B_128))
        val fakeAlbums = listOf(vkAlbum("Alan Walker", "Alone", 204), vkAlbum("Alan Walker", "Alone", 271))
        val tribute = t("ynd", "Shannon Nelson", "Alone", 156, version = "Tribute to Alan Walker")
        val other = t("ynd", "LEX STONE, Alan Walker", "ALONE", 187)
        val refs = listOf(melos.search.Reference.Ref(listOf("Alan Walker"), "Alone", 159_000), melos.search.Reference.Ref(listOf("Alan Walker"), "Alone", 161_000))
        val groups = TrackMatch.group("alan walker alone", fakeAlbums + tribute + other + uploads, refs)
        val top = groups.first()
        assertTrue(top.original, "оригинал отмечен")
        assertTrue(top.versions.all { it.track in uploads }, "наверху — загрузки нужной длины, а не «альбомы»")
        assertEquals(setOf("vk", "sc"), top.services.toSet(), "загрузки оригинала (2:35…2:42) — одна строка")
        fun at(x: FTrack) = groups.indexOfFirst { g -> g.versions.any { it.track == x } }
        assertTrue(fakeAlbums.all { at(it) > 0 } && at(tribute) > 0, "подделки и трибьют — ниже")
    }

    @Test fun suggestsAuthorsForBareTitle() {
        val refs = listOf(
            melos.search.Reference.Ref(listOf("Alan Walker"), "Alone", 161_000, "a.jpg"),
            melos.search.Reference.Ref(listOf("Marshmello"), "Alone", 200_000),
            melos.search.Reference.Ref(listOf("Heart"), "Alone", 213_000),
            melos.search.Reference.Ref(listOf("Some Karaoke Band"), "Alone (Karaoke Version)", 160_000),
            melos.search.Reference.Ref(listOf("Alan Walker"), "Alone (Restrung)", 185_000),
        )
        assertEquals(listOf("Alan Walker", "Marshmello", "Heart"), TrackMatch.authors("alone", refs).map { it.first })
        assertEquals(emptyList(), TrackMatch.authors("alan walker alone", refs), "исполнитель уже в запросе")
    }

    @Test fun bareTitlePrefersTheMostPopularOriginal() {
        // «faded»: и Alan Walker, и Zhu — оригиналы; каталоги ставят выше Alan Walker
        val walker = t("vk", "Alan Walker", "Faded", 212)
        val zhu = t("ynd", "ZHU", "Faded", 223, version = "Original Mix")
        val refs = listOf(melos.search.Reference.Ref(listOf("Alan Walker"), "Faded", 212_000, rank = 0),
            melos.search.Reference.Ref(listOf("Zhu"), "Faded", 223_000, rank = 1))
        val groups = TrackMatch.group("faded", listOf(zhu, walker), refs)
        assertEquals(walker, groups.first().best.track)
        assertEquals("Alan Walker", TrackMatch.likelyAuthor("faded", refs))
    }

    @Test fun saysWhenTheOriginalIsNowhere() {
        // «alan walker faded»: на площадках только ремикс и загрузки другой длины
        val remix = t("sc", "Alan Walker", "Faded (SLUSHII Remix)", 211, kbps = Bitrate.B_160)
        val uploads = listOf(t("vk", "Alan Walker", "Faded", 175), t("vk", "Alan Walker", "Faded", 260))
        val refs = listOf(melos.search.Reference.Ref(listOf("Alan Walker"), "Faded", 212_000, rank = 0),
            melos.search.Reference.Ref(listOf("Alan Walker"), "Faded (Slushii Remix)", 211_000, rank = 9))
        val groups = TrackMatch.group("alan walker faded", uploads + remix, refs)
        assertTrue(groups.none { it.original }, "ремикс не выдаём за оригинал")
        assertEquals("Faded", TrackMatch.missingOriginal("alan walker faded", refs, groups)?.title)
        val real = t("vk", "Alan Walker", "Faded", 213)
        assertEquals(null, TrackMatch.missingOriginal("alan walker faded", refs, TrackMatch.group("alan walker faded", uploads + remix + real, refs)))
    }

    @Test fun onlyTheOriginalGetsTheBadge() {
        // «believer»: оригинал Imagine Dragons; версия с Lil Wayne и песня Sybrid с тем же названием — не он
        val orig = listOf(t("ynd", "Imagine Dragons", "Believer", 204), t("vk", "Imagine Dragons", "Believer", 204))
        val wayne = FTrack("w", "Believer", listOf("a", "b"), listOf("Imagine Dragons", "Lil Wayne"), 219_000,
            listOf(source("s", "x", Extension.MP3, Bitrate.B_320)), MediaId.build("ynd", "w"))
        val sybrid = t("ynd", "Sybrid, PLEXXAGLASS", "Believer", 192, version = "feat. Plexxaglass")
        val refs = listOf(
            melos.search.Reference.Ref(listOf("Imagine Dragons"), "Believer", 202_000, rank = 0, source = "deezer"),
            melos.search.Reference.Ref(listOf("Imagine Dragons"), "Believer", 219_000, rank = 2, source = "deezer"),
            melos.search.Reference.Ref(listOf("Imagine Dragons"), "Believer", 204_000, rank = 0, source = "itunes"),
            melos.search.Reference.Ref(listOf("Sybrid"), "Believer (feat. Plexxaglass)", 192_000, rank = 5, source = "itunes"))
        val groups = TrackMatch.group("believer", listOf(sybrid, wayne) + orig, refs)
        assertTrue(groups.first().versions.all { it.track in orig } && groups.first().original)
        assertEquals(1, groups.count { it.original }, "«оригинал» — только у одной строки")
    }

    @Test fun askedVersionWins() {
        val orig = t("ynd", "Daft Punk", "One More Time", 320)
        val remix = t("vk", "Daft Punk", "One More Time", 410, version = "Remix")
        val groups = TrackMatch.group("daft punk one more time remix", listOf(orig, remix))
        assertEquals(remix, groups.first().best.track, "просили ремикс — он первый")
    }

    @Test fun normalizesNoise() {
        assertEquals(TrackMatch.norm("One More Time"), TrackMatch.norm("One More Time (Official Audio) [feat. Romanthony]"))
        assertEquals(setOf("speed"), TrackMatch.tags("Ёлка — Прованс (Sped Up)"))
    }
}
