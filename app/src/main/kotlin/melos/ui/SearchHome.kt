package melos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.node.NodeType
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import melos.host.Service
import melos.host.Services
import melos.host.Toasts
import melos.library.Library
import melos.player.Player
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class SearchSection(val service: Service, val root: Node, val content: NodeContent)

private fun searchRoots(s: Service) = runCatching { s.murglar.nodeResolver.getRootNodes(true).filter { it.nodeParameters.isSearchable } }.getOrDefault(emptyList())

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(initial: String) {
    val p = pal
    var query by remember { mutableStateOf(initial) }
    var filter by remember { mutableStateOf<String?>(null) }
    var sections by remember { mutableStateOf<List<SearchSection>>(emptyList()) }
    val services by Services.all.collectAsState()
    Services.revision.collectAsState().value
    val searchable = services.filter { searchRoots(it).isNotEmpty() }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    LaunchedEffect(query, filter, searchable.size) {
        val q = query.trim()
        if (q.length < 2) { sections = emptyList(); return@LaunchedEffect }
        delay(450)
        if (q.startsWith("http")) {                         // ссылка из сервиса — открываем, как Murglar
            val s = services.firstOrNull { runCatching { it.murglar.nodeResolver.canGetNodeFromUrl(q) }.getOrDefault(false) }
            if (s != null) {
                runCatching { s.murglar.nodeResolver.getNodeFromUrl(q) }
                    .onSuccess { if (it is BaseTrack) { Player.playList(listOf(it)); BrainBus.wave() } else openNode(it) }
                    .onFailure { Toasts.error("Ссылка: ${it.message}") }
                return@LaunchedEffect
            }
        }
        val list = searchable.filter { filter == null || it.id == filter }.flatMap { s ->
            val roots = searchRoots(s)
            val use = if (filter == null) roots.filter { it.nodeParameters.contentNodeType == NodeType.TRACK }.take(1).ifEmpty { roots.take(1) } +
                roots.filter { it.nodeParameters.contentNodeType == NodeType.ARTIST || it.nodeParameters.contentNodeType == NodeType.ALBUM }.take(1)
            else roots
            use.mapNotNull { r -> runCatching { SearchSection(s, r, NodeContent(s.murglar.nodeResolver.specifySearchableNode(r, q))) }.getOrNull() }
        }
        sections = list
        coroutineScope { list.map { async { it.content.more() } }.awaitAll() }
    }

    ScrollList(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Input(query, { query = it; BrainBus.typed() }, Modifier.fillMaxWidth().focusRequester(focus),
                    placeholder = "Трек, альбом, исполнитель или ссылка из сервиса", leading = Ic.search)
                if (searchable.size > 1) Seg(listOf<Pair<String?, String>>(null to "Все") + searchable.map { it.id to it.name }, filter, { filter = it }, small = true)
            }
        }
        if (query.trim().length < 2) item {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Empty(if (searchable.isEmpty()) "Ни один сервис пока не умеет искать — войдите в аккаунт в настройках или добавьте плагин."
                    else "Ищем сразу в ${searchable.joinToString(", ") { it.name }}. Ссылку на трек или альбом можно просто вставить.")
            }
        }
        sections.forEachIndexed { si, sec ->
            item(key = "h${sec.service.id}/${sec.root.nodeId}") {
                Row2(Modifier.padding(top = if (si == 0) 0.dp else 18.dp, bottom = 6.dp)) {
                    Cap(sec.service.name); Txt(sec.root.nodeName.substringBefore(':'), color = p.muted, style = T.small)
                    Spacer(Modifier.weight(1f))
                    if (sec.content.items.isNotEmpty()) Pill("Все результаты", icon = Ic.right) { Nav.go(Screen.Open(sec.content.node)) }
                }
            }
            val tracks = sec.content.tracks
            val dirs = sec.content.dirs
            val shown = if (filter == null) tracks.take(5) else tracks
            shown.forEachIndexed { i, t ->
                item(key = "t${sec.service.id}${t.nodePath}") {
                    TrackRow(t, i, { Player.playList(tracks, i, "Поиск: $query") }, Modifier.enter(i, t.nodePath))
                }
            }
            if (dirs.isNotEmpty()) item(key = "d${sec.service.id}/${sec.root.nodeId}") {
                if (filter == null) LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(dirs.take(12), key = { it.nodePath.toString() }) { d -> NodeCard(d) { openNode(d) } }
                } else FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    dirs.forEachIndexed { i, d -> NodeCard(d, index = i) { openNode(d) } }
                }
            }
            item(key = "f${sec.service.id}/${sec.root.nodeId}") {
                when {
                    sec.content.loading && sec.content.items.isEmpty() -> Txt("Ищу…", Modifier.padding(8.dp), p.muted, T.small)
                    sec.content.error != null -> Txt(sec.content.error!!, Modifier.padding(8.dp), p.critical, T.small, maxLines = 3)
                    sec.content.items.isEmpty() && sec.content.end -> Txt("Ничего не нашлось", Modifier.padding(8.dp), p.muted, T.small)
                    filter != null && !sec.content.end -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Pill("Ещё", icon = Ic.plus) { scope.launch { sec.content.more() } }
                    }
                }
            }
        }
    }
}

