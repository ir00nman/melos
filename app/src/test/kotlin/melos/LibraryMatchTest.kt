package melos

import melos.library.Library
import melos.library.Snap
import melos.search.TrackMatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Поиск в Медиатеке: нормализация Ё/ё и регистров, слова запроса по всему «заголовок+исполнитель+альбом».
class LibraryMatchTest {
    private fun s(title: String, artists: String, album: String? = null) =
        Snap("id", "test", title, artists, album)

    @Test
    fun `нормализация приводит ё и регистр`() {
        assertEquals("елка", TrackMatch.norm("Ёлка"))
    }

    @Test
    fun `все слова запроса должны найтись`() {
        val snap = s("Lost in the Echo", "Linkin Park", album = "LIVING THINGS")
        assertTrue(Library.matches(snap, "linkin"))
        assertTrue(Library.matches(snap, "echo park"))
        assertTrue(Library.matches(snap, "living echo"))
        assertFalse(Library.matches(snap, "eminem"))
    }

    @Test
    fun `ё в запросе совпадает с е в треке`() {
        val snap = s("Елка", "Иван Дорн")
        assertTrue(Library.matches(snap, "ёлка"))
        assertTrue(Library.matches(snap, "ЕЛКА дорн"))
    }

    @Test
    fun `пустой запрос соответствует всему`() {
        assertTrue(Library.matches(s("Любой", "Трек"), ""))
        assertTrue(Library.matches(s("Любой", "Трек"), "   "))
    }
}
