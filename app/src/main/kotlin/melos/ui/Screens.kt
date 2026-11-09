package melos.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmanners.murglar.lib.core.model.node.NamedPath
import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.node.Node.Companion.bestCoverUrl
import com.badmanners.murglar.lib.core.model.node.NodeType
import com.badmanners.murglar.lib.core.model.radio.BaseRadio
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import melos.download.Downloads
import melos.host.Services
import melos.host.Toasts
import melos.library.Library
import melos.library.Snap
import melos.player.Player
import melos.player.Status
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.launch

// Постраничная загрузка содержимого узла, как в Murglar: страницы до пустой, «Ещё» — следующая.
class NodeContent(val node: Node) {
    val items = mutableStateListOf<Node>()
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var page = 0
    var end by mutableStateOf(false)
    private val resolver = Services.forNode(node)?.murglar?.nodeResolver
    val pageable = runCatching { node.nodeParameters.isPageable }.getOrDefault(false)

    suspend fun more() {
        val resolver = resolver ?: return
        if (loading || end) return
        loading = true; error = null
        runCatching { resolver.getNodeContent(node.nodePath, if (pageable) page else null) }
            .onSuccess { list ->
                val known = items.map { it.nodePath }.toSet()
                val fresh = list.filter { it.nodePath !in known }
                items += fresh; page++
                if (!pageable || fresh.isEmpty()) end = true
            }
            .onFailure { error = it.message ?: it.javaClass.simpleName; end = true }
        loading = false
    }

    val tracks get() = items.filterIsInstance<BaseTrack>()
    val dirs get() = items.filter { it !is BaseTrack }
}

