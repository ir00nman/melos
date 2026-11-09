package melos.player

import com.badmanners.murglar.lib.core.model.event.PlayerEvent
import com.badmanners.murglar.lib.core.model.node.NodeType
import com.badmanners.murglar.lib.core.model.radio.BaseRadio
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import com.badmanners.murglar.lib.core.model.track.source.Container
import com.badmanners.murglar.lib.core.model.track.source.Source
import com.badmanners.murglar.lib.core.network.NetworkRequest
import com.badmanners.murglar.lib.core.network.ResponseConverters
import melos.builtin.FTrack
import melos.core.Paths
import melos.core.Settings
import melos.core.json
import melos.host.HttpNetwork
import melos.host.Log
import melos.host.Services
import melos.host.Toasts
import melos.library.Library
import melos.library.Snap
import melos.library.snap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.pow

enum class Repeat { OFF, ALL, ONE }

enum class Quality(val label: String, val maxKbps: Int) { BEST("Лучшее", 100_000), NORMAL("Обычное", 192), ECONOMY("Экономия", 128) }

// Очередь, переходы, волны и всё, что Murglar делает вокруг плеера; звук — VlcEngine.
object Player {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = Settings.store

    private var engine: VlcEngine? = null
    internal val debugEngine get() = engine                           // отладка: EqProbe
    val available get() = VlcEngine.found

    private val _queue = MutableStateFlow<List<BaseTrack>>(emptyList()); val queue = _queue.asStateFlow()
    private val _index = MutableStateFlow(-1); val index = _index.asStateFlow()
    private val _current = MutableStateFlow<BaseTrack?>(null); val current = _current.asStateFlow()
    private val _status = MutableStateFlow(Status.IDLE); val status = _status.asStateFlow()
    private val _time = MutableStateFlow(0L); val time = _time.asStateFlow()
    private val _length = MutableStateFlow(0L); val length = _length.asStateFlow()
    private val _nowPlaying = MutableStateFlow<String?>(null); val nowPlaying = _nowPlaying.asStateFlow()
    private val _context = MutableStateFlow<String?>(null); val context = _context.asStateFlow()
    private val _radio = MutableStateFlow<BaseRadio?>(null); val radio = _radio.asStateFlow()

    val volume = MutableStateFlow((store.long("volume")?.toInt() ?: 80).coerceIn(0, 100))
    val shuffle = MutableStateFlow(store.bool("shuffle") ?: false)
    val repeat = MutableStateFlow(store.string("repeat")?.let { runCatching { Repeat.valueOf(it) }.getOrNull() } ?: Repeat.OFF)
    val rate = MutableStateFlow(1f)
    val quality = MutableStateFlow(store.string("quality")?.let { runCatching { Quality.valueOf(it) }.getOrNull() } ?: Quality.BEST)
    // как в Murglar: звук идёт из VLC прямо в систему; через Melos — по желанию (живой спектр для мозга)
    val tapAudio = MutableStateFlow(store.bool("tapAudio.v5") ?: true)        // звук через Melos: эквалайзер VLC применяется сразу (VLC напрямую — с задержкой буфера)
    /** Выравнивание громкости по gain/peak трека (ReplayGain / EBU R128 от сервиса). */
    val normalize = MutableStateFlow(store.bool("normalize.v2") ?: false)   // по умолчанию выкл., как исходный мастер
    // Эквалайзер как в Murglar (тот же эквалайзер VLC): вкл/выкл отдельно от значений, пресет загружается как есть
    // (с его усилением), движение полосы переключает на «Свой» ("custom").
    val eqOn = MutableStateFlow(store.bool("eq.on") ?: (store.string("eq.preset") != null))
    val eqPreset = MutableStateFlow(store.string("eq.preset") ?: "custom")
    val eqAmps = MutableStateFlow(store.string("eq.amps")?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: List(10) { 0f })   // без округления: значения из Murglar дробные
    // шкала усиления сменилась (раньше хранилось по-VLC, с +12 дБ за «без изменений») — старое значение сбрасываем
    // усиление — в шкале VLC, как в Murglar: число уходит в VLC как есть, «без изменений» = +12
    val eqPreamp = MutableStateFlow(store.double("eq.preamp")?.toFloat() ?: VLC_UNITY_PREAMP)

