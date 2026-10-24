package melos.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
fun Card(modifier: Modifier = Modifier, shape: Shape = R.lg, color: Color = pal.card, pad: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.clip(shape).background(color).border(1.dp, pal.bd, shape).padding(pad), content = content)

@Composable
fun Cap(text: String, modifier: Modifier = Modifier) = Txt(text.uppercase(), modifier, pal.muted, T.cap)

// Нажатие — утяжеление по «бархату», наведение — цветение рамки акцентом (Motion-Luxe §6).
@Composable
fun Modifier.lux(onClick: (() -> Unit)?, shape: Shape = R.pill, bloom: Boolean = true): Modifier {
    if (onClick == null) return this
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(200, easing = EaseVelvet))
    val p = pal
    val border by animateColorAsState(if (hovered && bloom) lerpC(p.bd, p.acc, 0.72f) else Color.Transparent, tween(280, easing = EaseLux))
    return this
        .scale(scale)
        .then(if (bloom) Modifier.border(1.dp, border, shape) else Modifier)
        .clip(shape)
        .hoverable(src)
        .clickable(src, indication = null, onClick = onClick)
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
// Зона нажатия не меньше 44×44, сама иконка может быть мельче.
fun IconBtn(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = pal.txt.copy(alpha = .86f), size: Dp = 20.dp, active: Boolean = false) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val p = pal
    Box(modifier.size(maxOf(44.dp, size + 24.dp)).clip(CircleShape).hoverable(src).clickable(src, null, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
        .background(if (active) p.acc.copy(alpha = .14f) else if (hovered) p.txt.copy(alpha = 0.09f) else Color.Transparent), contentAlignment = Alignment.Center) {
        Icon(icon, if (active) p.acc else if (hovered) p.txt else tint, size = size)
    }
}

// Акцентная кнопка: по ней пробегает металлический блик при наведении.
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val p = pal
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val shine by animateFloatAsState(if (hovered) 1.32f else -0.45f, tween(if (hovered) 680 else 0, easing = EaseLux))
    Row(
        modifier.shadow(10.dp, R.pill, ambientColor = p.acc, spotColor = p.acc.copy(alpha = .6f)).clip(R.pill).background(p.acc)
            .hoverable(src).lux(onClick, R.pill, bloom = false)
            .drawWithContent {
                drawContent()
                val x = size.width * shine
                drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .42f), Color.Transparent),
                    start = Offset(x - size.width * .25f, 0f), end = Offset(x + size.width * .15f, size.height)))
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Icon(it, p.accInk, size = 18.dp) }
        Txt(text, color = p.accInk, style = T.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp))
    }
}

@Composable
fun Btn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, danger: Boolean = false) {
    val p = pal
    Row(
        modifier.clip(R.pill).background(p.card2).border(1.dp, if (danger) p.critical.copy(alpha = .6f) else p.bd, R.pill).lux(onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, if (danger) p.critical else p.txt, size = 18.dp) }
        Txt(text, color = if (danger) p.critical else p.txt, style = T.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
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
        Txt(text, Modifier.widthIn(max = 220.dp), color = if (on) p.txt else p.txt.copy(alpha = .86f), style = T.body.copy(fontSize = 13.5.sp))
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
        Txt(text, color = pal.muted, style = T.body.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), maxLines = 6)
        content()
    }

@Composable
fun Divider(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(pal.bd))

@Composable
fun Boxed(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) = Box(modifier, content = content)

val NoPad = PaddingValues(0.dp)
