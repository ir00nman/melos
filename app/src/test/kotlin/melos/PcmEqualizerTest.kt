package melos

import melos.player.PcmEqualizer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class PcmEqualizerTest {
    private val rate = 44100

    private fun sine(freq: Float, amp: Float, frames: Int) = ShortArray(frames * 2) { i ->
        (sin(2 * PI * freq * (i / 2) / rate) * amp * 32767).toInt().toShort()
    }
    private fun peak(pcm: ShortArray, skip: Int) = (skip * 2 until pcm.size).maxOf { abs(pcm[it].toInt()) } / 32767f

    @Test fun flatIsBitExact() {
        val eq = PcmEqualizer(rate, 2)
        eq.set(true, 0f, List(10) { 0f })
        val src = sine(440f, 0.8f, 4096); val out = src.copyOf()
        eq.process(out, 4096)
        assertContentEquals(src, out)
    }

    @Test fun boostsOnlyItsBand() {
        val eq = PcmEqualizer(rate, 2)
        eq.set(true, 0f, List(10) { if (it == 5) 6f else 0f })            // +6 дБ на 1 кГц
        val a = sine(1000f, 0.25f, 22050); eq.process(a, 22050)
        val g1k = peak(a, 4000) / 0.25f
        eq.set(true, 0f, List(10) { if (it == 5) 6f else 0f })
        val b = sine(125f, 0.25f, 22050); eq.process(b, 22050)
        val g125 = peak(b, 4000) / 0.25f
        println("gain@1k=$g1k gain@125=$g125")
        assertTrue(g1k in 1.85f..2.15f, "1 кГц: ожидали ~2, получили $g1k")
        assertTrue(g125 in 0.95f..1.12f, "125 Гц: ожидали ~1, получили $g125")
    }

    @Test fun softLimitInsteadOfClipping() {
        val eq = PcmEqualizer(rate, 2)
        eq.set(true, 12f, List(10) { 6f })
        val a = sine(500f, 0.9f, 8192); eq.process(a, 8192)
        assertTrue(peak(a, 1000) <= 1f)
        assertTrue(a.count { it == Short.MAX_VALUE || it == Short.MIN_VALUE } < a.size / 50, "почти нет «полок» жёсткого клиппинга")
    }
}
