package melos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import melos.host.Services
import melos.host.Toasts
import melos.library.Library
import melos.player.Player
import melos.player.Status

// Страница трека на телефоне — как в Яндекс Музыке: большая обложка во всю ширину,
// название, исполнитель, метаданные и действия. Открывается тапом по обложке в списке.
@Composable
fun DroidTrackScreen(track: BaseTrack) {
    val p = pal
    val current by Player.current.collectAsState()
    val status by Player.status.collectAsState()
    val favs by Library.favorites.items.collectAsState()
    val serviceLikes by melos.library.ServiceLikes.ids.collectAsState()
    val liked = track.mediaId in serviceLikes || favs.any { it.mediaId == track.mediaId }
    val isCurrent = current?.mediaId == track.mediaId
    val service = Services.forTrack(track)

    ScrollList(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillMaxWidth()) {
                Cover(track.bigCoverUrl ?: track.smallCoverUrl, 420.dp, Modifier.fillMaxWidth(), shape = R.md)
                Box(Modifier.fillMaxWidth().height(120.dp).align(Alignment.TopStart)
                    .background(Brush.verticalGradient(listOf(p.bg.copy(alpha = .55f), Color.Transparent))))
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Ic.left, { Nav.back() }, size = 20.dp)
                    Spacer(Modifier.weight(1f))
                    IconBtn(if (liked) Ic.heartOn else Ic.heart, { Player.toggleLike(track) }, active = liked, size = 20.dp)
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(track.fullTitle, style = T.h1.copy(fontSize = 24.sp), maxLines = 2)
                Txt(track.artists, Modifier.padding(top = 2.dp).lux({ Nav.go(Screen.Search(track.artists)) }, R.md, bloom = false),
                    p.acc, T.h2.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    service?.let { ServiceLogo(it.id, 16.dp) }
                    Txt(listOfNotNull(track.albumName?.takeIf { it.isNotBlank() }, fmt(track.durationMs)).joinToString(" · "),
                        Modifier, p.muted, T.small, maxLines = 1)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    PrimaryButton(
                        if (isCurrent && status == Status.PLAYING) "Слушаю" else "Слушать",
                        {
                            if (isCurrent) Player.toggle()
                            else Player.playList(listOf(track), 0, "Трек")
                            BrainBus.wave()
                        },
                        icon = Ic.play)
                }
                IconBtn(Ic.playNext, { Player.playNext(track); Toasts.shortNotify("Сыграет следующим") }, size = 22.dp)
                IconBtn(Ic.plus, { Player.enqueue(listOf(track)); Toasts.shortNotify("В очереди") }, size = 22.dp)
                track.serviceUrl?.let { url -> IconBtn(Ic.link, { melos.core.Browse.url(url) }, size = 20.dp) }
            }
        }
    }
}
