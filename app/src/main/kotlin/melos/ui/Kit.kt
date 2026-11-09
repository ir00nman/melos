package melos.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay

val pal @Composable get() = LocalPalette.current

@Composable
fun Txt(
    text: String, modifier: Modifier = Modifier, color: Color = pal.txt, style: TextStyle = T.body,
    maxLines: Int = 1, weight: FontWeight? = null,
) = BasicText(
    text, modifier, style.copy(color = color, fontWeight = weight ?: style.fontWeight),
    overflow = TextOverflow.Ellipsis, maxLines = maxLines, softWrap = maxLines > 1,
)

@Composable
fun Card(modifier: Modifier = Modifier, shape: Shape = R.lg, color: Color = pal.card, pad: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.clip(shape).background(color).border(1.dp, pal.bd, shape).padding(pad), content = content)

@Composable
fun Cap(text: String, modifier: Modifier = Modifier) = Txt(text.uppercase(), modifier, pal.muted, T.cap)

// Нажатие — утяжеление по «бархату», наведение/фокус — цветение рамки акцентом (Motion-Luxe §6).
@Composable
fun Modifier.lux(onClick: (() -> Unit)?, shape: Shape = R.pill, bloom: Boolean = true, enabled: Boolean = true): Modifier {
    if (onClick == null) return this
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val focused by src.collectIsFocusedAsState()
    // пружинка: нажатие проваливает кнопку, отпускание возвращает с лёгким перелётом — тактильно, как физическая
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed && enabled) 0.955f else 1f,
        androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow))
    val p = pal
    val border by animateColorAsState(if ((hovered || focused) && bloom) lerpC(p.bd, p.acc, if (focused) 0.9f else 0.72f) else Color.Transparent, tween(280, easing = EaseLux))
    return this
        .scale(scale)
        .then(if (bloom) Modifier.border(1.dp, border, shape) else Modifier)
        .clip(shape)
        .alpha(if (enabled) 1f else 0.45f)
        .hoverable(src)
        .clickable(src, indication = null, onClick = onClick, enabled = enabled)
        .pointerHoverIcon(PointerIcon.Hand)
}

fun lerpC(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

@Composable
fun RoundButton(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, active: Boolean = false,
                tint: Color? = null, filled: Color? = null, iconSize: Dp = 20.dp, hint: String? = null) {
    val p = pal
    val bg = filled ?: if (active) lerpC(p.card, p.acc, 0.16f) else p.card
    Box(
        modifier.size(size).clip(CircleShape).background(bg).border(1.dp, if (filled != null) Color.Transparent else p.bd, CircleShape)
            .lux(onClick, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, tint ?: if (active) p.acc else p.txt, size = iconSize) }
}

@Composable
// Иконочная кнопка: цель 40×40, иконка по умолчанию 22; подложка — только у активных (+ едва заметный hover).
fun IconBtn(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = pal.txt.copy(alpha = .86f), size: Dp = 22.dp, active: Boolean = false) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val p = pal
    Box(modifier.size(40.dp).clip(CircleShape).hoverable(src).clickable(src, null, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
        .background(if (active) p.acc.copy(alpha = .14f) else if (hovered) p.txt.copy(alpha = 0.06f) else Color.Transparent), contentAlignment = Alignment.Center) {
        Icon(icon, if (active) p.acc else if (hovered) p.txt else tint, size = size.coerceAtMost(22.dp))
    }
}

// Акцентная кнопка: ровная заливка акцентом (в моно — белая), чёрный текст; одна высота с secondary.
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val p = pal
    Row(
        modifier.shadow(4.dp, R.pill, ambientColor = Color.Black, spotColor = Color.Black.copy(alpha = .35f)).clip(R.pill).background(p.acc)
            .lux(onClick, R.pill, bloom = false, enabled = enabled)
            .heightIn(min = 44.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Icon(it, p.accInk, size = 18.dp) }
        Txt(text, color = p.accInk, style = T.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp))
    }
}

