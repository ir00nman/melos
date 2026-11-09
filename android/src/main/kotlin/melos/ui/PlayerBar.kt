package melos.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.border
import melos.library.Library
import melos.player.Player
import melos.player.Repeat
import melos.player.Status

// Мини-плеер над нижней навигацией — как в Яндекс Музыке: одна строка с обложкой, названием,
// play и next; тонкая полоса прогресса сверху. Тап по строке — полноэкранный плеер.

@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val p = pal
    val track by Player.current.collectAsState()
    val status by Player.status.collectAsState()
    val time by Player.time.collectAsState()
    val length by Player.length.collectAsState()
    val playing = status == Status.PLAYING
    val t = track ?: return
    val frac = if (length > 0) (time.toFloat() / length).coerceIn(0f, 1f) else 0f

    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(R.lg).background(p.card).border(1.dp, p.bd, R.lg)
        .clickable(onClick = onOpen)) {
        // прогресс — тонкая полоса по верху
        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            drawRoundRect(p.txt.copy(alpha = .12f), Offset.Zero, Size(size.width, size.height), CornerRadius(size.height / 2))
            drawRoundRect(p.acc, Offset.Zero, Size(size.width * frac, size.height), CornerRadius(size.height / 2))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Cover(t.smallCoverUrl ?: t.bigCoverUrl, 46.dp, shape = R.md)
            Column(Modifier.weight(1f)) {
                Txt(t.fullTitle, style = T.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                Txt(t.artists, color = p.muted, style = T.small, maxLines = 1)
            }
            RoundButton(if (playing) Ic.pause else Ic.play, { Player.toggle() }, size = 44.dp, filled = p.acc, tint = p.accInk, iconSize = 22.dp)
            IconBtn(Ic.next, Player::next, size = 22.dp)
        }
    }
}

// Полноэкранный плеер (стиль Яндекс Музыки): большая обложка, управление, очередь/текст/эквалайзер.
@Composable
fun NowPlaying(onClose: () -> Unit) {
    val p = pal
    val track by Player.current.collectAsState()
    val status by Player.status.collectAsState()
    val time by Player.time.collectAsState()
    val length by Player.length.collectAsState()
    val shuffle by Player.shuffle.collectAsState()
    val repeat by Player.repeat.collectAsState()
    val favs by Library.favorites.items.collectAsState()
    val serviceLikes by melos.library.ServiceLikes.ids.collectAsState()
    val t = track ?: return
    val playing = status == Status.PLAYING
    val liked = t.mediaId in serviceLikes || favs.any { it.mediaId == t.mediaId }
    val disliked = Library.isDisliked(t)
    val live = Player.isLive(t)

    Column(Modifier.fillMaxWidth().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
        // верхняя строка: свернуть, «Сейчас играет», эквалайзер
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.chevronDown, onClose, size = 22.dp)
            Txt("Сейчас играет", Modifier.weight(1f), p.muted, T.cap.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
            IconBtn(Ic.eq, { Nav.sheet = Sheet.EQ }, active = Nav.sheet == Sheet.EQ)
        }
        // обложка
        Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.Center) {
            Cover(t.bigCoverUrl ?: t.smallCoverUrl, 320.dp, shape = R.lg)
        }
        // название и лайк
        Row(Modifier.fillMaxWidth().padding(top = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(t.fullTitle, style = T.h1.copy(fontSize = 24.sp), maxLines = 2)
                Txt(t.artists, Modifier.padding(top = 4.dp), p.muted, T.h2.copy(fontWeight = FontWeight.Normal), maxLines = 1)
            }
            IconBtn(if (liked) Ic.heartOn else Ic.heart, { Player.toggleLike(t); if (!liked) { BrainBus.heart(); Bursts.fire() } }, size = 24.dp, active = liked)
            IconBtn(if (disliked) Ic.dislikeOn else Ic.dislike, { Player.toggleDislike(t) }, size = 22.dp, active = disliked)
        }
        // перемотка
        PlayerSeek(Modifier.fillMaxWidth().padding(top = 24.dp), live)
        // управление: перемешать, назад, играть, вперёд, повтор
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.shuffle, { Player.shuffle.value = !shuffle }, active = shuffle, size = 22.dp)
            RoundButton(Ic.prev, Player::prev, size = 62.dp, tint = p.txt, iconSize = 26.dp)
            RoundButton(if (playing) Ic.pause else Ic.play, { Player.toggle() }, size = 84.dp, filled = p.acc, tint = p.accInk, iconSize = 36.dp)
            RoundButton(Ic.next, Player::next, size = 62.dp, tint = p.txt, iconSize = 26.dp)
            IconBtn(if (repeat == Repeat.ONE) Ic.repeatOne else Ic.repeat, {
                Player.repeat.value = Repeat.entries[(repeat.ordinal + 1) % Repeat.entries.size]
            }, active = repeat != Repeat.OFF, size = 22.dp)
        }
        // низ: очередь и текст
        Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
            IconBtn(Ic.queue, { Nav.toggleSide(Side.QUEUE) }, active = Nav.side == Side.QUEUE, size = 22.dp)
            IconBtn(Ic.lyrics, { Nav.toggleSide(Side.LYRICS) }, active = Nav.side == Side.LYRICS, size = 22.dp)
        }
    }
}

// Ползунок перемотки плеера: толстая полоса с ручкой, время по краям.
@Composable
private fun PlayerSeek(modifier: Modifier, live: Boolean) {
    val p = pal
    val time by Player.time.collectAsState()
    val length by Player.length.collectAsState()
    var drag by remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    val frac = drag ?: if (length > 0) (time.toFloat() / length).coerceIn(0f, 1f) else 0f
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt(fmt(drag?.let { (it * length).toLong() } ?: time), Modifier, p.muted, T.num)
        Box(Modifier.weight(1f).height(30.dp)) {
            Canvas(Modifier.fillMaxWidth().height(30.dp)
                .pointerInput(length, live) { detectTapGestures { if (!live && length > 0) Player.seek((it.x / size.width * length).toLong()) } }
                .pointerInput(length, live) {
                    detectDragGestures(onDragEnd = { drag?.let { Player.seek((it * length).toLong()) }; drag = null }, onDragCancel = { drag = null }) { c, _ ->
                        if (!live) drag = (c.position.x / size.width).coerceIn(0f, 1f)
                    }
                }) {
                val h = 7.dp.toPx(); val cy = size.height / 2; val r = CornerRadius(h / 2)
                drawRoundRect(p.txt.copy(alpha = .14f), Offset(0f, cy - h / 2), Size(size.width, h), r)
                drawRoundRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(p.acc, lerpC(p.acc, p.c2, .6f))),
                    Offset(0f, cy - h / 2), Size(size.width * frac, h), r)
                drawCircle(p.acc, 9.dp.toPx(), Offset(size.width * frac, cy))
                drawCircle(Color.White, 5.dp.toPx(), Offset(size.width * frac, cy))
            }
        }
        Txt(if (live) "LIVE" else fmt(length), Modifier, p.muted, T.num)
    }
}