    private val _sleepAt = MutableStateFlow<Long?>(null); val sleepAt = _sleepAt.asStateFlow()
    private var sleepJob: Job? = null

    @Volatile private var timeAt = 0L

    /** Позиция, которую сейчас слышно: время VLC, интерполированное между его обновлениями, минус буфер вывода. */
    fun positionMs(): Long {
        val base = _time.value
        val extra = if (_status.value == Status.PLAYING && timeAt > 0) (System.currentTimeMillis() - timeAt).coerceIn(0, 1000) else 0
        return base + extra - (if (tapping) engine?.bufferedMs ?: 0 else 0)
    }

    private var loadJob: Job? = null
    private var failures = 0
    private var startedAt = 0L
    private var order: List<Int> = emptyList()       // порядок при перемешивании: индексы очереди

    fun init() {
        if (available) initEngine()        // без VLC всё равно подписываемся: после установки плеер поднимется на лету
        volume.onEach { applyVolume(); store.put("volume", it) }.launchIn(scope)
        normalize.onEach { applyVolume(); store.put("normalize.v2", it) }.launchIn(scope)
        _current.onEach { applyVolume() }.launchIn(scope)
        shuffle.onEach { store.put("shuffle", it); rebuildOrder() }.launchIn(scope)
        repeat.onEach { store.put("repeat", it.name) }.launchIn(scope)
        quality.onEach { store.put("quality", it.name) }.launchIn(scope)
        rate.onEach { engine?.setRate(it) }.launchIn(scope)
        // одно применение за раз и не чаще ~25 раз в секунду: VLC перестраивает фильтр на каждое изменение
        combine(eqOn, eqPreset, eqAmps, eqPreamp) { on, p, a, pre -> EqState(on, p, a, pre) }.conflate().onEach { st ->
            store.put("eq.v2", true); store.put("eq.on", st.on); store.put("eq.preset", st.preset); store.put("eq.amps", st.amps.joinToString(",")); store.put("eq.preamp", st.preamp)
            applyEq(st)
            // каждое применение перезапускает фильтры VLC; при выводе самим VLC новое слышно только после его буфера,
            // и частые перезапуски теряются — там применяем реже, через Melos (буфер 100 мс) — почти сразу
            delay(if (engine?.tapping == true) 40 else 250)
        }.launchIn(scope)
        combine(_queue, _index) { q, i -> q to i }.debounce(800).onEach { (q, i) -> saveQueue(q, i) }.launchIn(scope)
        scope.launch { restoreQueue() }
    }

    /** Движок создаётся отдельно от подписок настроек: после установки VLC его можно поднять без перезапуска. */
    private fun initEngine() {
        if (engine != null) return
        engine = newEngine(tapAudio.value)
    }

    /** «Проверить снова» после установки VLC: true — плеер заработал без перезапуска приложения. */
    fun recheckVlc(): Boolean {
        if (engine != null) return true
        if (!VlcEngine.rediscover()) return false
        initEngine()
        Toasts.shortNotify("VLC найден — плеер готов")
        return true
    }

    // MELOS_SILENT=1 (отладка): весь тракт работает, но в устройство идёт тишина
    private val silent = System.getenv("MELOS_SILENT") == "1"

