package melos.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import melos.host.Services
import melos.library.Library
import melos.player.Analyzer
import melos.player.Player
import melos.player.Repeat
import melos.player.Status
import kotlin.math.roundToInt

@Composable
fun PlayerBar(modifier: Modifier = Modifier) {
    val p = pal
    val track by Player.current.collectAsState()
    val favs by Library.favorites.items.collectAsState()
    val nowPlaying by Player.nowPlaying.collectAsState()
    val serviceLikes by melos.library.ServiceLikes.ids.collectAsState()
    val liked = track?.let { t -> t.mediaId in serviceLikes || favs.any { it.mediaId == t.mediaId } } == true

    BoxWithConstraints(modifier.fillMaxWidth().clip(R.lg).background(p.card).border(1.dp, p.bd, R.lg)) {
        val compact = maxWidth < 1200.dp
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)) {
        // перемотка во всю ширину панели — как в Murglar: ровная, читаемая, без реакции на звук
        Seek(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            // что играет
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // от обложки расходятся волна палитры и всплеск лайка (раньше — от точки статуса в шапке)
                Box(Modifier.onGloballyPositioned { Anchor.status = it.boundsInWindow().center }.lux({ track?.let { Nav.go(Screen.Track(it)) } ?: Nav.go(Screen.Home) }, R.md, bloom = false)) {
                    // обложка «дышит» на ударе: punch сам по себе — атака+спад, умножаем на силу кика
                    val punchNow = Pulse.punch()
                    Box(Modifier.graphicsLayer { translationY = -2.4f * punchNow; scaleX = 1f + 0.02f * punchNow; scaleY = 1f + 0.02f * punchNow }) {
                        Cover(track?.smallCoverUrl ?: track?.bigCoverUrl, 64.dp, shape = R.md)
                    }
                }
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt(track?.fullTitle ?: "Ничего не играет", style = T.title, modifier = Modifier.enter(0, track?.mediaId))
                    Txt(nowPlaying?.let { "♪ $it" } ?: track?.let { t -> listOfNotNull(t.artists, Services.forTrack(t)?.name).joinToString(" · ") } ?: "Выберите трек или волну",
                        color = if (nowPlaying != null) p.acc else p.muted, style = T.small, modifier = Modifier.enter(1, track?.mediaId))
                }
                track?.let { t -> IconBtn(if (liked) Ic.heartOn else Ic.heart, { Player.toggleLike(t); if (!liked) { BrainBus.heart(); Bursts.fire() } }, active = liked, modifier = Modifier.shake(Player.likeFailedAt)) }
                track?.let { t ->
                    val disliked = Library.dislikes.items.collectAsState().value.let { Library.isDisliked(t) }
                    IconBtn(if (disliked) Ic.dislikeOn else Ic.dislike, { Player.toggleDislike(t) }, active = disliked)
                }
            }
            Box(Modifier.weight(1.1f), contentAlignment = Alignment.Center) { Transport(big = false) }
            // справа: громкость, эквалайзер, панели; на узком окне сон и скорость уходят в «ещё»
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Volume()
                IconBtn(Ic.eq, { Nav.sheet = if (Nav.sheet == Sheet.EQ) null else Sheet.EQ }, active = Player.eqOn.collectAsState().value || Nav.sheet == Sheet.EQ)
                if (!compact) SleepButton()
                MoreMenu(withSleep = compact)
                IconBtn(Ic.lyrics, { Nav.toggleSide(Side.LYRICS) }, active = Nav.side == Side.LYRICS)
                IconBtn(Ic.queue, { Nav.toggleSide(Side.QUEUE) }, active = Nav.side == Side.QUEUE)
            }
        }
        }
    }
}


// Клавиши внутри popup: Esc закрывает сам popup (а не историю навигации), остальное — глобальные хоткеи.
private fun popupKey(onDismiss: () -> Unit) = { e: androidx.compose.ui.input.key.KeyEvent ->
    //
    if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Escape) { onDismiss(); true }
    else globalKey(e)
}

