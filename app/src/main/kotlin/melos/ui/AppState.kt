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
    /** service — поиск только в этом сервисе (из его меню); null — общий поиск Melos с переключателем сервисов. */
    data class Search(val query: String = "", val service: String? = null) : Screen
    data class Open(val node: Node) : Screen
    data class OpenPath(val path: Path, val title: String) : Screen
    data class Track(val track: com.badmanners.murglar.lib.core.model.track.BaseTrack) : Screen
    data object Favorites : Screen
    data object History : Screen
    data object Downloads : Screen
}

enum class Side { QUEUE, LYRICS }
enum class Sheet { SETTINGS, EQ, PLUGINS }

const val MELOS_SECTION = "melos"

// История переходов как в браузере: «назад» уходит в forward, новый переход вперёд её сбрасывает.
object Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val forward = mutableStateListOf<Screen>()
    val current get() = stack.last()
    val canBack get() = stack.size > 1
    val canForward get() = forward.isNotEmpty()

    fun go(s: Screen) {
        if (current == s) return
        if (s is Screen.Search && (current as? Screen.Search)?.service == s.service) { stack[stack.lastIndex] = s; return }
        stack += s
        forward.clear()
        if (stack.size > 60) stack.removeAt(0)
        BrainBus.tick()
    }

    fun back() { if (canBack) { forward += stack.removeAt(stack.lastIndex); BrainBus.tick() } }
    fun forward() { if (canForward) { stack += forward.removeAt(forward.lastIndex); BrainBus.tick() } }

    /** Убрать текущий экран без «вперёд» — он был промежуточным (например, открыл волну и сразу ушёл). */
    fun drop() { if (canBack) stack.removeAt(stack.lastIndex) }

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
        mod && e.key == Key.LeftBracket -> { Nav.back(); true }             // ⌘[ / ⌘] — как в браузере
        mod && e.key == Key.RightBracket -> { Nav.forward(); true }
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
