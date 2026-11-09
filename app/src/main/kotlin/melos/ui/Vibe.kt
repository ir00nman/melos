package melos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import melos.player.Analyzer
import melos.player.BeatStructure
import melos.player.Player
import melos.player.Status
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// Пульс музыки для всего интерфейса. Кадры и события анализатора приходят ровно тогда, когда их слышно.
//   bass/level/treble/bands — сглаженный спектр; kick/hat — онсеты (момент и сила); drop — 0..1, насколько
//   сейчас «дроп»; dropAt — момент взрыва; bpm — по интервалам бочек.
object Pulse {
    var bass by mutableFloatStateOf(0f); private set
    var level by mutableFloatStateOf(0f); private set
    var treble by mutableFloatStateOf(0f); private set
    var beatAt by mutableLongStateOf(0L); private set
    var kickAt = 0L; private set
    var kickPower = 0f; private set
    var hatAt = 0L; private set
    var hatPower = 0f; private set
    var frame by mutableLongStateOf(0L); private set
    val bands = FloatArray(Analyzer.BANDS)
    var bpm by mutableFloatStateOf(0f); private set
    var drop by mutableFloatStateOf(0f); private set
    var dropAt by mutableLongStateOf(0L); private set
    var dropPower = 0f; private set
    /** 0..1 — напряжение нарастания перед дропом (по карте трека), растёт до 1 ровно к дропу. */
    var tension by mutableFloatStateOf(0f); private set
    // ---- форма песни (по карте трека): части, такты, фразы по 8 тактов, предвкушение смены части ----
    /** Номер текущей части песни (−1 — карты нет) и когда (по часам) в неё вошли; на перемотке — без «события». */
    var section = -1; private set
    var sectionAt = 0L; private set
    /** Средняя энергия текущей части 0..1: тихая часть — мозг спокойнее, громкая — плотнее. */
    var sectionEnergy by mutableFloatStateOf(0.6f); private set
    /** 0..1 — последний такт перед сменой части: мозг «набирает воздух». */
    var antic by mutableFloatStateOf(0f); private set
    /** Последняя сильная доля и последняя граница фразы (каждые 8 тактов части), по часам. */
    var downAt = 0L; private set
    var phraseAt = 0L; private set
    /** 0..1 — «яркость» тембра (центр масс спектра): светлый звук — холоднее цвет, басовитый — теплее. */
    var brightness by mutableFloatStateOf(0.5f); private set
    /** 0..1 — последние 8 секунд трека: мозг затихает к концу. */
    var outro by mutableFloatStateOf(0f); private set

    // ---- структура трека (провал → нарастание → дроп): BeatStructure на живом звуке,
    //      в режиме карты — предпрогон по спектру карты, чтение по позиции воспроизведения ----
    val structure = BeatStructure()
    var stPhase = BeatStructure.Phase.NORMAL; private set
    /** 0..1 — провал энергии (BREAKDOWN). */
    var stCalm by mutableFloatStateOf(0f); private set
    /** 0..1 — нарастание перед дропом (BUILD). */
    var stTension by mutableFloatStateOf(0f); private set
    /** 0..1 — импульс дропа, затухает (~0.6 с). */
    var stDrop by mutableFloatStateOf(0f); private set
    /** 0..1 — «вдох» за 0.15–0.4 с до известного заранее дропа. */
    var stInhale by mutableFloatStateOf(0f); private set
    /** Сила дропа 0..1 (в кадр события). */
    var stDropStrength = 0f; private set
    /** true ровно в кадр найденного дропа. */
    var stDropEvent = false; private set

    /** Предпрогон структуры по карте: сэмплы каждые 50 мс, значения читаются по позиции. */
    private class StTimeline(val stepMs: Float, val n: Int, val calm: FloatArray, val tension: FloatArray,
                             val drop: FloatArray, val phase: IntArray, val dropIdx: IntArray)
    private var stMap: StTimeline? = null
    private var stMapFor: String? = null
    private var stLastIdx = -1
    private var stLoggedPhase = BeatStructure.Phase.NORMAL