// Главная. Есть трек: мозг держит обложку точками в ядре, рядом — крупное название, большой транспорт,
// действия, спектр из точек и «Далее». Широкое окно — две колонки, узкое — столбик. Нет трека — мозг и недавнее.
@Composable
fun HomeScreen() {
    val current by Player.current.collectAsState()
    Services.revision.collectAsState().value
    if (current == null) IdleHome() else NowPlayingHome()
}

// Играет трек: в центре только большой мозг — всё о треке уже есть в нижнем плеере.
@Composable
private fun NowPlayingHome() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val k = if (maxWidth > maxHeight * 1.2f) 1.3f else 1.15f
        if (Look.brain) Brain(Modifier.fillMaxSize(), interactive = true, center = androidx.compose.ui.geometry.Offset(0.5f, 0.45f), scaleK = k)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Player.current.collectAsState().value?.let { t -> DotCover(t.bigCoverUrl ?: t.smallCoverUrl, 280.dp) }
        }
    }
}

// Спектр точками: 32 столбца, цвета обложки слева направо. Без музыки — ровная сетка, как спящий мозг.
@Composable
fun SpectrumDots(modifier: Modifier = Modifier) {
    val p = pal
    androidx.compose.foundation.Canvas(modifier) {
        Pulse.frame.let { }
        val cols = 40; val rows = 8
        val stepX = size.width / cols; val stepY = size.height / rows
        val s = minOf(stepX, stepY) * 0.46f
        for (c in 0 until cols) {
            val f = c / (cols - 1f) * (Pulse.bands.size - 1)
            val i0 = f.toInt(); val fr = f - i0
            val v = Pulse.bands[i0] * (1 - fr) + Pulse.bands[minOf(i0 + 1, Pulse.bands.size - 1)] * fr
            val lit = (v * rows * 1.15f).toInt().coerceIn(0, rows)
            val col = if (c < cols / 2) lerpC(p.acc, p.c2, c / (cols / 2f)) else lerpC(p.c2, p.c3, (c - cols / 2f) / (cols / 2f))
            for (r in 0 until rows) {
                val on = rows - 1 - r < lit
                drawRect(if (on) col.copy(alpha = 0.55f + 0.45f * (rows - r) / rows) else p.txt.copy(alpha = .08f),
                    androidx.compose.ui.geometry.Offset(c * stepX + (stepX - s) / 2, r * stepY + (stepY - s) / 2), androidx.compose.ui.geometry.Size(s, s))
            }
        }
    }
}

@Composable
private fun IdleHome() {
    val p = pal
    val history by Library.history.items.collectAsState()
    val services by Services.all.collectAsState()
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val many = maxWidth > 900.dp
        Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (Look.brain) Brain(Modifier.fillMaxSize(), interactive = true, center = androidx.compose.ui.geometry.Offset(0.5f, 0.46f), scaleK = 0.95f)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Txt("Melos", style = T.display)
            // один путь: разделы сервисов — только в меню слева; здесь — продолжить с того, что слушали
            Txt("Выберите сервис слева: его меню — как на сайте", color = p.txt.copy(alpha = .8f), style = T.h2.copy(textAlign = TextAlign.Center), maxLines = 2)
            Row2(gap = 8.dp) {
                if (history.isNotEmpty()) PrimaryButton("Продолжить", {
                    scope.launch { val tr = Library.resolve(history.take(50)); if (tr.isNotEmpty()) { Player.playList(tr, 0, "История"); BrainBus.wave() } }
                }, icon = Ic.play)
            }
            if (history.isNotEmpty()) {
                Cap("Недавнее", Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    history.take(if (many) 10 else 6).forEachIndexed { i, s ->
                        Column(Modifier.width(84.dp).enter(i, s.mediaId).lux({
                            scope.launch { val tr = Library.resolve(listOf(s)); if (tr.isNotEmpty()) { Player.playList(tr); BrainBus.wave() } }
                        }, R.md, bloom = false).padding(2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Cover(s.cover, 80.dp, shape = R.md)
                            Txt(s.title, style = T.small, color = p.txt.copy(alpha = .9f))
                        }
                    }
                }
            }
        }
        }
    }
}