@Composable
// Secondary: контур 1 dp, прозрачная заливка; одна высота с primary.
fun Btn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, danger: Boolean = false, enabled: Boolean = true, wrap: Boolean = false) {
    val p = pal
    Row(
        modifier.clip(R.pill).background(p.card2).border(1.dp, if (danger) p.critical.copy(alpha = .6f) else p.bd, R.pill)
            .lux(onClick, enabled = enabled)
            .heightIn(min = 44.dp)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, if (danger) p.critical else p.txt, size = 18.dp) }
        Txt(text, Modifier.weight(1f, fill = false), color = if (danger) p.critical else p.txt,
            style = T.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp), maxLines = if (wrap) 2 else 1)
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, on: Boolean = false, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    val p = pal
    val bg by animateColorAsState(if (on) lerpC(p.card2, p.acc, .16f) else Color.Transparent, tween(240, easing = EaseLux))
    Row(
        modifier.clip(R.pill).background(bg).border(1.dp, if (on) p.acc else p.bd, R.pill).lux(onClick, bloom = !on)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        icon?.let { Icon(it, if (on) p.acc else p.txt.copy(alpha = .8f), size = 16.dp) }
        Txt(text, Modifier.widthIn(max = 280.dp), color = if (on) p.txt else p.txt.copy(alpha = .86f), style = T.body.copy(fontSize = 13.5.sp), maxLines = 2)
    }
}

// Сегменты: выбранный — заливка акцентом, остальные мягко подсвечиваются при наведении.
@Composable
fun <T> Seg(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, small: Boolean = false) {
    val p = pal
    Row(modifier.clip(R.pill).background(p.chip).border(1.dp, p.bd, R.pill).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            val src = remember { MutableInteractionSource() }
            val hovered by src.collectIsHoveredAsState()
            val bg by animateColorAsState(when { on -> p.acc; hovered -> p.acc.copy(alpha = .09f); else -> Color.Transparent }, tween(240, easing = EaseLux))
            Box(Modifier.weight(1f, fill = false).clip(R.pill).background(bg).hoverable(src).clickable(src, null) { onSelect(value) }
                .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = if (small) 12.dp else 16.dp, vertical = if (small) 8.dp else 11.dp),
                contentAlignment = Alignment.Center) {
                Txt(label, color = if (on) p.accInk else if (hovered) p.txt else p.txt.copy(alpha = .8f),
                    style = T.small.copy(fontSize = if (small) 13.sp else 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal))
            }
        }
    }
}

@Composable
fun Switch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val p = pal
    val x by animateFloatAsState(if (checked) 16f else 0f, tween(200, easing = EaseLux))
    val bg by animateColorAsState(if (checked) p.acc else p.chip, tween(200))
    Box(Modifier.alpha(if (enabled) 1f else .45f).size(48.dp, 28.dp).clip(R.pill).background(bg).border(1.dp, if (checked) Color.Transparent else p.bd, R.pill)
        .then(if (enabled) Modifier.clickable { onChange(!checked) }.pointerHoverIcon(PointerIcon.Hand) else Modifier)) {
        Box(Modifier.padding(3.dp).graphicsLayer { translationX = x.dp.toPx() * 1.25f }.size(22.dp).clip(CircleShape).background(if (checked) p.accInk else p.muted))
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp).clickable(enabled = enabled) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(title, style = T.body)
            hint?.let { Txt(it, color = pal.muted, style = T.small, maxLines = 3) }
        }
        Switch(checked, onChange, enabled)
    }
}

@Composable
fun Input(
    value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "", secure: Boolean = false,
    onSubmit: (() -> Unit)? = null, leading: ImageVector? = null, singleLine: Boolean = true,
) {
    val p = pal
    val token = remember { Any() }
    DisposableEffect(Unit) { onDispose { typingFields.remove(token) } }
    BasicTextField(
        value, onChange, modifier.onFocusChanged { if (it.isFocused) typingFields.add(token) else typingFields.remove(token) }, singleLine = singleLine,
        textStyle = T.body.copy(color = p.txt, fontSize = 15.sp), cursorBrush = SolidColor(p.acc),
        visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Search else ImeAction.Default),
        keyboardActions = KeyboardActions(onAny = { onSubmit?.invoke() }),
        decorationBox = { inner ->
            Row(Modifier.clip(R.md).background(p.card2).border(1.dp, p.bd, R.md).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                leading?.let { Icon(it, p.muted, size = 20.dp) }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt(placeholder, color = p.muted, style = T.body.copy(fontSize = 15.sp))
                    inner()
                }
            }
        },
    )
}

