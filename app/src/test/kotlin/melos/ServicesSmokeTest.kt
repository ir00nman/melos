package melos

import com.badmanners.murglar.lib.core.model.track.BaseTrack
import melos.host.Services
import melos.lyrics.LyricsService
import melos.player.Player
import melos.player.VlcEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

// Живые запросы к сервисам: MELOS_NET=1 ./gradlew :app:test
class ServicesSmokeTest {
    private val enabled = System.getenv("MELOS_NET") == "1"

    private suspend fun search(service: String, root: String, q: String): List<BaseTrack> {
        val r = Services.byId(service)!!.murglar.nodeResolver
        val node = r.getRootNodes(false).first { it.nodeId == root }
        val searchNode = r.specifySearchableNode(node, q)
        return r.getNodeContent(searchNode.nodePath, 0).filterIsInstance<BaseTrack>()
    }

    @Test fun soundcloudAndRadio() = runBlocking {
        if (!enabled) return@runBlocking
        Services.load()
        val sc = search("sc", "searchTracks", "daft punk")
        println("SC: ${sc.size} — ${sc.take(3).map { it.tag }}")
        assertTrue(sc.isNotEmpty())
        println("SC stream: " + Player.resolve(sc.first()).take(80))
        val radio = search("radio", "searchStations", "jazz")
        println("Radio: ${radio.size} — ${radio.take(3).map { it.title }}")
        assertTrue(radio.isNotEmpty())
        println("Lyrics: " + LyricsService.find(sc.first())?.plain?.take(60))
        println("VLC found: ${VlcEngine.found} ${if (VlcEngine.found) VlcEngine.version() else ""}")
    }
}
