package melos.player

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Фазы по энергии: провал → нарастание → дроп. update(dt, rms, bass, high) каждый кадр. */
class BeatStructure {
    enum class Phase { NORMAL, BREAKDOWN, BUILD, DROP }

    var phase = Phase.NORMAL; private set
    /** 0..1: насколько «провалилась» энергия относительно обычного уровня трека. Плавная. */
    var calm = 0f; private set
    /** 0..1: нарастание перед дропом. */
    var tension = 0f; private set
    /** Импульс дропа: 1 в момент удара, дальше затухает (tau ~0.6 c). */
    var drop = 0f; private set
    /** true ровно в одном кадре, когда дроп найден. */
    var dropEvent = false; private set
    /** Сила дропа 0..1 (только вместе с dropEvent). */
    var dropStrength = 0f; private set
    /** 0..1: «вдох» прямо перед дропом, если дроп известен заранее (см. notifyDropAhead). Убывает за 0.5 c. */
    var inhale = 0f; private set

    private class OnePole(val att: Float, val rel: Float, var v: Float = 0f) {
        fun step(x: Float, dt: Float): Float {
            val tau = if (x > v) att else rel
            v += (x - v) * (1f - exp(-dt / max(tau, 1e-4f)))
            return v
        }
    }

    private var peak = 0.05f
    private val fast = OnePole(0.02f, 0.20f)
    private val bassFast = OnePole(0.02f, 0.20f)
    private val slow = OnePole(1.8f, 1.8f)
    private val long = OnePole(3.0f, 40.0f)   // «обычный громкий уровень»; замораживается в провале
    private val hiSlow = OnePole(1.2f, 1.2f)
    private val hist = ArrayDeque<Float>()     // slow за последние ~0.4 c
    private var prevSlow = 0f
    private var tIn = 0f
    private var lowT = 0f
    private var highT = 0f
    private var cool = 0f

    private fun clamp01(x: Float) = if (x < 0f) 0f else if (x > 1f) 1f else x

    fun update(dt: Float, rms: Float, bass: Float, high: Float) {
        dropEvent = false
        val e = 0.6f * rms + 0.4f * bass
        peak = max(max(e, peak * exp(-dt / 25f)), 0.05f)
        val en = clamp01(e / peak)
        val bn = clamp01(bass / peak)
        val hn = clamp01(high / peak)

        val f = fast.step(en, dt)
        val bf = bassFast.step(bn, dt)
        val sl = slow.step(en, dt)
        val hs = hiSlow.step(hn, dt)
        if ((phase == Phase.NORMAL || phase == Phase.DROP) && sl > 0.2f) long.step(en, dt)

        val lg = max(long.v, 0.15f)
        val ratio = sl / lg

        hist.addLast(sl)
        val n = max(1, (0.4f / dt).toInt())
        while (hist.size > n) hist.removeFirst()
        val slowPrev = hist.first()
        val slope = (sl - prevSlow) / dt
        prevSlow = sl

        tIn += dt
        cool = max(0f, cool - dt)
        lowT = if (ratio < 0.5f) lowT + dt else 0f
        highT = if (ratio > 0.85f) highT + dt else 0f

        when (phase) {
            Phase.NORMAL -> if (lowT > 0.5f && long.v > 0.2f) { phase = Phase.BREAKDOWN; tIn = 0f }
            Phase.BREAKDOWN, Phase.BUILD -> {
                val rising = slope > 0.02f || hs > 0.35f
                val target = if (rising) clamp01((ratio - 0.1f) / 0.6f) else 0f
                tension += (target - tension) * (1f - exp(-dt / 0.5f))
                if (phase == Phase.BREAKDOWN && tension > 0.3f) phase = Phase.BUILD
                else if (phase == Phase.BUILD && tension < 0.15f) phase = Phase.BREAKDOWN
                val hit = tIn > 1.0f && cool == 0f && f > max(0.6f * lg, 2.0f * slowPrev) && bf > 0.45f
                if (hit) {
                    dropEvent = true
                    dropStrength = clamp01((f - slowPrev) / lg)
                    drop = 1f; phase = Phase.DROP; tIn = 0f; cool = 4f
                } else if (highT > 0.5f) { phase = Phase.NORMAL; tIn = 0f }
            }
            Phase.DROP -> if (tIn > 1.5f) { phase = Phase.NORMAL; tIn = 0f }
        }
        if (phase != Phase.BREAKDOWN && phase != Phase.BUILD) tension *= exp(-dt / 0.3f)
        val calmTarget = if (phase == Phase.BREAKDOWN || phase == Phase.BUILD) clamp01(1f - ratio / 0.6f) else 0f
        calm += (calmTarget - calm) * (1f - exp(-dt / 0.6f))
        drop *= exp(-dt / 0.6f)
        inhale = max(0f, inhale - dt / 0.5f)
    }

