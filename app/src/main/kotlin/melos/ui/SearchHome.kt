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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
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

/** Вкладка поиска сервиса — по тому, что ищет раздел, как на сайтах: Треки, Альбомы, Исполнители, Плейлисты. */
private fun tabLabel(root: Node) = when (root.nodeParameters.contentNodeType) {
    NodeType.TRACK -> "Треки"; NodeType.ALBUM -> "Альбомы"; NodeType.ARTIST -> "Исполнители"; NodeType.PLAYLIST -> "Плейлисты"
    NodeType.PODCAST -> "Подкасты"; NodeType.AUDIOBOOK -> "Аудиокниги"; else -> root.nodeName.substringBefore(':')
}

/**
 * Поиск. serviceId == null — общий поиск Melos: сразу во всех сервисах, переключатель сервисов сверху.
 * Иначе — поиск одного сервиса (из его меню): без переключателя, вкладками его разделов поиска.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(initial: String, serviceId: String? = null) {
    val p = pal
    var query by remember { mutableStateOf(initial) }
    var filter by remember { mutableStateOf(serviceId) }
    var tab by remember { mutableStateOf<String?>(null) }                       // nodeId раздела поиска сервиса
    var sections by remember { mutableStateOf<List<SearchSection>>(emptyList()) }
    var refs by remember { mutableStateOf<List<melos.search.Reference.Ref>>(emptyList()) }     // эталон из каталогов лейблов
    var extra by remember { mutableStateOf<List<SearchSection>>(emptyList()) }                // поиск «исполнитель + название»
    var measured by remember { mutableStateOf(0) }                                             // сколько треков VK уже измерено
    val services by Services.all.collectAsState()
    Services.revision.collectAsState().value
    val scoped = serviceId?.let { id -> services.firstOrNull { it.id == id } }
// локальные файлы ищутся без сети и без неё идут первыми
    LaunchedEffect(Unit) { melos.host.NetStatus.recheck() }
    val all = if (serviceId != null) listOfNotNull(scoped) else services.sortedBy { if (melos.host.NetStatus.offline.value && it.id == "local") 0 else 1 }
    val searchable = all.filter { searchRoots(it).isNotEmpty() }
    val tabs = scoped?.let(::searchRoots).orEmpty()
    val curTab = tabs.firstOrNull { it.nodeId == tab } ?: tabs.firstOrNull()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    LaunchedEffect(query, filter, searchable.size, curTab?.nodeId) {
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
            val use = if (scoped != null) listOfNotNull(curTab)
            else if (filter == null) roots.filter { it.nodeParameters.contentNodeType == NodeType.TRACK }.take(1).ifEmpty { roots.take(1) } +
                roots.filter { it.nodeParameters.contentNodeType == NodeType.ARTIST || it.nodeParameters.contentNodeType == NodeType.ALBUM }.take(1)
            else roots
            use.mapNotNull { r -> runCatching { SearchSection(s, r, NodeContent(s.murglar.nodeResolver.specifySearchableNode(r, q))) }.getOrNull() }
        }.sortedBy { if (it.service.id == "local") 0 else 1 }   // офлайн-треки — вверху выдачи
        sections = list
        refs = emptyList()
        extra = emptyList()
        coroutineScope {
            launch {
                refs = melos.search.Reference.lookup(q)                          // эталон и подсказки «чья песня»
                // ввели только название — ищем ещё и «самый популярный исполнитель + название»: так оригинал попадёт в выдачу
                val author = if (scoped == null && filter == null) melos.search.TrackMatch.likelyAuthor(q, refs) else null
                if (author != null) {
                    val more = list.filter { it.root.nodeParameters.contentNodeType == NodeType.TRACK }.mapNotNull { sec ->
                        runCatching { SearchSection(sec.service, sec.root, NodeContent(sec.service.murglar.nodeResolver.specifySearchableNode(sec.root, "$author $q"))) }.getOrNull()
                    }
                    extra = more
                    more.map { async { it.content.more() } }.awaitAll()
                }
            }
            list.map { async { it.content.more() } }.awaitAll()
        }
    }

    // «Все»: один трек со всех площадок — одной строкой, в лучшем качестве; подделки и чужие версии — вниз
    val everywhere = scoped == null && filter == null
    val trackSecs = sections.filter { it.root.nodeParameters.contentNodeType == NodeType.TRACK }
    val merged = if (!everywhere) emptyList() else remember(query, (trackSecs + extra).map { it.content.items.size }, refs, measured) {
        melos.search.TrackMatch.group(query, (trackSecs + extra).flatMap { it.content.tracks }.distinctBy { it.mediaId }, refs)
    }
    // VK качество не сообщает: меряем файлы у верхних строк (по 6 сразу), строки пересчитываются по мере замеров
    val vk = (Services.byId("vk")?.murglar as? melos.builtin.vk.VkMurglar)
    val toMeasure = merged.take(15).flatMap { g -> g.versions.filter { it.serviceId == "vk" && vk?.measuredKbps(it.track.id) == null } }
        .mapNotNull { it.track as? melos.builtin.FTrack }
    LaunchedEffect(toMeasure.map { it.id }) {
        if (vk == null || toMeasure.isEmpty()) return@LaunchedEffect
        val gate = kotlinx.coroutines.sync.Semaphore(6)
        coroutineScope { toMeasure.forEach { t -> launch(kotlinx.coroutines.Dispatchers.IO) { gate.acquire(); try { vk.measure(t) } finally { gate.release() }; measured++ } } }
    }
    var showAll by remember(query) { mutableStateOf(false) }
    // самого популярного трека из каталогов нет в выдаче — догружаем ещё страницу с каждой площадки, потом честно пишем
    val missing = if (!everywhere || refs.isEmpty()) null else remember(merged, refs) { melos.search.TrackMatch.missingOriginal(query, refs, merged) }
    var dug by remember(query) { mutableStateOf(false) }
    LaunchedEffect(missing != null, trackSecs.all { !it.content.loading }) {
        if (missing == null || dug || trackSecs.any { it.content.loading }) return@LaunchedEffect
        dug = true
        coroutineScope { (trackSecs + extra).filter { !it.content.end }.map { async { it.content.more() } }.awaitAll() }
    }

    ScrollList(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (scoped != null) Txt("Поиск · ${scoped.name}", style = T.h1)
                Input(query, { query = it; BrainBus.typed() }, Modifier.fillMaxWidth().focusRequester(focus),
                    placeholder = if (scoped != null) "Искать в ${scoped.name}" else "Трек, альбом, исполнитель или ссылка из сервиса", leading = Ic.search)
                if (scoped != null) { if (tabs.size > 1) Seg(tabs.map { it.nodeId to tabLabel(it) }, curTab?.nodeId, { tab = it }, small = true) }
                else if (searchable.size > 1) Seg(listOf<Pair<String?, String>>(null to "Все") + searchable.map { it.id to it.name }, filter, { filter = it }, small = true)
                // ввели только название — «чья песня?»: выбор исполнителя уточняет запрос, подделок меньше
                val authors = remember(query, refs) { melos.search.TrackMatch.authors(query, refs) }
                if (authors.size > 1) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt("Чья песня «${query.trim()}»?", color = p.muted, style = T.small)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        authors.forEach { (name, picture) -> AuthorChip(name, picture) { query = "$name ${query.trim()}" } }
                    }
                }
            }
        }
        if (query.trim().length < 2) item {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Empty(when {
                    scoped != null && searchable.isEmpty() -> "Искать в ${scoped.name} можно после входа"
                    scoped != null -> "Ищем только в ${scoped.name}"
                    searchable.isEmpty() -> "Нет сервисов с поиском — войдите в аккаунт или добавьте плагин"
                    else -> "Ищем сразу в ${searchable.joinToString(", ") { it.name }}; ссылку можно вставить"
                })
            }
        }
        if (everywhere && query.trim().length >= 2 && trackSecs.isNotEmpty()) {
            item(key = "merged-h") {
                Row2(Modifier.padding(bottom = 6.dp)) {
                    Cap("Треки"); Txt("со всех площадок — играет версия в лучшем качестве", color = p.muted, style = T.small)
                }
                if (missing != null && dug && trackSecs.none { it.content.loading })
                    Txt("Оригинал «${missing.artists.joinToString(", ")} — ${missing.title}» (${fmt(missing.durationMs)}) на ваших площадках не нашёлся — ниже похожие версии",
                        Modifier.padding(start = 8.dp, bottom = 6.dp), p.warn, T.small, maxLines = 2)
            }
            val shown = if (showAll) merged else merged.take(12)
            val queue = merged.map { it.best.track }
            shown.forEachIndexed { i, g ->
                item(key = "m${g.best.track.mediaId}") {
                    TrackRow(g.best.track, i, { Player.playList(queue, i, "Поиск: $query") }, Modifier.enter(i, g.best.track.mediaId),
                        trailing = { MatchBadge(g) })
                }
            }
            item(key = "merged-f") {
                when {
                    merged.isEmpty() && trackSecs.any { it.content.loading } -> Txt("Ищу на всех площадках…", Modifier.padding(8.dp), p.muted, T.small)
                    merged.isEmpty() -> Txt("Ничего не нашлось", Modifier.padding(8.dp), p.muted, T.small)
                    !showAll && merged.size > 12 -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Pill("Ещё ${merged.size - 12}", icon = Ic.plus) { showAll = true }
                    }
                }
                trackSecs.filter { it.content.error != null }.forEach { s ->
                    Txt("${s.service.name}: ${s.content.error}", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), p.critical, T.small, maxLines = 2)
                }
            }
        }
        sections.forEachIndexed { si, sec ->
            // в «Все» треки уже собраны выше — здесь только исполнители и альбомы площадок
            if (everywhere && sec.root.nodeParameters.contentNodeType == NodeType.TRACK) return@forEachIndexed
            if (scoped == null) item(key = "h${sec.service.id}/${sec.root.nodeId}") {
                Row2(Modifier.padding(top = if (si == 0 && !everywhere) 0.dp else 18.dp, bottom = 6.dp)) {
                    ServiceLogo(sec.service.id, 16.dp); Cap(sec.service.name); Txt(tabLabel(sec.root), color = p.muted, style = T.small)
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

/** Исполнитель в подсказке «чья песня?»: фото и имя. */
@Composable
private fun AuthorChip(name: String, picture: String?, onClick: () -> Unit) {
    val p = pal
    Row(Modifier.clip(R.pill).background(p.chip).border(1.dp, p.bd, R.pill).lux(onClick, R.pill, bloom = false).padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Cover(picture, 24.dp, round = true, fallback = Ic.user)
        Txt(name, style = T.small, maxLines = 1)
    }
}

