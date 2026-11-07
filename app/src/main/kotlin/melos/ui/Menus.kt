package melos.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.node.NodeType
import melos.host.Service
import melos.host.Services
import melos.library.Library
import melos.player.Player

// Меню разделов и сервисов — общий файл: слева в окне на десктопе, в шторке на Android.

// Меню как в Murglar: слева столбик разделов (Melos, сервисы, «+» — плагины, внизу — настройки),
// справа — меню выбранного раздела: крупное название и привычный список.
@Composable
fun MelosMenu() {
    val favs by Library.favorites.items.collectAsState()
    SideTitle("Melos")
    SideItem("Главная", Nav.current == Screen.Home) { Nav.go(Screen.Home) }
    SideItem("Поиск", (Nav.current as? Screen.Search)?.service == null && Nav.current is Screen.Search) { Nav.go(Screen.Search()) }
    SideItem("Медиатека", Nav.current == Screen.Favorites, favs.size.takeIf { it > 0 }?.toString()) { Nav.go(Screen.Favorites) }
    SideItem("История", Nav.current == Screen.History) { Nav.go(Screen.History) }
    SideItem("Загрузки", Nav.current == Screen.Downloads) { Nav.go(Screen.Downloads) }
}

@Composable
fun ServiceMenu(s: Service) {
    val p = pal
    Services.revision.collectAsState().value                          // вход/выход сервиса — меню перерисовывается (иначе Compose его пропустит)
    val resolver = s.murglar.nodeResolver
    val roots = runCatching { resolver.getRootNodes(true).filter { !it.nodeParameters.isSearchable } }.getOrDefault(emptyList())
    val searchable = runCatching { resolver.getRootNodes(true).any { it.nodeParameters.isSearchable } }.getOrDefault(false)
    val login = s.murglar.loginResolver
    val canLogin = login.credentialsLoginVariants.isNotEmpty() || login.webLoginVariants.isNotEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        ServiceLogo(s.id, 26.dp, Modifier.padding(start = 10.dp, bottom = 6.dp))
        SideTitle(s.name)
    }
    if (canLogin) Txt(if (login.isLogged) "Вход выполнен" else "Вход не выполнен", Modifier.padding(start = 10.dp, bottom = 8.dp),
        if (login.isLogged) p.ok else p.muted, T.small)
    // поиск только по этому сервису; переключаться между сервисами — в общем поиске Melos
    if (searchable) SideItem("Поиск", (Nav.current as? Screen.Search)?.service == s.id) { Nav.go(Screen.Search(service = s.id)) }
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
val SITE_MENUS: Map<String, List<Pair<String?, List<String>>>> = mapOf(
    "ynd" to listOf(
        null to listOf("myWave", "chart", "newReleases"),
        "Коллекция" to listOf("myTracks", "myPlaylists", "myAlbums", "myArtists"),
    ),
    "sc" to listOf(                                                           // как на soundcloud.com: Лента, Библиотека
        null to listOf("stream"),
        "Библиотека" to listOf("myTracks", "myPlaylists"),
    ),
    "vk" to listOf(                                                           // как в разделе «Музыка» VK
        null to listOf("recommendations"),
        "Моя музыка" to listOf("myTracks", "myPlaylists"),
    ),
)

/** Сервисы в столбике: Яндекс, SoundCloud, VK и подключённые jar-плагины; «Файлы» скрыты, но работают — история и лайки с них открываются. */
val HIDDEN_BUILTINS = setOf("local")

@Composable
fun SideTitle(text: String) =
    Txt(text, Modifier.padding(start = 10.dp, end = 6.dp, bottom = 6.dp), pal.txt, T.h2.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold), maxLines = 2)

@Composable
fun SideItem(text: String, on: Boolean, badge: String? = null, dim: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = pal
    val bg by animateColorAsState(if (on) p.acc.copy(alpha = .14f) else Color.Transparent, tween(240, easing = EaseLux))
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp).clip(R.md).background(bg).lux(onClick, R.md, bloom = false).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Txt(text, Modifier.weight(1f), if (on) p.acc else if (dim) p.muted else lerpC(p.txt, p.acc, .3f),
            T.body.copy(fontSize = 16.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), maxLines = 2)
        badge?.let { Txt(it, color = p.muted, style = T.monoSmall) }
    }
}
