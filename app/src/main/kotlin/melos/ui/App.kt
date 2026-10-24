package melos.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.node.NodeType
import melos.host.Service
import melos.host.Services
import melos.library.Library
import melos.player.Player
import melos.player.Status

@Composable
fun App(topInset: Int = 0) {
    MelosTheme {
        val p = pal
        val track by Player.current.collectAsState()
        // смена трека: подбор акцента по обложке, волна в мозге и название точками
        LaunchedEffect(track?.mediaId) {
            val t = track ?: return@LaunchedEffect
            if (Player.status.value != Status.IDLE) { BrainBus.wave(0.9f); BrainBus.say(t.title) }
            val cover = t.bigCoverUrl ?: t.smallCoverUrl
            val palette = cover?.let { Images.paletteOf(it) }.orEmpty()
            Look.coverPalette = palette
            Look.coverColor = palette.firstOrNull()
        }
        PulseDriver()
        Box(Modifier.fillMaxSize().background(p.bg).rippleCatcher()) {
            Backdrop()
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            // узкое окно: сайдбар — колонка иконок, боковая панель уже
            val rail = maxWidth < 1180.dp
            Column(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = (8 + topInset).dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Sidebar(Modifier.fillMaxHeight(), rail)
                    Stage(Modifier.weight(1f).fillMaxHeight())
                    if (Nav.side != null) SidePanel(Modifier.width(if (rail) 320.dp else 360.dp).fillMaxHeight().enter(0, Nav.side))
                }
                PlayerBar()
            }
            }
            ToastLayer(Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
            PaletteWave(Look.coverColor)
            TentacleLayer()
            BurstLayer()
            RippleLayer()
            when (Nav.sheet) {
                Sheet.SETTINGS, Sheet.PLUGINS -> SettingsSheet()
                Sheet.EQ -> EqSheet()
                null -> {}
            }
        }
    }
}

@Composable
private fun Stage(modifier: Modifier) {
    val p = pal
    val screen = Nav.current
    val home = screen == Screen.Home
    val artAlpha by androidx.compose.animation.core.animateFloatAsState(if (home) 1f else if (p.dark) .32f else .25f, tween(400))
    Box(modifier.clip(R.lg).background(p.card).border(1.dp, p.bd, R.lg)) {
        if (Look.brain && !home) Brain(Modifier.fillMaxSize().alpha(artAlpha), interactive = false)
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.Home -> HomeScreen()
                is Screen.Search -> SearchScreen(screen.query)
                is Screen.Open -> NodeScreen(screen.node)
                is Screen.OpenPath -> PathScreen(screen)
                Screen.Favorites -> LibraryScreen()
                Screen.History -> {
                    val h by Library.history.items.collectAsState()
                    SnapScreen("История", "Здесь появится всё, что вы слушали", h, { Library.history.remove(it.mediaId) }, onClear = { Library.history.clear() })
                }
                Screen.Downloads -> DownloadsScreen()
            }
        }
    }
}

