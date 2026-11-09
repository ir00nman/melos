package melos.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import melos.core.Settings

// Палитра и движение — один в один с Chrome crawler (sidepanel.html: токены и блок Motion-Luxe).

val EaseLux = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)       // шёлк — ультрамягкий вылет
val EaseVelvet = CubicBezierEasing(0.34f, 1.4f, 0.64f, 1f) // бархат — лёгкий отскок физической кнопки
val EaseHeavy = CubicBezierEasing(0.7f, 0f, 0.3f, 1f)      // тяжёлая инерция

object R {
    val lg = RoundedCornerShape(20.dp)      // панели, крупные обложки
    val md = RoundedCornerShape(12.dp)      // строки, средние обложки
    val sm = RoundedCornerShape(8.dp)       // чипы, подсказки
    val pill = RoundedCornerShape(999.dp)
}

@Immutable
data class Palette(
    val dark: Boolean,
    val bg: Color, val card: Color, val card2: Color, val bd: Color, val chip: Color,
    val txt: Color, val muted: Color, val critical: Color, val ok: Color, val warn: Color,
    val acc: Color, val accInk: Color, val scrim: Color,
    val c2: Color = acc, val c3: Color = acc,          // ещё два цвета обложки — для градиентов мозга и фона
    val covered: Boolean = false,                      // плеер стилизован под обложку: поверхности полупрозрачные
)

enum class Accent(val label: String, val dark: Color, val light: Color) {
    AUTO("Обложка", Color(0xFFF5F5F5), Color(0xFF111111)),
    MONO("Моно", Color(0xFFF5F5F5), Color(0xFF111111)),
    BLUE("Синий", Color(0xFF6EA8FF), Color(0xFF2563EB)),
    GREEN("Зелёный", Color(0xFF4ADE80), Color(0xFF16A34A)),
    ORANGE("Оранжевый", Color(0xFFFB923C), Color(0xFFEA580C)),
    VIOLET("Фиолетовый", Color(0xFFA78BFA), Color(0xFF7C3AED)),
    ROSE("Розовый", Color(0xFFFB7185), Color(0xFFE11D74)),
}

enum class ThemeMode(val label: String) { SYSTEM("Системная"), DARK("Тёмная"), LIGHT("Светлая") }

// Настройки внешнего вида — глобальные, переживают перезапуск.
object Look {
    private val s = Settings.store
    var theme by mutableStateOf(s.string("theme")?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK)
    var accent by mutableStateOf(s.string("accent")?.let { runCatching { Accent.valueOf(it) }.getOrNull() } ?: Accent.MONO)
    var motion by mutableStateOf(s.bool("motion") ?: true)
    var brain by mutableStateOf(s.bool("brain") ?: true)
    var coverColor by mutableStateOf<Color?>(null)   // подобран по обложке текущего трека
    var coverPalette by mutableStateOf<List<Color>>(emptyList())   // до трёх цветов обложки, от яркого к глубокому

    fun save() {
        s.put("theme", theme.name); s.put("accent", accent.name); s.put("motion", motion); s.put("brain", brain)
    }
}

private fun mix(a: Color, pct: Float, base: Color) = lerp(base, a, pct)

@Composable
fun rememberPalette(): Palette {
    val dark = when (Look.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val auto = Look.accent == Accent.AUTO
    val target = when {
        auto && Look.coverColor != null -> Look.coverColor!!.let { if (dark) liftForDark(it) else dropForLight(it) }
        dark -> Look.accent.dark
        else -> Look.accent.light
    }
    // акцент перетекает за 420 мс, а не переключается (@property --acc в оригинале)
    val acc by animateColorAsState(target, tween(if (Look.motion) 420 else 0, easing = EaseLux))
    val ink = if (acc.luminance() > 0.45f) Color(0xFF0A0A0A) else Color.White
    val covered = auto && Look.coverPalette.isNotEmpty()
    val pal3 = Look.coverPalette
    val c2 by animateColorAsState(pal3.getOrNull(1)?.let { if (dark) liftForDark(it) else dropForLight(it) } ?: target, tween(if (Look.motion) 900 else 0, easing = EaseLux))
    val c3 by animateColorAsState(pal3.getOrNull(2)?.let { if (dark) liftForDark(it) else dropForLight(it) } ?: target, tween(if (Look.motion) 900 else 0, easing = EaseLux))
    // «Авто»: поверхности подкрашиваются акцентом; под обложкой — ещё и просвечивают, чтобы был виден фон из её цветов
    val tn = if (!auto) floatArrayOf(0f, 0f, 0f, 0f, 0f) else if (dark) floatArrayOf(.10f, .16f, .20f, .34f, .22f) else floatArrayOf(.04f, .07f, .09f, .16f, .09f)
    val glass = if (covered) 0.74f else 1f
    return if (dark) Palette(
        true, mix(acc, tn[0] * .55f, Color(0xFF0A0A0A)), mix(acc, tn[1] * .5f, Color(0xFF141414)).copy(alpha = glass),
        mix(acc, tn[2] * .5f, Color(0xFF1F1F1F)).copy(alpha = if (covered) .9f else 1f),
        mix(acc, tn[3] * .6f, Color(0xFF333333)), mix(acc, tn[4] * .5f, Color(0xFF1C1C1C)).copy(alpha = if (covered) .85f else 1f),
        // вторичный текст светлее, чем в Chrome crawler: на чёрном его не нужно разглядывать (контраст ~10:1)
        Color(0xFFF7F7F7), Color(0xFFBDBDBD), Color(0xFFFF6B66), Color(0xFF3CC47F), Color(0xFFFFB33D),
        acc, ink, Color(0xA6000000), c2, c3, covered,
    ) else Palette(
        false, mix(acc, tn[0], Color.White), mix(acc, tn[1], Color(0xFFF5F5F5)).copy(alpha = if (covered) .82f else 1f), mix(acc, tn[2], Color.White),
        mix(acc, tn[3], Color(0xFFDADADA)), mix(acc, tn[4], Color(0xFFEEEEEE)),
        Color(0xFF0E0E0E), Color(0xFF4A4A4A), Color(0xFFC62A1E), Color(0xFF137A48), Color(0xFFA86A00),
        acc, ink, Color(0x66111111), c2, c3, covered,
    )
}

fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

// подобранный по обложке цвет должен читаться на чёрном / белом
private fun liftForDark(c: Color): Color { var x = c; repeat(6) { if (x.luminance() < 0.42f) x = lerp(x, Color.White, 0.18f) }; return x }
private fun dropForLight(c: Color): Color { var x = c; repeat(6) { if (x.luminance() > 0.45f) x = lerp(x, Color.Black, 0.18f) }; return x }

val LocalPalette = staticCompositionLocalOf<Palette> { error("нет палитры") }

object T {
    val mono = Fonts.Inter                  // семейство одно на весь интерфейс
    // шкала крупнее исходной панели: это окно плеера, а не узкая боковая панель браузера
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontFamily = mono)
    val title = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = mono)
    val display = TextStyle(fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp, fontFamily = mono)
    val h1 = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, fontFamily = mono)
    val h2 = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp, fontFamily = mono)
    val cap = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, fontFamily = mono)
    val small = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontFamily = mono)
    // таймкоды, длительности, проценты: шрифт интерфейса с табличными цифрами — без моноширинного
    val num = TextStyle(fontSize = 12.sp, fontFamily = mono, fontFeatureSettings = "tnum")
}

@Composable
fun MelosTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides rememberPalette(), content = content)
}
