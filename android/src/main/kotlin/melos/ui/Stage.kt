package melos.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import melos.library.Library

// Сцена: экран по Nav.current — та же логика, что в десктопном App.kt (Stage), но без зависимостей окна.
@Composable
fun Stage(modifier: Modifier) {
    val p = pal
    val screen = Nav.current
    val home = screen == Screen.Home
    val artAlpha by animateFloatAsState(if (home) 1f else if (p.dark) .32f else .25f, tween(400))
    Box(modifier.clip(R.lg).background(p.card).border(1.dp, p.bd, R.lg)) {
        if (Look.brain && !home) Brain(Modifier.fillMaxSize().alpha(artAlpha), interactive = false)
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.Home -> HomeScreen()
                is Screen.Search -> key(screen.service) { SearchScreen(screen.query, screen.service) }
                is Screen.Track -> DroidTrackScreen(screen.track)
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
