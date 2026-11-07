package melos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import melos.host.Services
import melos.player.Player

// Мобильная оболочка Melos — как в Яндекс Музыке: нижняя навигация (Главное / Поиск / Медиатека / Ещё),
// мини-плеер над ней, полноэкранный плеер по тапу. Скорости и таймера сна на телефоне нет.

private enum class DroidTab(val label: String, val icon: ImageVector) {
    HOME("Главное", Ic.home), SEARCH("Поиск", Ic.search), LIBRARY("Медиатека", Ic.heart), MORE("Ещё", Ic.eq)
}

@Composable
fun DroidRoot(openPlayer: Boolean = false) {
    MelosTheme {
        val p = pal
        val track by Player.current.collectAsState()
        val services by Services.all.collectAsState()
        var tab by remember { mutableStateOf(DroidTab.HOME) }
        var playerOpen by remember { mutableStateOf(openPlayer) }

        // вкладка следует за навигацией (после «назад» из сервиса — на место)
        LaunchedEffect(Nav.current) {
            when (Nav.current) {
                is Screen.Home -> tab = DroidTab.HOME
                is Screen.Search -> tab = DroidTab.SEARCH
                Screen.Favorites -> tab = DroidTab.LIBRARY
                Screen.History, Screen.Downloads -> tab = DroidTab.MORE
                else -> {}
            }
        }

        // «назад»: плеер → панель → шторка → история навигации → вкладка домой
        BackHandler(enabled = playerOpen) { playerOpen = false }
        BackHandler(enabled = Nav.side != null) { Nav.side = null }
        BackHandler(enabled = Nav.sheet != null) { Nav.sheet = null }
        BackHandler(enabled = Nav.canBack) { Nav.back() }
        BackHandler(enabled = tab != DroidTab.HOME) { tab = DroidTab.HOME }

        Box(Modifier.fillMaxSize().background(p.bg).rippleCatcher()) {
            Backdrop()
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (tab == DroidTab.MORE) MoreScreen(services) else Stage(Modifier.fillMaxSize())
                    // очередь/текст — панель поверх сцены
                    if (Nav.side != null && !playerOpen) Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight()) {
                        SidePanel(Modifier.width(340.dp).fillMaxHeight().enter(0, Nav.side))
                    }
                }
                if (track != null && !playerOpen) MiniPlayer(onOpen = { playerOpen = true })
                BottomBar(tab) { t ->
                    when (t) {
                        DroidTab.HOME -> Nav.go(Screen.Home)
                        DroidTab.SEARCH -> Nav.go(Screen.Search())
                        DroidTab.LIBRARY -> Nav.go(Screen.Favorites)
                        DroidTab.MORE -> tab = DroidTab.MORE
                    }
                    if (t != tab) tab = t
                }
            }

            // полноэкранный плеер поверх всего
            if (playerOpen && track != null) Box(Modifier.fillMaxSize().background(p.bg).statusBarsPadding()) {
                NowPlaying(onClose = { playerOpen = false })
            }

            when (Nav.sheet) {
                Sheet.SETTINGS, Sheet.PLUGINS -> SettingsSheet()
                Sheet.EQ -> EqSheet()
                null -> {}
            }
            PaletteWave(Look.coverColor)
            TentacleLayer()
            BurstLayer()
            RippleLayer()
            ToastLayer(Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp))
        }
    }
}

// Нижняя навигация: четыре вкладки, выбранная — акцентом (как таб-бар Яндекс Музыки).
@Composable
private fun BottomBar(tab: DroidTab, onTab: (DroidTab) -> Unit) {
    val p = pal
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clip(R.lg).background(p.card).border(1.dp, p.bd, R.lg)) {
        Row(Modifier.fillMaxWidth().height(58.dp)) {
            DroidTab.entries.forEach { t ->
                val on = t == tab
                Column(Modifier.weight(1f).fillMaxHeight().clip(R.md).clickable { onTab(t) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(t.icon, if (on) p.acc else p.muted, size = 21.dp)
                    Txt(t.label, Modifier.padding(top = 3.dp), if (on) p.acc else p.muted, T.cap.copy(fontSize = 10.sp))
                }
            }
        }
    }
}

// «Ещё»: сервисы, история, загрузки, настройки.
@Composable
private fun MoreScreen(services: List<melos.host.Service>) {
    ScrollList(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Cap("Melos", Modifier.padding(top = 6.dp)); Txt("Ещё", style = T.h1)
                SideItem("История", Nav.current == Screen.History) { Nav.go(Screen.History) }
                SideItem("Загрузки", Nav.current == Screen.Downloads) { Nav.go(Screen.Downloads) }
                SideItem("Настройки", Nav.sheet == Sheet.SETTINGS, dim = true) { Nav.sheet = Sheet.SETTINGS }
            }
        }
        services.filter { it.id !in HIDDEN_BUILTINS }.forEachIndexed { i, s ->
            item(key = "svc$i") {
                Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ServiceMenu(s)
                }
            }
        }
    }
}
