package melos.host

import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.node.Path
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import com.badmanners.murglar.lib.core.service.Murglar
import com.badmanners.murglar.lib.core.utils.MediaId
import melos.builtin.lrclib.LrcLibProvider
import melos.builtin.soundcloud.SoundCloudMurglar
import melos.builtin.vk.VkMurglar
import melos.builtin.yandex.YandexMurglar
import melos.core.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

// их тут просто нет. API — 1:1 с десктопным, чтобы общий код ничего не заметил.
sealed interface Origin {
    data object Builtin : Origin
    data class Plugin(val file: java.io.File, val version: String) : Origin
}

class Service(val murglar: Murglar<BaseTrack>, val origin: Origin) {
    val id get() = murglar.id
    val name get() = murglar.name
}

data class PluginError(val file: java.io.File, val message: String)

object Services {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _all = MutableStateFlow<List<Service>>(emptyList())
    val all = _all.asStateFlow()
    private val _errors = MutableStateFlow<List<PluginError>>(emptyList())
    val errors = _errors.asStateFlow()
    private val _lyrics = MutableStateFlow<List<com.badmanners.murglar.lib.core.provider.lyrics.LyricsProvider>>(emptyList())
    val lyricsProviders = _lyrics.asStateFlow()
    private val _covers = MutableStateFlow<List<com.badmanners.murglar.lib.core.provider.cover.CoverProvider>>(emptyList())
    val coverProviders = _covers.asStateFlow()

    /** Растёт при логине/выходе/смене настроек, чтобы экраны перечитали корни. */
    val revision = MutableStateFlow(0)
    fun bump() { revision.value++ }

    fun load() {
        val builtins = listOf(
            builtin("ynd") { id, p -> YandexMurglar(id, p, HttpNetwork, Toasts, Log) },
            builtin("sc") { id, p -> SoundCloudMurglar(id, p, HttpNetwork, Toasts, Log) },
            builtin("vk") { id, p -> VkMurglar(id, p, HttpNetwork, Toasts, Log) },
        )
        val services = builtins.map { Service(it as Murglar<BaseTrack>, Origin.Builtin) }
        val lyrics = listOf(LrcLibProvider(HttpNetwork))
        val ru = Locale.Builder().setLanguage("ru").build()
        services.forEach { s -> runCatching { if (ru in s.murglar.availableLocales) s.murglar.locale = ru } }

        _all.value = services.distinctBy { it.id }
        _errors.value = emptyList()
        _lyrics.value = lyrics
        _covers.value = emptyList()
        services.forEach { s ->
            scope.launch {
                runCatching { s.murglar.onCreate() }.onFailure { Log.e("Plugins", "onCreate ${s.id}", it) }
                bump()
            }
        }
    }

    private fun <T : Any> builtin(id: String, make: (String, com.badmanners.murglar.lib.core.preference.PreferenceMiddleware) -> T) =
        make(id, MelosPreferences(id))

    fun byId(id: String) = all.value.firstOrNull { it.id == id }
    fun forPath(path: Path) = all.value.firstOrNull { it.murglar.nodeResolver.isNodeOwner(path) }
    fun forNode(node: Node) = forPath(node.nodePath)
    fun forTrack(track: BaseTrack) = byId(MediaId.getServiceId(track.mediaId)) ?: forNode(track)
}

// Пути плагинов на Android пусты — jar-плагины не поддерживаются; папка нужна, чтобы не падать на чтении настроек.
val pluginsDir: java.io.File get() = Paths.plugins
