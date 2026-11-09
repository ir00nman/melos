package melos

import melos.download.DownloadIndex
import melos.download.Downloads
import melos.library.Snap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.junit.jupiter.api.Test

// Индекс загрузок и правила имён: переживает перезапуск, выбрасывает пропавшие файлы,
// разруливает коллизии имён без подпапок.
class DownloadIndexTest {
    @TempDir lateinit var tmp: File

    private fun snap(id: String) = Snap(id, "test", "Трек $id", "Исполнитель", album = "Альбом")

    private fun fresh() {
        DownloadIndex.file = File(tmp, "download-index.json")
        DownloadIndex.reload()
    }

    @Test
    fun `запись переживает перечитывание с диска`() {
        fresh()
        val f = File(tmp, "Исполнитель - Трек 1.mp3").apply { writeText("x") }
        DownloadIndex.add("ynd:1", f, snap("ynd:1"))
        DownloadIndex.reload()
        assertEquals(f.path, DownloadIndex.get("ynd:1")?.path)
        assertTrue(DownloadIndex.has("ynd:1"))
    }

    @Test
    fun `пропавший файл выбрасывается`() {
        fresh()
        val f = File(tmp, "gone.mp3").apply { writeText("x") }
        DownloadIndex.add("ynd:2", f, snap("ynd:2"))
        f.delete()
        assertNull(DownloadIndex.get("ynd:2"))
        assertFalse(DownloadIndex.has("ynd:2"))
        assertTrue(DownloadIndex.all().isEmpty())
    }

    @Test
    fun `имя занято тем же треком - то же имя, чужим - суффикс`() {
        fresh()
        val same = File(tmp, "Artist - Song.mp3").apply { writeText("a") }
        DownloadIndex.add("ynd:same", same, snap("ynd:same"))
        // тот же mediaId — имя не меняется (будет пропущен как «уже скачан»)
        assertEquals(same, Downloads.targetFile(tmp, "Artist - Song", "mp3", "ynd:same"))

        // чужой трек 1: базовое имя занято индексом другого — « (2)»
        assertEquals(File(tmp, "Artist - Song (2).mp3"), Downloads.targetFile(tmp, "Artist - Song", "mp3", "ynd:3"))
        // «скачали» его — файл и индекс появились
        val second = File(tmp, "Artist - Song (2).mp3").apply { writeText("b") }
        DownloadIndex.add("ynd:3", second, snap("ynd:3"))

        // чужой трек 2: « (2)» занята индексом, « (3)» — неиндексированный файл пользователя, не перезаписываем
        File(tmp, "Artist - Song (3).mp3").apply { writeText("c") }
        assertEquals(File(tmp, "Artist - Song (4).mp3"), Downloads.targetFile(tmp, "Artist - Song", "mp3", "ynd:4"))
    }


    @Test
    fun `файл без записи в индексе не перезаписывается`() {
        fresh()
        File(tmp, "Artist - Song.mp3").apply { writeText("пользовательский") }
        assertEquals(File(tmp, "Artist - Song (2).mp3"), Downloads.targetFile(tmp, "Artist - Song", "mp3", "ynd:x"))
    }
}
