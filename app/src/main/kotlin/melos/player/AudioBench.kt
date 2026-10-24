package melos.player

import melos.core.Paths
import melos.host.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

// Диагностический стенд (MELOS_AUDIOBENCH=1): тестовый сигнал через VLC в разных конфигурациях,
// АЧХ эквалайзера VLC: уровень 15 частот на выходе VLC при подъёме каждой полосы.
object AudioBench {
    private const val RATE = 44100
    private val FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 6000f, 8000f, 10000f, 12000f, 14000f, 16000f, 18000f)
    private const val AMP = 0.04

    fun run() {
        val wav = File(Paths.cache, "bench.wav")
        writeTone(wav, 90.0)
        val eng = VlcEngine(tap = true, silent = true)   // меряем до вывода — в наушники ничего не идёт
        val sums = DoubleArray(FREQS.size); var count = 0
        val tape = java.util.concurrent.ConcurrentLinkedQueue<Pair<Long, Double>>()   // (время, уровень 16 кГц)
        val lock = Any()
        eng.probe = { pcm, frames ->
            synchronized(lock) {
                // Гёрцель по левому каналу: амплитуда каждой частоты в этом блоке
                for ((fi, f) in FREQS.withIndex()) {
                    val w = 2 * cos(2 * PI * f / RATE); var s1 = 0.0; var s2 = 0.0
                    for (i in 0 until frames) { val s0 = pcm[i * 2] / 32768.0 + w * s1 - s2; s2 = s1; s1 = s0 }
                    val power = s1 * s1 + s2 * s2 - w * s1 * s2
                    sums[fi] += sqrt(power) * 2 / frames
                    if (f == 16000f) tape.add(System.nanoTime() to sqrt(power) * 2 / frames)
                }
                count++
            }
        }
        var ref: List<Double>? = null
        fun measure(label: String, setup: () -> Unit) {
            setup(); Thread.sleep(700)
            synchronized(lock) { sums.fill(0.0); count = 0 }
            Thread.sleep(1500)
            val amps = synchronized(lock) { sums.map { it / count.coerceAtLeast(1) } }
            val base = ref ?: amps.also { ref = it }
            val row = FREQS.indices.joinToString(" ") { i -> "%5.1f".format(20 * log10(amps[i] / base[i])) }
            Log.i("Bench", "%-34s %s".format(label, row))
        }
        Log.i("Bench", "tapping=${eng.tapping}; полосы VLC: ${VlcEngine.bands.joinToString { "%.0f".format(it) }}")
        eng.play(wav.toPath().toUri().toString())
        Thread.sleep(1500)
        Log.i("Bench", "status=${eng.status.value} blocks=$count")
        Log.i("Bench", "%-34s %s".format("частота, Гц →", FREQS.joinToString(" ") { "%5s".format(if (it >= 1000) "${(it / 1000).toInt()}k" else it.toInt().toString()) }))
        eng.setVolume(100)
        measure("без эквалайзера (опора, 0 дБ)") { eng.setEqualizer(null) }
        fun vlcEq(pre: Float, amps: List<Float>) = VlcEngine.factory.equalizer().newEqualizer().apply {
            setPreamp(pre); setAmps(FloatArray(bandCount()) { amps.getOrElse(it) { 0f } })
        }
        measure("VLC: все 0, усиление 12") { eng.setEqualizer(vlcEq(12f, emptyList())) }
        for (b in 0 until 10) measure("VLC: полоса ${VlcEngine.bands[b].toInt()} Гц +12") {
            eng.setEqualizer(vlcEq(12f, List(10) { if (it == b) 12f else 0f }))
        }
        if (System.getenv("MELOS_BENCH_LAT") == "1") {
            val ref16 = ref!![FREQS.indexOfFirst { it == 16000f }]
            fun db(v: Double) = 20 * log10(v / ref16)
            fun until(t0: Long, target: (Double) -> Boolean): String {
                val deadline = t0 + 4_000_000_000L
                while (System.nanoTime() < deadline) {
                    tape.firstOrNull { it.first > t0 && target(db(it.second)) }?.let { return "%.0f мс".format((it.first - t0) / 1e6) }
                    Thread.sleep(20)
                }
                return "не дошло за 4 с (сейчас ${"%.1f".format(db(tape.last().second))} дБ)"
            }
            eng.setEqualizer(null); Thread.sleep(1500); tape.clear()
            var t0 = System.nanoTime()
            eng.setEqualizer(vlcEq(12f, List(10) { if (it == 9) 12f else 0f }))
            Log.i("Bench", "включение эквалайзера (16 кГц +12): дошло через ${until(t0) { it > 11f }}")
            Thread.sleep(1000); tape.clear(); t0 = System.nanoTime()
            eng.setEqualizer(vlcEq(12f, List(10) { if (it == 9) 6f else 0f }))
            Log.i("Bench", "смена полосы при включённом (+12 → +6): дошло через ${until(t0) { it in 5f..7f }}")
            Thread.sleep(1000); tape.clear()
            // «тянем ползунок»: 25 изменений за секунду, последнее — +12
            for (k in 0 until 25) { eng.setEqualizer(vlcEq(12f, List(10) { if (it == 9) (k % 13).toFloat() else 0f })); Thread.sleep(40) }
            eng.setEqualizer(vlcEq(12f, List(10) { if (it == 9) 12f else 0f }))
            t0 = System.nanoTime()
            Log.i("Bench", "после 25 быстрых изменений последнее (+12): ${until(t0) { it > 11f }}")
            val gaps = tape.zipWithNext().count { (a, b) -> (b.first - a.first) > 150_000_000L }
            Log.i("Bench", "разрывов звука >150 мс во время серии: $gaps")
        }
        val user = System.getenv("MELOS_BENCH_EQ")?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
        if (user != null && user.size == 11) measure("VLC: настройки пользователя") { eng.setEqualizer(vlcEq(user[0], user.drop(1))) }
        eng.release()
        Log.i("Bench", "done")
    }

    // набор синусов по всему диапазону, каждый с амплитудой AMP (сумма ≤ 0,6 — без клиппинга)
    private fun writeTone(f: File, seconds: Double) {
        val n = (RATE * seconds).toInt()
        val bb = ByteBuffer.allocate(44 + n * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + n * 4).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(2).putInt(RATE).putInt(RATE * 4).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(n * 4)
        for (i in 0 until n) {
            var v = 0.0; for (fr in FREQS) v += AMP * sin(2 * PI * fr * i / RATE)
            val s = (v * 32767).toInt().toShort(); bb.putShort(s).putShort(s)
        }
        f.writeBytes(bb.array())
    }
}
