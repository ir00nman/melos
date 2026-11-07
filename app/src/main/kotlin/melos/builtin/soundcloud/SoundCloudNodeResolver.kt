package melos.builtin.soundcloud

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
import com.badmanners.murglar.lib.core.model.node.Path
import com.badmanners.murglar.lib.core.model.node.RadioNodeUpdate
import com.badmanners.murglar.lib.core.model.radio.BaseRadio
import com.badmanners.murglar.lib.core.model.radio.RadioSettingsUpdate
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import com.badmanners.murglar.lib.core.node.BaseNodeResolver
import com.badmanners.murglar.lib.core.node.Directory
import com.badmanners.murglar.lib.core.node.MappedEntity
import com.badmanners.murglar.lib.core.node.LikeConfig
import com.badmanners.murglar.lib.core.node.Root
import com.badmanners.murglar.lib.core.node.Search
import com.badmanners.murglar.lib.core.node.Track
import melos.builtin.BuiltinMessages
import melos.builtin.FAlbum
import melos.builtin.FPlaylist
import melos.builtin.FRadio
import melos.builtin.FTrack
import melos.builtin.long
import melos.builtin.str

class SoundCloudNodeResolver(murglar: SoundCloudMurglar, messages: BuiltinMessages) :
    BaseNodeResolver<SoundCloudMurglar, BuiltinMessages>(murglar, messages) {

    private val ownRoots = setOf("myTracks", "myPlaylists", "stream")

    private suspend fun likeTrack(node: com.badmanners.murglar.lib.core.model.node.Node, like: Boolean) =
        murglar.likeTrack((node as melos.builtin.FTrack).id, like)

    override val configurations = listOf(
        Root("myTracks", { "Лайки" }, NON_PAGEABLE, false, true, TRACK, nodeContentSupplier = { p, _, _ -> murglar.myLikes().convertTracks(p) }),
        Root("myPlaylists", { "Плейлисты" }, NON_PAGEABLE, true, true, PLAYLIST, nodeContentSupplier = { p, _, _ ->
            murglar.myPlaylists().map { if (it is FAlbum) it.convertAlbum(p) else (it as FPlaylist).convertPlaylist(p) }
        }),
        Root("stream", { "Лента" }, NON_PAGEABLE, false, true, TRACK, nodeContentSupplier = { p, _, _ -> murglar.myStream().convertTracks(p) }),

        Search("searchTracks", messages::tracksSearch, hasSubdirectories = false, contentNodeType = TRACK,
            nodeContentSupplier = { p, page, params -> murglar.searchTracks(params.getQuery(), page).convertTracks(p) }),
        Search("searchAlbums", messages::albumsSearch, hasSubdirectories = true, contentNodeType = ALBUM,
            nodeContentSupplier = { p, page, params -> murglar.searchAlbums(params.getQuery(), page).convertAlbums(p) }),
        Search("searchArtists", messages::artistsSearch, hasSubdirectories = true, contentNodeType = ARTIST,
            nodeContentSupplier = { p, page, params -> murglar.searchArtists(params.getQuery(), page).convertArtists(p) }),
        Search("searchPlaylists", messages::playlistsSearch, hasSubdirectories = true, contentNodeType = PLAYLIST,
            nodeContentSupplier = { p, page, params -> murglar.searchPlaylists(params.getQuery(), page).convertPlaylists(p) }),

        Directory("*/track-<trackId>/similar", NON_PAGEABLE, false, TRACK,
            nodeSupplier = { p, _ -> subdirectoryNode("similar", "Похожие треки", p) },            // страница трека открывает раздел по пути
            nodeContentSupplier = { p, _, params -> murglar.related(params["trackId"]!!).convertTracks(p) }),
        Track("*/track-<trackId>", relatedPaths = ::relatedForTrack,
            like = LikeConfig(rootNodePath("myTracks"), ::likeTrack),            // лайки треков — «Лайки» и медиатека Melos
            nodeSupplier = { p, params -> murglar.getTrack(params["trackId"]!!).convertTrack(p) }),

        MappedEntity("*/album-<id>", NON_PAGEABLE, false, ALBUM, TRACK,
            nodeSupplier = { p, params -> playlistNode(params["id"]!!, p) },
            nodeContentSupplier = { p, _, params -> murglar.getPlaylistTracks(params["id"]!!).convertTracks(p) }),
        MappedEntity("*/playlist-<id>", NON_PAGEABLE, false, PLAYLIST, TRACK,
            nodeSupplier = { p, params -> playlistNode(params["id"]!!, p) },
            nodeContentSupplier = { p, _, params -> murglar.getPlaylistTracks(params["id"]!!).convertTracks(p) }),

        Directory("*/artist-<artistId>/popular", NON_PAGEABLE, false, TRACK,
            nodeContentSupplier = { p, _, params -> murglar.artistPopular(params["artistId"]!!).convertTracks(p) }),
        Directory("*/artist-<artistId>/tracks", ENDLESSLY_PAGEABLE, false, TRACK,
            nodeContentSupplier = { p, page, params -> murglar.artistTracks(params["artistId"]!!, page).convertTracks(p) }),
        Directory("*/artist-<artistId>/albums", NON_PAGEABLE, true, ALBUM,
            nodeContentSupplier = { p, _, params -> murglar.artistAlbums(params["artistId"]!!).convertAlbums(p) }),
        Directory("*/artist-<artistId>/playlists", NON_PAGEABLE, true, PLAYLIST,
            nodeContentSupplier = { p, _, params -> murglar.artistPlaylists(params["artistId"]!!).convertPlaylists(p) }),
        MappedEntity("*/artist-<artistId>", NON_PAGEABLE, true, ARTIST, NODE,
            nodeSupplier = { p, params -> murglar.getArtist(params["artistId"]!!).convertArtist(p) },
            nodeContentSupplier = { p, _, _ ->
                listOf(
                    subdirectoryNode("popular", messages.popularTracks, p),
                    subdirectoryNode("tracks", messages.tracks, p),
                    subdirectoryNode("albums", messages.albums, p),
                    subdirectoryNode("playlists", messages.playlists, p),
                )
            }),

        MappedEntity("*/radio-<trackId>", ENDLESSLY_PAGEABLE, false, RADIO, TRACK,
            nodeSupplier = { p, params ->
                val seed = murglar.getTrack(params["trackId"]!!)
                node(FRadio(seed.id, "Волна: ${seed.title}", seed.artists, seed.smallCoverUrl, seed.bigCoverUrl, seed.id), p.child("radio-${seed.id}"))
            },
            radioContentSupplier = { radio, _, _ -> radioNext(radio) }),
    )

    override val isAvailable = true

    override fun getRootNodes(onlyAvailable: Boolean) =
        super.getRootNodes(false).filter { murglar.loginResolver.isLogged || it.nodeId !in ownRoots }

    private suspend fun playlistNode(id: String, parent: Path): Node = when (val pl = murglar.playlist(murglar.getPlaylistRaw(id))) {
        is FAlbum -> pl.convertAlbum(parent)
        is FPlaylist -> pl.convertPlaylist(parent)
        else -> error("Плейлист $id недоступен")
    }

    private fun relatedForTrack(node: Node): List<NamedPath> {
        val t = node as? FTrack ?: return emptyList()
        return listOfNotNull(
            NamedPath("Волна по треку", RADIO, unmappedPath().child("radio-${t.id}")),
            t.artistId?.let { NamedPath(t.artistName ?: "Исполнитель", ARTIST, unmappedPath().child("artist-$it")) },
            NamedPath("Похожие треки", TRACK, unmappedPath().child("track-${t.id}").child("similar")),
        )
    }

    // Волна: похожие на последний сыгранный трек, без повторов.
    private val played = mutableMapOf<String, MutableSet<String>>()

    private suspend fun radioNext(radio: BaseRadio): RadioNodeUpdate {
        val r = radio as FRadio
        val seen = played.getOrPut(r.id) { mutableSetOf(r.id) }
        val next = murglar.related(r.cursor ?: r.id).filter { seen.add(it.id) }.take(10)
        val updated = FRadio(r.id, r.title, r.description, r.smallCoverUrl, r.bigCoverUrl, next.lastOrNull()?.id ?: r.cursor)
            .apply { withNodeAttributes(radio) }
        return RadioNodeUpdate(updated, next.convertTracks(radio.nodePath))
    }

    override fun canGetNodeFromUrl(url: String) = url.contains("soundcloud.com/")

    override suspend fun getNodeFromUrl(url: String): Node {
        val o = murglar.resolve(url.substringBefore('?'))
        val parent = unmappedPath()
        return when (o.str("kind")) {
            "track" -> murglar.track(o)!!.convertTrack(parent)
            "playlist" -> playlistNode(o.long("id").toString(), parent)
            "user" -> murglar.artist(o)!!.convertArtist(parent)
            else -> error("Ссылка не поддерживается")
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun getTracksByMediaIds(mediaIds: List<String>): List<BaseTrack> =
        murglar.getTracksByMediaIds(mediaIds).convertTracks(unmappedPath()) as List<BaseTrack>
}
