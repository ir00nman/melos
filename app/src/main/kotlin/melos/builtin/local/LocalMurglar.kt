package melos.builtin.local

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.login.NoLoginResolver
import com.badmanners.murglar.lib.core.model.node.Directory as DirNode
import com.badmanners.murglar.lib.core.model.node.NodeParameters.PagingType.NON_PAGEABLE
import com.badmanners.murglar.lib.core.model.node.NodeType.ALBUM
import com.badmanners.murglar.lib.core.model.node.NodeType.ARTIST
import com.badmanners.murglar.lib.core.model.node.NodeType.NODE
import com.badmanners.murglar.lib.core.model.node.NodeType.TRACK
import com.badmanners.murglar.lib.core.model.tag.Lyrics
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import com.badmanners.murglar.lib.core.model.track.source.Bitrate
import com.badmanners.murglar.lib.core.model.track.source.Extension
import com.badmanners.murglar.lib.core.model.track.source.QualityTier
import com.badmanners.murglar.lib.core.model.track.source.Source
import com.badmanners.murglar.lib.core.model.track.source.SourceFormat
import com.badmanners.murglar.lib.core.network.NetworkMiddleware
import com.badmanners.murglar.lib.core.notification.NotificationMiddleware
import com.badmanners.murglar.lib.core.node.BaseNodeResolver
import com.badmanners.murglar.lib.core.node.Directory
import com.badmanners.murglar.lib.core.node.MappedEntity
import com.badmanners.murglar.lib.core.node.Root
import com.badmanners.murglar.lib.core.node.Search
import com.badmanners.murglar.lib.core.node.Track
import com.badmanners.murglar.lib.core.preference.ActionPreference
import com.badmanners.murglar.lib.core.preference.EditPreference
import com.badmanners.murglar.lib.core.preference.PreferenceMiddleware
import com.badmanners.murglar.lib.core.service.BaseMurglar
import com.badmanners.murglar.lib.core.utils.MediaId
import melos.builtin.BuiltinMessages
import melos.builtin.FAlbum
import melos.builtin.FArtist
import melos.builtin.FTrack
import melos.builtin.bitrateOf
import melos.builtin.builtinMessages
import melos.builtin.source
import melos.core.Paths
import melos.core.json
import melos.lyrics.Lrc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger

