package melos.player

import com.sun.jna.Pointer
import melos.host.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.media.Meta
import uk.co.caprica.vlcj.player.base.Equalizer
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.callback.AudioCallbackAdapter
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.roundToInt

enum class Status { IDLE, OPENING, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

// Обёртка над libVLC (vlcj). Два режима вывода:
//  direct — звук выводит сам VLC (любое устройство, минимум задержек);
//  tap    — VLC отдаёт PCM нам, мы играем его через Java Sound и по пути считаем спектр для «мозга».
class VlcEngine(private val tap: Boolean, private val silent: Boolean = false) {   // silent — тишина в устройство (диагностика)

    companion object {
        /** Найдена ли библиотека VLC. Переопределяется без перезапуска: [rediscover] после установки VLC. */
        var found: Boolean = false; private set
        fun rediscover(): Boolean {
            found = runCatching { NativeDiscovery().discover() }.getOrDefault(false)
            return found
        }

        init { rediscover() }

        /** Эквалайзер vlcj, спрятанный за [PlayerEqualizer] — Player.kt не знает про vlcj. */
        private class VlcjEq(val native: Equalizer) : PlayerEqualizer {
            override fun bandCount() = native.bandCount()
            override fun setPreamp(v: Float) { native.setPreamp(v) }
            override fun setAmps(a: FloatArray) { native.setAmps(a) }
            override fun amps(): FloatArray = native.amps()
            override fun preamp(): Float = native.preamp()
        }

        /** Та же поверхность, что у vlcj-фабрики, но типы — платформенные оболочки. */
        object EqFactory {
            fun newEqualizer(): PlayerEqualizer = VlcjEq(nativeFactory.equalizer().newEqualizer())
            fun newEqualizer(name: String): PlayerEqualizer = VlcjEq(nativeFactory.equalizer().newEqualizer(name))
            fun presets(): List<String> = nativeFactory.equalizer().presets()
            fun bands(): List<Float> = nativeFactory.equalizer().bands()
        }

        /** Та же цепочка вызовов, что и у libVLC на Android: factory.equalizer().newEqualizer() */
        val factory: EqSupport get() = EqSupport
        object EqSupport { fun equalizer(): EqFactory = EqFactory }
        fun preset(name: String): PlayerEqualizer = EqFactory.newEqualizer(name)
        val presets: List<String> get() = if (found) EqFactory.presets() else emptyList()
        val bands: List<Float> get() = if (found) EqFactory.bands() else emptyList()
        internal val nativeFactory: MediaPlayerFactory by lazy {
            numericLocaleC()
            // MELOS_VLC_ARGS — отладка: дополнительные параметры libVLC через пробел
            val extra = System.getenv("MELOS_VLC_ARGS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
            MediaPlayerFactory(*(listOf("--no-video", "--quiet", "--http-reconnect", "--network-caching=1500") + extra).toTypedArray())
        }
        fun version(): String = runCatching { nativeFactory.application().version() }.getOrDefault("?")
        /**
         * libVLC передаёт полосы эквалайзера строкой через snprintf("%f"), а фильтр читает её с точкой.
         * При русской локали (запуск из терминала с LANG=ru_RU) получалось "12,0000000 0,0000000 …": каждая запятая
         * сдвигала полосы — 500 Гц попадали на 8 кГц, всё выше 500 Гц не доходило вовсе. Числа процесса — с точкой.
         */
        private fun numericLocaleC() = runCatching {
            val os = System.getProperty("os.name").lowercase()
            val lcNumeric = if (os.contains("linux")) 1 else 4                // LC_NUMERIC: Linux — 1, macOS и Windows — 4
            val lib = com.sun.jna.Native.load(if (os.contains("win")) "msvcrt" else "c", CLib::class.java)
            Log.i("Vlc", "LC_NUMERIC: ${lib.setlocale(lcNumeric, null)} → ${lib.setlocale(lcNumeric, "C")}")
        }.onFailure { Log.w("Vlc", "не удалось выставить LC_NUMERIC=C: ${it.message}") }

        private const val RATE = 44100
        private const val CH = 2
        private const val LIMITER_DELAY = RATE / 200                 // 5 мс упреждения лимитера
    }

    val player: MediaPlayer = nativeFactory.mediaPlayers().newMediaPlayer()

    private interface CLib : com.sun.jna.Library { fun setlocale(category: Int, locale: String?): String? }

    private val _status = MutableStateFlow(Status.IDLE); val status = _status.asStateFlow()
    private val _time = MutableStateFlow(0L); val time = _time.asStateFlow()
    private val _length = MutableStateFlow(0L); val length = _length.asStateFlow()
    private val _meta = MutableStateFlow<String?>(null); val nowPlaying = _meta.asStateFlow()   // ICY-метаданные радио

    var onFinished: () -> Unit = {}
    var onError: () -> Unit = {}

    @Volatile private var gain = 1f
    private var line: SourceDataLine? = null
    /** Диагностика: сырой PCM из VLC и коэффициент громкости, который VLC сам передаёт в вывод. */
    @Volatile var probe: ((ShortArray, Int) -> Unit)? = null
    @Volatile var vlcVolume = -1f; private set
    /** Эквалайзер в нашем тракте (работает, когда звук идёт через Melos). */
    val pcmEq = PcmEqualizer(RATE, CH)
    /** Сколько звука ждёт в буфере вывода — позиция, которую слышно, меньше времени VLC на столько. */
    @Volatile var bufferedMs = 0L; private set

    init {
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun opening(mp: MediaPlayer) { _status.value = Status.OPENING; _meta.value = null }
            // BUFFERING — только пока грузится (OPENING); на паузе VLC тоже шлёт buffering (после seek, при долёте сети) —
            // иначе пауза перезатиралась на BUFFERING, а из него «Плей» не работал
            override fun buffering(mp: MediaPlayer, c: Float) {
                if (c < 100f && _status.value !in setOf(Status.PLAYING, Status.PAUSED)) _status.value = Status.BUFFERING
            }
            override fun playing(mp: MediaPlayer) { _status.value = Status.PLAYING }
            override fun paused(mp: MediaPlayer) { _status.value = Status.PAUSED }
            override fun stopped(mp: MediaPlayer) { if (_status.value != Status.ENDED) _status.value = Status.IDLE }
            override fun finished(mp: MediaPlayer) { _status.value = Status.ENDED; onFinished() }
            override fun error(mp: MediaPlayer) { _status.value = Status.ERROR; onError() }
            override fun timeChanged(mp: MediaPlayer, t: Long) { _time.value = t }
            override fun lengthChanged(mp: MediaPlayer, l: Long) { _length.value = l }
        })
        player.events().addMediaEventListener(object : uk.co.caprica.vlcj.media.MediaEventAdapter() {
            override fun mediaMetaChanged(media: uk.co.caprica.vlcj.media.Media, metaType: Meta) {
                if (metaType == Meta.NOW_PLAYING) _meta.value = media.meta().get(Meta.NOW_PLAYING)?.takeIf { it.isNotBlank() }
            }
        })
        if (tap) installTap()
    }