private fun typeLabel(t: String) = when (t) {
    NodeType.ALBUM -> "Альбом"; NodeType.ARTIST -> "Исполнитель"; NodeType.PLAYLIST -> "Плейлист"; NodeType.RADIO -> "Волна"
    NodeType.PODCAST -> "Подкаст"; NodeType.AUDIOBOOK -> "Аудиокнига"; else -> "Раздел"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NodeScreen(node: Node) {
    val p = pal
    val content = remember(node.nodePath) { NodeContent(node) }
    val scope = rememberCoroutineScope()
    val service = Services.forNode(node)
    val related = remember(node.nodePath) { runCatching { service?.murglar?.nodeResolver?.getRelatedNodePaths(node) }.getOrNull().orEmpty() }
    LaunchedEffect(node.nodePath) { content.more() }
    val tracks = content.tracks
    val dirs = content.dirs
    val context = node.nodeName

    ScrollList(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp).enter(0, node.nodePath), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
                Cover(node.bestCoverUrl, 140.dp, shape = R.lg, round = node.nodeType == NodeType.ARTIST,
                    fallback = if (node.nodeType == NodeType.ARTIST) Ic.user else Ic.disc)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row2 { Cap(typeLabel(node.nodeType)); service?.let { ServiceLogo(it.id, 14.dp); Txt(it.name, color = p.muted, style = T.small) } }
                    Txt(node.nodeName, style = T.h1, maxLines = 2)
                    node.nodeSummary?.takeIf { it.isNotBlank() }?.let { Txt(it, color = p.muted, style = T.body, maxLines = 3) }
                    Spacer(Modifier.height(4.dp))
                    Row2 {
                        if (tracks.isNotEmpty()) {
                            PrimaryButton("Слушать", { Player.playList(tracks, 0, context); BrainBus.wave() }, icon = Ic.play)
                            Btn("Перемешать", { Player.shuffle.value = true; Player.playList(tracks.shuffled(), 0, context); BrainBus.wave() }, icon = Ic.shuffle)
                            Btn("В очередь", { Player.enqueue(tracks) }, icon = Ic.plus)
                            Btn("Сохранить на устройство", { saveToDevice(tracks) }, icon = Ic.download)
                        }
                        val likeable = runCatching { node.nodeParameters.isLikeable && service!!.murglar.nodeResolver.supportsLikes }.getOrDefault(false)
                        if (likeable) RoundButton(Ic.heart, {
                            scope.launch {
                                runCatching { service!!.murglar.nodeResolver.likeNode(node, true) }
                                    .onSuccess { Toasts.shortNotify("Добавлено в медиатеку ${service!!.name}"); BrainBus.flash(p.acc) }
                                    .onFailure { Toasts.error(it.message ?: "Не вышло") }
                            }
                        }, size = 36.dp)
                    }
                    if (related.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        related.forEach { r -> Pill(r.name, icon = relIcon(r)) { Nav.go(Screen.OpenPath(r.path, r.name)) } }
                    }
                }
            }
        }
        if (dirs.isNotEmpty()) item {
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                dirs.forEachIndexed { i, d ->
                    if (d.bestCoverUrl == null && d.nodeType == NodeType.NODE) Box(Modifier.width(300.dp)) { DirRow(d) { openNode(d) } }
                    else NodeCard(d, index = i) { openNode(d) }
                }
            }
        }
        itemsIndexed(tracks, key = { _, t -> t.nodePath.toString() }) { i, t ->
            TrackRow(t, i, { Player.playList(tracks, i, context) }, Modifier.enter(i, t.nodePath), showCover = node.nodeType != NodeType.ALBUM)
        }
        item { Footer(content) { scope.launch { content.more() } } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackScreen(track: BaseTrack) {
    val p = pal
    val current by Player.current.collectAsState()
    val service = Services.forTrack(track)
    val resolver = service?.murglar?.nodeResolver
    val related = remember(track.mediaId) { runCatching { resolver?.getRelatedNodePaths(track) }.getOrNull().orEmpty() }
    val lists = remember(track.mediaId) { mutableStateListOf<Pair<NamedPath, NodeContent>>() }
    val favs by Library.favorites.items.collectAsState()
    val serviceLikes by melos.library.ServiceLikes.ids.collectAsState()
    val liked = track.mediaId in serviceLikes || favs.any { it.mediaId == track.mediaId }
    LaunchedEffect(track.mediaId) {
        lists.clear()
        // сначала похожие, потом альбом — как на страницах треков в сервисах
        for (r in related.filter { it.type == NodeType.TRACK } + related.filter { it.type == NodeType.ALBUM }) {
            val node = runCatching { resolver!!.getNode(r.path) }.onFailure { melos.host.Log.w("Track", "${r.name}: ${it.message}") }.getOrNull() ?: continue
            val c = NodeContent(node)
            lists += r to c
            c.more()
        }
    }
    val similar = lists.firstOrNull { it.first.type == NodeType.TRACK }?.second?.tracks.orEmpty()

    ScrollList(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).enter(0, track.mediaId), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                Cover(track.bigCoverUrl ?: track.smallCoverUrl, 240.dp, shape = R.lg)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row2 { Cap("Трек"); service?.let { ServiceLogo(it.id, 14.dp); Txt(it.name, color = p.muted, style = T.small) } }
                    Txt(track.fullTitle, style = T.h1.copy(fontSize = 24.sp, lineHeight = 28.sp), maxLines = 2)
                    val meta = listOfNotNull(track.artists.ifEmpty { null }, track.albumName, track.albumReleaseDate?.year?.toString()).joinToString(" · ")
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        HoverTip(meta) {
                            Txt(meta, Modifier.widthIn(max = 520.dp), color = p.muted, maxLines = 1)
                        }
                        track.genre?.takeIf { it.isNotBlank() }?.let { g ->
                            Box(Modifier.clip(R.sm).background(p.chip).border(1.dp, p.bd, R.sm).padding(horizontal = 8.dp, vertical = 3.dp)) { Txt(g, color = p.muted, style = T.small, maxLines = 2) }
                        }
                        if (track.durationMs > 0) Box(Modifier.clip(R.sm).background(p.chip).border(1.dp, p.bd, R.sm).padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Txt(fmt(track.durationMs), color = p.muted, style = T.num, maxLines = 2)
                        }
                    }
                    track.subtitle?.takeIf { it.isNotBlank() }?.let { Txt(it, color = p.warn, style = T.small) }
                    // дублей с плеером нет: у играющего трека — только метка и «⋯», лайк/дизлайк/очередь — там
                    val st by Player.status.collectAsState()
                    val playingNow = current?.mediaId == track.mediaId && st == Status.PLAYING
                    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (playingNow) {
                                Box(Modifier.heightIn(min = 44.dp).clip(R.pill).background(p.acc.copy(alpha = .14f)).border(1.dp, p.acc.copy(alpha = .5f), R.pill)
                                    .padding(horizontal = 16.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
                                    Row2(gap = 8.dp) { Bars(true, p.acc); Txt("Играет", color = p.acc, style = T.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp)) }
                                }
                                var more by remember { mutableStateOf(false) }
                                Box {
                                    IconBtn(Ic.more, { more = true })
                                    if (more) Popup(alignment = Alignment.BottomStart, onDismissRequest = { more = false },
                                        properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
                                        val disliked = Library.dislikes.items.collectAsState().value.let { Library.isDisliked(track) }
                                        Card(Modifier.widthIn(min = 200.dp), color = p.card2, pad = 6.dp) {
                                            MenuItem(if (disliked) "Убрать из «Не нравится»" else "Не нравится", on = disliked) {
                                                Player.toggleDislike(track); more = false
                                            }
                                        }
                                    }
                                }
                            } else {
                                PrimaryButton("Слушать", { Player.playList(listOf(track) + similar.filter { it.mediaId != track.mediaId }, 0, track.title); BrainBus.wave() }, icon = Ic.play)
                                HoverTip("Следующим") { IconBtn(Ic.playNext, { Player.playNext(track) }) }
                                HoverTip("В очередь") { IconBtn(Ic.plus, { Player.enqueue(listOf(track)) }) }
                                RoundButton(if (liked) Ic.heartOn else Ic.heart, { Player.toggleLike(track); if (!liked) BrainBus.heart() }, size = 44.dp, active = liked)
                                // дизлайк — в «⋯», чтобы не дублировать плеер
                                var more by remember { mutableStateOf(false) }
                                Box {
                                    IconBtn(Ic.more, { more = true })
                                    if (more) Popup(alignment = Alignment.BottomStart, onDismissRequest = { more = false },
                                        properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
                                        val disliked = Library.dislikes.items.collectAsState().value.let { Library.isDisliked(track) }
                                        Card(Modifier.widthIn(min = 200.dp), color = p.card2, pad = 6.dp) {
                                            MenuItem(if (disliked) "Убрать из «Не нравится»" else "Не нравится", on = disliked) {
                                                Player.toggleDislike(track); more = false
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Btn("Сохранить на устройство", { saveToDevice(listOf(track)) }, icon = Ic.download, wrap = true)
                            track.serviceUrl?.takeIf { it.startsWith("http") }?.let { url ->
                                Btn("Открыть в ${service?.let { svc -> serviceNameIn[svc.name] ?: svc.name } ?: "сервисе"}", { melos.core.Browse.url(url) }, icon = Ic.link, wrap = true)
                            }
                        }
                        val links = related.filter { it.type != NodeType.TRACK }
                        if (links.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            links.forEach { r -> Pill(r.name, icon = relIcon(r)) { Nav.go(Screen.OpenPath(r.path, r.name)) } }
                        }
                    }
                }
            }
        }
        lists.forEach { (r, c) ->
            val album = r.type == NodeType.ALBUM
            val title = if (album) "Из альбома «${r.name}»" else r.name
            stickyHeader(key = "h${r.path}") {
                Row2(Modifier.fillMaxWidth().background(p.card).padding(start = 8.dp, top = 4.dp, bottom = 8.dp)) {
                    Cap(title)
                    Spacer(Modifier.weight(1f))
                    if (c.items.isNotEmpty()) Pill(if (album) "Открыть альбом" else "Все", icon = Ic.right) { Nav.go(Screen.Open(c.node)) }
                }
            }
            val tracks = c.tracks
            val shown = if (album) tracks else tracks.take(10)
            itemsIndexed(shown, key = { _, t -> "${r.path}/${t.nodePath}" }) { i, t ->
                TrackRow(t, i, { Player.playList(tracks, i, title) }, Modifier.enter(i, t.nodePath), showCover = !album)
            }
            if (c.loading && c.items.isEmpty()) item(key = "l${r.path}") { Txt("Загружаю…", Modifier.padding(8.dp), p.muted, T.small) }
            c.error?.let { e -> item(key = "e${r.path}") { Txt(e, Modifier.padding(8.dp), p.critical, T.small, maxLines = 3) } }
        }
    }
}

