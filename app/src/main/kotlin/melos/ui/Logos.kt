package melos.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Логотипы сервисов в их цветах: Яндекс Музыка — значок с её сайта (favicon.svg),
// SoundCloud и VK — из Simple Icons (CC0). У плагинов логотипа нет — показываем букву названия.
object Logos {
    private val yandex = ImageVector.Builder("yandex", 24.dp, 24.dp, 144f, 144f).apply {
        addPath(addPathNodes("M41 0h62a41 41 0 0 1 41 41v62a41 41 0 0 1-41 41H41A41 41 0 0 1 0 103V41A41 41 0 0 1 41 0z"), fill = SolidColor(Color(0xFF110503)))
        addPath(addPathNodes("m130.863 57.739-.468-2.327-19.788-3.457 11.498-15.557-1.337-1.462-16.913 8.11 2.139-21.54-1.738-.997-10.295 17.418L82.395 12H80.39l2.74 25.064-29.08-23.269-2.474.732 22.396 28.122-44.323-14.76-2.006 2.261L67.22 52.686l-54.618 4.521-.602 3.39 56.757 6.184-47.33 39.157 2.005 2.726 56.356-30.648-11.164 53.983h3.41l21.592-50.792 13.17 39.756 2.34-1.795-5.415-40.42 20.524 23.268 1.337-2.128-15.711-28.853 21.928 8.111.201-2.46-19.655-14.493 18.518-4.454Z"),
            fill = SolidColor(Color(0xFFFFEE00)))
    }.build()

    // оранжевая плашка и белое облако по центру (сам знак — 24×24, ужат до 70 %)
    private val soundcloud = ImageVector.Builder("soundcloud", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M6 0h12a6 6 0 0 1 6 6v12a6 6 0 0 1-6 6H6a6 6 0 0 1-6-6V6a6 6 0 0 1 6-6z"), fill = SolidColor(Color(0xFFFF5500)))
        addGroup(scaleX = .72f, scaleY = .72f, translationX = 3.36f, translationY = 2.8f)
        addPath(addPathNodes("M23.999 14.165c-.052 1.796-1.612 3.169-3.4 3.169h-8.18a.68.68 0 0 1-.675-.683V7.862a.747.747 0 0 1 .452-.724s.75-.513 2.333-.513a5.364 5.364 0 0 1 2.763.755 5.433 5.433 0 0 1 2.57 3.54c.282-.08.574-.121.868-.12.884 0 1.73.358 2.347.992s.948 1.49.922 2.373ZM10.721 8.421c.247 2.98.427 5.697 0 8.672a.264.264 0 0 1-.53 0c-.395-2.946-.22-5.718 0-8.672a.264.264 0 0 1 .53 0ZM9.072 9.448c.285 2.659.37 4.986-.006 7.655a.277.277 0 0 1-.55 0c-.331-2.63-.256-5.020 0-7.655a.277.277 0 0 1 .556 0Zm-1.663-.257c.27 2.726.39 5.171 0 7.904a.266.266 0 0 1-.532 0c-.38-2.69-.257-5.21 0-7.904a.266.266 0 0 1 .532 0Zm-1.647.77a26.108 26.108 0 0 1-.008 7.147.272.272 0 0 1-.542 0 27.955 27.955 0 0 1 0-7.147.275.275 0 0 1 .55 0Zm-1.67 1.769c.421 1.865.228 3.5-.029 5.388a.257.257 0 0 1-.514 0c-.21-1.858-.398-3.549 0-5.389a.272.272 0 0 1 .543 0Zm-1.655-.273c.388 1.897.26 3.508-.01 5.412-.026.28-.514.283-.54 0-.244-1.878-.347-3.54-.01-5.412a.283.283 0 0 1 .56 0Zm-1.668.911c.4 1.268.257 2.292-.026 3.572a.257.257 0 0 1-.514 0c-.241-1.262-.354-2.312-.023-3.572a.283.283 0 0 1 .563 0Z"),
            fill = SolidColor(Color.White))
        clearGroup()
    }.build()

    // плашка VK с вырезанными буквами: белая подложка под вырез, синий знак сверху
    private val vk = ImageVector.Builder("vk", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M4 6h16v12H4z"), fill = SolidColor(Color.White))
        addPath(addPathNodes("m9.489.004.729-.003h3.564l.73.003.914.01.433.007.418.011.403.014.388.016.374.021.36.025.345.03.333.033c1.74.196 2.933.616 3.833 1.516.9.9 1.32 2.092 1.516 3.833l.034.333.029.346.025.36.02.373.025.588.012.41.013.644.009.915.004.98-.001 3.313-.003.73-.01.914-.007.433-.011.418-.014.403-.016.388-.021.374-.025.36-.03.345-.033.333c-.196 1.74-.616 2.933-1.516 3.833-.9.9-2.092 1.32-3.833 1.516l-.333.034-.346.029-.36.025-.373.02-.588.025-.41.012-.644.013-.915.009-.98.004-3.313-.001-.73-.003-.914-.01-.433-.007-.418-.011-.403-.014-.388-.016-.374-.021-.36-.025-.345-.03-.333-.033c-1.74-.196-2.933-.616-3.833-1.516-.9-.9-1.32-2.092-1.516-3.833l-.034-.333-.029-.346-.025-.36-.02-.373-.025-.588-.012-.41-.013-.644-.009-.915-.004-.98.001-3.313.003-.73.01-.914.007-.433.011-.418.014-.403.016-.388.021-.374.025-.36.03-.345.033-.333c.196-1.74.616-2.933 1.516-3.833.9-.9 2.092-1.32 3.833-1.516l.333-.034.346-.029.36-.025.373-.02.588-.025.41-.012.644-.013.915-.009ZM6.79 7.3H4.05c.13 6.24 3.25 9.99 8.72 9.99h.31v-3.57c2.01.2 3.53 1.67 4.14 3.57h2.84c-.78-2.84-2.83-4.41-4.11-5.01 1.28-.74 3.08-2.54 3.51-4.98h-2.58c-.56 1.98-2.22 3.78-3.8 3.95V7.3H10.5v6.92c-1.6-.4-3.62-2.34-3.71-6.92Z"),
            fill = SolidColor(Color(0xFF0077FF)), pathFillType = PathFillType.EvenOdd)
    }.build()

    fun of(serviceId: String): ImageVector? = when (serviceId) { "ynd" -> yandex; "sc" -> soundcloud; "vk" -> vk; else -> null }
}

/** Логотип сервиса; у плагинов без логотипа — ничего (рядом обычно есть название). */
@Composable
fun ServiceLogo(serviceId: String, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    val v = Logos.of(serviceId) ?: return
    Image(rememberVectorPainter(v), null, modifier.size(size))
}
