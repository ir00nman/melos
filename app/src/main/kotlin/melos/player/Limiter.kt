package melos.player

import kotlin.math.abs
import kotlin.math.exp

// Лимитер с упреждением (look-ahead brickwall) — вместо срезания пиков и вместо мягкого клипа, который «пачкает» бас:
// звук идёт с задержкой L отсчётов, а усиление начинает плавно опускаться заранее, ещё до пика.
//   q[t] — нужное усиление для отсчёта t (ceiling / |x|), m — минимум q за окно L, r — m с медленным отпусканием,
//   итог — скользящее среднее r за то же окно: оно не больше q того отсчёта, что сейчас выходит, — значит, перегруза нет,
//   а форма волны не искажается (только плавная огибающая длиной 5 мс).
class Limiter(rate: Int, private val channels: Int, private val ceiling: Float = 0.977f /* −0,2 dBFS */) {
    private val L = (rate * 0.005).toInt().coerceAtLeast(8)                    // 5 мс упреждения
    private val release = 1f - exp(-1f / (rate * 0.12f))                       // отпускание ~120 мс
    private val delay = FloatArray(L * channels)
    private var pos = 0
    // скользящий минимум (монотонная очередь по индексам) и скользящее среднее
    private val W = L + 1                                                     // окно минимума на 1 длиннее задержки
    private val minQ = FloatArray(W); private val minIdx = LongArray(W); private var qHead = 0; private var qLen = 0
    private val avgBuf = FloatArray(L) { 1f }; private var avgSum = L.toDouble(); private var avgPos = 0
    private var r = 1f
    private var t = 0L
    /** Насколько сейчас поджато, дБ (для отладки). */
    @Volatile var reductionDb = 0f; private set

    fun reset() {
        delay.fill(0f); pos = 0; qLen = 0; avgBuf.fill(1f); avgSum = L.toDouble(); avgPos = 0; r = 1f
    }

    /** interleaved float, на месте; выход задержан на L отсчётов. */
    fun process(x: FloatArray, frames: Int) {
        var minGain = 1f
        for (i in 0 until frames) {
            val base = i * channels
            var peak = 0f
            for (c in 0 until channels) peak = maxOf(peak, abs(x[base + c]))
            val q = if (peak > ceiling) ceiling / peak else 1f
            // скользящий минимум за L+1 (выходит отсчёт t−L)
            while (qLen > 0 && minQ[(qHead + qLen - 1) % W] >= q) qLen--
            minQ[(qHead + qLen) % W] = q; minIdx[(qHead + qLen) % W] = t; qLen++
            while (minIdx[qHead] <= t - W) { qHead = (qHead + 1) % W; qLen-- }
            val m = minQ[qHead]
            r = if (m < r) m else r + (m - r) * release                        // вниз — сразу, вверх — плавно
            avgSum += r - avgBuf[avgPos]; avgBuf[avgPos] = r; avgPos = (avgPos + 1) % L
            val g = minOf(1.0, avgSum / L).toFloat()
            if (g < minGain) minGain = g
            for (c in 0 until channels) {
                val d = pos * channels + c
                val out = delay[d] * g
                delay[d] = x[base + c]
                x[base + c] = out
            }
            pos = (pos + 1) % L
            t++
        }
        reductionDb = if (minGain < 1f) (20 * kotlin.math.log10(minGain)) else 0f
    }
}
