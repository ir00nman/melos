package melos.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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

// Строка трека: обложка, название (акцентом, если играет — с «эквалайзером»), исполнители, действия при наведении.
@Composable
fun TrackRow(
    track: BaseTrack, index: Int, onPlay: () -> Unit, modifier: Modifier = Modifier,
    showCover: Boolean = true, onRemove: (() -> Unit)? = null, dense: Boolean = false, showLike: Boolean = true,
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
    val clipboard = LocalClipboardManager.current
    val service = Services.forTrack(track)

    ContextMenuArea(items = {
        buildList {
            add(ContextMenuItem("Сыграть следующим") { Player.playNext(track) })
            add(ContextMenuItem("Добавить в очередь") { Player.enqueue(listOf(track)) })
            add(ContextMenuItem(if (liked) "Убрать из избранного" else "В избранное") { Player.toggleLike(track) })
            add(ContextMenuItem("Сохранить на устройство…") { saveToDevice(listOf(track)) })
            runCatching { service?.murglar?.nodeResolver?.getRelatedNodePaths(track) }.getOrNull().orEmpty().forEach { rel ->
                add(ContextMenuItem(rel.name) { Nav.go(Screen.OpenPath(rel.path, rel.name)) })
            }
            track.serviceUrl?.let { url ->
                add(ContextMenuItem("Копировать ссылку") { clipboard.setText(AnnotatedString(url)) })
                if (url.startsWith("http")) add(ContextMenuItem("Открыть в браузере") { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } })
            }
            onRemove?.let { add(ContextMenuItem("Убрать") { it() }) }
        }
    }) {
        Row(
            modifier.fillMaxWidth().clip(R.md)
                .background(when { isCurrent -> p.acc.copy(alpha = .10f); hovered -> p.txt.copy(alpha = .05f); else -> Color.Transparent })
                .hoverable(src).clickable { onPlay(); BrainBus.absorb() }.pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 8.dp, vertical = if (dense) 4.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showCover) Box(contentAlignment = Alignment.Center) {
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
            if (hovered || (liked && showLike)) Row2(gap = 0.dp) {
                if (hovered) {
                    IconBtn(Ic.playNext, { Player.playNext(track) }, size = 15.dp)
                    IconBtn(Ic.plus, { Player.enqueue(listOf(track)) }, size = 15.dp)
                    if (onRemove != null) IconBtn(Ic.x, onRemove, size = 14.dp)
                }
                if (showLike) IconBtn(if (liked) Ic.heartOn else Ic.heart, { Player.toggleLike(track); if (!liked) BrainBus.heart() }, size = 15.dp, active = liked)
            }
            when (val d = downloads[track.mediaId]) {
                is Downloads.State.Running -> Txt("${(d.progress * 100).toInt()}%", color = p.acc, style = T.monoSmall)
                is Downloads.State.Done -> Icon(Ic.check, p.ok, size = 13.dp)
                else -> {}
            }
            Txt(if (track.durationMs > 0) fmt(track.durationMs) else "LIVE", color = if (track.durationMs > 0) p.muted else p.critical, style = T.monoSmall)
        }
    }
}

// Три подпрыгивающих столбика у играющего трека.
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

// Карточка раздела / альбома / исполнителя / плейлиста.
@Composable
fun NodeCard(node: Node, modifier: Modifier = Modifier, index: Int = 0, onClick: () -> Unit) {
    val p = pal
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val round = node.nodeType == NodeType.ARTIST
    val fallback = when (node.nodeType) { NodeType.ARTIST -> Ic.user; NodeType.ALBUM -> Ic.disc; NodeType.PLAYLIST -> Ic.list; NodeType.RADIO -> Ic.wave; else -> Ic.folder }
    Column(
        modifier.width(150.dp).enter(index, node.nodePath).clip(R.md).hoverable(src)
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

// Строка раздела списком (корни сервисов, папки).
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

/** «Сохранить на устройство…»: выбрать папку (на маке — системный диалог Finder) и сохранить туда треки с тегами и обложкой. */
fun saveToDevice(tracks: List<com.badmanners.murglar.lib.core.model.track.BaseTrack>) {
    if (tracks.isEmpty()) return
    val dir = pickFolder(melos.download.Downloads.folder) ?: return
    melos.download.Downloads.download(tracks, dir)
    melos.host.Toasts.shortNotify(if (tracks.size == 1) "Сохраняю в ${dir.name}" else "Сохраняю ${tracks.size} треков в ${dir.name}")
}

private fun pickFolder(start: java.io.File): java.io.File? {
    if (melos.core.Paths.isMac) {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val d = java.awt.FileDialog(null as java.awt.Frame?, "Куда сохранить", java.awt.FileDialog.LOAD)
            d.directory = start.apply { mkdirs() }.path
            d.isVisible = true
            return d.file?.let { java.io.File(d.directory, it) }
        } finally { System.setProperty("apple.awt.fileDialogForDirectories", "false") }
    }
    val ch = javax.swing.JFileChooser(start).apply { fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY; dialogTitle = "Куда сохранить" }
    return if (ch.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) ch.selectedFile else null
}

fun openNode(node: Node) {
    if (node.nodeType == NodeType.RADIO && node is com.badmanners.murglar.lib.core.model.radio.BaseRadio) {
        Player.playRadio(node); BrainBus.wave(1.2f); return
    }
    Nav.go(Screen.Open(node))
}
