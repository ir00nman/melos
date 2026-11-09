package melos.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmanners.murglar.lib.core.login.CaptchaRequiredStep
import com.badmanners.murglar.lib.core.login.CredentialLoginStep
import com.badmanners.murglar.lib.core.login.SuccessfulLogin
import com.badmanners.murglar.lib.core.login.TwoFARequiredStep
import com.badmanners.murglar.lib.core.preference.ActionPreference
import com.badmanners.murglar.lib.core.preference.CopyPreference
import com.badmanners.murglar.lib.core.preference.EditPreference
import com.badmanners.murglar.lib.core.preference.ListPreference
import com.badmanners.murglar.lib.core.preference.SwitchPreference
import melos.core.Paths
import melos.download.Downloads
import melos.host.Origin
import melos.host.Service
import melos.host.Services
import melos.host.EmbeddedWebView
import melos.host.Toasts
import melos.player.Player
import melos.player.Quality
import melos.player.VlcEngine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Лист: поднимается тяжёлой стеклянной панелью, карточки внутри вылетают каскадом (Motion-Luxe §5.3).
@Composable
fun SheetHost(title: String, onClose: () -> Unit, width: Int = 640, content: @Composable ColumnScope.() -> Unit) {
    val p = pal
    val a = remember { Animatable(if (Look.motion) 0f else 1f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(360, easing = EaseLux)) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = a.value }.background(p.scrim)
        .onPreviewKeyEvent { e ->
            if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Escape) { onClose(); true } else false
        }
        .clickable(remember { MutableInteractionSource() }, null, onClick = onClose), contentAlignment = Alignment.BottomCenter) {
        val scroll = rememberScrollState()
        Column(Modifier.widthIn(max = width.dp).fillMaxWidth().fillMaxHeight(0.9f)
            .graphicsLayer { translationY = (1 - a.value) * 16.dp.toPx(); scaleX = .985f + .015f * a.value; scaleY = scaleX; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f) }
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(p.bg)
            .border(1.dp, p.bd, androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .clickable(remember { MutableInteractionSource() }, null) {}.padding(10.dp)) {
            Row2(Modifier.padding(4.dp)) {
                RoundButton(Ic.left, onClose)
                Txt(title, style = T.h2)
            }
            Spacer(Modifier.height(8.dp))
            SheetScroll(scroll, Modifier.weight(1f), content = content)
        }
    }
}