/** Качество версии, которая заиграет, и логотипы площадок, где этот трек есть (её — ярче). */
@Composable
private fun MatchBadge(g: melos.search.TrackMatch.Group) {
    val p = pal
    Row2(gap = 6.dp) {
        // совпал с эталоном из каталогов лейблов (Deezer, iTunes): исполнитель, название и длительность — те самые
        if (g.original) Row2(gap = 3.dp) { Icon(Ic.check, p.ok, size = 12.dp); Txt("оригинал", color = p.ok, style = T.small) }
        Txt(g.best.label, color = if (g.best.kbps >= 1411) p.acc else p.muted, style = T.num)
        g.services.forEach { id ->
            ServiceLogo(id, 14.dp, Modifier.alpha(if (id == g.best.serviceId) 1f else .45f))
        }
    }
}

@Composable
fun HomeScreen() {
    val current by Player.current.collectAsState()
    Services.revision.collectAsState().value
    // переход «пустая → играет» кроссфейдом: мозг вырастает из спящего состояния, а не выскакивает рывком
    androidx.compose.animation.Crossfade(current == null, animationSpec = androidx.compose.animation.core.tween(700, easing = EaseLux), label = "home") { idle ->
        if (idle) IdleHome() else NowPlayingHome()
    }
}

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
    var vlc by remember { mutableStateOf(Player.available) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val many = maxWidth > 900.dp
        Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (Look.brain) Brain(Modifier.fillMaxSize(), interactive = true, center = androidx.compose.ui.geometry.Offset(0.5f, 0.46f), scaleK = 0.95f)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Txt("Melos", style = T.display)
            if (!vlc) {
                // чаще всего на Windows: VLC не установлен или 32-битный — без него плеер молчит
                Card(Modifier.widthIn(max = 520.dp), color = p.card2, pad = 16.dp) {
                    Txt("Melos не нашёл VLC", style = T.h2)
                    Txt("Для звука нужен VLC 3${if (melos.core.Paths.isWindows) " (64-битный)" else ""}.",
                        Modifier.padding(top = 4.dp), p.muted, T.small, maxLines = 3)
                    Row2(gap = 8.dp, modifier = Modifier.padding(top = 12.dp)) {
                        Btn("Найти на ПК", icon = Ic.refresh, onClick = { vlc = Player.recheckVlc() })
                        Btn("Установить VLC", icon = Ic.download, onClick = {
                            melos.core.Browse.url(melos.core.Paths.vlcDownloadUrl)
                        })
                    }
                }
            }
            Txt("Меню сервисов — слева", color = p.txt.copy(alpha = .8f), style = T.h2.copy(textAlign = TextAlign.Center), maxLines = 2)
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
