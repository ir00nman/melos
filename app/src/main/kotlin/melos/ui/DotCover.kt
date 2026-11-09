package melos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.sin

// Обложка на главной: при смене трека новая проступает клетками — той же сеткой, что у мозга, —
// а вокруг рамкой бегут точки, каждая слушает свою полосу спектра.
private const val CELLS = 16

private fun cellDelay(gx: Int, gy: Int): Float {
    val v = sin(gx * 12.9898 + gy * 78.233) * 43758.5453
    return ((v - floor(v)) * 0.75).toFloat()
}

@Composable
fun DotCover(url: String?, size: Dp, modifier: Modifier = Modifier) {
    val p = pal
    val img = rememberImage(url, (size.value * 1.6f).toInt())
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    var prev by remember { mutableStateOf<ImageBitmap?>(null) }
    var changedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(img) { if (img != null && img !== shown) { prev = shown; shown = img; changedAt = System.currentTimeMillis() } }
    val pad = 12.dp
    Box(modifier.size(size + pad * 2)) {
        Box(Modifier.padding(pad).size(size).clip(R.lg).background(p.chip)) {
            prev?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            shown?.let { cur ->
                Image(cur, null, Modifier.fillMaxSize().drawWithContent {
                    Pulse.frame.let { }
                    val u = if (!Look.motion) 2f else (System.currentTimeMillis() - changedAt) / 700f
                    if (u >= 1.3f) { drawContent(); return@drawWithContent }
                    val c = this.size.width / CELLS
                    val mask = Path()
                    for (gy in 0 until CELLS) for (gx in 0 until CELLS) if (u >= cellDelay(gx, gy)) mask.addRect(Rect(gx * c, gy * c, (gx + 1) * c + 0.5f, (gy + 1) * c + 0.5f))
                    clipPath(mask) { this@drawWithContent.drawContent() }
                    // свежая клетка на миг вспыхивает
                    for (gy in 0 until CELLS) for (gx in 0 until CELLS) {
                        val pop = 1 - ((u - cellDelay(gx, gy)) / 0.12f)
                        if (pop in 0f..1f) drawRect(Color.White.copy(alpha = 0.5f * pop), Offset(gx * c, gy * c), Size(c, c))
                    }
                }, contentScale = ContentScale.Crop)
            }
        }
        // рамка из точек: по периметру, точка — полоса спектра, на бит рамка вспыхивает
        Canvas(Modifier.fillMaxSize()) {
            Pulse.frame.let { }
            val beat = Pulse.beat()
            val count = 64; val inset = pad.toPx() - 6.dp.toPx(); val side = size.toPx() + 12.dp.toPx()
            val dot = 2.6.dp.toPx()
            for (k in 0 until count) {
                val t = k / count.toFloat() * 4
                val e = t - floor(t)
                val pt = when (t.toInt()) {
                    0 -> Offset(inset + side * e, inset)
                    1 -> Offset(inset + side, inset + side * e)
                    2 -> Offset(inset + side * (1 - e), inset + side)
                    else -> Offset(inset, inset + side * (1 - e))
                }
                val b = Pulse.bands[(k * Pulse.bands.size / count) % Pulse.bands.size]
                val a = (0.18f + b * 0.8f + beat * 0.3f).coerceIn(0f, 1f)
                drawCircle(lerpC(p.acc, p.c2, k / count.toFloat()).copy(alpha = a), dot * (1 + b * 0.8f) / 2, pt)
            }
        }
    }
}
