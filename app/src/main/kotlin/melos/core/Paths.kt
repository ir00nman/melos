package melos.core

import java.io.File

// Где Melos хранит настройки, плагины, кэш и загрузки — по правилам каждой ОС.
object Paths {
    private val os = System.getProperty("os.name").lowercase()
    val isMac = os.contains("mac")
    val isWindows = os.contains("win")

    val home: File by lazy {
        System.getenv("MELOS_HOME")?.let { return@lazy File(it).apply { mkdirs() } }   // отдельные данные для тестов
        val user = System.getProperty("user.home")
        val base = when {
            isMac -> File(user, "Library/Application Support")
            isWindows -> File(System.getenv("APPDATA") ?: user)
            else -> File(System.getenv("XDG_CONFIG_HOME") ?: "$user/.config")
        }
        val dir = File(base, if (isMac || isWindows) "Melos" else "melos")
        migrate(File(base, if (isMac || isWindows) "Flayer" else "flayer"), dir)
        dir.apply { mkdirs() }
    }

    val plugins: File get() = File(home, "plugins").apply { mkdirs() }
    val cache: File get() = File(home, "cache").apply { mkdirs() }
    val logs: File get() = File(home, "logs").apply { mkdirs() }
    fun file(name: String) = File(home, name)

    /** Откуда качать VLC, если плеер его не нашёл, — страница под текущую ОС. */
    val vlcDownloadUrl: String
        get() = when {
            isWindows -> "https://www.videolan.org/vlc/download-windows.html"
            isMac -> "https://www.videolan.org/vlc/download-macosx.html"
            else -> "https://www.videolan.org/vlc/download-linux.html"
        }

    /** Старые загрузки остаются, где были: в их записях — абсолютные пути. */
    val defaultDownloads: File
        get() = File(System.getProperty("user.home"), "Music/Flayer").takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"), "Music/Melos")

    /** Приложение раньше называлось Flayer: данные переезжают в новую папку, на старом месте — ссылка (в настройках есть абсолютные пути). */
    private fun migrate(old: File, new: File) {
        if (new.exists() || !old.isDirectory || java.nio.file.Files.isSymbolicLink(old.toPath())) return
        runCatching {
            java.nio.file.Files.move(old.toPath(), new.toPath())
            java.nio.file.Files.createSymbolicLink(old.toPath(), new.toPath())
        }
    }
}