// «Открыть в Яндекс Музыке / VK Музыке» — родительный падеж для кнопки страницы трека
private val serviceNameIn = mapOf("Яндекс Музыка" to "Яндекс Музыке", "VK Музыка" to "VK Музыке")

private fun relIcon(r: NamedPath) = when (r.type) { NodeType.ARTIST -> Ic.user; NodeType.ALBUM -> Ic.disc; NodeType.RADIO -> Ic.wave; else -> Ic.link }

@Composable
fun Footer(content: NodeContent, onMore: () -> Unit) {
    val p = pal
    Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
        when {
            content.loading -> Txt("Загружаю…", color = p.muted, style = T.small)
            content.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(content.error!!, color = p.critical, style = T.small, maxLines = 4)
                val s = Services.forNode(content.node)
                if (s != null && !s.murglar.loginResolver.isLogged && s.murglar.loginResolver.credentialsLoginVariants.isNotEmpty())
                    Btn("Войти в ${s.name}", { Nav.loginFor = s.id; Nav.sheet = Sheet.SETTINGS })
            }
            content.items.isEmpty() && content.end -> Txt("Здесь пусто", color = p.muted, style = T.small)
            !content.end -> Pill("Ещё", icon = Ic.plus, onClick = onMore)
        }
    }
}

// Узел по пути (из «Похожие», ссылок на альбом/исполнителя): сначала getNode, потом как обычно.
@Composable
fun PathScreen(s: Screen.OpenPath) {
    var node by remember(s.path) { mutableStateOf<Node?>(null) }
    var err by remember(s.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(s.path) {
        runCatching { Services.forPath(s.path)!!.murglar.nodeResolver.getNode(s.path) }
            .onSuccess {
                if (it is BaseRadio) { Player.playRadio(it); BrainBus.wave(1.2f); Nav.drop() } else node = it
            }
            .onFailure { err = it.message ?: "Не открылось" }
    }
    when {
        node != null -> NodeScreen(node!!)
        err != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Empty(err!!) }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Txt("Открываю «${s.title}»…", color = pal.muted) }
    }
}

