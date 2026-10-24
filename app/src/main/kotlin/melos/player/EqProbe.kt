package melos.player

import melos.host.Log
import kotlin.math.log10
import kotlin.math.sqrt

// Отладка (MELOS_EQPROBE=1, вместе с MELOS_PLAY и MELOS_SILENT=1): на реальном треке по очереди поднимает каждую полосу
// эквалайзера тем же путём, что и ползунок (Player.eqAmps), и пишет, на сколько выросла энергия в каждой октаве.
object EqProbe {
    private val CENTERS = floatArrayOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    private const val N = 4096
    private const val RATE = 44100

    /** MELOS_EQTIMELINE=1: каждые 6 с меняет эквалайзер тем же путём, что ползунок (для проверки режима «VLC напрямую»). */
    fun timeline() {
        for (i in 0 until 60) { if (Player.status.value == Status.PLAYING) break; Thread.sleep(500) }
        Player.eqPreamp.value = 12f; Player.eqPreset.value = "custom"
        val steps = listOf("выкл" to null, "8 кГц +12" to 12f, "8 кГц 0" to 0f, "8 кГц +12" to 12f)
        for ((name, v) in steps) {
            if (v == null) Player.eqOn.value = false
            else { Player.eqAmps.value = List(10) { if (it == 8) v else 0f }; Player.eqOn.value = true }
            Log.i("EqProbe", "шаг: $name")
            Thread.sleep(6000)
        }
        Log.i("EqProbe", "done")
    }

    fun run() {
        var eng: VlcEngine? = null
        for (i in 0 until 60) { eng = Player.debugEngine; if (eng != null && Player.status.value == Status.PLAYING) break; Thread.sleep(500) }
        val e = eng ?: return Log.w("EqProbe", "нет плеера")
        Log.i("EqProbe", "режим: ${if (e.tapping) "через Melos" else "VLC напрямую"}")
        val acc = DoubleArray(CENTERS.size); var blocks = 0
        val buf = FloatArray(N); var fill = 0
        val lock = Any()
        e.probe = { pcm, frames ->
            synchronized(lock) {
                for (i in 0 until frames) {
                    buf[fill++] = (pcm[i * 2] + pcm[i * 2 + 1]) / 65536f
                    if (fill == N) {
                        fill = 0
                        val re = FloatArray(N) { buf[it] * (0.5f - 0.5f * kotlin.math.cos(2 * Math.PI * it / N).toFloat()) }
                        val im = FloatArray(N)
                        Analyzer.fft(re, im)
                        for ((b, c) in CENTERS.withIndex()) {
                            val lo = (c / 1.4142f * N / RATE).toInt().coerceAtLeast(1); val hi = (c * 1.4142f * N / RATE).toInt().coerceAtMost(N / 2 - 1)
                            var s = 0.0; for (k in lo..hi) s += re[k] * re[k] + im[k] * im[k]
                            acc[b] += s
                        }
                        blocks++
                    }
                }
            }
        }
        fun measure(): DoubleArray {
            Thread.sleep(400); synchronized(lock) { acc.fill(0.0); blocks = 0 }
            Thread.sleep(1500)
            return synchronized(lock) { DoubleArray(acc.size) { acc[it] / blocks.coerceAtLeast(1) } }
        }
        val head = CENTERS.joinToString(" ") { "%6s".format(if (it >= 1000) "${(it / 1000).toInt()}k" else it.toInt().toString()) }
        Log.i("EqProbe", "%-24s %s".format("октава, Гц →", head))
        Player.eqPreamp.value = 12f
        for (b in listOf(-1)) {
            val diff = DoubleArray(CENTERS.size)
            repeat(2) {
                Player.eqOn.value = false
                val off = measure()
                Player.eqAmps.value = List(10) { if (it == b) 12f else 0f }
                Player.eqOn.value = true
                val on = measure()
                for (k in diff.indices) diff[k] += 10 * log10(on[k] / off[k]) / 2
            }
            val label = if (b < 0) "все 0, усиление 12" else "полоса ${CENTERS[b].toInt()} Гц +12"
            Log.i("EqProbe", "%-24s %s".format(label, diff.joinToString(" ") { "%6.1f".format(it) }))
        }
        // после переключения трека (новый файл — VLC может пересоздать звуковой вывод)
        if (System.getenv("MELOS_EQPROBE_NEXT") == "1") {
            Player.next()
            Thread.sleep(1000)
            for (i in 0 until 40) { if (Player.status.value == Status.PLAYING) break; Thread.sleep(250) }
            Thread.sleep(1500)
            Log.i("EqProbe", "переключил трек: ${Player.current.value?.title}")
        }
        // как ползунок: эквалайзер всё время включён, меняется одна полоса (и пресет становится «Свой»)
        Player.eqOn.value = true; Player.eqAmps.value = List(10) { 0f }; Player.eqPreset.value = "custom"
        for (b in listOf(4, 7, 8, 9)) {
            val diff = DoubleArray(CENTERS.size)
            repeat(2) {
                Player.eqAmps.value = List(10) { 0f }; Player.eqPreset.value = "custom"
                val flat = measure()
                Player.eqAmps.value = Player.eqAmps.value.toMutableList().also { it[b] = 12f }; Player.eqPreset.value = "custom"
                val up = measure()
                for (k in diff.indices) diff[k] += 10 * log10(up[k] / flat[k]) / 2
            }
            Log.i("EqProbe", "%-24s %s".format("вкл., ${CENTERS[b].toInt()} Гц 0→+12", diff.joinToString(" ") { "%6.1f".format(it) }))
        }
        e.probe = null
        Log.i("EqProbe", "done")
    }
}
