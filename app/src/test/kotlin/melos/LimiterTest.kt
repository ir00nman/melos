package melos

import melos.player.Limiter
import melos.player.PcmEqualizer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LimiterTest {
    private val rate = 44100

    private fun sine(freq: Float, amp: Float, frames: Int) = FloatArray(frames * 2) { i ->
        (sin(2 * PI * freq * (i / 2) / rate) * amp).toFloat()
    }

    @Test fun neverExceedsCeiling() {
        val x = sine(60f, 3f, rate)                      // +9,5 дБ сверху — как бас +12 на мастере у 0 dBFS
        Limiter(rate, 2).process(x, rate)
        assertTrue(x.maxOf { abs(it) } <= 0.977f + 1e-5f, "пик ${x.maxOf { abs(it) }}")
    }

    @Test fun quietSignalPassesUntouched() {
        val src = sine(1000f, 0.5f, 4096)
        val x = src.copyOf()
        Limiter(rate, 2).process(x, 4096)
        val d = rate / 200                               // задержка упреждения
        for (i in d until 4096) for (c in 0..1) assertEquals(src[(i - d) * 2 + c], x[i * 2 + c], 1e-6f)
    }

    @Test fun peakOfAdjacentBoostsExceedsSingleBand() {
        val p = PcmEqualizer.peakDb(listOf(8f, 12f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 8f))
        assertTrue(p in 12.5f..16f, "пик $p")
    }
}
