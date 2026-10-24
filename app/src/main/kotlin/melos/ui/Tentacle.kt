package melos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import melos.player.Player
import melos.player.Repeat
import melos.player.Status
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// Мозг сам переключает трек: за секунду до конца из ближайшей вершины тянется щупальце из точек к кнопке
// «следующий» (как щупальца Chrome crawler тянулись к странице), «нажимает» её ровно в момент смены,
// по щупальцу в мозг убегают пакеты — следующий трек «выкачан», — и оно втягивается.
object Tentacle {
    var nextTarget: Offset? = null                       // центр кнопки «следующий» в нижнем плеере (окно)
    var pressAt by mutableLongStateOf(0L)               // для «вдавливания» кнопки
    class Run(val node: Int, val start: Long, val arrive: Long) { var pressed = false }
    var run: Run? = null
    private var armedFor: String? = null

    const val LEAD_MS = 1000L                            // за сколько до конца трека тянуться
    const val HOLD_MS = 420L
    const val RETRACT_MS = 460L

    /** 0..1 — «вдавливание» кнопки: быстро внутрь, мягко обратно */
    fun press(now: Long = System.currentTimeMillis()): Float {
        val k = (now - pressAt) / 380f
        return if (k < 0 || k > 1) 0f else if (k < 0.25f) k / 0.25f else 1 - (k - 0.25f) / 0.75f
    }

    fun check() {
        if (!Look.motion || !Look.brain) return
        val t = Player.current.value ?: return
        val length = Player.length.value; val time = Player.time.value
        if (Player.status.value != Status.PLAYING || length <= 0 || Player.isLive(t)) return
        val left = length - time
        val hasNext = Player.index.value < Player.queue.value.lastIndex || Player.repeat.value == Repeat.ALL || Player.radio.value != null
        if (!hasNext || Player.repeat.value == Repeat.ONE) return
        if (left in 1..LEAD_MS && armedFor != t.mediaId && run == null) {
            val target = nextTarget ?: return
            val node = Net.nearestNode(target.x, target.y) ?: return
            val now = System.currentTimeMillis()
            armedFor = t.mediaId
            run = Run(node, now, now + left - 60)
        }
    }
}

@Composable
fun TentacleLayer() {
    val p = pal
    LaunchedEffect(Unit) { while (true) { Tentacle.check(); delay(80) } }
    Canvas(Modifier.fillMaxSize()) {
        Pulse.frame.let { }
        val r = Tentacle.run ?: return@Canvas
        val target = Tentacle.nextTarget ?: run { Tentacle.run = null; return@Canvas }
        val now = System.currentTimeMillis()
        val end = r.arrive + Tentacle.HOLD_MS + Tentacle.RETRACT_MS
        if (now > end) { Tentacle.run = null; return@Canvas }
        if (!r.pressed && now >= r.arrive) {
            r.pressed = true; Tentacle.pressAt = now
            Net.nodes.getOrNull(r.node)?.fireAt = now
            BrainBus.wave(0.7f)
        }
        val reach = when {
            now < r.arrive -> { val k = ((now - r.start).toFloat() / (r.arrive - r.start)).coerceIn(0f, 1f); 1 - (1 - k).pow(3) }
            now < r.arrive + Tentacle.HOLD_MS -> 1f
            else -> 1 - ((now - r.arrive - Tentacle.HOLD_MS).toFloat() / Tentacle.RETRACT_MS).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        }
        if (reach <= 0f) return@Canvas
        val sx = Net.nodeWin[r.node * 2]; val sy = Net.nodeWin[r.node * 2 + 1]
        val ex = target.x; val ey = target.y - 4.dp.toPx()
        val len = hypot(ex - sx, ey - sy)
        if (len < 4f) return@Canvas
        val nx = -(ey - sy) / len; val ny = (ex - sx) / len
        val time = now / 1000f
        val bend = len * 0.22f * (if (sx < ex) 1f else -1f)
        // изогнутое тело: квадратичная кривая + бегущая волна вдоль, к кончику волна затихает
        fun at(t: Float): Offset {
            val cxp = (sx + ex) / 2 + nx * bend; val cyp = (sy + ey) / 2 + ny * bend
            val bx = (1 - t) * (1 - t) * sx + 2 * (1 - t) * t * cxp + t * t * ex
            val by = (1 - t) * (1 - t) * sy + 2 * (1 - t) * t * cyp + t * t * ey
            val wob = sin(t * 9f - time * 7f) * len * 0.035f * t * (1 - t) * 4
            return Offset(bx + nx * wob, by + ny * wob)
        }
        val cell = Net.cellPx
        val dot = max(1f, cell * 0.85f); val hot = dot * 1.4f
        fun cellDot(o: Offset, size: Float, c: Color) {
            val gx = floor(o.x / cell) * cell + cell / 2; val gy = floor(o.y / cell) * cell + cell / 2
            drawCircle(c, size / 2, Offset(gx, gy))
        }
        val steps = max(8, (len / cell).toInt())
        val tip = reach
        for (s in 0..(steps * tip).toInt()) {
            val t = s / steps.toFloat()
            if (s % 2 == 1 && t < 0.2f && t < tip - 0.05f) continue                   // у самого мозга — пунктир
            val o = at(t)
            val headK = if (t > tip - 0.06f) 1f else 0f
            val c = lerpC(p.acc, Color.White, 0.3f + 0.6f * headK + 0.15f * t).copy(alpha = 0.75f + 0.25f * t)
            cellDot(o, if (headK > 0) hot else dot, c)
            // к кончику тело толще: соседние клетки поперёк
            if (t > 0.2f) cellDot(o + Offset(nx * cell, ny * cell), dot, c.copy(alpha = c.alpha * 0.85f))
            if (t > 0.5f) cellDot(o - Offset(nx * cell, ny * cell), dot, c.copy(alpha = c.alpha * 0.8f))
            if (t > 0.8f) cellDot(o + Offset(nx * cell * 2, ny * cell * 2), dot, c.copy(alpha = c.alpha * 0.6f))
        }
        // присоска на кончике: пульсирующее кольцо из точек вокруг кнопки
        if (reach >= 0.98f) {
            val pr = (7.dp.toPx() + 2.dp.toPx() * sin(time * 12)) * (1 + Tentacle.press(now) * 0.6f)
            for (k in 0 until 10) {
                val a = k / 10f * 2 * PI.toFloat() + time * 2
                cellDot(Offset(ex + cos(a) * pr, ey + 4.dp.toPx() + sin(a) * pr), dot, p.acc.copy(alpha = 0.9f))
            }
            // нажали — по щупальцу в мозг бегут пакеты: следующий трек «выкачан»
            if (now >= r.arrive) for (j in 0 until 3) {
                val u = 1 - (((now - r.arrive) / 520f + j / 3f) % 1f)
                cellDot(at(u), hot, lerpC(p.acc, Color.White, 0.9f))
            }
        }
        // хвостик у вершины — короткий сгусток, щупальце «растёт» из сети
        val g = min(1f, reach * 3)
        for (k in 0 until 6) {
            val a = k / 6f * 2 * PI.toFloat()
            cellDot(Offset(sx + cos(a) * cell * 1.6f * g, sy + sin(a) * cell * 1.6f * g), dot, p.acc.copy(alpha = 0.8f))
        }
    }
}
