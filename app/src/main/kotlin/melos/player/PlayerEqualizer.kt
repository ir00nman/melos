package melos.player

/**
 * Эквалайзер без привязки к платформе: десктоп — vlcj, Android — libVLC.
 * Поверхность — ровно то, что дёргает Player.applyEq/loadEqPreset.
 */
interface PlayerEqualizer {
    fun bandCount(): Int
    fun setPreamp(v: Float)
    fun setAmps(a: FloatArray)
    fun amps(): FloatArray
    fun preamp(): Float
}
