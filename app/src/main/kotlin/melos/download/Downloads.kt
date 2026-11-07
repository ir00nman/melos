package melos.download

import com.badmanners.murglar.lib.core.model.track.BaseTrack
import melos.core.Paths
import melos.core.Settings
import melos.host.Log
import melos.host.Services
import melos.host.Toasts
import melos.lyrics.LyricsService
import melos.player.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// Скачивание с тегами, обложкой и текстом — как в Murglar. Все треки — прямо в папку с музыкой, без подпапок,
// «Исполнитель - Название», в лучшем качестве, какое отдаёт сервис (настройка качества потока на загрузки не влияет).
object Downloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(3)
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()

    sealed interface State { data class Running(val progress: Float) : State; data class Done(val file: File) : State; data class Failed(val reason: String) : State }

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap()); val states = _states.asStateFlow()

    var folder: File
        get() = Settings.store.string("downloads.folder")?.let(::File) ?: Paths.defaultDownloads
        set(v) = Settings.store.put("downloads.folder", v.path)

    private fun set(id: String, s: State) { _states.value = _states.value + (id to s) }

    /** Сохранить на устройство: в папку [to] (её же запоминаем как папку загрузок) или в текущую. */
    fun download(tracks: List<BaseTrack>, to: File? = null) {
        if (to != null) folder = to
        tracks.forEach(::download)
    }

    fun download(track: BaseTrack) {
        if (_states.value[track.mediaId] is State.Running) return
        set(track.mediaId, State.Running(0f))
        scope.launch {
            gate.withPermit {
                runCatching { fetch(track) }
                    .onSuccess { set(track.mediaId, State.Done(it)); Toasts.shortNotify("Скачано: ${track.title}") }
                    .onFailure {
                        Log.e("Downloads", track.tag, it)
                        set(track.mediaId, State.Failed(it.message ?: "ошибка"))
                        Toasts.error("Не скачалось «${track.title}»: ${it.message}")
                    }
            }
        }
    }

    private fun clean(s: String) = s.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trimEnd('.').ifEmpty { "_" }.take(120)

    private fun target(track: BaseTrack, ext: String): File {
        val name = listOfNotNull(track.artists.ifBlank { null }, track.fullTitle).joinToString(" - ")
        return File(folder, "${clean(name)}.$ext")
    }

    private suspend fun fetch(track: BaseTrack): File {
        val (url, srcExt) = Player.resolveWithExt(track, forDownload = true)
        if (url.contains(".m3u8")) error("сервис отдаёт только HLS-поток")
        val ext = when {
            url.startsWith("file:") -> File(URI(url)).extension
            else -> srcExt ?: "mp3"                                       // расширение — того источника, что реально пришёл
        }
        val out = target(track, ext).apply { parentFile.mkdirs() }
        val tmp = File(out.parentFile, out.name + ".part")
        if (url.startsWith("file:")) File(URI(url)).copyTo(tmp, overwrite = true)
        else {
            val resp = http.send(HttpRequest.newBuilder(URI(url)).header("User-Agent", "Mozilla/5.0 Melos").build(), HttpResponse.BodyHandlers.ofInputStream())
            check(resp.statusCode() in 200..299) { "HTTP ${resp.statusCode()}" }
            val total = resp.headers().firstValueAsLong("content-length").orElse(-1)
            resp.body().use { input ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        o.write(buf, 0, n); done += n
                        if (total > 0) set(track.mediaId, State.Running(done.toFloat() / total))
                    }
                }
            }
        }
        tmp.renameTo(out)
        runCatching { tag(track, out) }.onFailure { Log.w("Downloads", "tags: ${it.message}") }
        return out
    }

    private suspend fun tag(track: BaseTrack, file: File) {
        val murglar = Services.forTrack(track)?.murglar ?: return
        val tags = runCatching { murglar.getTags(track, null) }.getOrNull()
        val lyrics = runCatching { LyricsService.find(track) }.getOrNull()
        val cover = (track.bigCoverUrl ?: track.smallCoverUrl)?.takeIf { it.startsWith("http") }?.let { u ->
            runCatching { http.send(HttpRequest.newBuilder(URI(u)).build(), HttpResponse.BodyHandlers.ofByteArray()).body() }.getOrNull()
        }
        val audio = AudioFileIO.read(file)
        val t = audio.tagOrCreateAndSetDefault
        fun put(k: FieldKey, v: String?) { if (!v.isNullOrBlank()) runCatching { t.setField(k, v) } }
        put(FieldKey.TITLE, tags?.title ?: track.fullTitle)
        put(FieldKey.ARTIST, tags?.artists?.joinToString(", ") ?: track.artists)
        put(FieldKey.ALBUM, tags?.album ?: track.albumName)
        put(FieldKey.ALBUM_ARTIST, tags?.albumArtist)
        put(FieldKey.TRACK, (tags?.trackNumber ?: track.indexInAlbum)?.toString())
        put(FieldKey.YEAR, (tags?.releaseDate ?: track.albumReleaseDate)?.year?.toString())
        put(FieldKey.GENRE, tags?.genre ?: track.genre)
        put(FieldKey.URL_OFFICIAL_ARTIST_SITE, track.serviceUrl)
        lyrics?.let { put(FieldKey.LYRICS, it.synced?.buildAsLrc() ?: it.plain) }
        cover?.let { bytes ->
            val tmp = File.createTempFile("cover", ".jpg").apply { writeBytes(bytes); deleteOnExit() }
            runCatching { t.deleteArtworkField(); t.setField(ArtworkFactory.createArtworkFromFile(tmp)) }
        }
        audio.commit()
        lyrics?.synced?.let { File(file.path.substringBeforeLast('.') + ".lrc").writeText(it.buildAsLrcWithHeader()) }
    }
}
