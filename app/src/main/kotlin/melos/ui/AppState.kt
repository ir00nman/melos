package melos.ui

import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.node.Path
import melos.core.Settings

sealed interface Screen {
    data object Home : Screen
    data class Search(val query: String = "") : Screen
    data class Open(val node: Node) : Screen
    data class OpenPath(val path: Path, val title: String) : Screen
    data object Favorites : Screen
    data object History : Screen
    data object Downloads : Screen
}

enum class Side { QUEUE, LYRICS }
enum class Sheet { SETTINGS, EQ, PLUGINS }

const val MELOS_SECTION = "melos"

object Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current get() = stack.last()

    fun go(s: Screen) {
        if (current == s) return
        if (s == Screen.Home) { stack.clear(); stack += Screen.Home; return }
        if (s is Screen.Search && current is Screen.Search) { stack[stack.lastIndex] = s; return }
        stack += s
        if (stack.size > 40) stack.removeAt(1)
        BrainBus.tick()
    }

    fun back() { if (stack.size > 1) { stack.removeAt(stack.lastIndex); BrainBus.tick() } }

    var side by mutableStateOf(Settings.store.string("side")?.let { runCatching { Side.valueOf(it) }.getOrNull() })
    fun toggleSide(s: Side) { side = if (side == s) null else s; Settings.store.put("side", side?.name) }

    /** Раздел левого меню, как в Murglar: свой (Melos) или id сервиса — справа от столбика показывается его меню. */
    var section by mutableStateOf(Settings.store.string("nav.section") ?: MELOS_SECTION)
    fun selectSection(id: String) { section = id; Settings.store.put("nav.section", id); BrainBus.tick() }

    var sheet by mutableStateOf<Sheet?>(null)
    var loginFor by mutableStateOf<String?>(null)        // id сервиса, для которого открыт вход
}

// ---- горячие клавиши: одни и те же в окне и во всплывающих меню (у Popup с фокусом свои клавиши) ----

/** Поля ввода, в которых сейчас фокус: пока печатаешь, пробел и стрелки — текст, а не управление плеером. */
val typingFields = mutableSetOf<Any>()

fun globalKey(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return false
    val mod = e.isMetaPressed || e.isCtrlPressed
    val typing = typingFields.isNotEmpty()
    return when {
        mod && e.key == Key.F -> { Nav.go(Screen.Search()); true }
        mod && e.key == Key.DirectionRight -> { melos.player.Player.next(); true }
        mod && e.key == Key.DirectionLeft -> { melos.player.Player.prev(); true }
        e.key == Key.Escape -> { if (Nav.sheet != null) Nav.sheet = null else Nav.back(); true }
        typing -> false
        e.key == Key.Spacebar -> { melos.player.Player.toggle(); true }          // при любой открытой панели или меню
        e.key == Key.DirectionRight -> { melos.player.Player.seekBy(5000); true }
        e.key == Key.DirectionLeft -> { melos.player.Player.seekBy(-5000); true }
        else -> false
    }
}