    /** Анимации идут по заранее разобранной карте трека. */
    var mapActive = false; private set
    private var mapPos = -1L
    // сдвиг «позиция VLC → слышимое»: по замерам ~−150 мс, дальше подстраивается по услышанным ударам
    private const val MAP_OFFSET_TAP = -140L
    // прямой вывод VLC: позиция обгоняет звук на буфер вывода самого VLC (кэш + аудиовыход)
    private const val MAP_OFFSET_DIRECT = -110L
    /** Ручная поправка синхронизации анимаций (настройки), мс: «+» — анимации раньше, «−» — позже. */
    var syncShiftMs by mutableLongStateOf(melos.core.Settings.store.long("anim.shift") ?: 0L)
    fun setSyncShift(v: Long) { syncShiftMs = v.coerceIn(-400, 400); melos.core.Settings.store.put("anim.shift", syncShiftMs) }
    private fun baseOffset() = (if (Player.tapping) MAP_OFFSET_TAP else MAP_OFFSET_DIRECT) - syncShiftMs
    private val calib = ArrayDeque<Long>(); private var mapOffset = 0L
    /** Сглаженная за ~2 с плотность трека 0..1: спокойный трек — реакции мягкие, плотный — сильные. */
    var energy = 0f; private set
    private val kicks = ArrayDeque<Long>()
    private var intensity = 0f
    private var lastTrack: String? = null
    private val debugBeats = System.getenv("MELOS_DEBUG_BEATS") == "1"

    /** 0..1 — затухающая вспышка после удара бочки (атака ~35 мс — не «щёлкает» мгновенно) */
    fun beat(now: Long = System.currentTimeMillis()): Float {
        val t = (now - beatAt).toFloat()
        if (t < 0f) return 0f
        if (t < 35f) { val u = t / 35f; return u * u * (3 - 2 * u) }
        return (1f - (t - 35f) / 300f).coerceIn(0f, 1f)
    }
    /** «Панч» бочки: резкая атака ~25 мс, упругий спад ~220 мс, умножен на силу удара */
    fun punch(now: Long = System.currentTimeMillis()): Float {
        val t = (now - kickAt).toFloat()
        if (t < 0 || t > 400) return 0f
        if (t > 345) return 0f
        // плавная атака ~45 мс и мягкий спад ~300 мс
        val shape = if (t < 45) { val u = t / 45; u * u * (3 - 2 * u) } else 0.5f + 0.5f * kotlin.math.cos((t - 45) / 300f * Math.PI.toFloat())
        return kickPower * shape
    }
    fun hit(now: Long = System.currentTimeMillis()): Float {
        val t = (now - hatAt).toFloat()
        return if (t < 0 || t > 180) 0f else hatPower * (1 - t / 180)
    }

    private fun onKick(at: Long) {
        kicks.addLast(at); while (kicks.size > 16) kicks.removeFirst()
        if (kicks.size < 6) return
        val gaps = kicks.zipWithNext { a, b -> (b - a).toFloat() }.filter { it in 250f..1200f }.sorted()
        if (gaps.size < 4) return
        var b = 60_000f / gaps[gaps.size / 2]
        while (b < 80) b *= 2; while (b > 180) b /= 2
        bpm = if (bpm == 0f) b else bpm * 0.85f + b * 0.15f
        if (debugBeats) melos.host.Log.i("Beats", "kick gap=${(kicks.last() - kicks[kicks.size - 2])}ms bpm=${bpm.toInt()}")
    }

    // ---- параметры трёх алгоритмов цикла кадра ----
    private const val LERP_UP = 0.45f            // [Lerp] доля пути к цели за кадр при росте (60 к/с)
    private const val LERP_DOWN = 0.14f          // [Lerp] … и при спаде: полосы опускаются мягче, чем поднимаются
    private const val PEAK_HOLD_MS = 320L        // [Peak Decay] сколько вершина «висит» наверху
    private const val PEAK_GRAVITY = 2.2f        // [Peak Decay] ускорение падения, долей высоты в с²