@Composable
private fun SheetCard(n: Int, content: @Composable ColumnScope.() -> Unit) {
    val a = remember { Animatable(if (Look.motion) 0f else 1f) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(90L + n * 70L); a.animateTo(1f, tween(500, easing = EaseLux)) }
    Card(Modifier.fillMaxWidth().graphicsLayer { alpha = a.value; translationY = (1 - a.value) * 10.dp.toPx() }, content = content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet() {
    val p = pal
    val services by Services.all.collectAsState()
    val errors by Services.errors.collectAsState()
    Services.revision.collectAsState().value
    val quality by Player.quality.collectAsState()
    val tap by Player.tapAudio.collectAsState()
    // «Войти в аккаунт» из меню сервиса — сразу вход этого сервиса, без общих настроек вокруг
    val target = services.firstOrNull { it.id == Nav.loginFor }
    if (target != null) {
        SheetHost(if (target.murglar.loginResolver.isLogged) "${target.name}: аккаунт" else "Вход в ${target.name}", { Nav.sheet = null; Nav.loginFor = null }) {
            SheetCard(0) { ServiceCard(target) }
        }
        // только что вошли — панель закрывается сама; открыли уже залогиненным («Аккаунт») — остаётся, чтобы выйти
        val wasLogged = remember(target.id) { target.murglar.loginResolver.isLogged }
        if (!wasLogged && target.murglar.loginResolver.isLogged) LaunchedEffect(Unit) { Nav.sheet = null; Nav.loginFor = null; Nav.selectSection(target.id) }
    } else SheetHost("Настройки", { Nav.sheet = null; Nav.loginFor = null }) {
        var n = 0
        SheetCard(n++) {
            Cap("Тема")
            Seg(ThemeMode.entries.map { it to it.label }, Look.theme, { Look.theme = it; Look.save() }, Modifier.padding(top = 8.dp))
            Cap("Акцент", Modifier.padding(top = 14.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Accent.entries.forEach { acc ->
                    val on = Look.accent == acc
                    val c = if (p.dark) acc.dark else acc.light
                    Box(Modifier.size(28.dp).clip(CircleShape).background(if (acc == Accent.AUTO) androidx.compose.ui.graphics.Brush.sweepGradient(
                        listOf(Accent.BLUE.dark, Accent.GREEN.dark, Accent.ORANGE.dark, Accent.ROSE.dark, Accent.VIOLET.dark, Accent.BLUE.dark)) else androidx.compose.ui.graphics.SolidColor(c))
                        .border(if (on) 2.dp else 1.dp, if (on) p.txt else p.bd, CircleShape).lux({ Look.accent = acc; Look.save() }, CircleShape, bloom = false))
                }
                Txt(Look.accent.label, color = p.muted, style = T.small)
            }
            SwitchRow("Анимации", Look.motion, { Look.motion = it; Look.save() })
            SwitchRow("Мозг", Look.brain, { Look.brain = it; Look.save() })
        }
        SheetCard(n++) {
            Cap("Звук")
            // на виду: чем реально играет Melos; без VLC плеер молчит — отсюда же можно его найти
            var vlc by remember { mutableStateOf(Player.available) }
            Row2(Modifier.padding(top = 6.dp)) {
                if (vlc) {
                    Icon(Ic.check, p.ok, size = 15.dp)
                    Txt("VLC ${remember { runCatching { melos.player.VlcEngine.version() }.getOrDefault("?") }} — звук через него",
                        Modifier.padding(start = 6.dp), p.muted, T.small)
                } else {
                    Icon(Ic.shield, p.critical, size = 15.dp)
                    Txt("VLC не найден — плеер молчит", Modifier.weight(1f).padding(start = 6.dp), p.critical, T.small)
                    Btn("Найти", { vlc = Player.recheckVlc() })
                    Btn("Установить", {
                        melos.core.Browse.url(melos.core.Paths.vlcDownloadUrl)
                    })
                }
            }
            SwitchRow("Выравнивать громкость", Player.normalize.collectAsState().value, { Player.normalize.value = it })
            SwitchRow("Звук через Melos", tap, { Player.setTap(it) })
            Row2(Modifier.padding(top = 12.dp)) {
                Txt("Сдвиг анимаций", Modifier.weight(1f))
                Btn("−20", { Pulse.setSyncShift(Pulse.syncShiftMs - 20) })
                Txt("${Pulse.syncShiftMs} мс", Modifier.width(72.dp), style = T.num.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
                Btn("+20", { Pulse.setSyncShift(Pulse.syncShiftMs + 20) })
            }
            Txt("Качество потока", Modifier.padding(top = 12.dp), style = T.body)
            Seg(Quality.entries.map { it to it.label }, quality, { Player.quality.value = it }, Modifier.padding(top = 6.dp))
        }
        SheetCard(n++) {
            Cap("Загрузки")
            var folder by remember { mutableStateOf(Downloads.folder.path) }
            Row2(Modifier.padding(top = 8.dp)) {
                Input(folder, { folder = it; Downloads.folder = java.io.File(it) }, Modifier.weight(1f))
                Btn("Выбрать…", { melos.core.Browse.pickDir(java.io.File(folder)) { folder = it.path; Downloads.folder = it } })
            }
            Txt("Без подпапок: «Исполнитель - Название», лучшее качество сервиса.",
                Modifier.padding(top = 8.dp), pal.muted, T.small, maxLines = 3)
        }
        // аккаунты сервисов — только в меню сервиса слева («Войти в аккаунт» / «Аккаунт»), здесь их нет
        SheetCard(n++) {
            Cap("Обновления")
            val st by melos.host.Updater.state.collectAsState()
            Row2(Modifier.padding(top = 8.dp)) {
                Txt("Melos ${melos.host.Updater.current}", Modifier.weight(1f), p.muted, T.small)
                when (st) {
                    is melos.host.Updater.State.Checking -> Txt("Проверяю…", color = p.muted, style = T.small)
                    is melos.host.Updater.State.Available -> PrimaryButton("Обновить до ${(st as melos.host.Updater.State.Available).version}",
                        { melos.host.Updater.update() }, icon = Ic.download)
                    is melos.host.Updater.State.Downloading -> Txt("Скачиваю… ${((st as melos.host.Updater.State.Downloading).frac * 100).toInt()} %", color = p.muted, style = T.small)
                    is melos.host.Updater.State.Restarting -> Txt("Перезапускаюсь…", color = p.muted, style = T.small)
                    else -> Btn("Проверить обновления", { melos.host.Updater.startCheck() }, icon = Ic.refresh)
                }
            }
        }
        SheetCard(n++) {
            Cap("Плагины Murglar")
            Txt(Paths.plugins.path, Modifier.padding(top = 6.dp), p.muted, T.num)
            Row2(Modifier.padding(top = 10.dp)) {
                Btn("Открыть папку", { melos.core.Browse.dir(Paths.plugins) }, icon = Ic.folder)
                Btn("Перезагрузить", { Services.load(); Toasts.shortNotify("Плагины перезагружены") }, icon = Ic.refresh)
            }
            val plugins = services.filter { it.origin is Origin.Plugin }
            plugins.forEach { s -> Txt("• ${s.name} — ${(s.origin as Origin.Plugin).file.name}, v${(s.origin as Origin.Plugin).version}", Modifier.padding(top = 6.dp), style = T.small) }
            errors.forEach { e -> Txt("✕ ${e.file.name}: ${e.message}", Modifier.padding(top = 6.dp), p.critical, T.small, maxLines = 3) }
        }
        Spacer(Modifier.height(20.dp))
    }
}

private val unofficial = setOf("ynd", "sc", "vk")
private val tokenHelp = mapOf("ynd" to "https://ym.marshal.dev/token/")

@OptIn(ExperimentalLayoutApi::class)
// вход живёт дольше карточки: панель закрылась, пока пользователь входит в окне Chromium, — вход не обрывается
private val loginScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

@Composable
private fun ServiceCard(s: Service) {
    val p = pal
    val scope = rememberCoroutineScope()
    val login = s.murglar.loginResolver
    val fields = remember(s.id) { mutableStateMapOf<String, String>() }
    var step by remember(s.id) { mutableStateOf<CredentialLoginStep?>(null) }
    var stepValue by remember(s.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var variant by remember(s.id) { mutableIntStateOf(0) }
    Row2 {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (login.isLogged) p.ok else p.muted))
        Txt(s.name, style = T.title, modifier = Modifier.weight(1f))
        Txt(when (val o = s.origin) { Origin.Builtin -> "встроенный"; is Origin.Plugin -> "плагин v${o.version}" }, color = p.muted, style = T.small)
    }
    if (login.isLogged) Txt(login.loginInfo, Modifier.padding(top = 4.dp), p.muted, T.small, maxLines = 1)
    // встроенные сервисы ходят в неофициальные API — честно предупреждаем
    if (s.origin == Origin.Builtin && s.id in unofficial) {
        Row(Modifier.padding(top = 8.dp).clip(R.md).background(p.warn.copy(alpha = .1f)).border(1.dp, p.warn.copy(alpha = .45f), R.md).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Ic.shield, p.warn, size = 18.dp)
            Txt("Неофициальный API — используете на свой страх и риск",
                color = p.txt.copy(alpha = .9f), style = T.small, maxLines = 4)
        }
    }
    tokenHelp[s.id]?.let { url ->
        if (!login.isLogged) Row2(Modifier.padding(top = 8.dp)) {
            Btn("Как получить токен", { melos.core.Browse.url(url) }, icon = Ic.link)
        }
    }

    fun finish(r: Result<CredentialLoginStep>) {
        busy = false
        r.onSuccess { st ->
            if (st is SuccessfulLogin) { step = null; fields.clear(); Toasts.shortNotify("Вход в ${s.name} выполнен"); BrainBus.flash(p.ok); Services.bump() }
            else { step = st; stepValue = "" }
        }.onFailure { Toasts.error("${s.name}: ${it.message}"); BrainBus.shake() }
    }

    // как в Murglar: сначала вход через веб (страница сервиса во встроенном окне), ниже — «или по токену/паролю»
    val variants = login.credentialsLoginVariants
    val web = login.webLoginVariants
    if (!login.isLogged) {
        if (web.isNotEmpty()) Row2(Modifier.padding(top = 12.dp)) {
            web.forEach { w ->
                PrimaryButton(if (busy) "Жду входа…" else w.label(), {
                    if (busy) return@PrimaryButton
                    busy = true
                    loginScope.launch {
                        val ok = runCatching { login.webLogin(w.id, EmbeddedWebView) }
                        busy = false
                        ok.onSuccess { done ->
                            if (done && login.isLogged) { Toasts.shortNotify("Вход в ${s.name} выполнен"); BrainBus.flash(p.ok); Bursts.fire(); Services.bump() }
                            else if (w.id == melos.builtin.TokenLoginResolver.BROWSER)
                                Toasts.shortNotify("Войдите в браузере — Melos сам заметит, когда вход завершится. Если macOS спросит, можно ли управлять браузером, — разрешите")
                            else Toasts.error("${s.name}: окно входа закрылось до выдачи токена — попробуйте ещё раз или войдите с токеном")
                        }.onFailure { melos.host.Log.e("Login", "${s.id} ${w.id}", it); Toasts.error("${s.name}: ${it.message}"); BrainBus.shake() }
                    }
                }, icon = Ic.user)
            }
        }
        if (variants.isNotEmpty()) {
            if (web.isNotEmpty()) Txt("или", Modifier.padding(top = 12.dp), p.muted, T.small)
            if (variants.size > 1) Seg(variants.indices.map { it to variants[it].label() }, variant, { variant = it }, Modifier.padding(top = 8.dp), small = true)
            val v = variants[variant.coerceIn(variants.indices)]
            val st = step
            if (st == null) {
                v.credentials.forEach { c ->
                    Input(fields[c.id] ?: "", { fields[c.id] = it }, Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = c.label(), secure = c.secure)
                }
            } else {
                if (st is CaptchaRequiredStep) Cover(st.captchaImageUrl, 120.dp, Modifier.padding(top = 8.dp))
                Input(stepValue, { stepValue = it }, Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = if (st is TwoFARequiredStep) st.label else "Текст с картинки")
            }
            Row2(Modifier.padding(top = 8.dp)) {
                val primary = web.isEmpty()
                val onLogin = {
                    if (!busy) {
                        busy = true
                        scope.launch {
                            val args = when (val cur = step) {
                                null -> fields.toMap()
                                is TwoFARequiredStep -> cur.callbackArgs + (TwoFARequiredStep.TWO_F_A_VALUE to stepValue)
                                is CaptchaRequiredStep -> cur.callbackArgs + (CaptchaRequiredStep.CAPTCHA_VALUE to stepValue)
                                else -> cur.callbackArgs
                            }
                            finish(runCatching { login.credentialsLogin(v.id, args) })
                        }
                    }
                }
                if (primary) PrimaryButton(if (busy) "Вхожу…" else "Войти", onLogin) else Btn(if (busy) "Вхожу…" else "Войти", onLogin)
            }
        }
    } else if (variants.isNotEmpty() || web.isNotEmpty())
        Row2(Modifier.padding(top = 10.dp)) { Btn("Выйти", { login.logout(); Services.bump() }, danger = true) }

    s.murglar.murglarPreferences.forEach { pref -> PrefRow(pref) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrefRow(pref: com.badmanners.murglar.lib.core.preference.Preference) {
    val p = pal
    var rev by remember { mutableIntStateOf(0) }
    rev.let { }
    fun changed(refresh: Boolean) { rev++; if (refresh) Services.bump() }
    when (pref) {
        is SwitchPreference -> SwitchRow(pref.title, pref.getter(), { pref.setter(it); changed(pref.refreshAllAfterChange) })
        is EditPreference -> {
            var v by remember(pref.id) { mutableStateOf(pref.getter()) }
            Txt(pref.title, Modifier.padding(top = 12.dp))
            Row2(Modifier.padding(top = 6.dp)) {
                Input(v, { v = it }, Modifier.weight(1f), onSubmit = { pref.setter(v); changed(pref.refreshAllAfterChange) })
                Btn("Сохранить", { pref.setter(v); changed(pref.refreshAllAfterChange); Toasts.shortNotify("Сохранено") })
            }
        }
        is ListPreference -> {
            Txt(pref.title, Modifier.padding(top = 12.dp))
            val cur = pref.valueGetter()
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                pref.values.forEachIndexed { i, value -> Pill(pref.names.getOrElse(i) { value }, on = value == cur) { pref.valueSetter(value); changed(pref.refreshAllAfterChange) } }
            }
        }
        is ActionPreference -> Row2(Modifier.padding(top = 10.dp)) {
            Column(Modifier.weight(1f)) {
                Txt(pref.title)
            }
            Btn("Выполнить", { pref.action(); changed(pref.refreshAllAfterInvoke); Toasts.shortNotify("Готово") })
        }
        is CopyPreference -> Row2(Modifier.padding(top = 10.dp)) {
            Column(Modifier.weight(1f)) { Txt(pref.title); Txt(pref.displayGetter(), color = p.muted, style = T.num) }
            val cb = androidx.compose.ui.platform.LocalClipboardManager.current
            Btn("Копировать", { cb.setText(androidx.compose.ui.text.AnnotatedString(pref.getter())) }, icon = Ic.link)
        }
    }
}

private val presetNames = mapOf(
    "Flat" to "Ровно", "Classical" to "Классика", "Club" to "Клуб", "Dance" to "Танцы", "Full bass" to "Бас", "Full bass and treble" to "Бас и верха",
    "Full treble" to "Верха", "Headphones" to "Наушники", "Large Hall" to "Большой зал", "Live" to "Концерт", "Party" to "Вечеринка",
    "Pop" to "Поп", "Reggae" to "Регги", "Rock" to "Рок", "Ska" to "Ска", "Soft" to "Мягко", "Soft rock" to "Софт-рок", "Techno" to "Техно",
)

// Эквалайзер, как в Murglar: тот же эквалайзер VLC. Панель над нижним плеером (плеер под ней продолжает работать),
// список пресетов, переключатель, крестик; 11 вертикальных полос: усиление и 10 частот, шаг 1 дБ, двойной клик — 0.
// Пресет загружается целиком (с усилением), движение полосы переключает на «Свой»; выключение не трогает значения.
@Composable
fun EqSheet() {
    val p = pal
    val on by Player.eqOn.collectAsState()
    val preset by Player.eqPreset.collectAsState()
    val amps by Player.eqAmps.collectAsState()
    val preamp by Player.eqPreamp.collectAsState()
    var menu by remember { mutableStateOf(false) }
    fun label(name: String) = if (name == "custom") "Свой" else presetNames[name] ?: name
    fun edit(set: () -> Unit) { set(); Player.eqPreset.value = "custom" }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 12.dp).widthIn(max = 740.dp).fillMaxWidth()
            .enter(0, "eq").shadow(24.dp, R.lg).clip(R.lg).background(lerpC(p.bg, p.card2.copy(alpha = 1f), 0.9f)).border(1.dp, p.bd, R.lg)
            .clickable(remember { MutableInteractionSource() }, null) {}.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(R.md).background(p.card).border(1.dp, p.bd, R.md)
                        .lux({ menu = !menu }, R.md, bloom = true).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(label(preset), Modifier.weight(1f), style = T.title)
                        Icon(Ic.right, p.muted, Modifier.graphicsLayer { rotationZ = if (menu) -90f else 90f }, 18.dp)
                    }
                    if (menu) androidx.compose.ui.window.Popup(alignment = Alignment.BottomStart, offset = androidx.compose.ui.unit.IntOffset(0, -56),
                        onDismissRequest = { menu = false }, properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
                        Column(Modifier.width(300.dp).heightIn(max = 420.dp).clip(R.md).background(p.card2.copy(alpha = 1f)).border(1.dp, p.bd, R.md)
                            .verticalScroll(rememberScrollState()).padding(6.dp)) {
                            if (Player.hasMurglarEq) MenuItem("Как в Murglar", on = false) {
                                if (!Player.importMurglarEq()) Toasts.shortNotify("Не удалось прочитать настройки Murglar")
                                menu = false
                            }
                            (listOf("custom") + VlcEngine.presets).forEach { name ->
                                MenuItem(label(name), on = preset == name) { Player.loadEqPreset(name); Player.eqOn.value = true; menu = false }
                            }
                        }
                    }
                }
                Switch(on, { Player.eqOn.value = it })
                IconBtn(Ic.x, { Nav.sheet = null }, size = 20.dp)
            }
            // как в Murglar, эквалайзер громкость не подстраивает: если кривая выше исходного уровня — громкие места перегрузятся
            val over = remember(amps, preamp) { Player.eqOverDb(amps, preamp) }
            if (on && over >= 1f) Txt("Перегруз до +${over.roundToInt()} дБ на громких местах — убавь «Усил.» на ${over.roundToInt()}",
                color = p.acc, style = T.small)
            val bands = VlcEngine.bands
            @Composable fun bar(i: Int) {
                val hz = bands.getOrNull(i - 1) ?: 0f
                if (i == 0) EqBar("Усил.", preamp, reset = Player.VLC_UNITY_PREAMP) { v -> edit { Player.eqPreamp.value = v } }
                else EqBar(if (hz >= 1000) "${(hz / 1000).roundToInt()} кГц" else "${hz.roundToInt()} Гц", amps[i - 1]) { nv ->
                    edit { Player.eqAmps.value = Player.eqAmps.value.toMutableList().also { it[i - 1] = nv } }
                }
            }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().graphicsLayer { alpha = if (on) 1f else 0.5f }) {
                if (maxWidth < 680.dp) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.SpaceBetween) { (0..5).forEach { bar(it) } }
                    Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.SpaceBetween) { (6..10).forEach { bar(it) } }
                } else Row(Modifier.fillMaxWidth().height(250.dp), horizontalArrangement = Arrangement.SpaceBetween) { (0..10).forEach { bar(it) } }
            }
        }
    }
}