// Кнопки транспорта. Большой вариант — на главной: кнопка «играть» 76 dp, вокруг неё кольцо из точек вспыхивает на бит,
// а пока поток загружается, по орбите бегут точки — как искры в мозге.
@Composable
fun Transport(big: Boolean) {
    val p = pal
    val status by Player.status.collectAsState()
    val shuffle by Player.shuffle.collectAsState()
    val repeat by Player.repeat.collectAsState()
    val playing = status == Status.PLAYING
    val loading = status == Status.OPENING || status == Status.BUFFERING
    val side = if (big) 26.dp else 25.dp
    val play = if (big) 76.dp else 58.dp
    val ring = if (big) 20.dp else 12.dp
    Row2(gap = if (big) 18.dp else 2.dp) {
        IconBtn(Ic.shuffle, { Player.shuffle.value = !shuffle }, active = shuffle, size = side - 2.dp)
        IconBtn(Ic.prev, Player::prev, tint = p.txt, size = side)
        Box(Modifier.size(play + ring), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(play + ring)) {
                Pulse.frame.let { }
                val c = center; val r0 = play.toPx() / 2 + ring.toPx() / 4
                val t = System.currentTimeMillis() / 1000f
                val beat = if (playing) Pulse.beat() else 0f
                val n = if (big) 36 else 26
                for (i in 0 until n) {
                    val a = i / n.toFloat() * 2 * Math.PI.toFloat()
                    val orbit = if (loading) ((a - t * 5f) % (2 * Math.PI.toFloat()) + 2 * Math.PI.toFloat()) % (2 * Math.PI.toFloat()) else 0f
                    val alpha = when {
                        loading -> (1f - orbit / 2.4f).coerceIn(0f, 1f)
                        playing -> 0.2f + Pulse.punch() * 0.6f
                        else -> 0.12f
                    }
                    val rr = r0 + (if (playing) Pulse.punch() * 2.dp.toPx() else 0f)
                    val s = 2.4.dp.toPx()
                    drawRect(lerpC(p.acc, p.c2, i / n.toFloat()).copy(alpha = alpha.coerceIn(0f, 1f)),
                        Offset(c.x + kotlin.math.cos(a) * rr - s / 2, c.y + kotlin.math.sin(a) * rr - s / 2), androidx.compose.ui.geometry.Size(s, s))
                }
            }
            RoundButton(if (playing) Ic.pause else Ic.play, { Player.toggle() }, size = play, filled = p.acc, tint = p.accInk, iconSize = if (big) 30.dp else 26.dp,
                modifier = Modifier)
        }
        // кнопку «следующий» нижнего плеера мозг «нажимает» щупальцем в конце трека
        val press = if (big) 0f else Tentacle.press()
        IconBtn(Ic.next, Player::next, tint = if (press > 0f) p.acc else p.txt, size = side,
            modifier = Modifier.scale(1f - press * 0.18f).then(if (big) Modifier else Modifier.onGloballyPositioned { Tentacle.nextTarget = it.boundsInWindow().center }))
        IconBtn(if (repeat == Repeat.ONE) Ic.repeatOne else Ic.repeat, {
            Player.repeat.value = Repeat.entries[(repeat.ordinal + 1) % Repeat.entries.size]
        }, active = repeat != Repeat.OFF, size = side - 2.dp)
    }
}

@Composable
private fun MoreMenu(withSleep: Boolean) {
    var open by remember { mutableStateOf(false) }
    val rate by Player.rate.collectAsState()
    val steps = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    Box {
        IconBtn(Ic.speed, { open = !open }, active = open || rate != 1f)
        if (open) Popup(alignment = Alignment.BottomCenter, offset = IntOffset(0, -52), onDismissRequest = { open = false }, properties = androidx.compose.ui.window.PopupProperties(focusable = true), onPreviewKeyEvent = popupKey({ open = false })) {
            val pop by androidx.compose.animation.core.animateFloatAsState(if (open) 1f else 0f, tween(220, easing = EaseLux))
            Card(pad = 8.dp, color = pal.card2, modifier = Modifier.graphicsLayer { alpha = pop; scaleX = .96f + .04f * pop; scaleY = scaleX; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f) }) {
                Cap("Скорость", Modifier.padding(8.dp))
                steps.forEach { r -> MenuItem("${r}×", on = r == rate) { Player.rate.value = r } }
                if (withSleep) {
                    Cap("Таймер сна", Modifier.padding(8.dp))
                    listOf(15, 30, 60).forEach { m -> MenuItem("$m минут") { Player.sleepIn(m); open = false } }
                    if (Player.sleepAt.collectAsState().value != null) MenuItem("Выключить таймер") { Player.sleepIn(null); open = false }
                }
            }
        }
    }
}

