package melos.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import melos.host.Toasts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// Точка статуса в шапке — от неё расходятся волна палитры и всплеск успеха.
object Anchor { var status by mutableStateOf(Offset(44f, 44f)) }

// Подобрали цвет по обложке — акцентная волна затопляет окно от точки статуса (pal-wave, 900 мс).
@Composable
fun PaletteWave(trigger: Any?) {
    val a = remember { Animatable(1f) }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(trigger) {
        if (!armed) { armed = true; return@LaunchedEffect }     // первое значение — память, а не срабатывание
        if (!Look.motion || Look.accent != Accent.AUTO) return@LaunchedEffect
        a.snapTo(0f); a.animateTo(1f, tween(900, easing = EaseLux))
    }
    val v = a.value
    if (v >= 1f) return
    val acc = pal.acc
    Canvas(Modifier.fillMaxSize()) {
        val r = max(size.width, size.height) * 1.3f * v
        val alpha = if (v < 0.22f) v / 0.22f else 1 - (v - 0.22f) / 0.78f
        if (r > 1f) drawCircle(Brush.radialGradient(listOf(acc.copy(alpha = .32f * alpha), acc.copy(alpha = .09f * alpha), Color.Transparent), Anchor.status, r), r, Anchor.status)
    }
}

// Сдержанный всплеск 12 акцентных частиц и тающий ореол (lux-burst).
object Bursts {
    val list = mutableStateListOf<Long>()
    fun fire() { if (Look.motion) list += System.nanoTime() }
}

@Composable
fun BurstLayer() {
    val acc = pal.acc
    Bursts.list.toList().forEach { id ->
        key(id) {
            val a = remember { Animatable(0f) }
            val seeds = remember { List(12) { i -> floatArrayOf((Math.PI * 2 * i / 12 + (Math.random() - .5) * .5).toFloat(), (15 + Math.random() * 15).toFloat(), (2.5 + Math.random() * 2.5).toFloat()) } }
            LaunchedEffect(id) { a.animateTo(1f, tween(900, easing = EaseLux)); Bursts.list.remove(id) }
            Canvas(Modifier.fillMaxSize()) {
                val t = a.value; val c = Anchor.status
                drawCircle(acc.copy(alpha = (0.85f * (1 - t)).coerceAtLeast(0f)), 5.dp.toPx() * (0.55f + 4f * t), c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                seeds.forEach { s ->
                    val r = s[1].dp.toPx() * t
                    val alpha = if (t < 0.16f) t / 0.16f else 1 - (t - 0.16f) / 0.84f
                    drawCircle(acc.copy(alpha = alpha.coerceIn(0f, 1f)), s[2].dp.toPx() / 2 * (1 - t * 0.5f), Offset(c.x + cos(s[0]) * r, c.y + sin(s[0]) * r))
                }
            }
        }
    }
}

// Свечение обложки: два очень мягких пятна цвета трека в углах окна — фон дышит вместе с музыкой,
// но не мешает контенту (появляется и уходит медленно, ~1,2 с).
@Composable
fun CoverGlow(color: Color?) {
    val a by androidx.compose.animation.core.animateFloatAsState(
        if (color != null && Look.motion) 1f else 0f, androidx.compose.animation.core.tween(1200, easing = EaseLux))
    if (a <= 0.01f) return
    val c = color ?: return
    Canvas(Modifier.fillMaxSize()) {
        val R = max(size.width, size.height) * 0.9f
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = .13f * a), Color.Transparent), Offset(size.width * .86f, size.height * .1f), R),
            R, Offset(size.width * .86f, size.height * .1f))
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = .10f * a), Color.Transparent), Offset(size.width * .08f, size.height * .95f), R * .8f),
            R * .8f, Offset(size.width * .08f, size.height * .95f))
    }
}

@Composable
fun ToastLayer(modifier: Modifier = Modifier) {
    val p = pal
    val items = remember { mutableStateListOf<Toasts.Toast>() }
    LaunchedEffect(Unit) {
        Toasts.events.collect { t ->
            items += t
            while (items.size > 3) items.removeAt(0)
            launch { delay(if (t.long) 5200 else 2600); items.remove(t) }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        items.forEach { t ->
            key(t.id) {
                Box(Modifier.enter(0, t.id).widthIn(max = 520.dp).clip(R.md).background(p.card2)
                    .border(1.dp, if (t.error) p.critical.copy(alpha = .7f) else p.bd, R.md).padding(horizontal = 14.dp, vertical = 9.dp)) {
                    Txt(t.text, color = if (t.error) p.critical else p.txt, style = T.body, maxLines = 4)
                }
            }
        }
    }
}