@Composable
private fun EqBar(label: String, value: Float, reset: Float = 0f, shownOffset: Float = 0f, onChange: (Float) -> Unit) {
    val p = pal
    val change by androidx.compose.runtime.rememberUpdatedState(onChange)
    fun snap(v: Float) = v.roundToInt().toFloat().coerceIn(-20f, 20f)       // шаг 1 дБ, как в Murglar
    Column(Modifier.width(60.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // подпись — число как в Murglar (у усиления шкала VLC: 12 = без изменений); дробные — после «Как в Murglar»
        val d = value - shownOffset
        val shown = (if (d > 0.05f) "+" else "") + (if (kotlin.math.abs(d - kotlin.math.round(d)) < 0.05f) d.roundToInt().toString() else "%.1f".format(d))
        Txt("$shown дБ", color = if (kotlin.math.abs(d) >= 0.25f) p.txt else p.txt.copy(alpha = .7f), style = T.num)
        Canvas(Modifier.weight(1f).width(44.dp).pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { change(reset) }) { change(snap((1 - it.y / size.height) * 40f - 20f)) }
            }
            .pointerInput(Unit) { detectDragGestures { c, _ -> change(snap((1 - c.position.y / size.height) * 40f - 20f)) } }) {
            val w = 14.dp.toPx(); val x = size.width / 2 - w / 2; val r = androidx.compose.ui.geometry.CornerRadius(w / 2)
            val f = (value + 20f) / 40f
            val y = size.height * (1 - f)
            drawRoundRect(p.txt.copy(alpha = .12f), Offset(x, 0f), Size(w, size.height), r)
            drawRoundRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(lerpC(p.acc, p.c2, .5f), p.acc), startY = y, endY = size.height),
                Offset(x, y), Size(w, size.height - y), r)
            drawLine(p.txt.copy(alpha = .25f), Offset(x - 4.dp.toPx(), size.height / 2), Offset(x + w + 4.dp.toPx(), size.height / 2), 1.dp.toPx())   // 0 дБ
            drawRoundRect(Color.White, Offset(x - 5.dp.toPx(), y - 2.dp.toPx()), Size(w + 10.dp.toPx(), 4.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        }
        Txt(label, color = p.txt.copy(alpha = .9f), style = T.small)
    }
}
