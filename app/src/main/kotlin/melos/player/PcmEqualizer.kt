package melos.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

// Эквалайзер в собственном звуковом тракте Melos (вместо фильтра VLC, который на нулях всё равно окрашивает звук).
// 10 пиковых фильтров (RBJ Audio EQ Cookbook, peaking EQ) на октавных полосах VLC + общее усиление.
//   полоса на 0 дБ не обрабатывается вовсе; всё на нуле — тракт пропускается целиком, звук бит в бит без изменений;
//   выход за предел — мягкое ограничение вместо жёсткого клиппинга.
class PcmEqualizer(private val rate: Int, private val channels: Int) {
    companion object {
        val FREQS = floatArrayOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        private const val Q = 1.41f                                  // ширина в октаву

        /** Максимум суммарной АЧХ полос, дБ (20 Гц…20 кГц): соседние полосы складываются, +6 и +6 дают больше +6. */
        fun peakDb(gainsDb: List<Float>, rate: Int = 44100): Float {
            val eq = PcmEqualizer(rate, 1).apply { set(true, 0f, gainsDb) }
            val coefs = eq.params.coefs.filterNotNull()
            if (coefs.isEmpty()) return 0f
            var best = -100.0
            var f = 20.0
            while (f <= 20000.0) {
                val w = 2 * PI * f / rate
                var db = 0.0
                for (c in coefs) {
                    // |H(e^jw)|² биквада
                    val b0 = c.b0.toDouble(); val b1 = c.b1.toDouble(); val b2 = c.b2.toDouble(); val a1 = c.a1.toDouble(); val a2 = c.a2.toDouble()
                    val nr = b0 + b1 * cos(w) + b2 * cos(2 * w); val ni = -(b1 * sin(w) + b2 * sin(2 * w))
                    val dr = 1 + a1 * cos(w) + a2 * cos(2 * w); val di = -(a1 * sin(w) + a2 * sin(2 * w))
                    db += 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
                }
                best = maxOf(best, db)
                f *= 1.02
            }
            return best.toFloat()
        }
    }

    private class Coef(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float)
    private class Params(val gain: Float, val coefs: Array<Coef?>, val bypass: Boolean)

    @Volatile private var params = Params(1f, arrayOfNulls(FREQS.size), true)
    // состояние фильтров (Transposed Direct Form II): [полоса][канал] → z1, z2
    private val z1 = Array(FREQS.size) { FloatArray(channels) }
    private val z2 = Array(FREQS.size) { FloatArray(channels) }

    fun set(enabled: Boolean, preampDb: Float, gainsDb: List<Float>) {
        val coefs = arrayOfNulls<Coef>(FREQS.size)
        var any = false
        if (enabled) for (i in FREQS.indices) {
            val g = gainsDb.getOrElse(i) { 0f }
            if (abs(g) < 0.05f) continue                                // 0 дБ — фильтр не нужен
            val f0 = FREQS[i].coerceAtMost(rate * 0.45f)
            val A = 10f.pow(g / 40f)
            val w0 = 2f * PI.toFloat() * f0 / rate
            val alpha = sin(w0) / (2f * Q)
            val c = cos(w0)
            val a0 = 1f + alpha / A
            coefs[i] = Coef((1f + alpha * A) / a0, (-2f * c) / a0, (1f - alpha * A) / a0, (-2f * c) / a0, (1f - alpha / A) / a0)
            any = true
        }
        val gain = if (enabled) 10f.pow(preampDb / 20f) else 1f
        val bypass = !enabled || (!any && abs(preampDb) < 0.05f)
        if (bypass) for (b in FREQS.indices) { z1[b].fill(0f); z2[b].fill(0f) }
        params = Params(gain, coefs, bypass)
    }

    val active get() = !params.bypass

    /** interleaved float, на месте; выход может быть > 1 — пики снимает [Limiter] дальше по тракту (без искажения формы). */
    fun process(pcm: FloatArray, frames: Int) {
        val p = params
        if (p.bypass) return
        for (i in 0 until frames) for (ch in 0 until channels) {
            val idx = i * channels + ch
            var x = pcm[idx] * p.gain
            for (b in FREQS.indices) {
                val c = p.coefs[b] ?: continue
                val y = c.b0 * x + z1[b][ch]
                z1[b][ch] = c.b1 * x - c.a1 * y + z2[b][ch]
                z2[b][ch] = c.b2 * x - c.a2 * y
                x = y
            }
            pcm[idx] = x
        }
    }

    /** interleaved s16, на месте (для тестов): то же, но с мягким ограничением у потолка шкалы. */
    fun process(pcm: ShortArray, frames: Int) {
        if (params.bypass) return
        val f = FloatArray(frames * channels) { pcm[it] / 32768f }
        process(f, frames)
        for (i in f.indices) {
            var x = f[i]
            val ax = abs(x)
            if (ax > 0.9f) x = (if (x > 0) 1f else -1f) * (0.9f + 0.09f * tanh((ax - 0.9f) / 0.09f))
            pcm[i] = (x * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
