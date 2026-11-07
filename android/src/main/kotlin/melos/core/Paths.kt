package melos.core

import android.content.Context
import android.os.Environment
import java.io.File

// Где Melos хранит данные на Android: приватная папка приложения (нужен Paths.init(context) до первого обращения).
object Paths {
    @Volatile private var base: File? = null

    fun init(context: Context) {
        base = File(context.applicationContext.filesDir, "data").apply { mkdirs() }
    }

    val isMac get() = false
    val isWindows get() = false

    val home: File get() = base ?: error("Paths.init(context) не вызван")
    val plugins: File get() = File(home, "plugins").apply { mkdirs() }
    val cache: File get() = File(home, "cache").apply { mkdirs() }
    val logs: File get() = File(home, "logs").apply { mkdirs() }
    fun file(name: String) = File(home, name)

    /** Куда сохранять треки пользователю: публичная «Музыка» (на этапе 1 загрузок нет, но API должен быть). */
    val defaultDownloads: File
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)

    /** Откуда качать VLC, если плеер его не нашёл. */
    val vlcDownloadUrl: String
        get() = "https://www.videolan.org/vlc/"
}