// Локальная медиатека: папки из настроек, теги через jaudiotagger, индекс кэшируется на диск.
class LocalMurglar(
    id: String,
    preferences: PreferenceMiddleware,
    network: NetworkMiddleware,
    notifications: NotificationMiddleware,
    logger: LoggerMiddleware,
) : BaseMurglar<FTrack, BuiltinMessages>(id, MESSAGES, preferences, network, notifications, logger) {

    companion object {
        private val MESSAGES = builtinMessages("Local files", "На этом компьютере")
        private const val K_FOLDERS = "folders"
        val AUDIO = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "aiff", "aif", "ape", "wv", "alac", "mka", "webm")
        init { Logger.getLogger("org.jaudiotagger").level = Level.OFF }
    }

    @Serializable
    data class Entry(
        val path: String, val modified: Long, val title: String, val artist: String, val album: String?,
        val albumArtist: String?, val year: Int?, val trackNo: Int?, val genre: String?, val durationMs: Long,
        val bitrate: Int?, val cover: String?,
    )

    override val loginResolver = NoLoginResolver()
    override val nodeResolver = LocalNodeResolver(this, messages)
    override val possibleFormats = listOf(SourceFormat(Extension.FLAC, Bitrate.B_LOSSLESS, QualityTier.LOSSLESS, SourceFormat.SourceFormatAvailability.AVAILABLE))

    val folders: List<File>
        get() = (preferences.getString(K_FOLDERS, File(System.getProperty("user.home"), "Music").path)
            .split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() } + melos.download.Downloads.folder.path)
            .distinct().map(::File)

    /** Папку загрузок сменили — индекс устарел. */
    fun invalidate() { index = null }

    override val murglarPreferences = listOf(
        EditPreference("local.folders", "Папки с музыкой", "Несколько папок — через точку с запятой",
            { preferences.getString(K_FOLDERS, File(System.getProperty("user.home"), "Music").path) },
            { preferences.setString(K_FOLDERS, it); index = null }, refreshAllAfterChange = true),
        ActionPreference("local.rescan", "Пересканировать", "Перечитать теги всех файлов", { index = null; indexFile.delete() }, false,
            refreshAllAfterInvoke = true),
    )

    private val indexFile = File(Paths.cache, "local-index.json")
    private val coverDir = File(Paths.cache, "covers").apply { mkdirs() }
    @Volatile private var index: List<Entry>? = null
    private val lock = Mutex()

    suspend fun entries(): List<Entry> = index ?: lock.withLock {
        index ?: withContext(Dispatchers.IO) { scan() }.also { index = it }
    }

    private fun scan(): List<Entry> {
        val cached = runCatching { json.decodeFromString(ListSerializer(Entry.serializer()), indexFile.readText()) }
            .getOrDefault(emptyList()).associateBy { it.path }
        val result = folders.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().onEnter { !it.name.startsWith('.') }
                .filter { it.isFile && it.extension.lowercase() in AUDIO }
                .mapNotNull { f -> cached[f.path]?.takeIf { it.modified == f.lastModified() } ?: read(f) }
                .toList()
        }.distinctBy { it.path }
        runCatching { indexFile.writeText(json.encodeToString(ListSerializer(Entry.serializer()), result)) }
        return result
    }

    private fun read(f: File): Entry? = runCatching {
        val audio = AudioFileIO.read(f)
        val tag = audio.tag
        fun field(k: FieldKey) = runCatching { tag?.getFirst(k) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val cover = runCatching { tag?.firstArtwork?.binaryData }.getOrNull()?.let { bytes ->
            val out = File(coverDir, hash(f.parent + (field(FieldKey.ALBUM) ?: f.name)) + ".jpg")
            if (!out.exists()) out.writeBytes(bytes)
            out.toURI().toString()
        } ?: f.parentFile.listFiles { c -> c.name.lowercase() in setOf("cover.jpg", "folder.jpg", "cover.png", "front.jpg") }
            ?.firstOrNull()?.toURI()?.toString()
        Entry(
            f.path, f.lastModified(), field(FieldKey.TITLE) ?: f.nameWithoutExtension,
            field(FieldKey.ARTIST) ?: "Неизвестный исполнитель", field(FieldKey.ALBUM), field(FieldKey.ALBUM_ARTIST),
            field(FieldKey.YEAR)?.take(4)?.toIntOrNull(), field(FieldKey.TRACK)?.substringBefore('/')?.toIntOrNull(), field(FieldKey.GENRE),
            audio.audioHeader.trackLength * 1000L, audio.audioHeader.bitRateAsNumber.toInt().takeIf { it > 0 }, cover,
        )
    }.getOrElse {
        Entry(f.path, f.lastModified(), f.nameWithoutExtension, "Неизвестный исполнитель", null, null, null, null, null, 0, null, null)
    }

    fun hash(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    fun track(e: Entry): FTrack {
        val f = File(e.path)
        val ext = runCatching { Extension.valueOf(f.extension.uppercase()) }.getOrDefault(Extension.UNKNOWN)
        val lossless = ext in setOf(Extension.FLAC, Extension.WAV)
        val id = hash(e.path)
        return FTrack(
            id = id, title = e.title, artistIds = listOf(hash(e.artist)), artistNames = listOf(e.artist), durationMs = e.durationMs,
            sources = listOf(source("file", f.toPath().toUri().toString(), ext, if (lossless) Bitrate.B_LOSSLESS else bitrateOf(e.bitrate))),
            mediaId = MediaId.build(this.id, id), albumId = e.album?.let { hash((e.albumArtist ?: e.artist) + "|" + it) }, albumName = e.album,
            smallCoverUrl = e.cover, bigCoverUrl = e.cover, serviceUrl = f.parentFile.toURI().toString(), genre = e.genre,
            indexInAlbum = e.trackNo, releaseDate = e.year?.let { org.threeten.bp.LocalDate.of(it, 1, 1) },
            extra = mapOf("path" to e.path),
        )
    }

    suspend fun tracks() = entries().sortedWith(compareBy({ it.artist.lowercase() }, { it.album ?: "" }, { it.trackNo ?: 0 }, { it.title })).map(::track)

    suspend fun albums() = entries().filter { it.album != null }.groupBy { (it.albumArtist ?: it.artist) + "|" + it.album }.map { (key, list) ->
        val e = list.first()
        FAlbum(hash(key), e.album!!, listOf(hash(e.albumArtist ?: e.artist)), listOf(e.albumArtist ?: e.artist), list.size,
            e.year?.let { org.threeten.bp.LocalDate.of(it, 1, 1) }, list.firstNotNullOfOrNull { it.cover }, list.firstNotNullOfOrNull { it.cover })
    }.sortedBy { it.artists.lowercase() + it.title }

    suspend fun albumTracks(id: String) = tracks().filter { it.albumId == id }.sortedBy { it.indexInAlbum ?: 0 }

    suspend fun artists() = entries().groupBy { it.artist }.map { (name, list) ->
        FArtist(hash(name), name, list.firstNotNullOfOrNull { it.cover })
    }.sortedBy { it.name.lowercase() }

    suspend fun artistTracks(id: String) = tracks().filter { it.artistId == id }

    suspend fun search(q: String): List<FTrack> {
        val words = q.lowercase().split(' ').filter { it.isNotBlank() }
        return tracks().filter { t -> val hay = "${t.title} ${t.artists} ${t.albumName.orEmpty()}".lowercase(); words.all { it in hay } }
    }

    // Папки: id — хэш пути, сам путь ищем в индексе по префиксу.
    suspend fun folderChildren(dirPath: String?): Pair<List<Pair<String, String>>, List<FTrack>> {
        val roots = folders.map { it.path }
        if (dirPath == null) return roots.map { hash(it) to it } to emptyList()
        val all = entries()
        val subdirs = all.mapNotNull { e ->
            val rel = e.path.removePrefix(dirPath + File.separator).takeIf { e.path.startsWith(dirPath + File.separator) } ?: return@mapNotNull null
            rel.split(File.separatorChar).takeIf { it.size > 1 }?.first()
        }.distinct().sorted().map { (dirPath + File.separator + it).let { p -> hash(p) to p } }
        val files = all.filter { File(it.path).parent == dirPath }.sortedBy { it.trackNo ?: 0 }.map(::track)
        return subdirs to files
    }

    suspend fun dirById(id: String): String? {
        folders.firstOrNull { hash(it.path) == id }?.let { return it.path }
        return entries().asSequence().flatMap { e -> generateSequence(File(e.path).parentFile) { it.parentFile }.map { it.path } }
            .firstOrNull { hash(it) == id }
    }

    override suspend fun resolveSourceForUrl(track: FTrack, source: Source) = source

    override fun hasLyrics(track: FTrack) = true

    override suspend fun getLyrics(track: FTrack): Lyrics {
        val path = track.extra["path"] ?: error(messages.trackHasNoLyrics)
        val lrc = File(path.substringBeforeLast('.') + ".lrc")
        if (lrc.exists()) return Lrc.parse(lrc.readText(), track.artists, track.title)
        val text = withContext(Dispatchers.IO) { runCatching { AudioFileIO.read(File(path)).tag?.getFirst(FieldKey.LYRICS) }.getOrNull() }
        if (text.isNullOrBlank()) error(messages.trackHasNoLyrics)
        return if (Lrc.looksLikeLrc(text)) Lrc.parse(text, track.artists, track.title) else Lyrics(text)
    }

    override suspend fun getTracksByMediaIds(mediaIds: List<String>): List<FTrack> {
        val ids = mediaIds.map { MediaId.getIds(it).first() }.toSet()
        return tracks().filter { it.id in ids }
    }
}

class LocalNodeResolver(murglar: LocalMurglar, messages: BuiltinMessages) : BaseNodeResolver<LocalMurglar, BuiltinMessages>(murglar, messages) {
    override val configurations = listOf(
        Root("tracks", { "Все треки" }, NON_PAGEABLE, false, true, TRACK, nodeContentSupplier = { p, _, _ -> murglar.tracks().convertTracks(p) }),
        Root("albums", messages::albums, NON_PAGEABLE, true, true, ALBUM, nodeContentSupplier = { p, _, _ -> murglar.albums().convertAlbums(p) }),
        Root("artists", messages::artists, NON_PAGEABLE, true, true, ARTIST, nodeContentSupplier = { p, _, _ -> murglar.artists().convertArtists(p) }),
        Root("folders", { "Папки" }, NON_PAGEABLE, true, true, NODE, nodeContentSupplier = { p, _, _ -> folder(p, null) }),
        Search("searchTracks", messages::tracksSearch, NON_PAGEABLE, false, TRACK,
            nodeContentSupplier = { p, _, params -> murglar.search(params.getQuery()).convertTracks(p) }),
        Track("*/track-<id>", nodeSupplier = { p, params -> murglar.tracks().first { it.id == params["id"] }.convertTrack(p) }),
        MappedEntity("*/album-<id>", NON_PAGEABLE, false, ALBUM, TRACK,
            nodeSupplier = { p, params -> murglar.albums().first { it.id == params["id"] }.convertAlbum(p) },
            nodeContentSupplier = { p, _, params -> murglar.albumTracks(params["id"]!!).convertTracks(p) }),
        MappedEntity("*/artist-<id>", NON_PAGEABLE, false, ARTIST, TRACK,
            nodeSupplier = { p, params -> murglar.artists().first { it.id == params["id"] }.convertArtist(p) },
            nodeContentSupplier = { p, _, params -> murglar.artistTracks(params["id"]!!).convertTracks(p) }),
        Directory("*/dir-<id>", NON_PAGEABLE, true, NODE,
            nodeContentSupplier = { p, _, params -> folder(p, murglar.dirById(params["id"]!!) ?: error("Папка не найдена")) }),
    )

    override val isAvailable = true

    private suspend fun folder(p: com.badmanners.murglar.lib.core.model.node.Path, dir: String?) = murglar.folderChildren(dir).let { (dirs, files) ->
        dirs.map { (id, path) -> node(DirNode("dir-$id", File(path).name.ifEmpty { path }, path), p.child("dir-$id")) } + files.convertTracks(p)
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun getTracksByMediaIds(mediaIds: List<String>): List<BaseTrack> =
        murglar.getTracksByMediaIds(mediaIds).convertTracks(unmappedPath()) as List<BaseTrack>
}
