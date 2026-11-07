package melos.builtin.yandex

import com.badmanners.murglar.lib.core.model.node.NamedPath
import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.node.NodeParameters.PagingType.ENDLESSLY_PAGEABLE
import com.badmanners.murglar.lib.core.model.node.NodeParameters.PagingType.NON_PAGEABLE
import com.badmanners.murglar.lib.core.model.node.NodeType.ALBUM
import com.badmanners.murglar.lib.core.model.node.NodeType.ARTIST
import com.badmanners.murglar.lib.core.model.node.NodeType.NODE
import com.badmanners.murglar.lib.core.model.node.NodeType.PLAYLIST
import com.badmanners.murglar.lib.core.model.node.NodeType.RADIO
import com.badmanners.murglar.lib.core.model.node.NodeType.TRACK
import com.badmanners.murglar.lib.core.model.node.RadioNodeUpdate
import com.badmanners.murglar.lib.core.model.radio.BaseRadio
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import com.badmanners.murglar.lib.core.node.BaseNodeResolver
import com.badmanners.murglar.lib.core.model.event.PlayerEvent
import com.badmanners.murglar.lib.core.node.Directory
import com.badmanners.murglar.lib.core.node.EventConfig
import com.badmanners.murglar.lib.core.node.EventHandler
import com.badmanners.murglar.lib.core.node.LikeConfig
import com.badmanners.murglar.lib.core.node.MappedEntity
import com.badmanners.murglar.lib.core.node.Root
import com.badmanners.murglar.lib.core.node.Search
import com.badmanners.murglar.lib.core.node.Track
import melos.builtin.BuiltinMessages
import melos.builtin.FAlbum
import melos.builtin.FArtist
import melos.builtin.FRadio
import melos.builtin.FTrack
import melos.builtin.get
import melos.builtin.objects
import kotlinx.serialization.json.JsonObject

