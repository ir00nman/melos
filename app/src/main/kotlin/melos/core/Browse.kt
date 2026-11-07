package melos.core

import java.io.File

/**
 * Открыть ссылку или папку средствами системы: на десктопе — java.awt.Desktop, на Android — Intent.
 * Реализацию присваивает платформа при старте (Main.kt / melos.droid.Droid), общий код зовёт отсюда.
 */
object Browse {
    var url: (String) -> Unit = { }
    var dir: (File) -> Unit = { }

    /** Выбрать папку (системный диалог) и вернуть выбор в onPick; отменили — не вызывает. */
    var pickDir: (File, (File) -> Unit) -> Unit = { _, _ -> }
}