// Каскадное появление: вплывает снизу с расфокусом, до 6 элементов получают задержку (Motion-Luxe §5.2).
@Composable
fun Modifier.enter(index: Int = 0, key: Any? = Unit): Modifier {
    if (!Look.motion) return this
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay(minOf(index, 6) * 55L)
        a.animateTo(1f, tween(560, easing = EaseLux))
    }
    val v = a.value
    return this.graphicsLayer {
        alpha = v; translationY = (1 - v) * 9.dp.toPx(); scaleX = .985f + .015f * v; scaleY = scaleX
    }.then(if (v < 0.95f) Modifier.blur(((1 - v) * 3.5f).dp) else Modifier)
}

@Composable
fun Row2(modifier: Modifier = Modifier, gap: Dp = 8.dp, content: @Composable RowScope.() -> Unit) =
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap), content = content)

@Composable
fun Cover(url: String?, size: Dp, modifier: Modifier = Modifier, shape: Shape = R.sm, fallback: ImageVector = Ic.note, round: Boolean = false) {
    val p = pal
    val img = rememberImage(url, (size.value * 1.2f).toInt())
    val sh = if (round) CircleShape else shape
    Box(modifier.size(size).clip(sh).background(p.chip), contentAlignment = Alignment.Center) {
        if (img != null) androidx.compose.foundation.Image(img, null, Modifier.size(size), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else Icon(fallback, p.muted.copy(alpha = .6f), size = size * 0.4f)
    }
}

fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun Empty(text: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit = {}) =
    Column(modifier.padding(24.dp).width(300.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HexDots()                                            // контур шестиугольника из дышащих точек — знак логотипа
        Txt(text, color = pal.muted, style = T.body.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), maxLines = 6)
        content()
    }

/** Вздрог влево-вправо после ошибки (сердце при неудачном лайке): вызывать с ключом момента ошибки. */
@Composable
fun Modifier.shake(key: Long): Modifier {
    if (!Look.motion) return this
    val a = remember(key) { Animatable(if (key == 0L) 0f else 1f) }
    LaunchedEffect(key) { if (key > 0L) a.animateTo(0f, tween(500, easing = EaseHeavy)) }
    val v = a.value
    return if (v <= 0f) this else graphicsLayer { translationX = sin(v * 3 * Math.PI).toFloat() * 6f * v }
}

// Контур шестиугольника из точек с медленной «дыхательной» волной, бегущей по периметру, — фирменный знак там, где пусто.
@Composable
private fun HexDots() {
    if (!Look.motion) return
    val p = pal
    var frame by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) androidx.compose.runtime.withFrameNanos { frame = it } }
    Canvas(Modifier.width(120.dp).height(56.dp)) {
        frame.let { }
        val now = System.currentTimeMillis() / 1000f
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = min(size.width, size.height) / 2f * 0.72f
        // вершины плоского (flat-top) шестиугольника
        val v = (0..5).map { k ->
            val a = Math.toRadians(60.0 * k)
            Offset(c.x + r * kotlin.math.cos(a).toFloat(), c.y + r * kotlin.math.sin(a).toFloat())
        }
        val side = r                                                        // сторона = радиусу у правильного шестиугольника
        val n = 18
        for (k in 0 until n) {
            val t = k / n.toFloat()
            val s = t * 6f * side                                           // дуга по периметру
            val e = (s / side).toInt() % 6                                  // номер ребра
            val u = (s - e * side) / side                                   // положение на ребре 0..1
            val a = v[e]; val b = v[(e + 1) % 6]
            val breathe = 0.5f + 0.5f * kotlin.math.sin(now * 1.4f - t * 2.6f)
            drawCircle(lerpC(p.acc, p.c2, t).copy(alpha = 0.20f + 0.45f * breathe), (1.4f + 1.1f * breathe).dp.toPx(),
                Offset(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u))
        }
    }
}

/** Подсказка при наведении (обрезанный текст и т.п.) — работает и на десктопе, и на Android. */
@Composable
fun HoverTip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = pal
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(modifier.hoverable(src)) {
        content()
        if (hovered) {
            val dy = with(androidx.compose.ui.platform.LocalDensity.current) { 30.dp.roundToPx() }
            androidx.compose.ui.window.Popup(offset = androidx.compose.ui.unit.IntOffset(0, dy),
                properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
                Box(Modifier.clip(R.sm).background(p.card2).border(1.dp, p.bd, R.sm).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Txt(text, color = p.txt, style = T.small, maxLines = 4)
                }
            }
        }
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(pal.bd))

@Composable
fun Boxed(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) = Box(modifier, content = content)

val NoPad = PaddingValues(0.dp)
