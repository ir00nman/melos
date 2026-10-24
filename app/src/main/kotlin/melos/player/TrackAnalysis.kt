package melos.player

import melos.core.Paths
import melos.core.json
import melos.host.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

const val SPEC_BANDS = 24
const val SPEC_MS = 4 * 256 * 1000f / 22050f              // ~46 мс

// Карта трека: заранее разобранная песня, по которой строятся анимации (доли ровно в такт, дропы заранее известны).
@Serializable
data class TrackMap(
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _map = MutableStateFlow<Pair<String, TrackMap>?>(null)
    val current = _map.asStateFlow()
    private var job: Job? = null
    private val cacheDir get() = File(Paths.cache, "analysis").apply { mkdirs() }

    private const val SR = 22050
    private const val SPEC_HOPS = 4                       // спектр в карту — каждые 4 хопа (~46 мс)
    private const val N = 1024
    private const val HOP = 256
    private val HOP_MS = HOP * 1000.0 / SR

    fun mapFor(mediaId: String?): TrackMap? = _map.value?.takeIf { it.first == mediaId }?.second

    /** Разобрать трек (из кэша или заново) — вызывается, когда поток уже открыт для воспроизведения. */
    fun request(mediaId: String, mrl: String) {
        if (_map.value?.first == mediaId) return
        job?.cancel()
        job = scope.launch {
            val cache = File(cacheDir, mediaId.hashCode().toUInt().toString() + ".v3.json")
            val cached = runCatching { json.decodeFromString(TrackMap.serializer(), cache.readText()) }.getOrNull()
            if (cached != null) { _map.value = mediaId to cached; return@launch }
            val t0 = System.currentTimeMillis()
            val pcm = decode(mrl) ?: run { Log.w("Analysis", "decode failed"); return@launch }
            val map = analyze(pcm)
            Log.i("Analysis", "map ready in ${System.currentTimeMillis() - t0} ms: bpm=${"%.1f".format(map.bpm)} beats=${map.beats.size} drops=${map.drops.map { it / 1000 }} " +
                "sections=${map.sections.zip(map.sectionEnergy).map { (t, e) -> "${t / 1000}s/${"%.2f".format(e)}" }}")
            _map.value = mediaId to map
            runCatching { cache.writeText(json.encodeToString(TrackMap.serializer(), map)) }
        }
    }

    // ---- декод: беззвучный VLC перегоняет поток в WAV 22 кГц моно (файлы — быстрее реального времени) ----
    private suspend fun decode(mrl: String): ShortArray? {
        val out = File.createTempFile("melos-analysis", ".wav", Paths.cache)
        val player: MediaPlayer = VlcEngine.factory.mediaPlayers().newMediaPlayer()
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun finished(mp: MediaPlayer) { done.complete(true) }
            override fun error(mp: MediaPlayer) { done.complete(false) }
        })
        try {
            val sout = ":sout=#transcode{vcodec=none,acodec=s16l,channels=1,samplerate=$SR}:std{access=file,mux=wav,dst=${out.path}}"
            player.media().play(mrl, sout, ":no-sout-video", ":sout-keep", ":http-user-agent=Mozilla/5.0 Melos")
            val ok = withTimeoutOrNull(180_000) { done.await() } ?: false
            delay(150)
            if (!ok) return null
            return readWav(out)
        } finally {
            runCatching { player.controls().stop() }
            runCatching { player.release() }
            out.delete()
        }
    }

    private fun readWav(f: File): ShortArray? {
        RandomAccessFile(f, "r").use { raf ->
            val bytes = ByteArray(raf.length().toInt()); raf.readFully(bytes)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var p = 12
            while (p + 8 <= bytes.size) {                               // ищем блок "data"
                val id = String(bytes, p, 4); val size = bb.getInt(p + 4)
                if (id == "data") {
                    val n = min((bytes.size - p - 8) / 2, if (size > 0) size / 2 else Int.MAX_VALUE)
                    return ShortArray(n) { bb.getShort(p + 8 + it * 2) }
                }
                p += 8 + size + (size and 1)
            }
        }
        return null
    }

    // ---- разбор ----
    fun analyze(pcm: ShortArray): TrackMap {
        val frames = max(1, (pcm.size - N) / HOP)
        val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
        val re = FloatArray(N); val im = FloatArray(N)
        val prev = FloatArray(N / 2)
        val magBuf = FloatArray(N / 2)
        val spec = java.io.ByteArrayOutputStream(); val lvl = java.io.ByteArrayOutputStream()
        val onset = FloatArray(frames)            // поток по всему спектру
        val low = FloatArray(frames)              // поток по низу (~40–170 Гц)
        val rms = FloatArray(frames)
        val lowBins = 2..8
        for (f in 0 until frames) {
            val o = f * HOP
            var e = 0f
            for (i in 0 until N) { val v = pcm[o + i] / 32768f; e += v * v; re[i] = v * window[i]; im[i] = 0f }
            rms[f] = sqrt(e / N)
            fft(re, im)
            var flux = 0f; var lf = 0f
            val keep = f % SPEC_HOPS == 0
            for (k in 1 until N / 2) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                if (keep) magBuf[k] = mag
                val m = ln(1f + 100f * mag)
                val d = m - prev[k]
                if (d > 0) { flux += d; if (k in lowBins) lf += d }
                prev[k] = m
            }
            onset[f] = flux; low[f] = lf
            if (keep) {
                // те же логарифмические полосы, что у живого анализатора (~40 Гц … верх спектра)
                for (b in 0 until SPEC_BANDS) {
                    val lo = max(1, (N / 2 * Math.pow(2.0, (b.toDouble() / SPEC_BANDS) * 8.4 - 8.4)).toInt())
                    val hi = min(N / 2, max(lo + 1, (N / 2 * Math.pow(2.0, ((b + 1.0) / SPEC_BANDS) * 8.4 - 8.4)).toInt()))
                    var sum = 0f; for (k in lo until hi) sum += magBuf[k]
                    spec.write(((ln(1 + sum / (hi - lo) * 6f) / 3.2f).coerceIn(0f, 1f) * 255).toInt())
                }
                lvl.write(((rms[f] * 3f).coerceIn(0f, 1f) * 255).toInt())
            }
        }
        // огибающая: минус локальное среднее (~0,5 с), только положительное, нормировка
        fun detrend(a: FloatArray): FloatArray {
            val w = (500 / HOP_MS).toInt(); val out = FloatArray(a.size); var sum = 0f
            for (i in a.indices) {
                sum += a[i]; if (i > w) sum -= a[i - w - 1]
                out[i] = max(0f, a[i] - sum / min(i + 1, w + 1))
            }
            val sorted = out.sorted(); val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)].coerceAtLeast(1e-6f)
            for (i in out.indices) out[i] = (out[i] / p95).coerceAtMost(2f)
            return out
        }
        val env = detrend(onset); val lowEnv = detrend(low)
        val drive = FloatArray(frames) { env[it] * 0.6f + lowEnv[it] * 0.4f }

        // темп: автокорреляция огибающей на лагах 70–180 BPM, вес — лог-нормаль вокруг 120 BPM
        val minLag = (60_000 / 180 / HOP_MS).toInt(); val maxLag = (60_000 / 70 / HOP_MS).toInt()
        var bestLag = minLag; var bestScore = -1f
        val acf = FloatArray(maxLag + 2)
        for (lag in minLag..maxLag + 1) {
            var s = 0f; var i = lag
            while (i < frames) { s += drive[i] * drive[i - lag]; i++ }
            acf[lag] = s / (frames - lag)
        }
        for (lag in minLag..maxLag) {
            val bpm = 60_000 / (lag * HOP_MS)
            val w = exp(-0.5 * (ln(bpm / 120.0) / ln(2.0) / 0.9).let { it * it }).toFloat()
            val score = acf[lag] * w
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }
        // уточнение лага параболой
        val y0 = acf[bestLag - 1]; val y1 = acf[bestLag]; val y2 = acf[bestLag + 1]
        val shift = ((y0 - y2) / (2 * (y0 - 2 * y1 + y2))).takeIf { it.isFinite() }?.coerceIn(-0.5f, 0.5f) ?: 0f
        val period = bestLag + shift
        val bpm = (60_000 / (period * HOP_MS)).toFloat()

        // сетка долей: динамическое программирование (Ellis 2007) — счёт = онсет + лучший предыдущий
        // с штрафом за отклонение интервала от периода
        val score = FloatArray(frames); val back = IntArray(frames) { -1 }
        val alpha = 100f
        for (t in 0 until frames) {
            var best = 0f; var arg = -1
            val from = max(0, t - (period * 2).toInt()); val to = t - (period / 2).toInt()
            for (pIdx in from..to) {
                if (pIdx < 0) continue
                val r = ln((t - pIdx) / period)
                val v = score[pIdx] - alpha * r * r
                if (arg < 0 || v > best) { best = v; arg = pIdx }
            }
            score[t] = drive[t] + (if (arg >= 0) max(0f, best) else 0f)
            back[t] = if (arg >= 0 && best > 0f) arg else -1
        }
        var end = frames - 1
        val tail = (period * 1.5f).toInt()
        for (t in max(0, frames - tail) until frames) if (score[t] > score[end]) end = t
        val beatFrames = ArrayList<Int>()
        var t = end
        while (t >= 0) { beatFrames += t; t = back[t] }
        beatFrames.reverse()

        // сила доли — бас вокруг доли; сильная доля — фаза такта (4 доли) с самым сильным басом
        val strength = beatFrames.map { b -> var m = 0f; for (k in max(0, b - 2)..min(frames - 1, b + 2)) m = max(m, lowEnv[k]); m }
        val maxS = strength.maxOrNull()?.coerceAtLeast(1e-6f) ?: 1f
        val strengthN = strength.map { (it / maxS).coerceIn(0.2f, 1f) }
        var phase = 0; var phaseBest = -1f
        for (ph in 0..3) { var s = 0f; for (i in strengthN.indices) if (i % 4 == ph) s += strengthN[i]; if (s > phaseBest) { phaseBest = s; phase = ph } }
        val downbeat = beatFrames.indices.map { it % 4 == phase }

        // энергия: RMS, сглаженная ~1 с, нормировка по 95-му перцентилю, шаг 100 мс
        val step = (100 / HOP_MS)
        val samples = (frames / step).toInt() + 1
        val energyRaw = FloatArray(samples) { i ->
            val c = (i * step).toInt(); val w = (500 / HOP_MS).toInt()
            var s = 0f; var n = 0
            for (k in max(0, c - w)..min(frames - 1, c + w)) { s += rms[k]; n++ }
            if (n > 0) s / n else 0f
        }
        val p95 = energyRaw.sorted()[(samples * 0.95).toInt().coerceAtMost(samples - 1)].coerceAtLeast(1e-6f)
        val energy = energyRaw.map { (it / p95).coerceIn(0f, 1f) }

        // дропы — по тактам: энергия следующих 4 тактов заметно выше предыдущих 4, и сама высокая
        val beatsMs = beatFrames.map { (it * HOP_MS).roundToInt().toLong() }
        val bars = beatsMs.indices.filter { downbeat[it] }.map { beatsMs[it] }
        fun eAt(ms: Long) = energy.getOrNull((ms / 100).toInt().coerceIn(0, energy.size - 1)) ?: 0f
        fun meanE(a: Long, b: Long): Float { var s = 0f; var n = 0; var m = a; while (m < b) { s += eAt(m); n++; m += 100 }; return if (n > 0) s / n else 0f }
        val drops = ArrayList<Long>(); val dropEnds = ArrayList<Long>(); val builds = ArrayList<Long>()
        var i = 4
        while (i < bars.size - 4) {
            val before = meanE(bars[i - 4], bars[i]); val after = meanE(bars[i], bars[i + 4])
            if (after > 0.62f && after - before > 0.22f && (drops.isEmpty() || bars[i] - drops.last() > 30_000)) {
                drops += bars[i]
                // подводка: назад от дропа, пока энергия не ниже ~ее минимума перед дропом (до 8 тактов)
                var b = i - 1; while (b > i - 8 && b > 0 && meanE(bars[b - 1], bars[b]) <= meanE(bars[b], bars[b + 1]) + 0.08f) b--
                builds += bars[max(0, b)]
                var e = i + 1; while (e < bars.size - 1 && meanE(bars[e], bars[e + 1]) > 0.55f) e++
                dropEnds += bars[e]
                i += 8
            } else i++
        }
        // части песни: на какой границе такта звук «другой» — тембр (спектр) и энергия четырёх тактов до против четырёх после;
        // берём выраженные пики этой «новизны» не чаще чем раз в 8 тактов, дропы — всегда границы
        val specBytes = spec.toByteArray()
        val specFrames = specBytes.size / SPEC_BANDS
        fun specMean(a: Long, b: Long): FloatArray {
            val v = FloatArray(SPEC_BANDS); var n = 0
            var f = (a / SPEC_MS).toInt().coerceAtLeast(0); val fe = (b / SPEC_MS).toInt().coerceAtMost(specFrames)
            while (f < fe) { for (k in 0 until SPEC_BANDS) v[k] += (specBytes[f * SPEC_BANDS + k].toInt() and 0xff) / 255f; n++; f++ }
            if (n > 0) for (k in v.indices) v[k] /= n
            return v
        }
        fun cosDist(x: FloatArray, y: FloatArray): Float {
            var d = 0f; var nx = 0f; var ny = 0f
            for (k in x.indices) { d += x[k] * y[k]; nx += x[k] * x[k]; ny += y[k] * y[k] }
            return if (nx <= 0f || ny <= 0f) 0f else 1f - d / sqrt(nx * ny)
        }
        val novelty = FloatArray(bars.size)
        for (b in 4 until bars.size - 4) {
            val tim = cosDist(specMean(bars[b - 4], bars[b]), specMean(bars[b], bars[b + 4]))
            val en = kotlin.math.abs(meanE(bars[b], bars[b + 4]) - meanE(bars[b - 4], bars[b]))
            novelty[b] = tim * 4f + en
        }
        val nz = novelty.filter { it > 0f }
        val nMean = if (nz.isEmpty()) 0f else nz.average().toFloat()
        val nStd = if (nz.isEmpty()) 0f else sqrt(nz.map { (it - nMean) * (it - nMean) }.average().toFloat())
        val cuts = sortedSetOf<Int>()
        bars.indices.filter { b -> novelty[b] > nMean + 0.8f * nStd && (max(0, b - 2)..min(bars.size - 1, b + 2)).all { novelty[it] <= novelty[b] } }
            .sortedByDescending { novelty[it] }
            .forEach { b -> if (cuts.none { kotlin.math.abs(it - b) < 8 }) cuts += b }
        for (d in drops) bars.indexOf(d).takeIf { it > 0 }?.let { b -> cuts.removeIf { kotlin.math.abs(it - b) < 4 }; cuts += b }
        val sections = listOf(0L) + cuts.map { bars[it] }
        val sectionEnergy = sections.indices.map { k -> meanE(sections[k], sections.getOrNull(k + 1) ?: (energy.size * 100L)) }

        val enc = java.util.Base64.getEncoder()
        return TrackMap((pcm.size * 1000L / SR), bpm, beatsMs, strengthN, downbeat, energy, drops, dropEnds, builds,
            enc.encodeToString(specBytes), enc.encodeToString(lvl.toByteArray()), sections, sectionEnergy)
    }

    private fun fft(re: FloatArray, im: FloatArray) {
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
