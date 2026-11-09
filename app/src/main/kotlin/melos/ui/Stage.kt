@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

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
import androidx.compose.runtime.remember
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
import androidx.compose.runtime.key
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

// Сцена: экран по Nav.current (общий: десктоп — центр окна, Android — центр экрана).

@Composable
fun Stage(modifier: Modifier) {
    val p = pal
    val screen = Nav.current
    val home = screen == Screen.Home
    val artAlpha by androidx.compose.animation.core.animateFloatAsState(if (home) 1f else .5f, tween(400))
    Box(modifier.clip(R.lg).background(p.card).border(1.dp, p.bd, R.lg)) {
        if (Look.brain && !home) {
            Brain(Modifier.fillMaxSize().alpha(artAlpha), interactive = false, light = 0.3f)
            // scrim: контент важнее фона — под текстом и кнопками «мозг» почти не просвечивает
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(p.bg.copy(alpha = 0.9f), p.bg.copy(alpha = 0.82f), p.bg.copy(alpha = 0.94f)))))
        }
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.Home -> HomeScreen()
                is Screen.Search -> key(screen.service) { SearchScreen(screen.query, screen.service) }
                is Screen.Track -> TrackScreen(screen.track)
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

@Composable
fun Sidebar(modifier: Modifier, rail: Boolean) {
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
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).heightIn(min = maxHeight)
                    .padding(horizontal = 8.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    val service = services.firstOrNull { it.id == sel }
                    if (service == null) MelosMenu() else ServiceMenu(service)
                }
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}


@Composable
private fun RailButton(name: String, on: Boolean, icon: @Composable (Boolean) -> Unit, dot: Color? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = pal
    val bg by animateColorAsState(if (on) p.acc.copy(alpha = .14f) else Color.Transparent, tween(240, easing = EaseLux))
    androidx.compose.foundation.TooltipArea(tooltip = {
        Box(Modifier.clip(R.sm).background(p.card2).border(1.dp, p.bd, R.sm).padding(horizontal = 10.dp, vertical = 6.dp)) { Txt(name, color = p.txt, style = T.small) }
    }, delayMillis = 350, tooltipPlacement = androidx.compose.foundation.TooltipPlacement.CursorPoint(offset = androidx.compose.ui.unit.DpOffset(16.dp, 8.dp))) {
        // выбранная — круглая подложка; от логотипа остаются линия и круглые скругления, без многоугольных обводок
        Box(modifier.size(44.dp).clip(CircleShape).background(bg).lux(onClick, CircleShape, bloom = false), contentAlignment = Alignment.Center) {
            icon(on)
            // точка входа: один и тот же угол у всех, с канвой цвета панели — не налезает на знак и не обрезается
            if (dot != null) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(7.dp).clip(CircleShape).background(dot).border(1.5.dp, p.card, CircleShape))
        }
    }
}

/** Значок сервиса: логотип; файлы — пиктограммой, плагины без логотипа — буквой названия. */

@Composable
fun ServiceMark(s: Service, on: Boolean) {
    val p = pal
    val c = if (on) p.acc else p.txt.copy(alpha = .86f)
    when {
        Logos.of(s.id) != null -> ServiceLogo(s.id, 28.dp)
        s.id == "local" -> Icon(Ic.folder, c, size = 22.dp)
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


