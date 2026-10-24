package melos.player

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Анализ того, что сейчас звучит. Работает в аудиопотоке: каждые HOP сэмплов (~11,6 мс) — FFT, полосы,
// детектор онсетов (spectral flux с адаптивным порогом: среднее + k·σ за последние ~0,7 с) отдельно для
// бочки (низ) и малого/хэтов (верх), детектор дропа по истории энергии. Каждый кадр и событие помечаются
// моментом, когда их реально услышат (с учётом буфера вывода), и отдаются интерфейсу ровно тогда.
object Analyzer {
    const val N = 1024
    private const val HOP = 512
    const val BANDS = 24
    private const val RATE = 44100

    /** lowEnergy — линейная энергия низких частот (Σ|X|² по ~43–215 Гц), без логарифма и обрезки — для детектора бита. */
    class Frame(val at: Long, val bands: FloatArray, val level: Float, val bass: Float, val treble: Float, val intensity: Float, val lowEnergy: Float)
    sealed interface Event { val at: Long }
    class Kick(override val at: Long, val power: Float) : Event
    class Hat(override val at: Long, val power: Float) : Event
    class Drop(override val at: Long, val power: Float) : Event

    private val events = ConcurrentLinkedQueue<Event>()
    private val frames = ConcurrentLinkedQueue<Frame>()
    @Volatile var lastPushAt = 0L; private set

    // кольцо моно-сэмплов
    private val ring = FloatArray(1 shl 14)
    private var written = 0L
    private var nextHop = N.toLong()

    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
    private val re = FloatArray(N); private val im = FloatArray(N); private val mag = FloatArray(N / 2)

    // онсеты
    private const val HIST = 60
    private val lowFlux = FloatArray(HIST); private val hiFlux = FloatArray(HIST); private var hIdx = 0
    private var prevLow = 0f; private var prevHi = 0f
    private var hopsSinceKick = 1000; private var hopsSinceHat = 1000
    // энергия для дропа: ~8 с истории с шагом хопа
    private const val EHIST = 690
    private val energy = FloatArray(EHIST); private var eIdx = 0; private var eCount = 0
    private var hopsSinceDrop = 100_000
    private var peakLow = 0.2f
    // трекер долей (в хопах по ~11,6 мс)
    private var hop = 0L
    private val onsets = ArrayDeque<Long>()
    private var period = 0L; private var lastBeat = 0L; private var confidence = 0f
    private var rival = 0; private var rivalHits = 0; private var lastPower = 0.5f
    /** Темп по трекеру долей, 0 — пока не ясен. */
    val bpm: Float get() = if (period > 0 && confidence > 0.3f) 60_000f / (period * HOP * 1000f / RATE) else 0f

    // Темп по гистограмме интервалов между онсетами за ~8 с: кратные интервалы сворачиваем
    // в диапазон 80–200 BPM (26–64 хопа), лёгкий приоритет у ~120 BPM.
    private fun estimatePeriod() {
        if (onsets.size < 8) { period = 0; return }
        val hist = FloatArray(80)
        val list = onsets.toList()
        for (i in list.indices) for (j in i + 1 until list.size) {
            val d = (list[j] - list[i]).toFloat()
            if (d > 200) break
            for (m in 1..4) {
                val q = d / m
                if (q < 26 || q > 64) continue
                val b = q.toInt()
                hist[b] += 1f / m; if (b + 1 < hist.size) hist[b + 1] += 0.5f / m; hist[b - 1] += 0.5f / m
            }
        }
        var best = 0; var bestV = 0f
        for (b in 26..64) { val prior = 1f - abs(b - 43) / 60f; val v = hist[b] * prior; if (v > bestV) { bestV = v; best = b } }
        if (best <= 0) return
        when {
            period == 0L -> period = best.toLong()
            abs(best - period) <= period / 10 -> { period = (period * 3 + best) / 4; rival = 0; rivalHits = 0 }   // рядом — плавно подстраиваемся
            // далёкий темп принимаем, только если он побеждает 5 оценок подряд (~1 с): смена ритма, а не случайность
            abs(best - rival) <= 2 -> if (++rivalHits >= 5) { period = best.toLong(); rival = 0; rivalHits = 0 }
            else -> { rival = best; rivalHits = 1 }
        }
    }