// Перемотка точками: сыгранное — акцентом, у головки точки подпрыгивают в такт спектру.
// Перемотка: длинная ровная полоса во всю ширину. Сыгранное — акцентом, при наведении полоса толще,
// появляется ручка и подсказка со временем; клик и перетаскивание — перемотка.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Seek(modifier: Modifier = Modifier) {
    val p = pal
    val time by Player.time.collectAsState()
    val length by Player.length.collectAsState()
    val track by Player.current.collectAsState()
    val live = Player.isLive(track)
    var hover by remember { mutableStateOf<Float?>(null) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (length > 0) (time.toFloat() / length).coerceIn(0f, 1f) else 0f
    val thick by androidx.compose.animation.core.animateDpAsState(if (hover != null || drag != null) 6.dp else 4.dp, androidx.compose.animation.core.tween(160))
    // скраббинг: над полосой — крупный таймкод места, куда тянем
    val scrubAt = drag?.let { (it * length).toLong() }
    Row(modifier.height(26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt(fmt(drag?.let { (it * length).toLong() } ?: time), Modifier.width(50.dp), p.txt.copy(alpha = .8f), T.monoSmall.copy(textAlign = androidx.compose.ui.text.style.TextAlign.End))
        Box(Modifier.weight(1f).height(26.dp)) {
            Canvas(Modifier.fillMaxWidth().height(26.dp).pointerHoverIcon(if (live) PointerIcon.Default else PointerIcon.Hand)
                .onPointerEvent(PointerEventType.Move) { hover = it.changes.first().position.x / size.width }
                .onPointerEvent(PointerEventType.Exit) { hover = null }
                .pointerInput(length, live) { detectTapGestures { if (!live && length > 0) Player.seek((it.x / size.width * length).toLong()) } }
                .pointerInput(length, live) {
                    detectDragGestures(onDragEnd = { drag?.let { Player.seek((it * length).toLong()) }; drag = null }, onDragCancel = { drag = null }) { c, _ ->
                        if (!live) drag = (c.position.x / size.width).coerceIn(0f, 1f)
                    }
                }) {
                val h = thick.toPx(); val cy = size.height / 2; val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
                drawRoundRect(p.txt.copy(alpha = .16f), Offset(0f, cy - h / 2), androidx.compose.ui.geometry.Size(size.width, h), r)
                if (live) {
                    drawRoundRect(p.acc.copy(alpha = .55f), Offset(0f, cy - h / 2), androidx.compose.ui.geometry.Size(size.width, h), r)
                } else {
                    hover?.let { hx -> if (hx > frac) drawRoundRect(p.txt.copy(alpha = .14f), Offset(0f, cy - h / 2), androidx.compose.ui.geometry.Size(size.width * hx, h), r) }
                    drawRoundRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(p.acc, lerpC(p.acc, p.c2, .6f))), Offset(0f, cy - h / 2),
                        androidx.compose.ui.geometry.Size(size.width * frac, h), r)
                    if (hover != null || drag != null) {
                        drawCircle(p.acc.copy(alpha = .25f), 10.dp.toPx(), Offset(size.width * frac, cy))
                        drawCircle(Color.White, 6.dp.toPx(), Offset(size.width * frac, cy))
                    }
                }
            }
            hover?.takeIf { !live && length > 0 }?.let { hv ->
                Popup(offset = IntOffset(0, -38)) {
                    Box(Modifier.clip(R.sm).background(p.card2).border(1.dp, p.bd, R.sm).padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Txt(fmt((hv * length).toLong()), style = T.monoSmall)
                    }
                }
            }
            scrubAt?.takeIf { length > 0 }?.let { at ->
                Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, -104)) {
                    Box(Modifier.clip(R.lg).background(p.card2).border(1.dp, p.acc, R.lg).padding(horizontal = 16.dp, vertical = 7.dp)) {
                        Txt(fmt(at), color = p.acc, style = T.h2.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                    }
                }
            }
        }
        Txt(if (live) "LIVE" else fmt(length), Modifier.width(50.dp), if (live) p.critical else p.txt.copy(alpha = .8f), T.monoSmall)
    }
}

