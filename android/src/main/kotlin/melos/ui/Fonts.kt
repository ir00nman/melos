package melos.ui

import androidx.compose.ui.text.font.FontFamily

// Android: шрифт интерфейса — системный (свой impl вместо десктопного Fonts.kt).
// Ресурсные TTF сюда можно подключить позже через Font(R.font...) — API различается с десктопом.
object Fonts {
    val Inter: FontFamily = FontFamily.SansSerif
}