    /** Вызвать, когда по заранее разобранной карте трека известно, что дроп будет через ~0.15–0.5 c. */
    fun notifyDropAhead() { inhale = 1f }

    fun reset() {
        phase = Phase.NORMAL; calm = 0f; tension = 0f; drop = 0f; inhale = 0f; dropEvent = false
        peak = 0.05f; fast.v = 0f; bassFast.v = 0f; slow.v = 0f; long.v = 0f; hiSlow.v = 0f
        hist.clear(); prevSlow = 0f; tIn = 0f; lowT = 0f; highT = 0f; cool = 0f
    }
}

/**
 * Ограничитель вращения «мозга». Все внешние толчки (старт/стоп, смена трека, клик, дроп)
 * идут через kick() и жёстко ограничены; скорость выходит на цель пружиной, а не скачком.
 * Единицы: рад/с. Вызывать каждый кадр: step(dt, playing, bass01, calm, tension).
 */
class SpinGovernor(
    private val baseIdle: Float = 0.03f,     // скорость на паузе
    private val basePlay: Float = 0.10f,     // при воспроизведении без баса
    private val bassGain: Float = 0.16f,     // добавка от баса
    private val maxSpeed: Float = 0.35f,     // потолок без толчков
    private val kickCap: Float = 0.22f       // потолок суммарного толчка
) {
    var omega = 0f; private set
    private var kickV = 0f
    private var target = baseIdle
    private var play = 0f   // 0..1, плавный флаг «играет» (вход/выход за ~0.8 c)

    /** Полный сброс при смене трека. */
    fun reset() { omega = 0f; kickV = 0f; play = 0f; target = baseIdle }

    fun kick(amount: Float) { kickV = min(kickCap, kickV + amount.coerceIn(0f, kickCap)) }

    /** Страховка: точка входа dt <= 50 мс, NaN/Infinity в ответе не бывает. */
    fun step(dt: Float, playing: Boolean, bass01: Float, calm: Float, tension: Float): Float {
        val d = if (dt.isFinite()) dt.coerceIn(0f, 0.05f) else 0.016f
        if (!omega.isFinite()) omega = 0f
        if (!kickV.isFinite()) kickV = 0f
        if (!play.isFinite()) play = 0f
        val b = if (bass01.isFinite()) bass01 else 0f
        val c = if (calm.isFinite()) calm else 0f
        val t = if (tension.isFinite()) tension else 0f
        return stepInner(d, playing, b, c, t)
    }

    private fun stepInner(dt: Float, playing: Boolean, bass01: Float, calm: Float, tension: Float): Float {
        play += ((if (playing) 1f else 0f) - play) * (1f - exp(-dt / 0.8f))
        val driven = basePlay + bassGain * bass01.coerceIn(0f, 1f)
        // в провале почти замираем, на нарастании чуть разгоняемся
        val calmK = 1f - 0.85f * calm
        val buildK = 1f + 0.6f * tension
        target = (baseIdle + (driven - baseIdle) * play) * calmK * buildK
        target = min(target, maxSpeed)
        kickV *= exp(-dt / 0.6f)
        val want = target + kickV
        omega += (want - omega) * (1f - exp(-dt / 0.35f))
        return omega
    }
}
