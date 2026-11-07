package melos.host

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.node.Path
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import com.badmanners.murglar.lib.core.network.NetworkMiddleware
import com.badmanners.murglar.lib.core.notification.NotificationMiddleware
import com.badmanners.murglar.lib.core.preference.PreferenceMiddleware
import com.badmanners.murglar.lib.core.provider.cover.CoverProvider
import com.badmanners.murglar.lib.core.provider.lyrics.LyricsProvider
import com.badmanners.murglar.lib.core.service.Murglar
import com.badmanners.murglar.lib.core.utils.MediaId
import com.badmanners.murglar.lib.core.webview.WebViewProvider
import melos.builtin.local.LocalMurglar
import melos.builtin.lrclib.LrcLibProvider
import melos.builtin.soundcloud.SoundCloudMurglar
import melos.builtin.yandex.YandexMurglar
import melos.core.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLClassLoader
import java.util.Locale
import java.util.jar.JarFile

// Откуда взялся сервис: встроен в Melos или подгружен jar-плагином Murglar.
sealed interface Origin {
    data object Builtin : Origin
    data class Plugin(val file: File, val version: String) : Origin
}

class Service(val murglar: Murglar<BaseTrack>, val origin: Origin) {
    val id get() = murglar.id
    val name get() = murglar.name
}

data class PluginError(val file: File, val message: String)

// Реестр сервисов. Плагины грузятся так же, как в Murglar Desktop: jar с манифестом Plugin-*,
// точка входа получает id и middlewares хоста через конструктор.
object Services {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _all = MutableStateFlow<List<Service>>(emptyList())
    val all = _all.asStateFlow()
    private val _errors = MutableStateFlow<List<PluginError>>(emptyList())
    val errors = _errors.asStateFlow()
    private val _lyrics = MutableStateFlow<List<LyricsProvider>>(emptyList())
    val lyricsProviders = _lyrics.asStateFlow()
    private val _covers = MutableStateFlow<List<CoverProvider>>(emptyList())
    val coverProviders = _covers.asStateFlow()

    /** Растёт при логине/выходе/смене настроек, чтобы экраны перечитали корни. */
    val revision = MutableStateFlow(0)
    fun bump() { revision.value++ }

    private val loaders = mutableListOf<URLClassLoader>()

    fun load() {
        loaders.forEach { runCatching { it.close() } }
        loaders.clear()
        val errors = mutableListOf<PluginError>()
        val services = mutableListOf<Service>()
        val lyrics = mutableListOf<LyricsProvider>(LrcLibProvider(HttpNetwork))
        val covers = mutableListOf<CoverProvider>()

        fun <T : Any> builtin(id: String, make: (String, PreferenceMiddleware) -> T) = make(id, MelosPreferences(id))

        @Suppress("UNCHECKED_CAST")
        listOf(
            builtin("ynd") { id, p -> YandexMurglar(id, p, HttpNetwork, Toasts, Log) },
            builtin("sc") { id, p -> SoundCloudMurglar(id, p, HttpNetwork, Toasts, Log) },
            builtin("vk") { id, p -> melos.builtin.vk.VkMurglar(id, p, HttpNetwork, Toasts, Log) },
            builtin("local") { id, p -> LocalMurglar(id, p, HttpNetwork, Toasts, Log) },
        ).forEach { services += Service(it as Murglar<BaseTrack>, Origin.Builtin) }

        Paths.plugins.listFiles { f -> f.extension == "jar" }.orEmpty().sortedBy { it.name }.forEach { jar ->
            runCatching { loadJar(jar, services, lyrics, covers) }
                .onFailure {
                    Log.e("Plugins", "plugin ${jar.name}", it)
                    errors += PluginError(jar, it.message ?: it.javaClass.simpleName)
                }
        }

        val ru = Locale.Builder().setLanguage("ru").build()
        services.forEach { s -> runCatching { if (ru in s.murglar.availableLocales) s.murglar.locale = ru } }

        _all.value = services.distinctBy { it.id }
        _errors.value = errors
        _lyrics.value = lyrics
        _covers.value = covers
        services.forEach { s ->
            scope.launch {
                runCatching { s.murglar.onCreate() }.onFailure { Log.e("Plugins", "onCreate ${s.id}", it) }
                bump()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadJar(jar: File, services: MutableList<Service>, lyrics: MutableList<LyricsProvider>, covers: MutableList<CoverProvider>) {
        val attrs = JarFile(jar).use { it.manifest?.mainAttributes } ?: error("В jar нет манифеста")
        val type = attrs.getValue("Plugin-Type") ?: error("Это не плагин Murglar (нет Plugin-Type)")
        val entry = attrs.getValue("Plugin-Entry-Point-Class") ?: error("Нет Plugin-Entry-Point-Class")
        val id = attrs.getValue("Plugin-Id") ?: jar.nameWithoutExtension
        val libVersion = attrs.getValue("Plugin-Lib-Version")?.toIntOrNull()
        check(libVersion == null || libVersion == Murglar.MURGLAR_LIB_VERSION) {
            "Плагин собран под API $libVersion, Melos поддерживает ${Murglar.MURGLAR_LIB_VERSION}"
        }
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), Murglar::class.java.classLoader)
        loaders += loader
        val instance = construct(loader.loadClass(entry), id)
        val version = "${libVersion ?: "?"}.${attrs.getValue("Plugin-Version") ?: "?"}"
        when (type) {
            "MURGLAR" -> services += Service(instance as Murglar<BaseTrack>, Origin.Plugin(jar, version))
            "LYRICS_PROVIDER" -> lyrics += instance as LyricsProvider
            "COVERS_PROVIDER" -> covers += instance as CoverProvider
            else -> Log.w("Plugins", "${jar.name}: тип $type пока не поддерживается")
        }
        Log.i("Plugins", "loaded ${jar.name} ($type, $id)")
    }

    // Подбираем конструктор по типам: всё, что хост умеет дать, — даём.
    private fun construct(cls: Class<*>, id: String): Any {
        val pool: List<Any> = listOf(id, MelosPreferences(id), HttpNetwork, Toasts, Log, EmbeddedWebView)
        val kinds = listOf(String::class.java, PreferenceMiddleware::class.java, NetworkMiddleware::class.java,
            NotificationMiddleware::class.java, LoggerMiddleware::class.java, WebViewProvider::class.java)
        for (ctor in cls.constructors.sortedByDescending { it.parameterCount }) {
            val args = ctor.parameterTypes.map { p -> kinds.indexOfFirst { p.isAssignableFrom(it) }.takeIf { it >= 0 }?.let(pool::get) }
            if (args.none { it == null }) return ctor.newInstance(*args.toTypedArray())
        }
        error("Не нашёл подходящий конструктор у ${cls.name}")
    }

    fun byId(id: String) = all.value.firstOrNull { it.id == id }
    fun forPath(path: Path) = all.value.firstOrNull { it.murglar.nodeResolver.isNodeOwner(path) }
    fun forNode(node: Node) = forPath(node.nodePath)
    fun forTrack(track: BaseTrack) = byId(MediaId.getServiceId(track.mediaId)) ?: forNode(track)
}