// Избранное и история: слепки показываем сразу, живые треки получаем при запуске.
@Composable
fun SnapScreen(title: String, hint: String, snaps: List<Snap>, onRemove: (Snap) -> Unit, onClear: (() -> Unit)? = null) {
    val p = pal
    val scope = rememberCoroutineScope()
    fun play(from: Int) = scope.launch {
        val tracks = Library.resolve(snaps)
        if (tracks.isEmpty()) { Toasts.error("Сервисы этих треков сейчас недоступны"); return@launch }
        val start = tracks.indexOfFirst { it.mediaId == snaps[from].mediaId }.coerceAtLeast(0)
        Player.playList(tracks, start, title); BrainBus.wave()
    }
    ScrollList(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(bottom = 12.dp).enter(0, title), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Cap("Библиотека")
                Txt(title, style = T.h1)
                Txt(if (snaps.isEmpty()) hint else "${snaps.size} треков из всех сервисов", color = p.muted)
                if (snaps.isNotEmpty()) Row2(Modifier.padding(top = 6.dp)) {
                    PrimaryButton("Слушать", { play(0) }, icon = Ic.play)
                    Btn("Перемешать", { Player.shuffle.value = true; play((snaps.indices).random()) }, icon = Ic.shuffle)
                    onClear?.let { Btn("Очистить", it, danger = true) }
                }
            }
        }
        itemsIndexed(snaps, key = { _, s -> s.mediaId }) { i, s -> SnapRow(s, i, { play(i) }, { onRemove(s) }) }
    }
}

@Composable
fun SnapRow(s: Snap, i: Int, onPlay: () -> Unit, onRemove: () -> Unit) {
    val p = pal
    val current by Player.current.collectAsState()
    val isCur = current?.mediaId == s.mediaId
    Row(Modifier.fillMaxWidth().enter(i, s.mediaId).clip(R.md).lux(onPlay, R.md, bloom = false).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Cover(s.cover, 40.dp)
        Column(Modifier.weight(1f)) {
            Txt(s.title, color = if (isCur) p.acc else p.txt, style = T.body.copy(fontWeight = FontWeight.Medium))
            Txt(listOfNotNull(s.artists, Services.byId(s.service)?.name).joinToString(" · "), color = p.muted, style = T.small)
        }
        IconBtn(Ic.x, onRemove, size = 14.dp)
        Txt(if (s.durationMs > 0) fmt(s.durationMs) else "LIVE", color = p.muted, style = T.num)
    }
}

