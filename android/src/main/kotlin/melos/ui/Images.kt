package melos.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.badmanners.murglar.lib.core.network.NetworkRequest
import com.badmanners.murglar.lib.core.network.ResponseConverters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import melos.host.HttpNetwork
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

// Загрузка обложек на Android: OkHttp (общий HttpNetwork) + BitmapFactory, LruCache на последние ~200.
object Images {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 200
    }
    private val inFlight = AtomicInteger()

    fun cached(url: String) = synchronized(cache) { cache[url] }

    suspend fun load(url: String, size: Int = 160): ImageBitmap? = cached(url) ?: withContext(Dispatchers.IO) {
        runCatching {
            val bytes = when {
                url.startsWith("file:") -> File(URI(url)).readBytes()
                else -> HttpNetwork.execute(NetworkRequest.Builder(url).build(), ResponseConverters.asByteArray()).result
            } ?: return@runCatching null
            // декодируем с прореживанием до ~2x запрошенного размера — экономим память списка
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / (sample + 1) >= size * 2) sample++
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@runCatching null
            val img = bmp.asImageBitmap()
            synchronized(cache) { cache[url] = img }
            img
        }.getOrNull()
    }

    /** Палитра обложки: на Android (этап 2) не считается — акцент берётся из настроек. */
    fun paletteOf(url: String?): List<Color> = emptyList()
}

@Composable
fun rememberImage(url: String?, size: Int = 160): ImageBitmap? {
    var img by remember(url) { mutableStateOf(url?.let { Images.cached("$it@$size") }) }
    LaunchedEffect(url) { if (url != null && img == null) img = Images.load(url, size) ?: Images.cached(url) }
    return img
}