// Меню как в Murglar: слева столбик разделов (Melos, сервисы, «+» — плагины, внизу — настройки),
// справа — меню выбранного раздела: крупное название и привычный список.
@Composable
private fun Sidebar(modifier: Modifier, rail: Boolean) {
    val p = pal
    val services by Services.all.collectAsState()
    Services.revision.collectAsState().value
    val sel = Nav.section.takeIf { id -> id == MELOS_SECTION || services.any { it.id == id && id !in HIDDEN_BUILTINS } } ?: MELOS_SECTION
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.width(60.dp).fillMaxHeight().clip(R.lg).background(p.card).border(1.dp, p.bd, R.lg).padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RailButton("Melos", sel == MELOS_SECTION, { on -> Icon(Ic.home, if (on) p.acc else p.txt.copy(alpha = .86f), size = 22.dp) }) { Nav.selectSection(MELOS_SECTION) }
            Box(Modifier.padding(vertical = 2.dp).width(28.dp).height(1.dp).background(p.bd))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                services.filter { it.id !in HIDDEN_BUILTINS }.forEach { s ->
                    val logged = s.murglar.loginResolver.isLogged
                    val canLogin = s.murglar.loginResolver.credentialsLoginVariants.isNotEmpty() || s.murglar.loginResolver.webLoginVariants.isNotEmpty()
                    RailButton(s.name, sel == s.id, { on -> ServiceMark(s, on) }, dot = if (canLogin && logged) p.ok else null) { Nav.selectSection(s.id) }
                }
                RailButton("Добавить плагин", false, { Icon(Ic.plus, p.txt.copy(alpha = .7f), size = 22.dp) }) { Nav.sheet = Sheet.PLUGINS }
            }
            RailButton("Настройки", Nav.sheet == Sheet.SETTINGS, { on -> Icon(Ic.gear, if (on) p.acc else p.txt.copy(alpha = .86f), size = 22.dp) }) { Nav.sheet = Sheet.SETTINGS }
        }
        val scroll = rememberScrollState()
        Box(Modifier.width(if (rail) 184.dp else 216.dp).fillMaxHeight().clip(R.lg).background(p.card).border(1.dp, p.bd, R.lg)) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 8.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                val service = services.firstOrNull { it.id == sel }
                if (service == null) MelosMenu() else ServiceMenu(service)
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun MelosMenu() {
    val favs by Library.favorites.items.collectAsState()
    SideTitle("Melos")
    SideItem("Главная", Nav.current == Screen.Home) { Nav.go(Screen.Home) }
    SideItem("Поиск", Nav.current is Screen.Search) { Nav.go(Screen.Search()) }
    SideItem("Медиатека", Nav.current == Screen.Favorites, favs.size.takeIf { it > 0 }?.toString()) { Nav.go(Screen.Favorites) }
    SideItem("История", Nav.current == Screen.History) { Nav.go(Screen.History) }
    SideItem("Загрузки", Nav.current == Screen.Downloads) { Nav.go(Screen.Downloads) }
}

@Composable
private fun ServiceMenu(s: Service) {
    val p = pal
    val resolver = s.murglar.nodeResolver
    val roots = runCatching { resolver.getRootNodes(true).filter { !it.nodeParameters.isSearchable } }.getOrDefault(emptyList())
    val searchable = runCatching { resolver.getRootNodes(true).any { it.nodeParameters.isSearchable } }.getOrDefault(false)
    val login = s.murglar.loginResolver
    val canLogin = login.credentialsLoginVariants.isNotEmpty() || login.webLoginVariants.isNotEmpty()
    SideTitle(s.name)
    if (canLogin) Txt(if (login.isLogged) "Вход выполнен" else "Вход не выполнен", Modifier.padding(start = 10.dp, bottom = 8.dp),
        if (login.isLogged) p.ok else p.muted, T.small)
    // меню как на сайте сервиса: группы с заголовками; разделы, которых нет в раскладке, — в конце
    val layout = SITE_MENUS[s.id] ?: listOf(null to roots.map { it.nodePath.last })
    val byKey = roots.associateBy { it.nodePath.last }
    val shown = mutableSetOf<String>()
    for ((group, keys) in layout) {
        val nodes = keys.mapNotNull { byKey[it] }
        if (nodes.isEmpty()) continue
        if (group != null) Cap(group, Modifier.padding(start = 10.dp, top = 14.dp, bottom = 4.dp))
        nodes.forEach { node ->
            shown += node.nodePath.last
            SideItem(node.nodeName, (Nav.current as? Screen.Open)?.node?.nodePath == node.nodePath) { openNode(node) }
        }
    }
    roots.filter { it.nodePath.last !in shown }.forEach { node ->
        SideItem(node.nodeName, (Nav.current as? Screen.Open)?.node?.nodePath == node.nodePath) { openNode(node) }
    }
    if (canLogin || s.murglar.murglarPreferences.isNotEmpty())
        SideItem(if (login.isLogged || !canLogin) "Аккаунт" else if (roots.isEmpty() && !searchable) "Войти, чтобы открыть" else "Войти в аккаунт", false, dim = true) {
            Nav.loginFor = s.id; Nav.sheet = Sheet.SETTINGS
        }
}