    /** [Peak Decay] вершины полос спектра — для отрисовки «падающих пиков». */
    val peaks = FloatArray(Analyzer.BANDS)
    private val peakHoldUntil = LongArray(Analyzer.BANDS)
    private val peakVelocity = FloatArray(Analyzer.BANDS)
    private val raw = FloatArray(Analyzer.BANDS)                 // «сырые» полосы кадра — цель для Lerp
    private var lastFrameNs = 0L
    private var maxLowEnergy = 0f

    fun tick(nanos: Long) {
        // шаг кадра в секундах и поправка коэффициентов, чтобы сглаживание не зависело от частоты кадров
        val dt = if (lastFrameNs == 0L) 1f / 60 else ((nanos - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.1f)
        lastFrameNs = nanos
        val frames60 = dt * 60f
        fun k(base: Float) = 1f - (1f - base).pow(frames60)      // [Lerp] «base за кадр при 60 к/с» → за реальный кадр

        frame = nanos
        val now = System.currentTimeMillis()
        val playing = Player.status.value == Status.PLAYING
        val track = Player.current.value?.mediaId
        if (track != lastTrack) {
            lastTrack = track; kicks.clear(); bpm = 0f; drop = 0f; maxLowEnergy = 0f; mapPos = -1L; calib.clear(); mapOffset = baseOffset(); section = -1; antic = 0f
            structure.reset(); stMap = null; stMapFor = null; stLastIdx = -1
            stPhase = BeatStructure.Phase.NORMAL; stCalm = 0f; stTension = 0f; stDrop = 0f; stInhale = 0f; stDropEvent = false
        }
        if (calib.isEmpty()) mapOffset = baseOffset()
        val map = melos.player.TrackAnalysis.mapFor(track)
        mapActive = map != null && playing
        Analyzer.drain(now) { e ->
            when (e) {
                is Analyzer.Kick -> {
                    onKick(e.at); Analyzer.bpm.takeIf { it > 0 }?.let { bpm = it }
                    // визуал без карты ведёт трекер долей Analyzer (сетка темпа): он не срабатывает
                    // на произвольные волны баса, в отличие от порога по энергии
                    if (!mapActive) { kickAt = e.at; beatAt = e.at; kickPower = e.power }
                    // самокалибровка карты: где по позиции оказался услышанный удар относительно ближайшей доли карты.
                    // Принимаем только уверенные удары (сильные и близкие к доле): ложные онсеты синтов
                    // между долями иначе тянут offset в стороны (±200 мс) — визуал «не в бит»
                    if (map != null && map.beats.isNotEmpty() && e.power >= 0.45f) {
                        val posAt = Player.positionMs() - (now - e.at)
                        var lo = 0; var hi = map.beats.size - 1
                        while (lo < hi) { val mid = (lo + hi) / 2; if (map.beats[mid] < posAt) lo = mid + 1 else hi = mid }
                        val near = listOfNotNull(map.beats.getOrNull(lo), map.beats.getOrNull(lo - 1)).minByOrNull { kotlin.math.abs(it - posAt) }
                        if (near != null && kotlin.math.abs(posAt - near) < 120) {
                            calib.addLast(posAt - near); while (calib.size > 12) calib.removeFirst()
                            if (calib.size >= 4) mapOffset = calib.sorted()[calib.size / 2] - syncShiftMs
                            if (debugBeats) melos.host.Log.i("MapSync", "diff=${posAt - near}ms offset=${mapOffset}ms")
                        }
                    }
                }
                is Analyzer.Hat -> { hatAt = e.at; hatPower = e.power }
                is Analyzer.Drop -> if (!mapActive) { dropAt = e.at; dropPower = e.power }
            }
        }
        val f = if (playing) Analyzer.current(now) else null
        // звук напрямую из VLC (как в Murglar) — живого PCM нет: спектр и громкость берём из карты трека по позиции
        val fromMap = f == null && playing && map != null && map.hasSpectrum
        val mapPosNow = if (map != null) Player.positionMs() - mapOffset else 0L
        if (f != null) f.bands.copyInto(raw)
        else if (fromMap) map!!.spectrumAt(mapPosNow, raw)
        else if (!playing || !Analyzer.live) raw.fill(0f)

        // [Lerp] каждая полоса плавно догоняет «сырое» значение FFT: current += (target − current) · k
        for (i in bands.indices) {
            val target = raw[i]
            bands[i] += (target - bands[i]) * k(if (target > bands[i]) LERP_UP else LERP_DOWN)
        }
        val rawBass = (raw[0] + raw[1] + raw[2] + raw[3]) / 4f
        bass += (rawBass - bass) * k(if (rawBass > bass) 0.35f else 0.1f)   // [Lerp]
        if (f != null) {
            if (map == null) energy += (f.level * 1.6f - energy) * k(0.012f)  // [Lerp] «насколько трек плотный», ~2 с
            level += (f.level - level) * k(0.4f)                               // [Lerp]
            treble += (f.treble - treble) * k(0.4f)                            // [Lerp]
            intensity = f.intensity
        } else if (fromMap) {
            val lv = map!!.levelAt(mapPosNow)
            level += (lv - level) * k(0.4f)                                    // [Lerp]
            var tr = 0f; for (b in raw.size - 8 until raw.size) tr += raw[b]
            treble += (tr / 8 - treble) * k(0.4f)                              // [Lerp]
        } else if (!playing || !Analyzer.live) {
            level *= 1 - k(0.1f); treble *= 1 - k(0.1f); intensity *= 1 - k(0.1f)
        }

        // [Peak Decay] «гравитация» вершин: новая вершина — запоминаем и держим PEAK_HOLD_MS,
        // потом падаем с ускорением (скорость растёт на g·dt), но не ниже текущего уровня полосы
        for (i in peaks.indices) {
            if (bands[i] >= peaks[i]) {
                peaks[i] = bands[i]; peakHoldUntil[i] = now + PEAK_HOLD_MS; peakVelocity[i] = 0f
            } else if (now > peakHoldUntil[i]) {
                peakVelocity[i] += PEAK_GRAVITY * dt                            // v += g·dt
                peaks[i] = max(bands[i], peaks[i] - peakVelocity[i] * dt)        // y −= v·dt, но не ниже полосы
            }
        }

        // [Beat Detection] раньше здесь был второй детектор — энергия баса против среднего: он дёргался
        // на любые волны баса (не только бочку) и не держал сетку. Теперь удары визуала ведёт трекер
        // долей Analyzer (см. drain выше): он привязан к темпу и событию присвоен «слышимый» момент.
        if (playing && f != null) maxLowEnergy = max(maxLowEnergy * 0.9995f, f.lowEnergy)

        // ---- карта трека: доли, дропы, энергия и нарастание — ровно по позиции воспроизведения ----
        var mapDrop = -1f
        if (map != null && playing) {
            val pos = Player.positionMs() - mapOffset
            if (mapPos >= 0 && pos >= mapPos && pos - mapPos < 600) {
                // доли, пройденные с прошлого кадра: момент удара — когда позиция прошла долю
                var lo = 0; var hi = map.beats.size
                while (lo < hi) { val mid = (lo + hi) / 2; if (map.beats[mid] <= mapPos) lo = mid + 1 else hi = mid }
                var b = lo
                while (b < map.beats.size && map.beats[b] <= pos) {
                    // визуал бьёт только по настоящим ударам: слабые доли сетки (при ошибке октавы темпа
                    // их половина приходится между бочками) молчат — иначе пульс чаще, чем звук
                    val strength = map.strength[b]
                    if (strength >= 0.45f || map.downbeat[b]) {
                        kickAt = now - (pos - map.beats[b]); beatAt = kickAt
                        kickPower = strength * (if (map.downbeat[b]) 1f else 0.72f)   // сильная доля такта бьёт сильнее
                    }
                    if (map.downbeat[b]) {
                        downAt = kickAt
                        // фраза — каждые 8 тактов от начала части (по карте — и после перемотки); сама граница части — не фраза
                        val start = map.sections.getOrNull(map.sectionAt(map.beats[b])) ?: 0L
                        var bars = 0; var j = b - 1
                        while (j >= 0 && map.beats[j] >= start) { if (map.downbeat[j]) bars++; j-- }
                        if (bars > 0 && bars % 8 == 0) {
                            phraseAt = kickAt
                            if (debugBeats) melos.host.Log.i("Form", "фраза: такт $bars части ${map.sectionAt(map.beats[b])}, позиция ${map.beats[b] / 1000} с")
                        }
                    }
                    b++
                }
                for (d in map.drops) if (d in (mapPos + 1)..pos) { dropAt = now - (pos - d); dropPower = 1f }
            }
            val sec = map.sectionAt(pos)
            if (sec != section) {
                // вошли в новую часть: естественным ходом — событие для мозга; перемоткой — молча
                if (section >= 0 && mapPos >= 0 && pos >= mapPos && pos - mapPos < 600) {
                    sectionAt = now - (pos - map.sections[sec])
                    if (debugBeats) melos.host.Log.i("Form", "часть $sec с ${map.sections[sec] / 1000} с, энергия ${"%.2f".format(map.sectionEnergy.getOrElse(sec) { 0f })}")
                }
                section = sec
            }
            if (sec >= 0) sectionEnergy += (map.sectionEnergy.getOrElse(sec) { 0.6f } - sectionEnergy) * k(0.03f)   // [Lerp]
            val next = map.nextSection(pos); val bar = map.barMs(pos)
            val anticTarget = if (next != null && next - pos in 0..bar) 1f - (next - pos).toFloat() / bar else 0f
            antic += (anticTarget - antic) * k(if (anticTarget > antic) 0.3f else 0.5f)   // [Lerp]
            mapPos = pos
            bpm = map.bpm
            energy += (map.energyAt(pos) - energy) * k(0.05f)                   // [Lerp]
            tension += (map.tensionAt(pos) - tension) * k(0.2f)                 // [Lerp]
            mapDrop = map.dropAt(pos)
        } else { mapPos = -1L; tension *= 1 - k(0.1f); antic *= 1 - k(0.2f) }

        // яркость тембра: центр масс полос спектра (живых или из карты)
        var sw = 0f; var sb = 0f
        for (i in bands.indices) { sw += i * bands[i]; sb += bands[i] }
        val bright = if (sb > 0.05f) (sw / sb / (bands.size - 1) * 1.8f).coerceIn(0f, 1f) else 0.5f
        brightness += (bright - brightness) * k(0.04f)                          // [Lerp]
        // концовка: последние 8 с трека
        val len = Player.length.value
        val outroTarget = if (playing && len > 30_000 && !Player.isLive(Player.current.value)) ((8000f - (len - Player.positionMs())) / 8000f).coerceIn(0f, 1f) else 0f
        outro += (outroTarget - outro) * k(0.1f)                                // [Lerp]

        // дроп: после взрыва держится в полную силу ~4 с, дальше — по карте (или пока энергия выше обычной)
        val hold = if (now - dropAt < 4000) 1f - (now - dropAt) / 4000f * 0.3f else 0f
        val target = if (playing) max(hold, if (mapDrop >= 0f) mapDrop else intensity * 0.8f) else 0f
        drop += (target - drop) * k(if (target > drop) 0.3f else 0.015f)       // [Lerp]

        // ---- структура трека: живой звук — трекер в реальном времени; карта — предпрогон, чтение по позиции ----
        stDropEvent = false
        if (playing && f != null) {
            structure.update(dt, level, bass, treble)
            stPhase = structure.phase; stCalm = structure.calm; stTension = structure.tension
            stDrop = structure.drop; stInhale = structure.inhale
            if (structure.dropEvent) { stDropEvent = true; stDropStrength = structure.dropStrength }
        } else if (fromMap && map != null) {
            val len = Player.length.value
            if (stMapFor != track && len > 0) { stMapFor = track; stMap = buildStTimeline(map, len); stLastIdx = -1 }
            val tl = stMap
            if (tl != null) {
                val pos = Player.positionMs() - mapOffset                  // тот же сдвиг синхронизации, что у карты
                val idx = (pos / tl.stepMs).toInt().coerceIn(0, tl.n - 1)
                stPhase = BeatStructure.Phase.entries[tl.phase[idx]]
                stCalm = tl.calm[idx]; stTension = tl.tension[idx]; stDrop = tl.drop[idx]
                var aheadDrop = -1
                for (di in tl.dropIdx) {
                    if (di > idx) { aheadDrop = di; break }                // ближайший дроп впереди
                    if (di > stLastIdx) { stDropEvent = true; stDropStrength = 1f; stInhale = 0f }
                }
                stInhale = if (aheadDrop >= 0) {                            // «вдох» за 0.35 с до дропа
                    val ahead = aheadDrop * tl.stepMs - pos
                    if (ahead in 0f..350f) 1f - ahead / 350f else stInhale * (1 - k(0.3f))
                } else stInhale * (1 - k(0.3f))
                stLastIdx = idx
            }
        } else {
            stCalm *= 1 - k(0.1f); stTension *= 1 - k(0.1f); stDrop *= 1 - k(0.05f); stInhale *= 1 - k(0.2f)
        }
        if (debugBeats && (stPhase != stLoggedPhase || stDropEvent)) {
            stLoggedPhase = stPhase
            melos.host.Log.i("Beats", "структура: фаза=$stPhase calm=${"%.2f".format(stCalm)} tension=${"%.2f".format(stTension)}" +
                (if (stDropEvent) " dropEvent сила=${"%.2f".format(stDropStrength)}" else ""))
        }
    }

    /** Предпрогон трекера структуры по спектру карты: сэмпл каждые 50 мс, значения читаем потом по позиции. */
    private fun buildStTimeline(map: melos.player.TrackMap, len: Long): StTimeline? {
        val stepMs = 50f
        val n = (len / stepMs).toInt()
        if (n < 8) return null
        val bs = BeatStructure()
        val scratch = FloatArray(bands.size)
        val calm = FloatArray(n); val tension = FloatArray(n); val dropV = FloatArray(n); val phase = IntArray(n)
        val drops = mutableListOf<Int>()
        for (i in 0 until n) {
            val pos = (i * stepMs).toLong()
            map.spectrumAt(pos, scratch)
            var b = 0f; for (j in 0..3) b += scratch[j]
            var h = 0f; for (j in scratch.size - 8 until scratch.size) h += scratch[j]
            bs.update(stepMs / 1000f, map.levelAt(pos), b / 4f, h / 8f)
            calm[i] = bs.calm; tension[i] = bs.tension; dropV[i] = bs.drop; phase[i] = bs.phase.ordinal
            if (bs.dropEvent) drops += i
        }
        return StTimeline(stepMs, n, calm, tension, dropV, phase, drops.toIntArray())
    }
}

@Composable
fun PulseDriver() = LaunchedEffect(Unit) { while (true) withFrameNanos { Pulse.tick(it) } }

// Фон окна: мягкие пятна цветов обложки медленно плывут, сверху — редкое точечное поле, как небо мозга.
// На бит по полю проходит волна. Без металла и бликов — только свет и точки.
@Composable
fun Backdrop(modifier: Modifier = Modifier) {
    val p = pal
    Canvas(modifier.fillMaxSize()) {
        Pulse.frame.let { }
        val t = System.currentTimeMillis() / 1000f
        drawRect(p.bg)
        if (p.covered) {
            val w = size.width; val h = size.height; val r = max(w, h) * 0.62f
            val k = if (p.dark) 1f else 0.55f
            val calmK = 1f - 0.6f * Pulse.stCalm                  // BREAKDOWN: пятна палитры гаснут
            val blobs = listOf(
                Triple(p.acc, Offset(w * (0.22f + 0.08f * sin(t * 0.13f)), h * (0.30f + 0.10f * cos(t * 0.11f))), 0.30f),
                Triple(p.c2, Offset(w * (0.80f + 0.07f * cos(t * 0.09f)), h * (0.25f + 0.08f * sin(t * 0.15f))), 0.24f),
                Triple(p.c3, Offset(w * (0.55f + 0.10f * sin(t * 0.07f)), h * (0.92f + 0.06f * cos(t * 0.12f))), 0.22f),
            )
            blobs.forEach { (c, o, a) ->
                val alpha = (a + Pulse.bass * 0.08f) * k
                drawCircle(Brush.radialGradient(listOf(c.copy(alpha = alpha), c.copy(alpha = alpha * 0.35f), Color.Transparent), o, r), r, o)
            }
        }
        // точечное поле: два пакетных вызова вместо тысяч отдельных прямоугольников; на бит проходит волна
        if (Look.brain) {
            val step = 22.dp.toPx(); val dot = 1.6.dp.toPx()
            val beat = Pulse.beat()
            val center = Offset(size.width * 0.5f, size.height * 0.42f)
            val front = (1 - beat) * max(size.width, size.height) * 0.9f
            val band = 40.dp.toPx()
            val cols = (size.width / step).toInt() + 1; val rows = (size.height / step).toInt() + 1
            val base = ArrayList<Offset>(cols * rows); val lit = ArrayList<Offset>()
            for (r in 0 until rows) for (c in 0 until cols) {
                val o = Offset(step / 2 + c * step, step / 2 + r * step)
                if (beat > 0f && kotlin.math.abs(hypot(o.x - center.x, o.y - center.y) - front) < band) lit += o else base += o
            }
            val k = if (p.dark) 1f else 0.7f
            drawPoints(base, androidx.compose.ui.graphics.PointMode.Points, lerpC(p.txt, p.acc, 0.35f).copy(alpha = 0.05f * k), dot, androidx.compose.ui.graphics.StrokeCap.Square)
            if (lit.isNotEmpty()) drawPoints(lit, androidx.compose.ui.graphics.PointMode.Points, p.acc.copy(alpha = (0.05f + 0.22f * beat) * k), dot * 1.3f, androidx.compose.ui.graphics.StrokeCap.Square)
        }
    }
}

// Любой клик отзывается кольцом из точек — тот же язык, что у мозга.
object Ripples {
    val list = mutableStateListOf<Pair<Offset, Long>>()
    /** Последнее нажатие в окне — к нему мозг тянет линии, когда трек включают кликом. */
    @Volatile var last: Pair<Offset, Long>? = null
    fun at(o: Offset) { last = o to System.currentTimeMillis(); if (Look.motion) { list += o to System.currentTimeMillis(); if (list.size > 6) list.removeAt(0) } }
}

fun Modifier.rippleCatcher() = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            if (e.type == PointerEventType.Press) e.changes.firstOrNull()?.let { Ripples.at(it.position) }
        }
    }
}

@Composable
fun RippleLayer() {
    val acc = pal.acc
    if (Ripples.list.isEmpty()) return
    Canvas(Modifier.fillMaxSize()) {
        Pulse.frame.let { }
        val now = System.currentTimeMillis()
        Ripples.list.removeAll { now - it.second > 560 }
        for ((o, at) in Ripples.list) {
            val u = ((now - at) / 560f).coerceIn(0f, 1f)
            val e = 1 - (1 - u) * (1 - u) * (1 - u)
            val r = 6.dp.toPx() + 22.dp.toPx() * e
            val s = 3.dp.toPx() * (1 - u * 0.6f)
            for (i in 0 until 10) {
                val a = i / 10f * 2 * PI.toFloat() + u * 0.6f
                drawRect(acc.copy(alpha = (1 - u) * 0.9f), Offset(o.x + cos(a) * r - s / 2, o.y + sin(a) * r - s / 2), androidx.compose.ui.geometry.Size(s, s))
            }
        }
    }
}