// Громкость 0–100%: иконка открывает вертикальный ползунок над собой; колесо — и над иконкой, и над ползунком.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Volume() {
    val p = pal
    val vol by Player.volume.collectAsState()
    var open by remember { mutableStateOf(false) }
    var before by remember { mutableFloatStateOf(80f) }
    fun set(v: Int) { Player.volume.value = v.coerceIn(0, 100) }
    val wheel = Modifier.onPointerEvent(PointerEventType.Scroll) { e -> set(Player.volume.value - (e.changes.first().scrollDelta.y * 3).roundToInt()) }
    Box {
        IconBtn(if (vol == 0) Ic.mute else Ic.volume, { open = !open }, modifier = wheel, active = open)
        if (open) Popup(alignment = Alignment.BottomCenter, offset = IntOffset(0, -56), onDismissRequest = { open = false },
            properties = androidx.compose.ui.window.PopupProperties(focusable = true), onPreviewKeyEvent = popupKey({ open = false })) {
            Column(Modifier.width(64.dp).clip(R.lg).background(p.card2).border(1.dp, p.bd, R.lg).then(wheel).padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("$vol", color = p.txt, style = T.monoSmall)
                Canvas(Modifier.width(44.dp).height(170.dp).pointerHoverIcon(PointerIcon.Hand)
                    .pointerInput(Unit) { detectTapGestures { set(((1 - it.y / size.height) * 100).roundToInt()) } }
                    .pointerInput(Unit) { detectDragGestures { c, _ -> set(((1 - c.position.y / size.height) * 100).roundToInt()) } }) {
                    val w = 6.dp.toPx(); val x = size.width / 2 - w / 2; val r = androidx.compose.ui.geometry.CornerRadius(w / 2)
                    val f = Player.volume.value / 100f
                    drawRoundRect(p.txt.copy(alpha = .16f), Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height), r)
                    drawRoundRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(lerpC(p.acc, p.c2, .6f), p.acc)), Offset(x, size.height * (1 - f)),
                        androidx.compose.ui.geometry.Size(w, size.height * f), r)
                    drawCircle(p.acc.copy(alpha = .25f), 11.dp.toPx(), Offset(size.width / 2, size.height * (1 - f)))
                    drawCircle(Color.White, 7.dp.toPx(), Offset(size.width / 2, size.height * (1 - f)))
                }
                IconBtn(if (vol == 0) Ic.mute else Ic.volume, {
                    if (vol > 0) { before = vol.toFloat(); set(0) } else set(before.toInt().coerceAtLeast(10))
                }, size = 18.dp)
            }
        }
    }
}

@Composable
private fun SleepButton() {
    val p = pal
    val at by Player.sleepAt.collectAsState()
    var open by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) { while (at != null) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    Box {
        Row2(gap = 0.dp) {
            IconBtn(Ic.moon, { open = !open }, active = at != null)
            at?.let { Txt(fmt(it - now), color = p.acc, style = T.monoSmall) }
        }
        if (open) Popup(alignment = Alignment.BottomCenter, offset = IntOffset(0, -44), onDismissRequest = { open = false }, properties = androidx.compose.ui.window.PopupProperties(focusable = true), onPreviewKeyEvent = popupKey({ open = false })) {
            val pop by androidx.compose.animation.core.animateFloatAsState(if (open) 1f else 0f, tween(220, easing = EaseLux))
            Card(pad = 6.dp, color = p.card2, modifier = Modifier.graphicsLayer { alpha = pop; scaleX = .96f + .04f * pop; scaleY = scaleX; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f) }) {
                Cap("Таймер сна", Modifier.padding(6.dp))
                listOf(15, 30, 45, 60, 90).forEach { m -> MenuItem("$m минут") { Player.sleepIn(m); open = false } }
                if (at != null) MenuItem("Выключить") { Player.sleepIn(null); open = false }
            }
        }
    }
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


