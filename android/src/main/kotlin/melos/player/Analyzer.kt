package melos.player

/**
 * Анализатор PCM на Android не существует (тапа нет), но Vibe/Pulse/Brain читают его константы и события.
 * Заглушка с той же поверхностью: спектр пуст, событий нет — визуал честно живёт в «спящем» режиме.
 */
object Analyzer {
    const val BANDS = 24

    class Frame(val at: Long, val bands: FloatArray, val level: Float, val bass: Float, val treble: Float, val intensity: Float, val lowEnergy: Float)
    sealed interface Event { val at: Long }
    class Kick(override val at: Long, val power: Float) : Event
    class Hat(override val at: Long, val power: Float) : Event
    class Drop(override val at: Long, val power: Float) : Event

    @Volatile var live = false; private set
    val bpm: Float get() = 0f

    fun drain(now: Long, sink: (Event) -> Unit) {}
    fun current(now: Long): Frame? = null
    fun push(samples: ShortArray, count: Int, channels: Int, buffered: Long) {}
    fun flush() {}
}
