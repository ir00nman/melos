package melos.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// Загрузка обложек (http и file:) с памятью на последние ~200 картинок.
object Images {
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()
    private val cache = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 200
    }
    private val skia = object : LinkedHashMap<String, Image>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Image>?) = size > 20
    }

    fun cached(url: String) = synchronized(cache) { cache[url] }

    suspend fun load(url: String, size: Int = 160): ImageBitmap? = cached("$url@$size") ?: withContext(Dispatchers.IO) {
        runCatching {
            val bytes = when {
                url.startsWith("file:") -> File(URI(url)).readBytes()
                else -> http.send(HttpRequest.newBuilder(URI(url)).header("User-Agent", "Mozilla/5.0 Melos").timeout(Duration.ofSeconds(15)).build(),
                    HttpResponse.BodyHandlers.ofByteArray()).takeIf { it.statusCode() in 200..299 }?.body() ?: return@runCatching null
            }
            val img = Image.makeFromEncoded(bytes)
            synchronized(skia) { skia[url] = img }
            scaled(img, size).also { synchronized(cache) { cache["$url@$size"] = it } }
        }.getOrNull()
    }

    // уменьшаем один раз, чтобы список из сотни обложек не держал мегапиксели в памяти
    private fun scaled(img: Image, size: Int): ImageBitmap {
        if (img.width <= size * 2 && img.height <= size * 2) return img.toComposeImageBitmap()
        val k = size * 2f / minOf(img.width, img.height)
        val w = (img.width * k).toInt().coerceAtLeast(1); val h = (img.height * k).toInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImageRect(img, Rect.makeWH(w.toFloat(), h.toFloat()))
        return surface.makeImageSnapshot().toComposeImageBitmap()
    }

    private suspend fun skiaOf(url: String): Image? = synchronized(skia) { skia[url] } ?: run { load(url); synchronized(skia) { skia[url] } }

    /** Обложка, ужатая до n×n пикселей ARGB, — из неё мозг собирает обложку точками. */
    suspend fun grid(url: String, n: Int): IntArray? = withContext(Dispatchers.Default) {
        val img = skiaOf(url) ?: return@withContext null
        val surface = Surface.makeRasterN32Premul(n, n)
        val side = minOf(img.width, img.height).toFloat()
        surface.canvas.drawImageRect(img, Rect.makeXYWH((img.width - side) / 2, (img.height - side) / 2, side, side), Rect.makeWH(n.toFloat(), n.toFloat()))
        val bmp = Bitmap().apply { allocN32Pixels(n, n) }
        surface.makeImageSnapshot().readPixels(bmp, 0, 0)
        IntArray(n * n) { bmp.getColor(it % n, it / n) }
    }

    // «Подбор палитры под сайт» из Chrome crawler, только по обложке: до трёх насыщенных оттенков,
    // разнесённых по кругу, от самого весомого к менее. Ч/б обложка — пустой список (остаёмся в моно).
    suspend fun paletteOf(url: String): List<Color> = withContext(Dispatchers.Default) {
        val px = grid(url, 32) ?: return@withContext emptyList()
        val buckets = FloatArray(24); val sumR = FloatArray(24); val sumG = FloatArray(24); val sumB = FloatArray(24)
        for (c in px) {
            val r = (c shr 16 and 0xff) / 255f; val g = (c shr 8 and 0xff) / 255f; val b = (c and 0xff) / 255f
            val max = maxOf(r, g, b); val min = minOf(r, g, b); val sat = if (max == 0f) 0f else (max - min) / max
            if (sat < 0.22f || max < 0.16f) continue
            val hue = when (max) { r -> ((g - b) / (max - min) + 6) % 6; g -> (b - r) / (max - min) + 2; else -> (r - g) / (max - min) + 4 } / 6f
            val k = (hue * 24).toInt().coerceIn(0, 23)
            val w = sat * sat * max
            buckets[k] += w; sumR[k] += r * w; sumG[k] += g * w; sumB[k] += b * w
        }
        val picked = mutableListOf<Int>()
        for (k in buckets.indices.sortedByDescending { buckets[it] }) {
            if (buckets[k] < 0.6f || picked.size == 3) break
            if (picked.none { minOf(kotlin.math.abs(it - k), 24 - kotlin.math.abs(it - k)) < 3 }) picked += k
        }
        val colors = picked.map { Color(sumR[it] / buckets[it], sumG[it] / buckets[it], sumB[it] / buckets[it]) }
        if (colors.isEmpty()) return@withContext emptyList()
        // недостающие оттенки — соседи главного по кругу, чтобы градиенты не были плоскими
        colors + List(3 - colors.size) { i -> androidx.compose.ui.graphics.lerp(colors.first(), if (i == 0) Color(0xFF7C8CFF) else Color(0xFFFF7CB8), 0.45f) }
    }

    suspend fun accentOf(url: String): Color? = paletteOf(url).firstOrNull()
}

@Composable
fun rememberImage(url: String?, size: Int = 160): ImageBitmap? {
    var img by remember(url) { mutableStateOf(url?.let { Images.cached("$it@$size") }) }
    LaunchedEffect(url) { if (url != null && img == null) img = Images.load(url, size) }
    return img
}
