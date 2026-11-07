package melos.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmanners.murglar.lib.core.model.tag.Lyrics
import melos.lyrics.LyricsService
import melos.player.Player

@Composable
fun SidePanel(modifier: Modifier = Modifier) {
    val p = pal
    Column(modifier.clip(R.lg).background(p.card).border(1.dp, p.bd, R.lg).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // view-tabs из Chrome crawler
        Row(Modifier.clip(R.pill).background(p.chip).border(1.dp, p.bd, R.pill).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(Side.QUEUE to "Очередь", Side.LYRICS to "Текст").forEach { (s, label) ->
                val on = Nav.side == s
                val bg by animateColorAsState(if (on) p.card2 else p.chip, tween(240, easing = EaseLux))
                Box(Modifier.weight(1f).clip(R.pill).background(bg).then(if (on) Modifier.border(1.dp, p.bd, R.pill) else Modifier)
                    .clickable { Nav.side = s }.pointerHoverIcon(PointerIcon.Hand).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                    Txt(label, color = if (on) p.txt else p.muted, style = T.small.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal))
                }
            }
        }
        when (Nav.side) {
            Side.QUEUE -> QueueView()
            Side.LYRICS -> LyricsView()
            null -> {}
        }
    }
}

@Composable
private fun QueueView() {
    val p = pal
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val context by Player.context.collectAsState()
    val radio by Player.radio.collectAsState()
    val state = rememberLazyListState()
    LaunchedEffect(index) { if (index > 1) state.animateScrollToItem((index - 1).coerceAtLeast(0)) }
    Row2(Modifier.padding(horizontal = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Txt(context ?: "Очередь", style = T.title)
            Txt(if (radio != null) "Волна · подгружается сама" else "${queue.size} треков", color = p.muted, style = T.small)
        }
        if (queue.size > index + 1) Pill("Очистить дальше") { Player.clearUpcoming() }
    }
    if (queue.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Empty("Очередь пуста. «Сыграть следующим» и «В очередь» — в меню трека.") }
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
        }
    }
}

@Composable
private fun LyricsView() {
    val p = pal
    val track by Player.current.collectAsState()
    val time by Player.time.collectAsState()
    var lyrics by remember(track?.mediaId) { mutableStateOf<Lyrics?>(null) }
    var loading by remember(track?.mediaId) { mutableStateOf(true) }
    LaunchedEffect(track?.mediaId) {
        val t = track ?: run { loading = false; return@LaunchedEffect }
        lyrics = LyricsService.find(t); loading = false
    }
    val l = lyrics
    when {
        track == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Empty("Включите трек — здесь появится текст") }
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Txt("Ищу текст…", color = p.muted) }
        l == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Empty("Текста не нашлось ни у сервиса, ни в LRCLIB") }
        l.synced != null -> {
            val lines = l.synced!!.lines
            val cur = lines.indexOfLast { it.startMillis <= time + 250 }
            val state = rememberLazyListState()
            LaunchedEffect(cur) { if (cur >= 0) state.animateScrollToItem((cur - 3).coerceAtLeast(0)) }
            LazyColumn(Modifier.fillMaxSize(), state, contentPadding = PaddingValues(vertical = 40.dp, horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(lines) { i, line ->
                    val on = i == cur
                    val color by animateColorAsState(when { on -> p.acc; i < cur -> p.muted.copy(alpha = .6f); else -> p.txt.copy(alpha = .85f) }, tween(380, easing = EaseLux))
                    Txt(line.text?.ifBlank { "♪" } ?: "♪", Modifier.fillMaxWidth().clip(R.sm).clickable { Player.seek(line.startMillis) }.padding(4.dp),
                        color, T.body.copy(fontSize = if (on) 17.sp else 15.sp, lineHeight = 22.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center), maxLines = 4)
                }
            }
        }
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp)) {
            Txt(l.plain, Modifier.fillMaxWidth(), style = T.body.copy(fontSize = 14.sp, lineHeight = 22.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center), maxLines = Int.MAX_VALUE)
            Spacer(Modifier.height(20.dp))
        }
    }
}