    private fun newEngine(tap: Boolean): VlcEngine {
        var e = VlcEngine(tap || silent, silent)
        // вывод через Melos не поднялся (Java Sound не дал линию) — молчание недопустимо: играем напрямую через VLC
        if ((tap || silent) && !e.tapping) {
            Log.w("Player", "вывод через Melos недоступен — VLC напрямую")
            if (!silent) Toasts.error("«Звук через Melos» не поднялся на этой системе — играю напрямую через VLC")
            e.release()
            e = VlcEngine(false, silent)
        }
        return e.apply {
        e.onFinished = { scope.launch { onTrackEnd(natural = true) } }
        e.onError = { scope.launch { onEngineError() } }
        e.status.onEach { if (System.getenv("MELOS_EQTEST") == "1") Log.i("Status", it.name); _status.value = it }.launchIn(scope)
        e.time.onEach { _time.value = it; timeAt = System.currentTimeMillis() }.launchIn(scope)
        e.length.onEach { if (it > 0) _length.value = it }.launchIn(scope)
        e.nowPlaying.onEach { _nowPlaying.value = it }.launchIn(scope)
        e.setVolume(effectiveVolume())
        applyEq(EqState(eqOn.value, eqPreset.value, eqAmps.value, eqPreamp.value), e)
        }
    }

    /** Смена режима вывода звука пересоздаёт плеер — callback у libVLC ставится только до старта. */
    fun setTap(on: Boolean) {
        tapAudio.value = on; store.put("tapAudio.v5", on)
        val resumeAt = _status.value.takeIf { it == Status.PLAYING || it == Status.PAUSED }
        val pos = _time.value
        engine?.release()
        _status.value = Status.IDLE                      // новый движок пуст: старый статус (например, PAUSED) больше не правда
        engine = newEngine(on)
        if (_current.value != null && resumeAt != null) playIndex(_index.value, pos)   // продолжаем и после паузы
    }

    val tapping get() = engine?.tapping == true

    private fun gainFactor(t: BaseTrack?): Float {
        if (!normalize.value || t == null) return 1f
        val db = t.gain?.let { Regex("[-+]?\\d+(\\.\\d+)?").find(it)?.value?.toFloatOrNull() } ?: return 1f
        var f = 10f.pow(db / 20f)
        t.peak?.takeIf { it > 0 }?.let { f = minOf(f, (1.0 / it).toFloat()) }
        return f.coerceIn(0.25f, 2f)
    }

    // VLC переводит проценты в усиление по кубу ((v/100)³: 80 % = −5,8 дБ), поэтому поправку в разах
    // вносим через кубический корень — иначе −6 дБ выравнивания превращались в −24 дБ.
    // Эквалайзер на громкость не влияет никак — как в Murglar: усиление и полосы уходят в VLC как есть.
    private fun effectiveVolume() = (volume.value * gainFactor(_current.value).toDouble().pow(1.0 / 3).toFloat()).roundToInt().coerceIn(0, 150)
    private fun applyVolume() { engine?.setVolume(effectiveVolume()) }

    /** Насколько итоговая кривая эквалайзера выходит выше исходного уровня, дБ (> 0 — громкие места будут перегружаться). */
    fun eqOverDb(amps: List<Float> = eqAmps.value, preamp: Float = eqPreamp.value) =
        PcmEqualizer.peakDb(amps) + preamp - VLC_UNITY_PREAMP

        data class EqState(val on: Boolean, val preset: String, val amps: List<Float>, val preamp: Float)

    // Эквалайзер как в Murglar: полосы и усиление передаются в VLC как есть (у VLC «без изменений» = усиление +12,
    // вход фильтра внутри ослаблен в 4 раза) — в обоих режимах вывода.
    private fun applyEq(st: EqState, target: VlcEngine? = engine) {
        val e = target ?: return
        Log.i("Eq", "применяю: ${if (st.on) "вкл" else "выкл"}, усиление ${st.preamp}, полосы ${st.amps.joinToString(" ") { "%.0f".format(it) }}, " +
            if (e.tapping) "через Melos" else "VLC напрямую")
        e.pcmEq.set(false, 0f, emptyList())                          // свой эквалайзер не нужен — везде эквалайзер VLC, как в Murglar
        if (!st.on) { e.setEqualizer(null); return }
        val q = VlcEngine.factory.equalizer().newEqualizer()
        q.setPreamp(st.preamp.coerceIn(-20f, 20f))
        q.setAmps(FloatArray(q.bandCount()) { i -> st.amps.getOrElse(i) { 0f }.coerceIn(-20f, 20f) })
        e.setEqualizer(q)
    }

