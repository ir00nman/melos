package melos.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter

// Линейные иконки в духе Chrome crawler: 24×24, штрих 1.7, скруглённые концы. "F:" в начале пути — заливка.
object Ic {
    private fun icon(name: String, vararg paths: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { p ->
            val fill = p.startsWith("F:")
            addPath(
                pathData = addPathNodes(p.removePrefix("F:")),
                fill = if (fill) SolidColor(Color.Black) else null,
                stroke = if (fill) null else SolidColor(Color.Black),
                strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

    val play = icon("play", "F:M8 5.5v13a1 1 0 0 0 1.5.86l10.4-6.5a1 1 0 0 0 0-1.72L9.5 4.64A1 1 0 0 0 8 5.5z")
    val pause = icon("pause", "F:M7 5h3.2v14H7zM13.8 5H17v14h-3.2z")
    val next = icon("next", "F:M5 6.2v11.6a.9.9 0 0 0 1.4.75l8.3-5.8a.9.9 0 0 0 0-1.5L6.4 5.45A.9.9 0 0 0 5 6.2z", "M18.5 5.5v13")
    val prev = icon("prev", "F:M19 6.2v11.6a.9.9 0 0 1-1.4.75l-8.3-5.8a.9.9 0 0 1 0-1.5l8.3-5.8A.9.9 0 0 1 19 6.2z", "M5.5 5.5v13")
    val shuffle = icon("shuffle", "M3 7h3.5c4 0 5.5 10 10 10H21", "M3 17h3.5c1.6 0 2.7-1.6 3.6-3.4", "M14 10.4C15 8.6 16 7 17.5 7H21", "M18.5 4.5L21 7l-2.5 2.5", "M18.5 14.5L21 17l-2.5 2.5")
    val repeat = icon("repeat", "M4 11V9a3 3 0 0 1 3-3h12", "M16 3l3 3-3 3", "M20 13v2a3 3 0 0 1-3 3H5", "M8 21l-3-3 3-3")
    val repeatOne = icon("repeatOne", "M4 11V9a3 3 0 0 1 3-3h12", "M16 3l3 3-3 3", "M20 13v2a3 3 0 0 1-3 3H5", "M8 21l-3-3 3-3", "M11.5 10.5l1-.8v4.6")
    val heart = icon("heart", "M12 20s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.3a4.3 4.3 0 0 1 7.5 2.5C19.5 15.4 12 20 12 20z")
    val heartOn = icon("heartOn", "F:M12 20s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.3a4.3 4.3 0 0 1 7.5 2.5C19.5 15.4 12 20 12 20z")
    val search = icon("search", "M10.5 4a6.5 6.5 0 1 1 0 13 6.5 6.5 0 0 1 0-13z", "M15.5 15.5L20 20")
    val gear = icon("gear", "M12 9.2a2.8 2.8 0 1 1 0 5.6 2.8 2.8 0 0 1 0-5.6z", "M12 5.4a6.6 6.6 0 1 1 0 13.2 6.6 6.6 0 0 1 0-13.2z", "M12 2.6v1.6M12 19.8v1.6M2.6 12h1.6M19.8 12h1.6M5.4 5.4l1.1 1.1M17.5 17.5l1.1 1.1M5.4 18.6l1.1-1.1M17.5 6.5l1.1-1.1")
    val plus = icon("plus", "M12 5v14M5 12h14")
    val x = icon("x", "M6 6l12 12M18 6L6 18")
    val left = icon("left", "M15 5l-7 7 7 7")
    val right = icon("right", "M9 5l7 7-7 7")
    val home = icon("home", "M4 10.5L12 4l8 6.5V19a1 1 0 0 1-1 1h-4.5v-5.5h-5V20H5a1 1 0 0 1-1-1z")
    val clock = icon("clock", "M12 3.5a8.5 8.5 0 1 1 0 17 8.5 8.5 0 0 1 0-17z", "M12 7.5V12l3 2")
    val download = icon("download", "M12 4v11", "M7 10.5l5 5 5-5", "M5 19.5h14")
    val queue = icon("queue", "M4 6h12M4 11h12M4 16h7", "F:M15 14v6l5-3z")
    val lyrics = icon("lyrics", "M5 6.5h14M5 11h10M5 15.5h12M5 20h6")
    val moon = icon("moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val eq = icon("eq", "M6 4v16M12 4v16M18 4v16", "F:M4 13h4v2.6H4zM10 7h4v2.6h-4zM16 15h4v2.6h-4z")
    val volume = icon("volume", "F:M4 9.5h3.5L12 5.5v13l-4.5-4H4z", "M15.5 9a4 4 0 0 1 0 6", "M18 6.5a7.5 7.5 0 0 1 0 11")
    val mute = icon("mute", "F:M4 9.5h3.5L12 5.5v13l-4.5-4H4z", "M16 9.5l5 5M21 9.5l-5 5")
    val folder = icon("folder", "M3.5 7a2 2 0 0 1 2-2h4l2 2.2h7a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z")
    val wave = icon("wave", "M2.5 15c2.5-8 5-8 7.5 0s5 8 7.5 0c1-3 2-4.6 4-5")
    val radio = icon("radio", "M12 12m-1.6 0a1.6 1.6 0 1 0 3.2 0 1.6 1.6 0 1 0-3.2 0", "M8.2 8.2a5.4 5.4 0 0 0 0 7.6M15.8 8.2a5.4 5.4 0 0 1 0 7.6", "M5.3 5.3a9.5 9.5 0 0 0 0 13.4M18.7 5.3a9.5 9.5 0 0 1 0 13.4")
    val chip = icon("chip", "M6 6h12v12H6z", "M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4")
    val disc = icon("disc", "M12 3.5a8.5 8.5 0 1 1 0 17 8.5 8.5 0 0 1 0-17z", "M12 10a2 2 0 1 1 0 4 2 2 0 0 1 0-4z")
    val user = icon("user", "M12 4.5a3.8 3.8 0 1 1 0 7.6 3.8 3.8 0 0 1 0-7.6z", "M4.5 20c.8-3.8 3.8-5.6 7.5-5.6s6.7 1.8 7.5 5.6")
    val list = icon("list", "M8 6.5h12M8 12h12M8 17.5h12", "F:M3.5 5.5h2v2h-2zM3.5 11h2v2h-2zM3.5 16.5h2v2h-2z")
    val note = icon("note", "M9 18V5.5l10-2V16", "M9 18a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0zM19 16a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0z")
    val link = icon("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")
    val playNext = icon("playNext", "M4 6h10M4 11h10M4 16h6", "F:M14 13.5v7l6-3.5z")
    val more = icon("more", "F:M5 10.5h3v3H5zM10.5 10.5h3v3h-3zM16 10.5h3v3h-3z")
    val refresh = icon("refresh", "M20 11a8 8 0 1 0-2.4 5.7M20 4v7h-7")
    val speed = icon("speed", "M4.5 16a8 8 0 1 1 15 0", "M12 13l4-4.5")
    val check = icon("check", "M4.5 12.6l5 5L19.5 7")
    val shield = icon("shield", "M12 3l7 3v5c0 4.5-3 8.3-7 10-4-1.7-7-5.5-7-10V6z")
}

@Composable
fun Icon(v: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) =
    Image(rememberVectorPainter(v), null, modifier.size(size), colorFilter = ColorFilter.tint(tint))
