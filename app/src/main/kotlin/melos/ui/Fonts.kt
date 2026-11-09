package melos.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import java.io.File

// Inter — единственное семейство интерфейса: полная кириллица, табличные цифры (tnum) для таймкодов.
// Шрифты лежат в ресурсах (melos/fonts); Compose Desktop грузит их из файла — распаковываем в кеш один раз.
object Fonts {
    private const val DIR = "melos/fonts"

    private fun file(name: String): File? = runCatching {
        // 1) classpath (упакованный jar); 2) каталог ресурсов compose-запуска (:app:run);
        // 3) рядом с jar — build/resources/main; после — кеш во tmp
        val srcs = buildList {
            Fonts::class.java.getResourceAsStream("$DIR/$name")?.let { add("stream" to it) }
            System.getProperty("compose.application.resources.dir")?.let { add("file" to File(it, "$DIR/$name")) }
            runCatching {
                // build/libs/app.jar → build/resources/main
                File(Fonts::class.java.protectionDomain.codeSource.location.toURI()).takeIf { it.isFile }
                    ?.let { add("file" to File(it.parentFile.parentFile, "resources/main/$DIR/$name")) }
            }
        }
        val f = File(File(System.getProperty("java.io.tmpdir"), "melos-fonts").apply { mkdirs() }, name)
        if (!f.isFile || f.length() == 0L) for ((kind, s) in srcs) when (kind) {
            "stream" -> (s as java.io.InputStream).use { i -> f.outputStream().use { i.copyTo(it) } }
            else -> (s as File).takeIf { it.isFile }?.copyTo(f, overwrite = true) ?: continue
        }
        f
    }.getOrNull()?.takeIf { it.isFile && it.length() > 0L }

    private fun family(weights: List<Pair<FontWeight, String>>): FontFamily = runCatching {
        FontFamily(weights.mapNotNull { (w, name) -> file(name)?.let { Font(it, w) } })
    }.getOrDefault(FontFamily.SansSerif)

    val Inter: FontFamily by lazy {
        val f = family(listOf(
            FontWeight.Normal to "Inter-Regular.ttf",
            FontWeight.Medium to "Inter-Medium.ttf",
            FontWeight.SemiBold to "Inter-SemiBold.ttf",
            FontWeight.Bold to "Inter-Bold.ttf",
        ))
        if (f == FontFamily.SansSerif) melos.host.Log.w("Fonts", "Inter не загрузился — системный шрифт")
        f
    }
}
