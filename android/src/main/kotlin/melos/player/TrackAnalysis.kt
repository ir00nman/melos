package melos.player

import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// Этап 1 Android: PCM-тракта нет — карту трека не строим, но тип оставляем тот же,
// чтобы общий UI (Vibe/Pulse/Brain) компилировался без изменений и просто жил «без карты».
const val SPEC_BANDS = 24
const val SPEC_MS = 4 * 256 * 1000f / 22050f              // ~46 мс

@Suppress("NAME_SHADOWING") data class TrackMap(
    val durationMs: Long,
    val bpm: Float,
    val beats: List<Long>,              // моменты долей, мс
    val strength: List<Float>,          // сила доли 0..1 (по басу)
    val downbeat: List<Boolean>,        // сильная доля такта
    val energy: List<Float>,            // энергия 0..1 с шагом 100 мс
    val drops: List<Long>,              // моменты дропов, мс
    val dropEnds: List<Long>,           // где кончается «дроповая» секция
    val builds: List<Long>,             // где начинается нарастание перед каждым дропом
    val spectrum: String = "",          // полосы спектра: кадры по SPEC_MS, в каждом SPEC_BANDS байт 0..255 (Base64)
    val levels: String = "",            // громкость кадра 0..255 (Base64), тот же шаг
    val sections: List<Long> = emptyList(),      // начала частей песни (куплет, припев, проигрыш…), мс; первая — 0
    val sectionEnergy: List<Float> = emptyList(), // средняя энергия каждой части 0..1
) {
    @kotlinx.serialization.Transient private val specBytes: ByteArray by lazy { java.util.Base64.getDecoder().decode(spectrum) }
    @kotlinx.serialization.Transient private val levelBytes: ByteArray by lazy { java.util.Base64.getDecoder().decode(levels) }
    val hasSpectrum get() = spectrum.isNotEmpty()

    /** Полосы спектра в момент ms (линейная интерполяция между кадрами) — в out[0 until SPEC_BANDS]. */
    fun spectrumAt(ms: Long, out: FloatArray) {
        val frames = specBytes.size / SPEC_BANDS
        if (frames == 0) { out.fill(0f); return }
        val f = (ms.toFloat() / SPEC_MS).coerceIn(0f, frames - 1f)
        val i0 = f.toInt(); val i1 = minOf(i0 + 1, frames - 1); val t = f - i0
        for (b in 0 until minOf(out.size, SPEC_BANDS)) {
            val a = (specBytes[i0 * SPEC_BANDS + b].toInt() and 0xff) / 255f
            val c = (specBytes[i1 * SPEC_BANDS + b].toInt() and 0xff) / 255f
            out[b] = a + (c - a) * t
        }
    }

    fun levelAt(ms: Long): Float {
        if (levelBytes.isEmpty()) return 0f
        val i = (ms / SPEC_MS).toInt().coerceIn(0, levelBytes.size - 1)
        return (levelBytes[i].toInt() and 0xff) / 255f
    }

    fun energyAt(ms: Long): Float = energy.getOrNull((ms / 100).toInt().coerceIn(0, max(0, energy.size - 1))) ?: 0f

    /** 0..1 — напряжение нарастания: от начала подводки к дропу растёт до 1 ровно к дропу. */
    fun tensionAt(ms: Long): Float {
        for (k in drops.indices) {
            val b = builds[k]; val d = drops[k]
            if (ms in b until d) { val u = (ms - b).toFloat() / max(1L, d - b); return u * u }
        }
        return 0f
    }

    /** Номер части песни в момент ms (−1 — частей нет). */
    fun sectionAt(ms: Long): Int {
        if (sections.isEmpty()) return -1
        var lo = 0; var hi = sections.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (sections[mid] <= ms) lo = mid else hi = mid - 1 }
        return lo
    }

    /** Где начинается следующая часть (или null — это последняя). */
    fun nextSection(ms: Long): Long? = sections.getOrNull(sectionAt(ms) + 1)

    /** Длина такта около ms, мс (по сильным долям; без них — 4 доли по темпу). */
    fun barMs(ms: Long): Long {
        var lo = 0; var hi = beats.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (beats[mid] <= ms) lo = mid + 1 else hi = mid }
        var a = -1; var i = lo - 1; while (i >= 0) { if (downbeat[i]) { a = i; break }; i-- }
        var b = -1; i = lo; while (i < beats.size) { if (downbeat[i]) { b = i; break }; i++ }
        return if (a >= 0 && b > a) beats[b] - beats[a] else if (bpm > 0) (240_000 / bpm).toLong() else 2000L
    }

    /** 0..1 — насколько сейчас «дроп»: внутри дроповой секции — её энергия. */
    fun dropAt(ms: Long): Float {
        for (k in drops.indices) if (ms in drops[k] until dropEnds[k]) return (0.6f + 0.4f * energyAt(ms)).coerceIn(0f, 1f)
        return 0f
    }
}

object TrackAnalysis {
    private val _map = MutableStateFlow<Pair<String, TrackMap>?>(null)
    val current = _map.asStateFlow()

    fun mapFor(mediaId: String?): TrackMap? = _map.value?.takeIf { it.first == mediaId }?.second

    /** На Android карта не строится (нет PCM-тракта) — заглушка с той же сигнатурой. */
    fun request(mediaId: String, mrl: String) {}
}
