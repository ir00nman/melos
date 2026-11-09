package melos.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/* ---------- Пикселизация (dot-matrix, как в Nothing OS) ---------- */

object PixelFx {
    /** Размер ячейки в px. На нарастании крупнеет (картинка «собирается» в крупные пиксели), на дропе щёлкает в мелкую. */
    fun cell(basePx: Float, tension: Float, drop: Float, inhale: Float): Float {
        val coarse = 1f + 0.8f * tension + 0.5f * inhale   // до ~2.3x
        val fine = 1f - 0.35f * drop                        // на ударе мельче
        return (basePx * coarse * fine).coerceAtLeast(2f)
    }
    fun snap(v: Float, cell: Float): Float = (floor(v / cell) + 0.5f) * cell
    /** Яркость ступенями (по умолчанию 5 уровней) — даёт «аппаратный» вид. */
    fun quant(a: Float, steps: Int = 5): Float = (floor(a.coerceIn(0f, 1f) * (steps - 1) + 0.5f)) / (steps - 1)
}

/** Один пиксель-квадрат с лёгким круглым скруглением. gap — зазор между ячейками (0..0.4 от ячейки). */
fun DrawScope.drawPixel(center: Offset, cell: Float, color: Color, alpha: Float, gap: Float = 0.18f) {
    val side = cell * (1f - gap)
    drawRoundRect(
        color = color.copy(alpha = alpha),
        topLeft = Offset(center.x - side / 2f, center.y - side / 2f),
        size = Size(side, side),
        cornerRadius = CornerRadius(side * 0.18f)
    )
}

/* ---------- Свет: ядро и лучи ---------- */

fun DrawScope.drawCoreGlow(center: Offset, radius: Float, color: Color, intensity: Float) {
    val a = intensity.coerceIn(0f, 1.5f)
    drawCircle(
        brush = Brush.radialGradient(
            0f to color.copy(alpha = 0.55f * a),
            0.35f to color.copy(alpha = 0.22f * a),
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

// один Path на все лучи — без аллокаций в кадре (перф-бюджет: лучей ≤ 20)
private val rayPath = Path()

/**
 * Лучи из ядра. intensity — общая яркость (на подэкранах 0.25–0.4, на Главной 1).
 * tension: лучи сужаются и вытягиваются («сходятся»). drop: лучи расходятся веером и вспыхивают.
 * inhale: на миг втягиваются перед дропом. calm: лучи гаснут.
 */
fun DrawScope.drawLightRays(
    center: Offset,
    innerR: Float,
    outerR: Float,
    timeSec: Float,
    color: Color,
    intensity: Float,
    rays: Int = 16,
    tension: Float = 0f,
    drop: Float = 0f,
    inhale: Float = 0f,
    calm: Float = 0f,
    bass: Float = 0f
) {
    val master = intensity * (1f - 0.7f * calm) * (1f - 0.6f * inhale) * (1f + 0.8f * drop)
    if (master <= 0.01f) return
    val n = rays.coerceAtMost(20)
    for (i in 0 until n) {
        val h = hash01(i)
        val ang = (i.toFloat() / n) * 2f * PI.toFloat() + timeSec * (0.03f + 0.04f * h) + h * 2f
        val len = outerR * (0.55f + 0.45f * h) * (1f + 0.35f * tension + 0.45f * drop + 0.25f * bass) * (1f - 0.5f * inhale)
        val half = (0.010f + 0.012f * h) * (1f - 0.55f * tension) * (1f + 2.2f * drop)   // полуширина в радианах
        val dx = cos(ang); val dy = sin(ang)
        val px = -dy;      val py = dx
        val base = Offset(center.x + dx * innerR, center.y + dy * innerR)
        val tip = Offset(center.x + dx * len, center.y + dy * len)
        val w = innerR * half * 6f + 1.5f
        rayPath.reset()
        rayPath.moveTo(base.x + px * w, base.y + py * w)
        rayPath.lineTo(tip.x, tip.y)
        rayPath.lineTo(base.x - px * w, base.y - py * w)
        rayPath.close()
        val a = (0.10f + 0.10f * h) * master
        drawPath(
            path = rayPath,
            brush = Brush.linearGradient(
                0f to color.copy(alpha = a.coerceIn(0f, 0.6f)),
                1f to Color.Transparent,
                start = base, end = tip
            ),
            blendMode = BlendMode.Plus
        )
    }
}

/** Кольцо-ударная волна из квадратных пикселей. phase01: 0 -> 1 за ~0.9 c. */
fun DrawScope.drawPixelShockwave(center: Offset, maxR: Float, phase01: Float, cell: Float, color: Color, strength: Float) {
    if (phase01 <= 0f || phase01 >= 1f) return
    val r = maxR * (1f - (1f - phase01) * (1f - phase01))   // ease-out
    val n = (2f * PI.toFloat() * r / cell).toInt().coerceIn(12, 240)
    val alpha = (1f - phase01) * strength.coerceIn(0f, 1f)
    for (k in 0 until n) {
        val a = k.toFloat() / n * 2f * PI.toFloat()
        val x = PixelFx.snap(center.x + cos(a) * r, cell)
        val y = PixelFx.snap(center.y + sin(a) * r, cell)
        drawPixel(Offset(x, y), cell, color, PixelFx.quant(alpha))
    }
}
