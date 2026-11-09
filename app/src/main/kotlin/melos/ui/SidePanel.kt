package melos.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.badmanners.murglar.lib.core.model.tag.Lyrics
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import melos.lyrics.LyricsService
import melos.player.Player
import kotlin.math.roundToInt

/** Текст играющего трека, уже найденный (или ищется): один на панель, чтобы переключение вкладок не искало заново. */
private class LyricsState(val lyrics: Lyrics?, val loading: Boolean)

@Composable
private fun rememberLyrics(track: BaseTrack?): LyricsState {
    var lyrics by remember(track?.mediaId) { mutableStateOf<Lyrics?>(null) }
    var loading by remember(track?.mediaId) { mutableStateOf(track != null) }
    LaunchedEffect(track?.mediaId) {
        val t = track ?: return@LaunchedEffect
        lyrics = LyricsService.find(t); loading = false
    }
    return LyricsState(lyrics, loading)
}

@Composable
fun SidePanel(modifier: Modifier = Modifier) {
    val p = pal
    val side = Nav.side
    val track by Player.current.collectAsState()
    val time by Player.time.collectAsState()
    val queue by Player.queue.collectAsState()
    val ls = rememberLyrics(track)

    Column(modifier.clip(R.lg).background(p.card).border(1.dp, p.bd, R.lg).padding(10.dp)) {
        SideToggle(side, { Nav.showSide(it) }, Modifier.fillMaxWidth(), queueCount = queue.size.takeIf { it > 0 })
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (Look.motion) AnimatedContent(side, transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(tween(320, easing = EaseLux)) { dir * it / 5 } + fadeIn(tween(220)))
                    .togetherWith(slideOutHorizontally(tween(220, easing = EaseLux)) { -dir * it / 5 } + fadeOut(tween(160)))
            }, label = "side") { s -> SideBody(s, ls, time) }
            else SideBody(side, ls, time)
        }
    }
}

@Composable
private fun SideBody(side: Side, ls: LyricsState, time: Long) = Box(Modifier.fillMaxSize()) {
    when (side) {
        Side.LYRICS -> LyricsView(ls, time)
        Side.QUEUE -> Column(Modifier.fillMaxSize()) { QueueView() }
    }
}

/**
 * Переключатель «Текст | Очередь»: пилюля с ползунком-«таблеткой», который плавно переезжает к выбранному
 * (а не перекрашивает сегменты). Иконка + подпись; у очереди — число треков приглушённым.
 */
@Composable
fun SideToggle(selected: Side, onSelect: (Side) -> Unit, modifier: Modifier = Modifier, queueCount: Int? = null) {
    val p = pal
    val items = listOf(Side.LYRICS to ("Текст" to Ic.lyrics), Side.QUEUE to ("Очередь" to Ic.queue))
    val idx = items.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val pos by animateFloatAsState(idx.toFloat(), tween(if (Look.motion) 320 else 0, easing = EaseLux), label = "thumb")
    BoxWithConstraints(modifier.height(38.dp).clip(R.pill).background(p.chip).border(1.dp, p.bd, R.pill).padding(3.dp)) {
        val cell = maxWidth / items.size
        val density = androidx.compose.ui.platform.LocalDensity.current
        val cellPx = with(density) { cell.toPx() }
        // ползунок: акцентная «таблетка» едет под выбранной подписью
        Box(Modifier.offset { IntOffset((pos * cellPx).roundToInt(), 0) }.width(cell).fillMaxHeight().clip(R.pill).background(p.acc))
        Row(Modifier.fillMaxSize()) {
            items.forEach { (value, item) ->
                val (label, icon) = item
                val on = value == selected
                val src = remember { MutableInteractionSource() }
                val hovered by src.collectIsHoveredAsState()
                val ink by animateColorAsState(if (on) p.accInk else if (hovered) p.txt else p.txt.copy(alpha = .72f), tween(220, easing = EaseLux))
                Row(Modifier.weight(1f).fillMaxHeight().clip(R.pill).hoverable(src).clickable(src, null) { onSelect(value) }
                    .pointerHoverIcon(PointerIcon.Hand),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                    Icon(icon, ink, size = 15.dp)
                    Txt(label, color = ink, style = T.small.copy(fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium))
                    if (value == Side.QUEUE && queueCount != null)
                        Txt(queueCount.toString(), color = ink.copy(alpha = .65f), style = T.num.copy(fontSize = 11.5.sp))
                }
            }
        }
    }
}

