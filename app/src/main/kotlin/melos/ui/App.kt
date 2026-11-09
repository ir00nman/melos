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
        // напоминание об обновлении при входе: проверяем раз в сессию, чуть дав приложению развернуться
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(5000); melos.host.Updater.startCheck() }
        Box(Modifier.fillMaxSize().background(p.bg).rippleCatcher()) {
            Backdrop()
            // свечение обложки: очень мягкий градиент цвета текущего трека из углов окна
            CoverGlow(Look.coverColor)
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            // узкое окно: сайдбар — колонка иконок, боковая панель уже
            val rail = maxWidth < 1180.dp
            Column(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = (8 + topInset).dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Sidebar(Modifier.fillMaxHeight(), rail)
                    Stage(Modifier.weight(1f).fillMaxHeight())
                    SidePanel(Modifier.width(if (rail) 320.dp else 360.dp).fillMaxHeight().enter(0))
                }
                PlayerBar()
            }
            }
            // «назад / вперёд» — слева сверху, как в браузере (на маке — сразу за кнопками окна)
            NavArrows(Modifier.align(Alignment.TopStart).padding(start = if (melos.core.Paths.isMac) 80.dp else 10.dp, top = 2.dp))
            ChromiumDownload(Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
            UpdateOffer(Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
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

// Первый вход без своего Chromium: качаем его — вместо тостов честная шкала поверх окна.
@Composable
private fun ChromiumDownload(modifier: Modifier) {
    val p = pal
    val frac by melos.host.Chromium.downloadProgress.collectAsState()
    val f = frac ?: return
    Card(modifier.width(340.dp), color = p.card2, pad = 10.dp) {
        Row2(gap = 10.dp) {
            Txt("Скачиваю Chromium для входа", style = T.small, modifier = Modifier.weight(1f))
            Txt("${(f * 100).toInt()} %", color = p.muted, style = T.num)
        }
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(R.pill).background(p.txt.copy(alpha = .12f))) {
            Box(Modifier.fillMaxWidth(f.coerceIn(0.02f, 1f)).height(6.dp).clip(R.pill).background(p.acc))
        }
    }
}

// Обновление: при выходе новой версии — карточка при входе («Обновить» = скачалось и само перезапустилось),
// в настройках — раздел «Обновления». Пока качается — та же карточка со шкалой.
@Composable
private fun UpdateOffer(modifier: Modifier) {
    val p = pal
    val st by melos.host.Updater.state.collectAsState()
    when (st) {
        is melos.host.Updater.State.Available -> Card(modifier.width(380.dp), color = p.card2, pad = 12.dp) {
            Row2(gap = 8.dp) {
                Icon(Ic.refresh, p.acc, size = 16.dp)
                Txt("Доступна Melos ${(st as melos.host.Updater.State.Available).version}", style = T.title, modifier = Modifier.weight(1f))
            }
            Row2(gap = 8.dp, modifier = Modifier.padding(top = 10.dp)) {
                PrimaryButton("Обновить", { melos.host.Updater.update() }, icon = Ic.download)
                Btn("Позже", { melos.host.Updater.dismiss() })
            }
        }
        is melos.host.Updater.State.Downloading -> {
            val frac = (st as melos.host.Updater.State.Downloading).frac
            Card(modifier.width(380.dp), color = p.card2, pad = 10.dp) {
                Row2(gap = 10.dp) {
                    Txt("Скачиваю обновление", style = T.small, modifier = Modifier.weight(1f))
                    Txt("${(frac * 100).toInt()} %", color = p.muted, style = T.num)
                }
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(R.pill).background(p.txt.copy(alpha = .12f))) {
                    Box(Modifier.fillMaxWidth(frac.coerceIn(0.02f, 1f)).height(6.dp).clip(R.pill).background(p.acc))
                }
            }
        }
        melos.host.Updater.State.Restarting -> Card(modifier.width(380.dp), color = p.card2, pad = 12.dp) {
            Txt("Обновляю — Melos сейчас перезапустится", style = T.small, maxLines = 2)
        }
        else -> {}
    }
}

@Composable
private fun NavArrows(modifier: Modifier) {
    val p = pal
    @Composable
    fun Arrow(icon: androidx.compose.ui.graphics.vector.ImageVector, hint: String, on: Boolean, go: () -> Unit) {
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        androidx.compose.foundation.TooltipArea(tooltip = {
            Box(Modifier.clip(R.sm).background(p.card2).border(1.dp, p.bd, R.sm).padding(horizontal = 10.dp, vertical = 6.dp)) { Txt(hint, color = p.txt, style = T.small) }
        }, delayMillis = 500) {
            Box(Modifier.size(26.dp).clip(CircleShape).then(if (on) Modifier.lux(go, CircleShape, bloom = false) else Modifier),
                contentAlignment = Alignment.Center) {
                Icon(icon, p.txt.copy(alpha = if (on) .86f else .28f), size = 16.dp)
            }
        }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Arrow(Ic.left, "Назад (⌘[)", Nav.canBack) { Nav.back() }
        Arrow(Ic.right, "Вперёд (⌘])", Nav.canForward) { Nav.forward() }
    }
}

