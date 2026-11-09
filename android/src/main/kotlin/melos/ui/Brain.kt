package melos.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

// поэтому тут пустая заглушка. Look.brain на Android выключаем при старте — дом показывает DotCover.
@Composable
fun Brain(modifier: Modifier = Modifier, interactive: Boolean = true, center: Offset = Offset(0.5f, 0.46f), scaleK: Float = 1f) {
    Box(modifier) {}
}
