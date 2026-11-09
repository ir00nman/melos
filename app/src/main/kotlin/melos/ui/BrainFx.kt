package melos.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/* ---------- Свет: ядро и лучи ---------- */
// Свет здесь ЛЁГКИЙ дополнительный слой поверх прежнего «мозга»: визуал самого «мозга» не менять.

/** Мягкое ядро. radius = ~0.5 * радиус фигуры (НЕ больше), к краю alpha -> 0, фон карточки остаётся тёмным. */
fun DrawScope.drawCoreGlow(center: Offset, radius: Float, color: Color, intensity: Float) {
    val a = intensity.coerceIn(0f, 1.5f)
    drawCircle(
        brush = Brush.radialGradient(
            0f to color.copy(alpha = (0.26f * a).coerceAtMost(0.5f)),
            0.25f to color.copy(alpha = 0.15f * a),
            0.55f to color.copy(alpha = 0.04f * a),
            1f to Color.Transparent,
            center = center, radius = radius
        ),
        radius = radius, center = center, blendMode = BlendMode.Plus
    )
}

private fun hash01(i: Int): Float {
    var x = i * 374761393 + 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0xFFFF) / 65535f
}

// один Path на все слои — без аллокаций в кадре
private val rayPath = Path()

/**
 * Мягкие лучи из ядра. figureR — радиус фигуры в px (лучи 1.05–1.55 * figureR, не длиннее).
 * intensity: Главная 1.0, подэкраны 0.25–0.35. Лучей до 6, при intensity < 0.4 — не больше 4.
 * Каждый луч = 4 слоя узких треугольников разной ширины и прозрачности (мягкий край вместо жёсткой полосы),
 * градиент по длине с затуханием в ноль; ширина у основания 6–14 px (в px, а не в долях радиуса!).
 * tension: лучи сужаются и вытягиваются. drop: вспышка, шире и ярче на ~1 c. inhale: втягиваются. calm: гаснут.
 */
fun DrawScope.drawLightRays(
    center: Offset,
    figureR: Float,
    timeSec: Float,
    color: Color,
    intensity: Float,
    rays: Int = 6,
    tension: Float = 0f,
    drop: Float = 0f,
    inhale: Float = 0f,
    calm: Float = 0f,
    bass: Float = 0f
) {
    val master = intensity * (1f - 0.75f * calm) * (1f - 0.6f * inhale) * (1f + 0.9f * drop)
    if (master <= 0.01f) return
    val count = if (intensity < 0.4f) minOf(rays, 4) else rays
    val widths = floatArrayOf(1.0f, 0.62f, 0.34f, 0.16f)        // доли ширины слоя
    val alphas = floatArrayOf(0.012f, 0.018f, 0.027f, 0.040f)   // прозрачность слоя (суммируется в центре луча)
    for (i in 0 until count) {
        val h = hash01(i)
        val ang = (i.toFloat() / count) * 2f * PI.toFloat() + timeSec * (0.025f + 0.03f * h) + h * 0.8f
        val len = figureR * (1.05f + 0.5f * h) * (1f + 0.25f * tension + 0.5f * drop + 0.2f * bass) * (1f - 0.5f * inhale)
        val w0 = (6f + 8f * h) * (1f - 0.5f * tension) * (1f + 1.2f * drop)   // px у основания
        val dx = cos(ang); val dy = sin(ang)
        val px = -dy;      val py = dx
        val start = figureR * 0.12f
        val base = Offset(center.x + dx * start, center.y + dy * start)
        val tip = Offset(center.x + dx * len, center.y + dy * len)
        val a = (1f + 0.5f * h)
        for (l in widths.indices) {
            val w = w0 * widths[l]
            rayPath.reset()
            rayPath.moveTo(base.x + px * w, base.y + py * w)
            rayPath.lineTo(tip.x, tip.y)
            rayPath.lineTo(base.x - px * w, base.y - py * w)
            rayPath.close()
            val al = (alphas[l] * a * master).coerceIn(0f, 0.08f)
            drawPath(
                path = rayPath,
                brush = Brush.linearGradient(
                    0f to color.copy(alpha = al),
                    0.45f to color.copy(alpha = al * 0.35f),
                    1f to Color.Transparent,
                    start = base, end = tip
                ),
                blendMode = BlendMode.Plus
            )
        }
    }
}
