package melos.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import melos.core.Paths
import melos.library.Snap
import java.io.File

// Постоянный индекс загрузок: mediaId -> файл и слепок трека. Даёт «скачано» в списках,
// офлайн-игру с диска и офлайн-Медиатеку. Файл читается один раз; битые записи
// (файл пропал) выбрасываются при обращении.
object DownloadIndex {
    @Serializable
    class Entry(val path: String, val snap: Snap)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Entry.serializer())

    // подменяемо для тестов
    @Volatile internal var file: File = Paths.file("download-index.json")

    @Volatile
    private var map: MutableMap<String, Entry> = load()

    private fun load(): MutableMap<String, Entry> = runCatching {
        json.decodeFromString(serializer, file.readText())
            .filterValues { File(it.path).isFile }
            .toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun save() = runCatching { file.writeText(json.encodeToString(serializer, map)) }
        .onFailure { melos.host.Log.w("DownloadIndex", "save: ${it.message}") }

    /** Запись трека, если файл ещё лежит на диске. */
    fun get(mediaId: String): Entry? = map[mediaId]?.takeIf { File(it.path).isFile }

    fun has(mediaId: String): Boolean = get(mediaId) != null

    fun add(mediaId: String, file: File, snap: Snap) {
        map[mediaId] = Entry(file.path, snap); save()
    }

    fun remove(mediaId: String) {
        if (map.remove(mediaId) != null) save()
    }

    /** Все живые записи (файл существует). */
    fun all(): List<Entry> = map.values.filter { File(it.path).isFile }

    /** Кто из индекса занимает этот путь: тот же mediaId — «уже скачано», чужой — нужен суффикс. */
    fun ownerOf(path: String): String? = map.entries.firstOrNull { it.value.path == path && File(it.value.path).isFile }?.key

    /** Тесты: перечитать с диска. */
    internal fun reload() {
        map = load()
    }
}
