package melos.lyrics

import com.badmanners.murglar.lib.core.model.tag.Lyrics
import com.badmanners.murglar.lib.core.model.tag.Lyrics.SyncedLyrics
import com.badmanners.murglar.lib.core.model.tag.Lyrics.SyncedLyrics.Line

object Lrc {
    private val stamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun looksLikeLrc(text: String) = text.lineSequence().take(30).count { stamp.containsMatchIn(it) } >= 3

    fun parse(text: String, artist: String, title: String): Lyrics {
        val lines = text.lineSequence().flatMap { raw ->
            val stamps = stamp.findAll(raw).toList()
            if (stamps.isEmpty()) return@flatMap emptySequence()
            val words = raw.substring(stamps.last().range.last + 1).trim()
            stamps.asSequence().map { m ->
                val (mm, ss, frac) = m.destructured
                val ms = when (frac.length) { 0 -> 0; 1 -> frac.toInt() * 100; 2 -> frac.toInt() * 10; else -> frac.take(3).toInt() }
                Line(mm.toLong() * 60_000 + ss.toLong() * 1000 + ms, null, words)
            }
        }.sortedBy { it.startMillis }.toList()
        val plain = lines.mapNotNull { it.text?.takeIf(String::isNotBlank) }.joinToString("\n")
        if (lines.isEmpty()) return Lyrics(text)
        return Lyrics(plain, SyncedLyrics(lines, artist, title))
    }
}
