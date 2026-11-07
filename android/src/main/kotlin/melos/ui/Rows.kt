package melos.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.node.Node.Companion.bestCoverUrl
import com.badmanners.murglar.lib.core.model.node.NodeType
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import melos.download.Downloads
import melos.host.Services
import melos.library.Library
import melos.player.Player
import melos.player.Status

// Копия десктопного Rows.kt под телефон: без контекст-меню (desktop-only) и без «Сохранить на устройство»
// (AWT/SAF). Отличия от десктопа помечены — при правках строк держать файлы вразумительно похожими.

@Composable
fun TrackRow(
    track: BaseTrack, index: Int, onPlay: () -> Unit, modifier: Modifier = Modifier,
    showCover: Boolean = true, onRemove: (() -> Unit)? = null, dense: Boolean = false, showLike: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = pal
    val current by Player.current.collectAsState()
    val status by Player.status.collectAsState()
    val favs by Library.favorites.items.collectAsState()
    val serviceLikes by melos.library.ServiceLikes.ids.collectAsState()
    val downloads by Downloads.states.collectAsState()
    val isCurrent = current?.mediaId == track.mediaId
    val liked = track.mediaId in serviceLikes || favs.any { it.mediaId == track.mediaId }
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val lastPlay = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    fun playOnce() { val now = System.currentTimeMillis(); if (now - lastPlay.longValue > 400) { lastPlay.longValue = now; onPlay() } }

    Row(
        modifier.fillMaxWidth().clip(R.md)
            .background(when { isCurrent -> p.acc.copy(alpha = .10f); hovered -> p.txt.copy(alpha = .05f); else -> Color.Transparent })
            .hoverable(src).clickable { playOnce(); BrainBus.absorb() }.pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = if (dense) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showCover) Box(Modifier.clip(R.sm).clickable { Nav.go(Screen.Track(track)) }, contentAlignment = Alignment.Center) {
            Cover(track.smallCoverUrl ?: track.bigCoverUrl, if (dense) 34.dp else 40.dp)
            if (isCurrent) Box(Modifier.size(if (dense) 34.dp else 40.dp).clip(R.sm).background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
                Bars(status == Status.PLAYING, Color.White)
            }
        } else Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) Bars(status == Status.PLAYING, p.acc) else Txt("${index + 1}", color = p.muted, style = T.monoSmall)
        }
        Column(Modifier.weight(1f)) {
            Txt(track.fullTitle, color = if (isCurrent) p.acc else p.txt, style = T.body.copy(fontWeight = FontWeight.Medium))
            Txt(listOfNotNull(track.artists.ifEmpty { null }, track.albumName?.takeIf { !dense }).joinToString(" · "), color = p.muted, style = T.small)
        }
        // на телефоне наведения нет — лайк виден всегда, остальные действия появятся с долгим нажатием
        if (showLike) IconBtn(if (liked) Ic.heartOn else Ic.heart, { Player.toggleLike(track); if (!liked) BrainBus.heart() }, size = 15.dp, active = liked, modifier = Modifier.shake(Player.likeFailedAt))
        trailing?.invoke()
        when (val d = downloads[track.mediaId]) {
            is Downloads.State.Running -> Txt("${(d.progress * 100).toInt()}%", color = p.acc, style = T.monoSmall)
            is Downloads.State.Done -> Icon(Ic.check, p.ok, size = 13.dp)
            else -> {}
        }
        Txt(if (track.durationMs > 0) fmt(track.durationMs) else "LIVE", color = if (track.durationMs > 0) p.muted else p.critical, style = T.monoSmall)
    }
}

@Composable
fun Bars(playing: Boolean, color: Color) {
    val inf = rememberInfiniteTransition()
    val hs = listOf(0, 140, 280).map { d ->
        if (playing && Look.motion) inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(420, d, easing = EaseHeavy), RepeatMode.Reverse)).value else 0.45f
    }
    Row(Modifier.height(13.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        hs.forEach { h -> Box(Modifier.width(3.dp).fillMaxHeight(h).clip(R.pill).background(color)) }
    }
}

@Composable
fun NodeCard(node: Node, modifier: Modifier = Modifier, index: Int = 0, onClick: () -> Unit) {
    val p = pal
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val lastPlay = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    fun playOnce() { val now = System.currentTimeMillis(); if (now - lastPlay.longValue > 400) { lastPlay.longValue = now; onPlay() } }
    val round = node.nodeType == NodeType.ARTIST
    val fallback = when (node.nodeType) { NodeType.ARTIST -> Ic.user; NodeType.ALBUM -> Ic.disc; NodeType.PLAYLIST -> Ic.list; NodeType.RADIO -> Ic.wave; else -> Ic.folder }
    Column(
        modifier.width(150.dp).enter(index, node.nodePath).hoverable(src)
            .background(if (hovered) p.txt.copy(alpha = .05f) else Color.Transparent)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            Cover(node.bestCoverUrl, 134.dp, shape = R.md, fallback = fallback, round = round)
            if (node.nodeType == NodeType.RADIO) Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(30.dp).clip(CircleShape).background(p.acc), contentAlignment = Alignment.Center) {
                Icon(Ic.play, p.accInk, size = 14.dp)
            }
        }
        Txt(node.nodeName, style = T.body.copy(fontWeight = FontWeight.Medium), maxLines = 2)
        node.nodeSummary?.takeIf { it.isNotBlank() }?.let { Txt(it, color = p.muted, style = T.small, maxLines = 2) }
    }
}

@Composable
fun DirRow(node: Node, onClick: () -> Unit) {
    val p = pal
    Row(Modifier.fillMaxWidth().clip(R.md).lux(onClick, R.md, bloom = false).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(if (node.nodeType == NodeType.RADIO) Ic.wave else Ic.folder, p.muted, size = 16.dp)
        Txt(node.nodeName, Modifier.weight(1f))
        node.nodeSummary?.let { Txt(it, color = p.muted, style = T.small) }
        Icon(Ic.right, p.muted, size = 14.dp)
    }
}

/** «Сохранить на устройство» на Android появится вместе с загрузками (этап 2, SAF/MediaStore). */
fun saveToDevice(tracks: List<com.badmanners.murglar.lib.core.model.track.BaseTrack>) {
    melos.host.Toasts.error("Загрузки на Android — следующий этап")
}

fun openNode(node: Node) {
    if (node.nodeType == NodeType.RADIO && node is com.badmanners.murglar.lib.core.model.radio.BaseRadio) {
        Player.playRadio(node); BrainBus.wave(1.2f); return
    }
    Nav.go(Screen.Open(node))
}

@Composable
fun MenuItem(text: String, on: Boolean = false, onClick: () -> Unit) {
    val p = pal
    Row(Modifier.widthIn(min = 180.dp).heightIn(min = 44.dp).clip(R.sm).lux(onClick, R.sm, bloom = false).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp)) { if (on) Icon(Ic.check, p.acc, size = 16.dp) }
        Txt(text, style = T.body.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal))
    }
}