class YandexNodeResolver(murglar: YandexMurglar, messages: BuiltinMessages) :
    BaseNodeResolver<YandexMurglar, BuiltinMessages>(murglar, messages) {

    override val configurations = listOf(
        Root("myWave", { "Моя волна" }, ENDLESSLY_PAGEABLE, false, false, TRACK,
            radioNodeSupplier = { id, name -> FRadio(id, name, "Бесконечный поток под ваш вкус", cursor = null) },
            radioContentSupplier = { radio, _, _ -> station(radio, "user:onyourwave") }),
        // как на сайте: подборки, потом «Коллекция» (Мне нравится, Плейлисты, Альбомы, Исполнители)
        Root("chart", { "Чарт" }, NON_PAGEABLE, false, false, TRACK,
            nodeContentSupplier = { p, _, _ -> murglar.chart().convertTracks(p) }),
        Root("newReleases", { "Новые релизы" }, NON_PAGEABLE, true, false, ALBUM,
            nodeContentSupplier = { p, _, _ -> murglar.newReleases().convertAlbums(p) }),
        Root("myTracks", { "Мне нравится" }, NON_PAGEABLE, false, true, TRACK,
            nodeContentSupplier = { p, _, _ -> murglar.myTracks().convertTracks(p) }),
        Root("myPlaylists", { "Плейлисты" }, NON_PAGEABLE, true, true, PLAYLIST,
            nodeContentSupplier = { p, _, _ -> murglar.myPlaylists().convertPlaylists(p) }),
        Root("myAlbums", { "Альбомы" }, NON_PAGEABLE, true, true, ALBUM,
            nodeContentSupplier = { p, _, _ -> murglar.myAlbums().convertAlbums(p) }),
        Root("myArtists", { "Исполнители" }, NON_PAGEABLE, true, true, ARTIST,
            nodeContentSupplier = { p, _, _ -> murglar.myArtists().convertArtists(p) }),

        Search("searchTracks", messages::tracksSearch, hasSubdirectories = false, contentNodeType = TRACK,
            nodeContentSupplier = { p, page, params -> murglar.search(params.getQuery(), "track", page).mapNotNull(murglar::track).convertTracks(p) }),
        Search("searchAlbums", messages::albumsSearch, hasSubdirectories = true, contentNodeType = ALBUM,
            nodeContentSupplier = { p, page, params -> murglar.search(params.getQuery(), "album", page).mapNotNull(murglar::album).convertAlbums(p) }),
        Search("searchArtists", messages::artistsSearch, hasSubdirectories = true, contentNodeType = ARTIST,
            nodeContentSupplier = { p, page, params -> murglar.search(params.getQuery(), "artist", page).mapNotNull(murglar::artist).convertArtists(p) }),
        Search("searchPlaylists", messages::playlistsSearch, hasSubdirectories = true, contentNodeType = PLAYLIST,
            nodeContentSupplier = { p, page, params -> murglar.search(params.getQuery(), "playlist", page).mapNotNull(murglar::playlist).convertPlaylists(p) }),

        Directory("*/track-<trackId>/similar", NON_PAGEABLE, false, TRACK,
            nodeSupplier = { p, _ -> subdirectoryNode("similar", "Похожие треки", p) },            // страница трека открывает раздел по пути
            nodeContentSupplier = { p, _, params -> murglar.similarTracks(params["trackId"]!!).convertTracks(p) }),
        Track("*/track-<trackId>", relatedPaths = ::relatedForTrack,
            like = LikeConfig(rootNodePath("myTracks"), ::likeTrack),
            // треки волны отчитываются ротору: начат / дослушан / пропущен — так волна учится и не стоит на месте
            events = listOf(
                EventConfig(PlayerEvent.TrackStart::class, object : EventHandler<PlayerEvent.TrackStart> {
                    override suspend fun Node.handleEvent(event: PlayerEvent.TrackStart, parentNode: Node?) { (this as? FTrack)?.let { murglar.waveEvent(it, started = true) } }
                }),
                EventConfig(PlayerEvent.TrackEnd::class, object : EventHandler<PlayerEvent.TrackEnd> {
                    override suspend fun Node.handleEvent(event: PlayerEvent.TrackEnd, parentNode: Node?) { (this as? FTrack)?.let { murglar.waveEvent(it, started = false, playedMs = event.endTimeMs) } }
                }),
            ),
            urlPatterns = listOf("*music.yandex.*/album/<albumId:\\d+>/track/<trackId:\\d+>*", "*music.yandex.*/track/<trackId:\\d+>*"),
            nodeSupplier = { p, params -> murglar.tracksByIds(listOf(params["trackId"]!!)).firstOrNull()?.convertTrack(p) ?: error("Трек недоступен") }),

        MappedEntity("*/album-<albumId>", NON_PAGEABLE, false, ALBUM, TRACK,
            relatedPaths = ::relatedForAlbum,
            like = LikeConfig(rootNodePath("myAlbums"), ::likeAlbum),
            urlPatterns = listOf("*music.yandex.*/album/<albumId:\\d+>*"),
            nodeSupplier = { p, params -> murglar.albumWithTracks(params["albumId"]!!).first.convertAlbum(p) },
            nodeContentSupplier = { p, _, params -> murglar.albumWithTracks(params["albumId"]!!).second.convertTracks(p) }),

        MappedEntity("*/playlist-<owner>_<kind>", NON_PAGEABLE, false, PLAYLIST, TRACK,
            urlPatterns = listOf("*music.yandex.*/users/<owner>/playlists/<kind:\\d+>*"),
            nodeSupplier = { p, params -> murglar.playlistInfo(params["owner"]!!, params["kind"]!!).convertPlaylist(p) },
            nodeContentSupplier = { p, _, params -> murglar.playlistTracks(params["owner"]!!, params["kind"]!!).convertTracks(p) }),

        Directory("*/artist-<artistId>/tracks", ENDLESSLY_PAGEABLE, false, TRACK,
            nodeContentSupplier = { p, page, params -> murglar.artistTracks(params["artistId"]!!, page).convertTracks(p) }),
        Directory("*/artist-<artistId>/albums", NON_PAGEABLE, true, ALBUM,
            nodeContentSupplier = { p, _, params -> murglar.artistAlbums(params["artistId"]!!).convertAlbums(p) }),
        Directory("*/artist-<artistId>/similar", NON_PAGEABLE, true, ARTIST,
            nodeContentSupplier = { p, _, params ->
                murglar.artistInfo(params["artistId"]!!)["similarArtists"].objects().mapNotNull(murglar::artist).convertArtists(p)
            }),
        MappedEntity("*/artist-<artistId>", NON_PAGEABLE, true, ARTIST, NODE,
            relatedPaths = ::relatedForArtist,
            like = LikeConfig(rootNodePath("myArtists"), ::likeArtist),
            urlPatterns = listOf("*music.yandex.*/artist/<artistId:\\d+>*"),
            nodeSupplier = { p, params ->
                (murglar.artistInfo(params["artistId"]!!)["artist"] as? JsonObject)?.let(murglar::artist)?.convertArtist(p) ?: error("Нет исполнителя")
            },
            nodeContentSupplier = { p, _, _ ->
                listOf(
                    subdirectoryNode("tracks", messages.popularTracks, p),
                    subdirectoryNode("albums", messages.albums, p),
                    subdirectoryNode("similar", messages.similarArtists, p),
                )
            }),

        MappedEntity("*/station-<type>_<tag>", ENDLESSLY_PAGEABLE, false, RADIO, TRACK,
            nodeSupplier = { p, params ->
                val (type, tag) = params["type"]!! to params["tag"]!!
                node(FRadio("${type}_$tag", if (type == "track") "Волна по треку" else "Волна исполнителя"), p.child("station-${type}_$tag"))
            },
            radioContentSupplier = { radio, _, _ -> station(radio, radio.id.replaceFirst('_', ':')) }),
    )

    private suspend fun station(radio: BaseRadio, station: String): RadioNodeUpdate {
        val r = radio as FRadio
        val (next, cursor) = murglar.wave(station, r.cursor)
        val updated = FRadio(r.id, r.title, r.description, r.smallCoverUrl, r.bigCoverUrl, cursor ?: r.cursor)
            .apply { withNodeAttributes(radio) }
        return RadioNodeUpdate(updated, next.convertTracks(radio.nodePath))
    }

    private fun relatedForTrack(node: Node): List<NamedPath> {
        val t = node as? FTrack ?: return emptyList()
        return listOfNotNull(
            NamedPath("Волна по треку", RADIO, unmappedPath().child("station-track_${t.id}")),
            t.albumId?.let { NamedPath(t.albumName ?: "Альбом", ALBUM, unmappedPath().child("album-$it")) },
        ) + t.artistIds.zip(t.artistNames).map { (id, name) -> NamedPath(name, ARTIST, unmappedPath().child("artist-$id")) } +
            NamedPath("Похожие треки", TRACK, unmappedPath().child("track-${t.id}").child("similar"))
    }

    private fun relatedForAlbum(node: Node): List<NamedPath> {
        val a = node as? FAlbum ?: return emptyList()
        return a.artistIds.zip(a.artistNames).map { (id, name) -> NamedPath(name, ARTIST, unmappedPath().child("artist-$id")) }
    }

    private fun relatedForArtist(node: Node): List<NamedPath> =
        listOf(NamedPath("Волна исполнителя", RADIO, unmappedPath().child("station-artist_${(node as FArtist).id}")))

    private suspend fun likeTrack(node: Node, like: Boolean) { val t = node as FTrack; murglar.like(t.id, t.albumId, like) }
    private suspend fun likeAlbum(node: Node, like: Boolean) = murglar.likeEntity("albums", (node as FAlbum).id, like)
    private suspend fun likeArtist(node: Node, like: Boolean) = murglar.likeEntity("artists", (node as FArtist).id, like)

    @Suppress("UNCHECKED_CAST")
    override suspend fun getTracksByMediaIds(mediaIds: List<String>): List<BaseTrack> =
        murglar.getTracksByMediaIds(mediaIds).convertTracks(unmappedPath()) as List<BaseTrack>
}