    const val VLC_UNITY_PREAMP = 12f

    /** Настройки эквалайзера установленного Murglar Desktop (его файл настроек), если он есть на этом компьютере. */
    private val murglarPrefs: java.io.File? get() {
        val user = System.getProperty("user.home")
        return listOf(
            "$user/Library/Application Support/Murglar/data/config/preferences/murglar.json",
            "${System.getenv("APPDATA") ?: "$user/AppData/Roaming"}/Murglar/data/config/preferences/murglar.json",
            "$user/.local/share/Murglar/data/config/preferences/murglar.json",
        ).map { java.io.File(it) }.firstOrNull { it.isFile }
    }

    val hasMurglarEq: Boolean get() = murglarPrefs != null

    /** Ставит те же числа, что в эквалайзере Murglar: шкала у нас та же (VLC как есть), значения — без округления. */
    fun importMurglarEq(): Boolean = runCatching {
        val o = json.parseToJsonElement(murglarPrefs!!.readText()) as kotlinx.serialization.json.JsonObject
        fun num(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull()
        val amps = List(10) { i -> num("equalizer_band_$i") ?: 0f }
        eqAmps.value = amps
        eqPreamp.value = num("equalizer_preamp") ?: VLC_UNITY_PREAMP
        eqPreset.value = "custom"
        eqOn.value = (o["equalizer_enabled"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "false"
        Log.i("Eq", "из Murglar: усиление ${eqPreamp.value}, полосы $amps")
    }.onFailure { Log.w("Eq", "не прочитал настройки Murglar: ${it.message}") }.isSuccess

    /** Пресет VLC: кривая полос и усиление как есть (округлено до 1 дБ). */
    fun loadEqPreset(name: String) {
        if (name != "custom") runCatching { VlcEngine.preset(name) }.getOrNull()?.let { p ->
            eqAmps.value = p.amps().map { kotlin.math.round(it) }
            eqPreamp.value = kotlin.math.round(p.preamp()).coerceIn(-20f, 20f)
        }
        eqPreset.value = name
    }


    fun playList(tracks: List<BaseTrack>, start: Int = 0, context: String? = null, radio: BaseRadio? = null) {
        if (tracks.isEmpty()) return
        _queue.value = tracks
        _context.value = context
        _radio.value = radio
        rebuildOrder(keep = start)
        playIndex(start)
    }

    fun playRadio(radio: BaseRadio, context: String = radio.nodeName) {
        loadJob?.cancel()
        _status.value = Status.OPENING
        loadJob = scope.launch {
            runCatching {
                val service = Services.forNode(radio) ?: error("Нет сервиса для волны")
                val update = service.murglar.nodeResolver.getRadioContent(radio, null)
                val tracks = update.nextTracks.filterIsInstance<BaseTrack>().filter { !Library.isDisliked(it) }
                if (tracks.isEmpty()) error("Волна пуста")
                playList(tracks, 0, context, update.updatedRadio)
            }.onFailure { _status.value = Status.ERROR; Toasts.error("Волна: ${it.message}") }
        }
    }

    fun playNext(track: BaseTrack) = insert(listOf(track), afterCurrent = true)
    fun enqueue(tracks: List<BaseTrack>) = insert(tracks, afterCurrent = false)

    private fun insert(tracks: List<BaseTrack>, afterCurrent: Boolean) {
        if (_queue.value.isEmpty()) { playList(tracks); return }
        val q = _queue.value.toMutableList()
        val at = if (afterCurrent) _index.value + 1 else q.size
        q.addAll(at, tracks)
        _queue.value = q
        rebuildOrder(keep = _index.value)
        Toasts.shortNotify(if (afterCurrent) "Сыграет следующим" else "Добавлено в очередь: ${tracks.size}")
    }

    fun removeAt(i: Int) {
        val q = _queue.value.toMutableList()
        if (i !in q.indices) return
        q.removeAt(i)
        _queue.value = q
        when {
            i < _index.value -> _index.value -= 1
            i == _index.value -> if (q.isEmpty()) stop() else playIndex(i.coerceAtMost(q.lastIndex))
        }
        rebuildOrder(keep = _index.value)
    }

    fun move(from: Int, to: Int) {
        val q = _queue.value.toMutableList()
        if (from !in q.indices || to !in q.indices) return
        val cur = q[_index.value.coerceIn(q.indices)]
        q.add(to, q.removeAt(from))
        _queue.value = q
        _index.value = q.indexOf(cur)
        rebuildOrder(keep = _index.value)
    }

    fun clearUpcoming() {
        val i = _index.value
        _queue.value = _queue.value.take(i + 1)
        _radio.value = null
        rebuildOrder(keep = i)
    }

    private fun rebuildOrder(keep: Int = _index.value) {
        val n = _queue.value.size
        // волна идёт строго по порядку: «перемешать» на неё не действует
        order = if (shuffle.value && n > 1 && _radio.value == null) {
            val rest = (0 until n).filter { it != keep }.shuffled()
            if (keep in 0 until n) listOf(keep) + rest else rest
        } else (0 until n).toList()
    }

    private fun neighbour(step: Int): Int? {
        val q = _queue.value
        if (q.isEmpty()) return null
        val pos = order.indexOf(_index.value)
        val next = pos + step
        return when {
            next in order.indices -> order[next]
            repeat.value == Repeat.ALL && step > 0 -> order.first()
            repeat.value == Repeat.ALL && step < 0 -> order.last()
            else -> null
        }
    }


    fun toggle() = when (_status.value) {
        Status.PLAYING -> engine?.pause()
        Status.PAUSED -> engine?.resume()
        // грузится — не мешаем; но если загрузка уже умерла, а VLC так и не начал (застряли в OPENING/BUFFERING),
        // «Плей» не должен молчать: перезапускаем трек с той же позиции
        Status.OPENING, Status.BUFFERING -> if (loadJob?.isActive != true && _index.value >= 0) playIndex(_index.value, startPosition()) else Unit
        // трек ещё не загружен — с позиции, куда перемотали (или где остановились до перезапуска)
        Status.IDLE, Status.ENDED, Status.ERROR -> if (_index.value >= 0) playIndex(_index.value, startPosition()) else Unit
    }

    fun next() { scope.launch { onTrackEnd(natural = false) } }

    fun prev() {
        if (_time.value > 3000) { seek(0); return }
        neighbour(-1)?.let { playIndex(it) } ?: seek(0)
    }

    fun seek(ms: Long) {
        if (_length.value <= 0) return
        val t = ms.coerceIn(0, _length.value)
        if (loaded) engine?.seek(t) else _time.value = t                // не загружен — запомним, «Плей» начнёт отсюда
    }

    /** Трек открыт в VLC: перемотку можно отдать ему. Иначе позиция просто запоминается. */
    private val loaded get() = _status.value !in setOf(Status.IDLE, Status.ENDED, Status.ERROR)

    private fun startPosition(): Long {
        val t = _time.value
        return if (t in 1 until _length.value - 1000) t else 0          // доиграл до конца — сначала
    }
    fun seekBy(delta: Long) = seek(_time.value + delta)
    fun stop() { engine?.stop(); _status.value = Status.IDLE }

    fun jump(i: Int) { if (i in _queue.value.indices) { playIndex(i) } }

    private suspend fun onTrackEnd(natural: Boolean) {
        reportEnd()
        if (natural && repeat.value == Repeat.ONE) { playIndex(_index.value); return }
        maybeExtendRadio(force = true)
        // дальше — первый трек, который не в «Не нравится» (выбранный вручную всё равно играет)
        var next = neighbour(+1)
        var guard = _queue.value.size
        while (next != null && guard-- > 0 && Library.isDisliked(_queue.value[next])) {
            _index.value = next
            next = neighbour(+1)
        }
        if (next != null) playIndex(next)
        else { _status.value = Status.ENDED; if (!natural) engine?.stop() }
    }

    private suspend fun onEngineError() {
        val t = _current.value
        Log.w("Player", "VLC error on ${t?.tag}")
        failures++
        if (failures >= 3) { Toasts.error("Не удаётся воспроизвести несколько треков подряд"); failures = 0; return }
        Toasts.error("Не играет: ${t?.title ?: "трек"} — следующий")
        delay(900)
        onTrackEnd(natural = false)
    }

    // Волна подгружается заранее, чтобы следующий трек уже был в очереди.
    private suspend fun maybeExtendRadio(force: Boolean = false) {
        val radio = _radio.value ?: return
        val left = _queue.value.size - 1 - _index.value
        if (left > 2 && !force) return
        if (left > 0 && force) return
        runCatching {
            val service = Services.forNode(radio) ?: return
            val update = service.murglar.nodeResolver.getRadioContent(radio, null)
            _radio.value = update.updatedRadio
            val known = _queue.value.map { it.mediaId }.toSet()
            val fresh = update.nextTracks.filterIsInstance<BaseTrack>().filter { it.mediaId !in known && !Library.isDisliked(it) }
            // новая порция — только в конец, сыгранное не трогаем (раньше порядок пересобирался и треки шли по кругу)
            if (fresh.isNotEmpty()) { val from = _queue.value.size; _queue.value = _queue.value + fresh; order = order + (from until _queue.value.size) }
        }.onFailure { Log.w("Player", "radio: ${it.message}") }
    }

    fun playIndex(i: Int, startMs: Long = 0) {
        val track = _queue.value.getOrNull(i) ?: return
        if (engine == null) {            // VLC не установлен: не зависаем в OPENING, а объясняем что делать
            _status.value = Status.IDLE
            Toasts.error("VLC не найден — установите VLC 3 (64-бит)")
            return
        }
        _index.value = i
        _current.value = track
        _length.value = track.durationMs
        _time.value = startMs
        _nowPlaying.value = null
        _status.value = Status.OPENING
        loadJob?.cancel()
        loadJob = scope.launch {
            val ok = runCatching {
                val mrl = resolve(track)
                engine?.play(mrl, startMs)
                if (!isLive(track)) TrackAnalysis.request(track.mediaId, mrl)        // карта трека для анимаций
                startedAt = System.currentTimeMillis()
                failures = 0
                Library.history.addFirst(track.snap())
                reportStart(track)
                maybeExtendRadio()
            }
            ok.onFailure {
                if (it is kotlinx.coroutines.CancellationException) return@launch
                Log.e("Player", "resolve ${track.tag}", it)
                onEngineError()
            }
        }
    }


    private fun pickSources(track: BaseTrack): List<Source> {
        val max = quality.value.maxKbps
        val sorted = track.sources.sortedWith(compareByDescending<Source> { it.qualityTier.ordinal }.thenByDescending { it.bitrate.value ?: 0 })
        val fit = sorted.filter { (it.bitrate.value ?: if (max >= 1000) 0 else Int.MAX_VALUE) <= max }
        return (fit + sorted).distinct()
    }

    suspend fun resolve(track: BaseTrack, forDownload: Boolean = false): String = resolveWithExt(track, forDownload).first

    /** Адрес потока и расширение файла по тому источнику, который реально отдал сервис (FLAC, AAC в MP4, MP3…). */
    suspend fun resolveWithExt(track: BaseTrack, forDownload: Boolean = false): Pair<String, String?> {
        val service = Services.forTrack(track) ?: error("Сервис трека не загружен")
        val murglar = service.murglar
        var last: Throwable? = null
        // загрузка — всегда в лучшем качестве (без предела из настроек); HLS скачать нельзя — он в конце
        val sources = if (forDownload) track.sources.sortedWith(compareBy<Source> { if (it.container == Container.HLS) 1 else 0 }
            .thenByDescending { it.qualityTier.ordinal }.thenByDescending { it.bitrate.value ?: 0 }) else pickSources(track)
        for (source in sources) {
            try {
                val resolved = murglar.resolveSourceForUrl(track, source)
                val url = resolved.url ?: continue
                if (url.startsWith("data:")) return writeTemp(track, resolved.data.data, resolved.extension.value) to resolved.extension.value
                if (murglar.decryptor.isEncrypted(track, source)) return decryptToFile(track, resolved) to resolved.extension.value
                return url to resolved.extension.value.takeIf { it != "unknown" }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
            }
        }
        throw last ?: IllegalStateException("У трека нет доступных источников")
    }

    private suspend fun decryptToFile(track: BaseTrack, source: Source): String {
        val murglar = Services.forTrack(track)!!.murglar
        val bytes = HttpNetwork.execute(NetworkRequest.Builder(source.url!!).build(), ResponseConverters.asByteArray()).result
        val chunk = murglar.decryptor.decryptionChunkSize.takeIf { it > 0 } ?: bytes.size
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var off = 0
        while (off < bytes.size) {
            val len = minOf(chunk, bytes.size - off)
            out.write(murglar.decryptor.decrypt(bytes, off, len, source.url!!))
            off += len
        }
        return writeTemp(track, out.toByteArray(), source.extension.value)
    }

    private fun writeTemp(track: BaseTrack, data: ByteArray, ext: String): String {
        val dir = File(Paths.cache, "stream").apply { mkdirs() }
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(6)?.forEach { it.delete() }   // держим пару последних
        val f = File(dir, "${track.mediaId.hashCode().toUInt()}.$ext")
        f.writeBytes(data)
        return f.toPath().toUri().toString()          // file:///… — VLC не открывает file:/… от File.toURI()
    }


    @Suppress("UNCHECKED_CAST")
    private fun supports(track: BaseTrack, event: kotlin.reflect.KClass<*>): Boolean {
        val r = Services.forTrack(track)?.murglar?.nodeResolver ?: return false
        return runCatching { r.supportedEventsMapping[track.nodeType]?.contains(event as kotlin.reflect.KClass<out com.badmanners.murglar.lib.core.model.event.Event>) == true }.getOrDefault(false)
    }

    private fun reportStart(track: BaseTrack) {
        if (!supports(track, PlayerEvent.TrackStart::class)) return
        scope.launch { runCatching { Services.forTrack(track)!!.murglar.nodeResolver.handleEvent(PlayerEvent.TrackStart, track) } }
    }

    private fun reportEnd() {
        val track = _current.value ?: return
        if (startedAt == 0L || !supports(track, PlayerEvent.TrackEnd::class)) return
        val ms = _time.value.toInt()
        scope.launch { runCatching { Services.forTrack(track)!!.murglar.nodeResolver.handleEvent(PlayerEvent.TrackEnd(ms), track) } }
    }


    fun isLiked(track: BaseTrack) = melos.library.ServiceLikes.isLiked(track.mediaId) || Library.isFavorite(track.mediaId)

    /** Момент последнего неудачного лайка: сердца в UI коротко вздрагивают. */
    @Volatile var likeFailedAt = 0L

    fun toggleLike(track: BaseTrack) {
        val liked = isLiked(track)
        val r = Services.forTrack(track)?.murglar?.nodeResolver
        val canLike = r != null && runCatching { r.supportsLikes && NodeType.TRACK in r.likesMapping && track.nodeParameters.isLikeable }.getOrDefault(false)
        if (!canLike) {
            if (liked) Library.favorites.remove(track.mediaId) else Library.favorites.addFirst(track.snap())
            return
        }
        melos.library.ServiceLikes.mark(track, !liked)
        if (liked) Library.favorites.remove(track.mediaId)
        scope.launch {
            runCatching { r!!.likeNode(track, !liked) }.onFailure {
                melos.library.ServiceLikes.mark(track, liked)                  // откатываем отметку
                likeFailedAt = System.currentTimeMillis()
                Toasts.error("Лайк в сервисе не прошёл: ${it.message}")
            }
        }
    }


    fun toggleDislike(track: BaseTrack) {
        val on = !Library.isDisliked(track)
        if (on) {
            Library.dislikes.addFirst(track.snap())
            if (isLiked(track)) toggleLike(track)                                 // не нравится — значит и не «нравится»
            Toasts.shortNotify("Больше не включу «${track.title}» сам — включить можно вручную")
        } else {
            Library.dislikes.items.value.filter { it.mediaId == track.mediaId || (it.title == track.fullTitle && it.artists == track.artists) }
                .forEach { Library.dislikes.remove(it.mediaId) }
            Library.dislikes.remove(track.mediaId)
        }
        scope.launch {
            val murglar = Services.forTrack(track)?.murglar
            runCatching {
                when {
                    murglar is melos.builtin.yandex.YandexMurglar -> murglar.dislike(track as FTrack, on)
                    on && supports(track, PlayerEvent.DislikeTrack::class) -> murglar!!.nodeResolver.handleEvent(PlayerEvent.DislikeTrack, track)
                }
            }.onFailure { Log.w("Player", "dislike: ${it.message}") }
        }
        if (on && _current.value?.mediaId == track.mediaId) next()                // играет сейчас — дальше
    }


    fun sleepIn(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) { _sleepAt.value = null; return }
        val at = System.currentTimeMillis() + minutes * 60_000L
        _sleepAt.value = at
        sleepJob = scope.launch {
            delay(at - System.currentTimeMillis())
            // мягко уводим громкость за 8 секунд и ставим паузу
            val v = effectiveVolume()
            for (k in 1..16) { engine?.setVolume((v * (1 - k / 16f)).toInt()); delay(500) }
            engine?.pause(); applyVolume(); _sleepAt.value = null
            Toasts.shortNotify("Спокойной ночи — таймер сна остановил музыку")
        }
    }


    @Serializable private data class SavedQueue(val items: List<Snap>, val index: Int, val time: Long, val context: String?)

    private fun saveQueue(q: List<BaseTrack>, i: Int) {
        val items = q.take(500).map { it.snap() }
        val saved = SavedQueue(items, i.coerceAtMost(items.lastIndex), _time.value, _context.value)
        runCatching { Paths.file("queue.json").writeText(json.encodeToString(SavedQueue.serializer(), saved)) }
    }

    private suspend fun restoreQueue() {
        val saved = runCatching { json.decodeFromString(SavedQueue.serializer(), Paths.file("queue.json").readText()) }.getOrNull() ?: return
        delay(1500)   // сервисы успевают выполнить onCreate
        val tracks = Library.resolve(saved.items)
        if (tracks.isEmpty()) return
        val cur = saved.items.getOrNull(saved.index)?.mediaId
        val i = tracks.indexOfFirst { it.mediaId == cur }.coerceAtLeast(0)
        _queue.value = tracks
        _index.value = i
        _current.value = tracks[i]
        _length.value = tracks[i].durationMs
        _time.value = saved.time                                         // «Плей» продолжит с того же места
        _context.value = saved.context
        rebuildOrder(keep = i)
    }

    fun isLive(t: BaseTrack?) = (t as? FTrack)?.live == true || (t != null && t.durationMs == 0L && _length.value <= 0)

    fun shutdown() { saveQueue(_queue.value, _index.value); engine?.release() }
}