    /** Лимитер в конце тракта: пики после эквалайзера и громкости снимает без клиппинга. */
    private val limiter = Limiter(RATE, CH)
    /** MELOS_TEE=<файл> (отладка): копия выходных байтов (s24le/s16le, 44,1 кГц, стерео). */
    private val tee = System.getenv("MELOS_TEE")?.let { java.io.FileOutputStream(it) }
    private val tilt = Tilt()

    /** Диагностика в лог раз в 2 с: насколько верх (4–16 кГц) громче середины (250–1000 Гц) в том, что уходит в звуковую карту. */
    private class Tilt {
        private val n = 4096
        private val buf = FloatArray(n); private var fill = 0
        private var hi = 0.0; private var mid = 0.0; private var frames = 0
        fun push(x: FloatArray, count: Int) {
            for (i in 0 until count) {
                buf[fill++] = (x[i * CH] + x[i * CH + 1]) / 2
                if (fill == n) { fill = 0; analyze() }
            }
            frames += count
            if (frames >= RATE * 2 && mid > 0) {
                Log.i("Out", "верх − середина: %+.1f дБ".format(10 * kotlin.math.log10(hi / mid)))
                hi = 0.0; mid = 0.0; frames = 0
            }
        }
        private fun analyze() {
            val re = FloatArray(n) { buf[it] * (0.5f - 0.5f * kotlin.math.cos(2 * Math.PI * it / n).toFloat()) }
            val im = FloatArray(n)
            Analyzer.fft(re, im)
            fun e(lo: Int, hiHz: Int): Double { var s = 0.0; for (k in lo * n / RATE..hiHz * n / RATE) s += re[k] * re[k] + im[k] * im[k]; return s }
            hi += e(4000, 16000); mid += e(250, 1000)
        }
    }