    /** interleaved s16; [delayFrames] — сколько кадров уже ждёт в буфере вывода до этого куска. */
    fun push(pcm: ShortArray, frames: Int, channels: Int, delayFrames: Int) {
        val pushAt = System.currentTimeMillis()
        lastPushAt = pushAt
        val startPos = written
        for (i in 0 until frames) {
            var s = 0f
            for (c in 0 until channels) s += pcm[i * channels + c]
            ring[(written and (ring.size - 1).toLong()).toInt()] = s / (channels * 32768f)
            written++
            while (written >= nextHop) {
                val playAt = pushAt + ((delayFrames + (nextHop - startPos)) * 1000L / RATE)
                analyze(nextHop, playAt)
                nextHop += HOP
            }
        }
    }

    /** Перемотка/смена трека: всё, что ещё не прозвучало, — выбросить. */
    fun flush() { events.clear(); frames.clear(); prevLow = 0f; prevHi = 0f; onsets.clear(); lastBeat = 0L; confidence = 0f; rival = 0; rivalHits = 0 }

    val live get() = System.currentTimeMillis() - lastPushAt < 400

    private fun analyze(end: Long, playAt: Long) {
        val mask = (ring.size - 1).toLong()
        for (i in 0 until N) { re[i] = ring[((end - N + i) and mask).toInt()] * window[i]; im[i] = 0f }
        fft(re, im)
        var total = 0f
        for (k in 0 until N / 2) { mag[k] = sqrt(re[k] * re[k] + im[k] * im[k]); total += mag[k] * mag[k] }
        val rms = sqrt(total / (N / 2)) / 8f

        // полосы: логарифмически от ~40 Гц до ~16 кГц
        val bands = FloatArray(BANDS)
        val half = N / 2
        for (b in 0 until BANDS) {
            val lo = max(1, (half * Math.pow(2.0, (b.toDouble() / BANDS) * 9 - 9)).toInt())
            val hi = min(half, max(lo + 1, (half * Math.pow(2.0, ((b + 1.0) / BANDS) * 9 - 9)).toInt()))
            var sum = 0f
            for (k in lo until hi) sum += mag[k]
            bands[b] = (ln(1 + sum / (hi - lo) * 6f) / 3.2f).coerceIn(0f, 1f)
        }
        var low = 0f; for (k in 1..4) low += mag[k]                       // ~43–215 Гц: бочка и бас
        var high = 0f; for (k in 60..300) high += mag[k]                   // ~2,5–13 кГц: малый, хэты
        val lowL = ln(1 + low); val highL = ln(1 + high * 0.2f)
        val lf = max(0f, lowL - prevLow); val hf = max(0f, highL - prevHi)
        prevLow = lowL; prevHi = highL
        lowFlux[hIdx] = lf; hiFlux[hIdx] = hf; hIdx = (hIdx + 1) % HIST
        hopsSinceKick++; hopsSinceHat++; hopsSinceDrop++

        fun thr(a: FloatArray, k: Float): Float {
            var m = 0f; for (v in a) m += v; m /= a.size
            var d = 0f; for (v in a) d += (v - m) * (v - m)
            return m + k * sqrt(d / a.size)
        }
        peakLow = max(peakLow * 0.9995f, lowL)
        hop++
        // сырой онсет низа: всплеск потока выше адаптивного порога, сам низ заметный
        val onset = lf > thr(lowFlux, 1.6f) + 0.05f && lowL > peakLow * 0.5f && hopsSinceKick > 8
        // сила доли — насколько громок низ относительно пика трека: ровная от удара к удару
        val power = ((lowL / max(0.1f, peakLow)) * 1.3f - 0.35f).coerceIn(0.25f, 1f)
        if (onset) { hopsSinceKick = 0; onsets.addLast(hop); while (onsets.isNotEmpty() && hop - onsets.first() > 690) onsets.removeFirst() }
        if (hop % 16 == 0L) estimatePeriod()
        // трекер долей: удар — на сетке темпа; онсет рядом с ожидаемой долей «притягивает» её и задаёт силу,
        // нет онсета — доля всё равно идёт (слабее), чтобы пульс не сбивался на басовых нотах и паузах
        val p = period
        if (p > 0) {
            val pred = lastBeat + p
            if (onset && (lastBeat == 0L || abs(hop - pred) <= 6 || hop - lastBeat > p * 2)) {
                lastBeat = hop; confidence = min(1f, confidence + 0.2f); lastPower = power; events += Kick(playAt, power)
            } else if (hop >= pred + 6 && confidence > 0.3f) {
                lastBeat = pred; confidence = max(0f, confidence - 0.08f)
                events += Kick(playAt - 6 * HOP * 1000L / RATE, lastPower * 0.85f)
            }
        } else if (onset && hopsSinceKick == 0 && hop - lastBeat > 25) { lastBeat = hop; events += Kick(playAt, power) }
        if (hf > thr(hiFlux, 1.8f) + 0.04f && hopsSinceHat > 7) {
            hopsSinceHat = 0
            events += Hat(playAt, (hf / (thr(hiFlux, 0f) + 0.1f) / 4f).coerceIn(0.2f, 1f))
        }

        // дроп: последние ~0,25 с заметно громче, чем было 3,5…0,5 с назад, при сильном низе
        val e = rms * 0.5f + (lowL / max(1f, peakLow)) * 0.5f
        energy[eIdx] = e; eIdx = (eIdx + 1) % EHIST; eCount = min(eCount + 1, EHIST)
        var intensity = 0f
        if (eCount > 320) {
            fun meanBack(from: Int, to: Int): Float { var s = 0f; for (k in from until to) s += energy[((eIdx - 1 - k) % EHIST + EHIST) % EHIST]; return s / (to - from) }
            val recent = meanBack(0, 22)
            val before = meanBack(43, 300)
            val all = meanBack(0, eCount)
            intensity = ((recent / max(0.02f, all) - 1f) / 0.35f).coerceIn(0f, 1f)
            if (recent > before * 1.4f && recent > 0.25f && lowL > peakLow * 0.7f && hopsSinceDrop > 690) {
                hopsSinceDrop = 0
                events += Drop(playAt, ((recent / max(0.02f, before) - 1f)).coerceIn(0.3f, 1f))
                melos.host.Log.i("Analyzer", "drop recent=$recent before=$before")
            }
        }
        var treble = 0f; for (b in BANDS - 8 until BANDS) treble += bands[b]
        var lowE = 0f; for (k in 1..4) lowE += mag[k] * mag[k]
        frames += Frame(playAt, bands, rms.coerceIn(0f, 1f), (bands[0] + bands[1] + bands[2] + bands[3]) / 4, treble / 8, intensity, lowE)
        while (frames.size > 64) frames.poll()
        while (events.size > 64) events.poll()
    }

    /** Кадр спектра, который звучит сейчас (последний с моментом ≤ now). */
    fun current(now: Long): Frame? {
        var f: Frame? = null
        while (true) { val h = frames.peek() ?: break; if (h.at > now) break; f = frames.poll() }
        return f
    }

    /** События, которые уже прозвучали. */
    fun drain(now: Long, sink: (Event) -> Unit) {
        while (true) { val e = events.peek() ?: break; if (e.at > now) break; events.poll(); if (now - e.at < 250) sink(e) }
    }

    internal fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