/** Раскладка меню сервиса как на его сайте: (заголовок группы или null) → id корневых разделов по порядку. */
private val SITE_MENUS: Map<String, List<Pair<String?, List<String>>>> = mapOf(
    "ynd" to listOf(
        null to listOf("myWave", "chart", "newReleases"),
        "Коллекция" to listOf("myTracks", "myPlaylists", "myAlbums", "myArtists"),
    ),
    "sc" to listOf(                                                           // как на soundcloud.com: Лента, Библиотека
        null to listOf("stream"),
        "Библиотека" to listOf("myTracks", "myPlaylists"),
    ),
)

/** Сервисы в столбике: Яндекс, SoundCloud и подключённые jar-плагины (например, VK); остальные встроенные скрыты, но работают — история и лайки с них открываются. */
private val HIDDEN_BUILTINS = setOf("radio", "local")

@Composable
private fun SideTitle(text: String) =
    Txt(text, Modifier.padding(start = 10.dp, end = 6.dp, bottom = 6.dp), pal.txt, T.h2.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold), maxLines = 2)

@Composable
private fun SideItem(text: String, on: Boolean, badge: String? = null, dim: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = pal
    val bg by animateColorAsState(if (on) p.acc.copy(alpha = .14f) else Color.Transparent, tween(240, easing = EaseLux))
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp).clip(R.md).background(bg).lux(onClick, R.md, bloom = false).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Txt(text, Modifier.weight(1f), if (on) p.acc else if (dim) p.muted else lerpC(p.txt, p.acc, .3f),
            T.body.copy(fontSize = 16.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), maxLines = 2)
        badge?.let { Txt(it, color = p.muted, style = T.monoSmall) }
    }
}

/** Кнопка столбика разделов: круглая, с подсказкой-названием при наведении. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RailButton(name: String, on: Boolean, icon: @Composable (Boolean) -> Unit, dot: Color? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = pal
    val bg by animateColorAsState(if (on) p.acc.copy(alpha = .18f) else Color.Transparent, tween(240, easing = EaseLux))
    androidx.compose.foundation.TooltipArea(tooltip = {
        Box(Modifier.clip(R.sm).background(p.card2).border(1.dp, p.bd, R.sm).padding(horizontal = 10.dp, vertical = 6.dp)) { Txt(name, color = p.txt, style = T.small) }
    }, delayMillis = 350, tooltipPlacement = androidx.compose.foundation.TooltipPlacement.CursorPoint(offset = androidx.compose.ui.unit.DpOffset(16.dp, 8.dp))) {
        Box(modifier.size(44.dp).clip(CircleShape).background(bg).lux(onClick, CircleShape, bloom = false), contentAlignment = Alignment.Center) {
            icon(on)
            if (dot != null) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(7.dp).clip(CircleShape).background(dot))
        }
    }
}

/** Значок сервиса: радио и файлы — пиктограммой, остальные — буквой названия. */
@Composable
private fun ServiceMark(s: Service, on: Boolean) {
    val p = pal
    val c = if (on) p.acc else p.txt.copy(alpha = .86f)
    when (s.id) {
        "radio" -> Icon(Ic.radio, c, size = 22.dp)
        "local" -> Icon(Ic.folder, c, size = 22.dp)
        else -> Txt(s.name.trim().take(1).uppercase(), color = c, style = T.h2.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold))
    }
}

private fun iconFor(n: Node) = when {
    n.nodeType == NodeType.RADIO -> Ic.wave
    n.nodeParameters.contentNodeType == NodeType.TRACK -> Ic.note
    n.nodeParameters.contentNodeType == NodeType.ALBUM -> Ic.disc
    n.nodeParameters.contentNodeType == NodeType.ARTIST -> Ic.user
    n.nodeParameters.contentNodeType == NodeType.PLAYLIST -> Ic.list
    else -> Ic.folder
}