    // Тракт во float от декодера до вывода: VLC → S16 (иначе amem не отдаёт) → float → эквалайзер → громкость → лимитер → дизер → 24 (или 16) бит.
    // Нигде нет промежуточного округления до 16 бит и срезания >0 dBFS — это и делало бас грязным.
    private fun installTap() {
        // на Windows 24-битная линия может открыться, но играть тишиной — там первым пробуем 16-битный вывод
        val order = if (melos.core.Paths.isWindows) listOf(16, 24) else listOf(24, 16)
        val (l, bytesPer) = order.firstNotNullOfOrNull { bits ->
            val format = AudioFormat(RATE.toFloat(), bits, CH, true, false)
            runCatching { AudioSystem.getSourceDataLine(format).apply { open(format, RATE * CH * (bits / 8) / 10) } to bits / 8 }.getOrNull()
        } ?: run { Log.e("Vlc", "Java Sound недоступен"); return }
        Log.i("Vlc", "вывод: ${bytesPer * 8} бит, ${RATE} Гц")
        line = l
        player.audio().callback("S16N", RATE, CH, object : AudioCallbackAdapter() {
            private var bytes = ByteArray(0)
            private var floats = FloatArray(0)
            private var shorts = ShortArray(0)
            private val rnd = java.util.Random()
            private var failed = false
            override fun play(mp: MediaPlayer, samples: Pointer, sampleCount: Int, pts: Long) {
                try { render(samples, sampleCount) } catch (t: Throwable) { if (!failed) { failed = true; Log.e("Vlc", "тракт", t) } }
            }
            private fun render(samples: Pointer, sampleCount: Int) {
                val l = line ?: return
                val n = sampleCount * CH
                if (floats.size < n) { floats = FloatArray(n); shorts = ShortArray(n); bytes = ByteArray(n * bytesPer) }
                samples.read(0, shorts, 0, n)
                for (i in 0 until n) floats[i] = shorts[i] / 32768f
                if (pcmEq.active) {                                // свой эквалайзер: на нулях — не трогает звук вовсе
                    pcmEq.process(floats, sampleCount)
                }
                if (pcmEq.active)
                    for (i in 0 until n) shorts[i] = (floats[i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
                // мозгу и диагностике — до громкости (мозг слышит музыку и на нуле); задержка — то, что ждёт в буфере вывода
                probe?.invoke(shorts, sampleCount)
                val buffered = (l.bufferSize - l.available()) / (CH * bytesPer) + LIMITER_DELAY
                bufferedMs = buffered * 1000L / RATE
                Analyzer.push(shorts, sampleCount, CH, buffered)
                val g = gain
                for (i in 0 until n) floats[i] *= g
                limiter.process(floats, sampleCount)
                // TPDF-дизер в 1 младший разряд: тихие хвосты и низкая громкость без «зерна» квантования
                val full = if (bytesPer == 3) 8388607f else 32767f
                for (i in 0 until n) {
                    val d = (rnd.nextFloat() - rnd.nextFloat())
                    val v = (floats[i] * full + d).roundToInt().coerceIn(-full.toInt() - 1, full.toInt())
                    val o = i * bytesPer
                    bytes[o] = v.toByte(); bytes[o + 1] = (v shr 8).toByte()
                    if (bytesPer == 3) bytes[o + 2] = (v shr 16).toByte()
                }
                tilt.push(floats, sampleCount)
                tee?.write(bytes, 0, n * bytesPer)                    // отладка: ровно то, что уходит в звуковую карту
                if (silent) bytes.fill(0, 0, n * bytesPer)       // темп воспроизведения сохраняется, но ничего не слышно
                if (!l.isRunning) l.start()
                l.write(bytes, 0, n * bytesPer)
            }
            override fun pause(mp: MediaPlayer, pts: Long) { l.stop() }
            override fun resume(mp: MediaPlayer, pts: Long) { l.start() }
            override fun flush(mp: MediaPlayer, pts: Long) { l.flush(); limiter.reset(); Analyzer.flush() }
            override fun drain(mp: MediaPlayer) { l.drain() }
            override fun setVolume(volume: Float, mute: Boolean) { gain = if (mute) 0f else volume * volume * volume; vlcVolume = volume }   // та же кубическая шкала, что у VLC
        })
    }

    val tapping get() = line != null

    fun play(mrl: String, startMs: Long = 0) {
        _time.value = startMs; _length.value = 0
        val opts = buildList {
            if (startMs > 0) add(":start-time=${startMs / 1000.0}")
            add(":http-user-agent=Mozilla/5.0 Melos")
        }
        player.submit { player.media().play(mrl, *opts.toTypedArray()) }
    }

    fun pause() = player.submit { player.controls().setPause(true) }
    fun resume() = player.submit { if (_status.value == Status.PAUSED) player.controls().setPause(false) else player.controls().play() }
    fun stop() = player.submit { player.controls().stop() }
    fun seek(ms: Long) { _time.value = ms; player.submit { player.controls().setTime(ms) } }
    fun setVolume(v: Int) = player.submit { player.audio().setVolume(v.coerceIn(0, 150)) }   // >100 — только поправка выравнивания
    fun setRate(r: Float) = player.submit { player.controls().setRate(r) }
    fun setEqualizer(eq: PlayerEqualizer?) = player.submit { player.audio().setEqualizer((eq as? VlcjEq)?.native) }

    fun release() {
        runCatching { player.controls().stop() }
        runCatching { player.release() }
        line?.runCatching { stop(); close() }
    }
}
