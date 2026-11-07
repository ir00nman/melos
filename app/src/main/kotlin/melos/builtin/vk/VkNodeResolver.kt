package melos.builtin.vk

import com.badmanners.murglar.lib.core.model.node.NamedPath
import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.node.NodeParameters.PagingType.ENDLESSLY_PAGEABLE
import com.badmanners.murglar.lib.core.model.node.NodeParameters.PagingType.NON_PAGEABLE
import com.badmanners.murglar.lib.core.model.node.NodeType.ALBUM
import com.badmanners.murglar.lib.core.model.node.NodeType.ARTIST
import com.badmanners.murglar.lib.core.model.node.NodeType.NODE
import com.badmanners.murglar.lib.core.model.node.NodeType.PLAYLIST
import com.badmanners.murglar.lib.core.model.node.NodeType.TRACK
import com.badmanners.murglar.lib.core.model.node.Path
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import com.badmanners.murglar.lib.core.node.BaseNodeResolver
import com.badmanners.murglar.lib.core.node.Directory
import com.badmanners.murglar.lib.core.node.LikeConfig
import com.badmanners.murglar.lib.core.node.MappedEntity
import com.badmanners.murglar.lib.core.node.Root
import com.badmanners.murglar.lib.core.node.Search
import com.badmanners.murglar.lib.core.node.Track
import melos.builtin.BuiltinMessages
import melos.builtin.FAlbum
import melos.builtin.FPlaylist
import melos.builtin.FTrack

class VkNodeResolver(murglar: VkMurglar, messages: BuiltinMessages) :
    BaseNodeResolver<VkMurglar, BuiltinMessages>(murglar, messages) {

    override val configurations = listOf(
        // как в разделе «Музыка» VK: моя музыка, рекомендации, плейлисты
        Root("myTracks", { "Моя музыка" }, ENDLESSLY_PAGEABLE, false, true, TRACK,
            nodeContentSupplier = { p, page, _ -> murglar.myTracks(page).convertTracks(p) }),
        Root("recommendations", { "Рекомендации" }, ENDLESSLY_PAGEABLE, false, false, TRACK,
            nodeContentSupplier = { p, page, _ -> murglar.recommendations(page).convertTracks(p) }),
        Root("myPlaylists", { "Плейлисты" }, NON_PAGEABLE, true, true, PLAYLIST,
            nodeContentSupplier = { p, _, _ -> murglar.myPlaylists().map { pl -> if (pl is FAlbum) pl.convertAlbum(p) else (pl as FPlaylist).convertPlaylist(p) } }),

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
            nodeContentSupplier = { p, _, params -> murglar.similar(params["trackId"]!!).convertTracks(p) }),
        Track("*/track-<trackId>", relatedPaths = ::relatedForTrack,
            like = LikeConfig(rootNodePath("myTracks"), ::likeTrack),
            nodeSupplier = { p, params -> murglar.tracksByIds(listOf(params["trackId"]!!)).firstOrNull()?.convertTrack(p) ?: error("Трек недоступен") }),

        MappedEntity("*/album-<id>", NON_PAGEABLE, false, ALBUM, TRACK,
            relatedPaths = ::relatedForAlbum,
            nodeSupplier = { p, params -> playlistNode(params["id"]!!, p) },
            nodeContentSupplier = { p, _, params -> murglar.playlistTracks(params["id"]!!).convertTracks(p) }),
        MappedEntity("*/playlist-<id>", NON_PAGEABLE, false, PLAYLIST, TRACK,
            nodeSupplier = { p, params -> playlistNode(params["id"]!!, p) },
            nodeContentSupplier = { p, _, params -> murglar.playlistTracks(params["id"]!!).convertTracks(p) }),

        Directory("*/artist-<artistId>/tracks", ENDLESSLY_PAGEABLE, false, TRACK,
            nodeContentSupplier = { p, page, params -> murglar.artistTracks(params["artistId"]!!, page).convertTracks(p) }),
        Directory("*/artist-<artistId>/albums", NON_PAGEABLE, true, ALBUM,
            nodeContentSupplier = { p, _, params -> murglar.artistAlbums(params["artistId"]!!).convertAlbums(p) }),
        MappedEntity("*/artist-<artistId>", NON_PAGEABLE, true, ARTIST, NODE,
            nodeSupplier = { p, params -> murglar.artistInfo(params["artistId"]!!).convertArtist(p) },
            nodeContentSupplier = { p, _, _ ->
                listOf(subdirectoryNode("tracks", messages.tracks, p), subdirectoryNode("albums", messages.albums, p))
            }),
    )

    override val isAvailable = true

    // без входа VK ничего не отдаёт — даже поиск
    override fun getRootNodes(onlyAvailable: Boolean) = if (murglar.loginResolver.isLogged) super.getRootNodes(false) else emptyList()

    private suspend fun playlistNode(id: String, parent: Path): Node = when (val pl = murglar.playlistInfo(id)) {
        is FAlbum -> pl.convertAlbum(parent)
        is FPlaylist -> pl.convertPlaylist(parent)
        else -> error("Плейлист недоступен")
    }

    private fun relatedForTrack(node: Node): List<NamedPath> {
        val t = node as? FTrack ?: return emptyList()
        return listOfNotNull(
            t.albumId?.let { NamedPath(t.albumName ?: "Альбом", ALBUM, unmappedPath().child("album-$it")) },
        ) + t.artistIds.zip(t.artistNames).map { (id, name) -> NamedPath(name, ARTIST, unmappedPath().child("artist-$id")) } +
            NamedPath("Похожие треки", TRACK, unmappedPath().child("track-${t.id}").child("similar"))
    }

    private fun relatedForAlbum(node: Node): List<NamedPath> {
        val a = node as? FAlbum ?: return emptyList()
        return a.artistIds.zip(a.artistNames).map { (id, name) -> NamedPath(name, ARTIST, unmappedPath().child("artist-$id")) }
    }

    private suspend fun likeTrack(node: Node, like: Boolean) = murglar.like((node as FTrack).id, like)

    override fun canGetNodeFromUrl(url: String) = Regex("""vk\.(com|ru)/audio-?\d+_\d+""").containsMatchIn(url)

    override suspend fun getNodeFromUrl(url: String): Node {
        val id = Regex("""audio(-?\d+_\d+(?:_[0-9a-f]+)?)""").find(url)?.groupValues?.get(1) ?: error("Ссылка не поддерживается")
        return murglar.tracksByIds(listOf(id)).firstOrNull()?.convertTrack(unmappedPath()) ?: error("Трек недоступен")
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun getTracksByMediaIds(mediaIds: List<String>): List<BaseTrack> =
        murglar.getTracksByMediaIds(mediaIds).convertTracks(unmappedPath()) as List<BaseTrack>
}
