package melos.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.Executors

val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; encodeDefaults = true }

// Плоское key-value хранилище в JSON-файле. Пишется в фоне, атомарно (через временный файл).
class JsonStore(private val file: File) {
    private val map: MutableMap<String, JsonElement> = runCatching {
        json.parseToJsonElement(file.readText()).let { it as JsonObject }.toMutableMap()
    }.getOrElse { mutableMapOf() }

    @Synchronized fun keys(): Set<String> = map.keys.toSet()
    @Synchronized operator fun contains(key: String) = key in map
    @Synchronized fun raw(key: String): JsonElement? = map[key]

    fun string(key: String): String? = (raw(key) as? JsonPrimitive)?.contentOrNull
    fun long(key: String): Long? = (raw(key) as? JsonPrimitive)?.longOrNull
    fun double(key: String): Double? = (raw(key) as? JsonPrimitive)?.doubleOrNull
    fun bool(key: String): Boolean? = (raw(key) as? JsonPrimitive)?.booleanOrNull

    @Synchronized fun put(key: String, value: JsonElement?) {
        if (value == null) map.remove(key) else map[key] = value
        save()
    }

    fun put(key: String, value: String?) = put(key, value?.let(::JsonPrimitive))
    fun put(key: String, value: Number?) = put(key, value?.let(::JsonPrimitive))
    fun put(key: String, value: Boolean?) = put(key, value?.let(::JsonPrimitive))

    @Synchronized fun removeAll(predicate: (String) -> Boolean) {
        map.keys.filter(predicate).forEach(map::remove)
        save()
    }

    private fun save() {
        val text = json.encodeToString(JsonObject.serializer(), JsonObject(map.toMap()))
        writer.execute {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    companion object {
        private val writer = Executors.newSingleThreadExecutor { Thread(it, "melos-store").apply { isDaemon = true } }
    }
}

// Общие настройки приложения.
object Settings {
    val store = JsonStore(Paths.file("settings.json"))
}