@Composable
fun DownloadsScreen() {
    val p = pal
    val states by Downloads.states.collectAsState()
    ScrollList(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Cap("Библиотека"); Txt("Загрузки", style = T.h1)
                Txt("Папка: ${Downloads.folder.path}", color = p.muted, style = T.num)
                Row2 {
                    Btn("Открыть папку", { Downloads.folder.mkdirs(); melos.core.Browse.dir(Downloads.folder) }, icon = Ic.folder)
                }
            }
        }
        if (states.isEmpty()) item { Empty("Скачанное появится здесь") }
        states.entries.reversed().forEachIndexed { i, (id, st) ->
            item(key = id) {
                Row(Modifier.fillMaxWidth().enter(i, id).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.download, p.muted, size = 15.dp)
                    Txt(when (st) { is Downloads.State.Done -> st.file.name; else -> id }, Modifier.weight(1f))
                    when (st) {
                        is Downloads.State.Running -> Txt("${(st.progress * 100).toInt()}%", color = p.acc, style = T.num)
                        is Downloads.State.Done -> Txt("готово", color = p.ok, style = T.small)
                        is Downloads.State.Failed -> Txt(st.reason, color = p.critical, style = T.small)
                    }
                }
            }
        }
    }
}

// Медиатека — один общий микс: лайки со всех площадок, где вы вошли, и избранное Melos (радио, файлы, плагины без лайков).
// Площадки перемешаны по очереди, повторы убраны. Разделы самих сервисов — только в их меню слева.
@Composable
fun LibraryScreen() {
    val p = pal
    val liked by melos.library.ServiceLikes.all.collectAsState()
    val loading by melos.library.ServiceLikes.loading.collectAsState()
    val favs by Library.favorites.items.collectAsState()
    var favTracks by remember { mutableStateOf<List<BaseTrack>>(emptyList()) }
    LaunchedEffect(favs) { favTracks = runCatching { Library.resolve(favs) }.getOrDefault(emptyList()) }
    val sources = liked.filter { it.tracks.isNotEmpty() }.map { it.service.name to it.tracks } +
        listOfNotNull(favTracks.takeIf { it.isNotEmpty() }?.let { "Избранное Melos" to it })
    val mix = remember(sources) {
        val seen = HashSet<String>(); val out = ArrayList<BaseTrack>()
        val its = sources.map { it.second.iterator() }
        while (its.any { it.hasNext() }) for (it in its) if (it.hasNext()) { val t = it.next(); if (seen.add(t.mediaId)) out += t }
        out
    }
    ScrollList(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(bottom = 8.dp).enter(0, "lib"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Cap("Библиотека")
                Txt("Медиатека", style = T.h1)
                Txt(if (sources.isEmpty()) "Лайки появятся здесь после входа в сервис"
                    else "${mix.size} треков · " + sources.joinToString(" · ") { (name, list) -> "$name ${list.size}" },
                    color = p.muted, maxLines = 3)
                Row2 {
                    if (mix.isNotEmpty()) {
                        PrimaryButton("Слушать", { Player.playList(mix, 0, "Медиатека"); BrainBus.wave() }, icon = Ic.play)
                        Btn("Перемешать", { Player.shuffle.value = true; Player.playList(mix.shuffled(), 0, "Медиатека"); BrainBus.wave() }, icon = Ic.shuffle)
                        Btn("Сохранить на устройство", { saveToDevice(mix) }, icon = Ic.download)
                    }
                    Btn(if (loading) "Обновляю…" else "Обновить", { melos.library.ServiceLikes.refresh() }, icon = Ic.refresh)
                }
            }
        }
        if (loading && mix.isEmpty()) item { Txt("Загружаю ваши лайки…", Modifier.padding(8.dp), p.muted) }
        itemsIndexed(mix, key = { _, t -> "m-${t.mediaId}" }) { i, t ->
            TrackRow(t, i, { Player.playList(mix, i, "Медиатека") }, Modifier.enter(i, t.mediaId))
        }
    }
}