@Composable
private fun QueueView() {
    val p = pal
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val state = rememberLazyListState()
    LaunchedEffect(index) { if (index > 1) state.animateScrollToItem((index - 1).coerceAtLeast(0)) }
    if (queue.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Empty("Очередь пуста") }
    else {
        // drag будущих треков за ручку: плавный сдвиг элементов на место перетаскиваемого
        val density = androidx.compose.ui.platform.LocalDensity.current
        val rowHpx = with(density) { 52.dp.toPx() }
        var dragging by remember { mutableStateOf<Pair<Int, Offset>?>(null) }   // индекс → offset от исходной позиции
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state, contentPadding = PaddingValues(bottom = 8.dp)) {
                itemsIndexed(queue, key = { i, t -> "$i/${t.mediaId}" }) { i, t ->
                    val past = i < index
                    val dragOffset = if (dragging?.first == i) dragging!!.second.y else 0f
                    val shift = if (dragging != null && dragging!!.first != i && !past) {
                        val from = dragging!!.first
                        val dy = dragging!!.second.y
                        val rowH = rowHpx
                        val target = (from + (dy / rowH).toInt()).coerceIn(index + 1, queue.size - 1)
                        when {
                            i in (target + 1)..from -> rowH                                   // сдвинуть вниз
                            i in from..(target - 1) -> -rowH                                  // сдвинуть вверх
                            else -> 0f
                        }
                    } else 0f
                    Row2(Modifier.graphicsLayer { translationY = dragOffset + shift }
                        .zIndex(if (dragging?.first == i) 1f else 0f)) {
                        // ручка перетаскивания (только у будущих; у прошлых — пустое место для ровного ряда)
                        if (!past) {
                            Box(Modifier.width(18.dp).fillMaxHeight().clip(R.sm)
                                .pointerInput(i) {
                                    detectDragGestures(
                                        onDragStart = { dragging = i to Offset.Zero },
                                        onDragEnd = {
                                            val from = dragging?.first
                                            dragging = null
                                            if (from != null) {
                                                val to = (from + (dragOffset / rowHpx).toInt()).coerceIn(index + 1, queue.size - 1)
                                                if (to != from) Player.move(from, to)
                                            }
                                        },
                                        onDragCancel = { dragging = null },
                                    ) { _, delta -> dragging?.let { cur -> dragging = cur.first to cur.second + delta } }
                                },
                                contentAlignment = Alignment.Center) {
                                Icon(Ic.queue, p.muted.copy(alpha = .5f), size = 13.dp)
                            }
                        } else Box(Modifier.width(18.dp))
                        TrackRow(t, i, { Player.jump(i) }, Modifier.then(if (past) Modifier.background(p.card) else Modifier),
                            dense = true, onRemove = { Player.removeAt(i) }, showLike = false)
                    }
                }
            }
            // низ списка растворяется в карточке — как у текста
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(20.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, p.card))))
        }
    }
}

@Composable
private fun LyricsView(ls: LyricsState, time: Long) {
    val p = pal
    val track by Player.current.collectAsState()
    val l = ls.lyrics
    when {
        track == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Empty("Текст появится у играющего трека") }
        ls.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Txt("Ищу текст…", color = p.muted) }
        l == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Empty("Текста не нашлось ни у сервиса, ни в LRCLIB") }
        l.synced != null -> {
            val lines = l.synced!!.lines
            val cur = lines.indexOfLast { it.startMillis <= time + 250 }
            val state = rememberLazyListState()
            LaunchedEffect(cur) { if (cur >= 0) state.animateScrollToItem((cur - 3).coerceAtLeast(0)) }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), state, contentPadding = PaddingValues(vertical = 40.dp, horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(lines) { i, line ->
                        val on = i == cur
                        val color by animateColorAsState(when { on -> p.acc; i < cur -> p.muted.copy(alpha = .6f); else -> p.txt.copy(alpha = .85f) }, tween(380, easing = EaseLux))
                        // одна высота строки для всех: акцент — цветом и весом, без скачков; без ripple-подложки
                        val src = remember { MutableInteractionSource() }
                        Txt(line.text?.ifBlank { "♪" } ?: "♪", Modifier.fillMaxWidth().clip(R.sm)
                            .clickable(src, indication = null) { Player.seek(line.startMillis) }.padding(4.dp),
                            color, T.body.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                textAlign = TextAlign.Center), maxLines = 4)
                    }
                }
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(28.dp)
                    .background(Brush.verticalGradient(listOf(p.card, Color.Transparent))))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, p.card))))
            }
        }
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp)) {
            Txt(l.plain, Modifier.fillMaxWidth(), style = T.body.copy(fontSize = 14.sp, lineHeight = 22.sp, textAlign = TextAlign.Center), maxLines = Int.MAX_VALUE)
            Spacer(Modifier.height(20.dp))
        }
    }
}
