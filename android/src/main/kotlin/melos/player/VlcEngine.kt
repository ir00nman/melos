package melos.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import melos.droid.Droid
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

enum class Status { IDLE, OPENING, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

// Движок на libVLC (Android): прямой вывод в audiotrack — тапа PCM тут нет, и он не нужен:
// единственный тракт и так прямой. API-поверхность — 1:1 с десктопным VlcEngine, чтобы Player.kt был общим.
class VlcEngine(tap: Boolean, silent: Boolean = false) {

    companion object {
        val found: Boolean = true                       // libVLC в APK — искать нечего
        fun rediscover(): Boolean = found

        /** Эквалайзер libVLC за [PlayerEqualizer] — Player.kt не знает про платформу. */
        private class VlcEq(val native: MediaPlayer.Equalizer) : PlayerEqualizer {
            override fun bandCount() = MediaPlayer.Equalizer.getBandCount()
            override fun setPreamp(v: Float) { native.setPreAmp(v) }
            override fun setAmps(a: FloatArray) { for (b in 0 until MediaPlayer.Equalizer.getBandCount()) native.setAmp(b, a.getOrElse(b) { 0f }) }
            override fun amps(): FloatArray = FloatArray(MediaPlayer.Equalizer.getBandCount()) { native.getAmp(it) }
            override fun preamp(): Float = native.getPreAmp()
        }

        object EqFactory {
            fun newEqualizer(): PlayerEqualizer = VlcEq(MediaPlayer.Equalizer.create())
            fun newEqualizer(name: String): PlayerEqualizer? = null      // именованных пресетов у libVLC-обёртки нет
            fun presets(): List<String> = emptyList()
            fun bands(): List<Float> = FloatArray(10) { MediaPlayer.Equalizer.getBandFrequency(it) }.toList()
        }

        /** Та же цепочка вызовов, что у vlcj на десктопе: factory.equalizer().newEqualizer() */
        val factory: EqSupport get() = EqSupport
        object EqSupport { fun equalizer(): EqFactory = EqFactory }
        fun preset(name: String): PlayerEqualizer? = EqFactory.newEqualizer(name)
        val presets: List<String> get() = emptyList()
        val bands: List<Float> get() = EqFactory.bands()
        fun version(): String = runCatching { LibVLC.version() }.getOrDefault("?")
    }

    // LibVLC сам дописывает опции в переданный список — он обязан быть мутируемым (ArrayList, не listOf)
    private val lib: LibVLC = LibVLC(Droid.app, arrayListOf("--no-video", "--quiet", "--http-reconnect", "--network-caching=1500"))
    val player: MediaPlayer = MediaPlayer(lib)

    private val _status = MutableStateFlow(Status.IDLE); val status = _status.asStateFlow()
    private val _time = MutableStateFlow(0L); val time = _time.asStateFlow()
    private val _length = MutableStateFlow(0L); val length = _length.asStateFlow()
    private val _meta = MutableStateFlow<String?>(null); val nowPlaying = _meta.asStateFlow()

    var onFinished: () -> Unit = {}
    var onError: () -> Unit = {}

    /** Тапа на Android нет, но «притворяемся» рабочим трактом: фолбэк Player'а на прямой вывод тут не нужен. */
    val tapping: Boolean get() = true
    val bufferedMs: Long get() = 0
    // PCM-эквалайзер тут не работает (нет тапа), но Player.applyEq его глушит по интерфейсу — держим заглушку
    val pcmEq = PcmEqualizer(44100, 2)

    init {
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> { _status.value = Status.OPENING; _meta.value = null }
                MediaPlayer.Event.Buffering -> if (event.buffering < 100f && _status.value !in setOf(Status.PLAYING, Status.PAUSED)) _status.value = Status.BUFFERING
                MediaPlayer.Event.Playing -> _status.value = Status.PLAYING
                MediaPlayer.Event.Paused -> _status.value = Status.PAUSED
                MediaPlayer.Event.Stopped -> if (_status.value != Status.ENDED) _status.value = Status.IDLE
                MediaPlayer.Event.EndReached -> { _status.value = Status.ENDED; onFinished() }
                MediaPlayer.Event.EncounteredError -> { _status.value = Status.ERROR; onError() }
                MediaPlayer.Event.TimeChanged -> _time.value = event.timeChanged
                MediaPlayer.Event.LengthChanged -> if (event.lengthChanged > 0) _length.value = event.lengthChanged
            }
        }
    }

    fun play(mrl: String, startMs: Long = 0) {
        _time.value = startMs; _length.value = 0
        // Media(lib, String) на Android — это ЛОКАЛЬНЫЙ ПУТЬ (https он заворачивает в file://);
        // сети живёт только в Uri-варианте
        val media = Media(lib, android.net.Uri.parse(mrl)).apply {
            if (startMs > 0) addOption(":start-time=${startMs / 1000.0}")
            setHWDecoderEnabled(false, false)               // звук: декодер не нужен
            addOption(":http-user-agent=Mozilla/5.0 Melos")
        }
        player.media = media
        player.play()
    }

    fun pause() { player.pause() }
    fun resume() { player.play() }
    fun stop() { player.stop() }
    fun seek(ms: Long) { _time.value = ms; player.time = ms }
    fun setVolume(v: Int) { player.volume = v.coerceIn(0, 150) }      // >100 — только поправка выравнивания
    fun setRate(r: Float) { player.rate = r }
    fun setEqualizer(eq: PlayerEqualizer?) { player.setEqualizer((eq as? VlcEq)?.native) }

    fun release() {
        runCatching { player.stop() }
        runCatching { player.release() }
        runCatching { lib.release() }
    }
}
