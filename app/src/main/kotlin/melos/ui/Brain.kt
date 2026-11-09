package melos.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import melos.player.Player
import melos.player.Status
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Вывод «звёздной сети» (render() и tick() из art-net.js). Музыка вместо состояний агента:
//   играет — вращение и импульсы разгоняет громкость, число импульсов на связи — бас, искры — верха;
//   бит — волна от ядра; вершина срабатывает синапсом на пике своей полосы, сгусток дышит ею;
//   поток грузится — «думает» (искры к ядру, ядро дышит); пауза — притихает; ошибка — красный отлив и вздрог.
@Composable
fun Brain(modifier: Modifier = Modifier, interactive: Boolean = true, center: Offset = Offset(0.5f, 0.46f), scaleK: Float = 1f, light: Float = 1f) {
    var frame by remember { mutableLongStateOf(0L) }
    val p = pal
    // на паузе и во сне рендер вполсилы — кадры даром не жжём
    LaunchedEffect(Unit) {
        var skip = false
        while (true) withFrameNanos {
            val idle = Player.status.value != Status.PLAYING && Net.sleep > 0.5f
            if (!(idle && skip)) frame = it
            skip = !skip
        }
    }
    // появление: мягкий рост и проявление (после анимации входа экрана не должно быть рывка)
    val appear = remember { Animatable(if (Look.motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (appear.value < 1f) appear.animateTo(1f, tween(650, easing = EaseLux)) }
    Canvas(modifier
        .graphicsLayer { alpha = appear.value; scaleX = 0.96f + 0.04f * appear.value; scaleY = scaleX }
        .clipToBounds()                                                  // искры и кольца не вылетают на сайдбар и плеер
        .onGloballyPositioned { Net.origin = it.positionInWindow() }
        .then(if (!interactive) Modifier else Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                Net.wake()
                if (!Look.motion) return@awaitEachGesture
                val g = Net.Grab(down.position.x, down.position.y, System.currentTimeMillis()); Net.grab = g
                do {
                    val ev = awaitPointerEvent()
                    ev.changes.firstOrNull()?.let { if (g.releasedAt == 0L) { g.x = it.position.x; g.y = it.position.y } }
                } while (ev.changes.any { it.pressed })
                if (!g.active) { Net.grab = null; Net.wave(1f); Net.poke() }          // короткий клик — пульс
                else if (g.releasedAt == 0L) { g.releasedAt = System.currentTimeMillis(); Net.wave(0.6f); Net.poke() }
            }
        }.pointerInput(Unit) {
            awaitPointerEventScope { while (true) { val e = awaitPointerEvent(); if (e.type == PointerEventType.Move) Net.wake() } }
        })) {
        frame.let { }
        render(p, center, scaleK, light)
    }
}

// Кружки группируются по (размер, доля белого, прозрачность) — один drawPoints на группу.
private class Bucket { var a = FloatArray(256); var n = 0; fun add(x: Float, y: Float) { if (n + 2 > a.size) a = a.copyOf(a.size * 2); a[n++] = x; a[n++] = y } }
private const val MIXQ = 12
private const val ALQ = 12
private val buckets = Array(2 * MIXQ * ALQ) { Bucket() }
private val halos = ArrayList<FloatArray>()
private var gridV = FloatArray(0)
private var gridD = FloatArray(0)
private var gridUsed = IntArray(0)
private var gridN = 0
private val paint = Paint().apply { isAntiAlias = true }

private fun bucket(size: Int, mix: Float, alpha: Float): Bucket {
    val mq = (mix.coerceIn(0f, 1f) * (MIXQ - 1)).roundToInt()
    val aq = ((alpha.coerceIn(0f, 1f) * ALQ).roundToInt() - 1).coerceIn(0, ALQ - 1)
    return buckets[size * MIXQ * ALQ + mq * ALQ + aq]
}

private val cellHash = { x: Float, y: Float -> val v = sin(x * 127.1 + y * 311.7) * 43758.5453; (v - floor(v)).toFloat() }
private fun smooth(t: Float): Float { val v = t.coerceIn(0f, 1f); return v * v * (3 - 2 * v) }
private fun ease(t: Float): Float = 1 - (1 - t.coerceIn(0f, 1f)).pow(3)

private fun DrawScope.render(p: Palette, center: Offset, scaleK: Float, light: Float) {
    val n = Net
    val now = System.currentTimeMillis()
    val dt = if (n.last == 0L) 0.016f else ((now - n.last) / 1000f).coerceIn(0f, 0.1f)
    n.last = now
    val motion = Look.motion

    while (true) {
        when (val e = BrainBus.events.poll() ?: break) {
            is BrainBus.Wave -> { n.wave(e.strength); n.activeAt = now }
            is BrainBus.Say -> n.pendingSay = e.text
            is BrainBus.Flash -> { n.flashColor(e.color, 900f); n.wave(0.8f); n.activeAt = now }
            is BrainBus.React -> n.react(e.kind, p)
            BrainBus.Tick -> { n.poke(); n.activeAt = now }
            BrainBus.Typed -> {
                n.typedAt = now; n.activeAt = now
                if (now - n.lastListenAt >= 70 && n.listens.size <= 10) { n.lastListenAt = now; n.listens += Net.Listen(now, x = null) }
            }
            BrainBus.Heart -> { n.heartAt = now; n.heartBurst = false; n.flashColor(p.acc, 700f); n.activeAt = now }
            BrainBus.Absorb -> Ripples.last?.let { (o, at) ->
                if (now - at < 1500 && motion) n.absorb = Net.Absorb(now, o.x - n.origin.x, o.y - n.origin.y)
            }
        }
    }

    val status = Player.status.value
    val playing = status == Status.PLAYING
    val loading = status == Status.OPENING || status == Status.BUFFERING
    if (status != n.lastStatus) {
        if (status == Status.ERROR) { n.errorAt = now; n.react("error", p) }
        if (status == Status.PAUSED && n.lastStatus == Status.PLAYING && motion) n.shrink(0.06f, 900f, 650f)   // пауза — сжимается и замирает
        if (status == Status.PLAYING && n.lastStatus == Status.PAUSED && motion) { n.wave(1f); n.poke() }    // продолжили — волна
        n.lastStatus = status
    }
    val trackId = Player.current.value?.mediaId
    if (trackId != n.lastTrack) {
        if (n.lastTrack != null && motion) { n.rewireAt = now; n.rewireFront = 0f; n.spin.kick(0.10f) }
        n.lastTrack = trackId; n.lastTimeAt = 0L
    }
    val tNow = Player.time.value
    if (playing && n.wasPlaying && n.lastTimeAt > 0) {                 // перемотка: время прыгнуло — мозг прокручивается в ту же сторону
        val jump = tNow - (n.lastTime + (now - n.lastTimeAt))
        if (abs(jump) > 2500) { n.scrubAt = now; n.scrubDir = if (jump > 0) 1f else -1f }
    }
    if (tNow != n.lastTime || !playing) { n.lastTime = tNow; n.lastTimeAt = now }
    n.wasPlaying = playing
    val bands = Pulse.bands
    val bass = if (playing) Pulse.bass else 0f
    val level = if (playing) Pulse.level else 0f
    // фазы структуры трека (BeatStructure): дроп — наибольший из карты и трекера
    val stD = if (playing && motion) Pulse.stDrop else 0f
    val drop = if (playing && motion) max(Pulse.drop, stD) else 0f
    val treble = if (playing) Pulse.treble else 0f
    //   бочка — физический «панч» всей сети и волна смещения точек от ядра;
    //   малый/хэты — хроматический сдвиг (цветная тень точек) и короткий срез-глитч сетки;
    //   бас — облако ядра разбухает; дроп — «большой взрыв»: сеть разлетается и пружинит обратно,
    //   камера трясётся, наклон переворачивается, цвета вспыхивают в белый; пока дроп — всё жёстче.
    val punch = if (playing && motion) Pulse.punch(now) * (1 + 0.8f * drop) else 0f
    val kickT = (now - Pulse.kickAt) / 1000f
    val kickFront = kickT / 0.32f * 1.4f
    val energy = if (playing) Pulse.energy.coerceIn(0f, 1f) else 0f
    val tension = max(if (playing && motion) Pulse.tension else 0f, if (playing && motion) Pulse.stTension else 0f)
    val inhale = if (playing && motion) Pulse.stInhale else 0f
    val kickAmp = if (playing && motion && kickT in 0f..0.45f) Pulse.kickPower * (0.04f + 0.08f * energy + 0.15f * drop) * (1 - kickT / 0.45f) else 0f
    // дроп: событие из структуры или карта — взрыв, вспышка, ударная волна и толчок вращения (только через губернатор)
    val dropHit = Pulse.stDropEvent || (Pulse.dropAt != n.lastDropAt && now - Pulse.dropAt < 400)
    if (playing && motion && dropHit) {
        n.lastDropAt = Pulse.dropAt; n.dropKickAt = now
        n.flipFrom = n.flipTo; n.flipTo += PI.toFloat()
        n.spin.kick(0.18f); n.wave(1.5f)                                     // волна от ядра на максимальной амплитуде
        if (n.sparks.size < 200) repeat(24) {                                // искры по всей сети
            val i = (Math.random() * n.nodes.size).toInt()
            n.nodes[i].fireAt = now
            n.sparks += Net.Spark(n.nodeWin[i * 2] - n.origin.x, n.nodeWin[i * 2 + 1] - n.origin.y,
                ((Math.random() - 0.5) * 500).toFloat(), ((Math.random() - 0.5) * 500).toFloat(), now, 0.5f + (Math.random() * 0.4f).toFloat())
        }
    }
    val secE = if (playing) Pulse.sectionEnergy else 0.6f
    // BREAKDOWN: провал и по карте (тихая часть), и по трекеру структуры — берём сильнейший
    val calm = max(((0.5f - secE) / 0.3f).coerceIn(0f, 1f), if (playing) Pulse.stCalm else 0f)
    val antic = if (playing && motion) Pulse.antic else 0f
    val outro = if (playing && motion) Pulse.outro else 0f
    if (playing && motion && Pulse.sectionAt != n.lastSectionAt && now - Pulse.sectionAt < 600) {
        n.lastSectionAt = Pulse.sectionAt
        n.yawDir = if (Math.random() < 0.5) -1f else 1f
        n.yawTo += n.yawDir * (PI.toFloat() / 2) * (0.7f + 0.6f * secE)        // четверть оборота: громкая часть — размашистее
        n.tiltTo = ((Math.random() - 0.5) * 0.5).toFloat() + (if (secE > 0.7f) 0.12f else -0.05f)
        n.wave(0.6f + 0.6f * secE); n.activeAt = now
    }
    if (playing && motion && Pulse.phraseAt != n.lastPhraseAt && now - Pulse.phraseAt < 400) {
        n.lastPhraseAt = Pulse.phraseAt
        n.yawTo += n.yawDir * 0.35f * (0.5f + secE)
        n.tiltTo += ((Math.random() - 0.5) * 0.16).toFloat()
        n.tiltTo = n.tiltTo.coerceIn(-0.3f, 0.35f)
    }
    if (playing && motion && Pulse.downAt != n.lastDownAt && now - Pulse.downAt < 300) {
        n.lastDownAt = Pulse.downAt
        n.yawTo += n.yawDir * 0.05f * (0.3f + energy + drop) * (1 - calm * 0.7f)
    }
    // поза догоняет цель мягко: поворот ~0,6 с, наклон ~1 с; тихая часть — ещё плавнее
    n.yaw += (n.yawTo - n.yaw) * min(1f, dt * (3.2f - 1.4f * calm))
    n.tiltOff += (n.tiltTo - n.tiltOff) * min(1f, dt * 1.6f)
    val bangT = (now - n.dropKickAt) / 1000f
    val bang = if (n.dropKickAt > 0 && bangT in 0f..2.5f && motion) 0.75f * exp(-bangT * 3.2f) * cos(bangT * 10f) else 0f
    val bangFlash = if (n.dropKickAt > 0 && bangT in 0f..1f && motion) exp(-bangT * 4f) else 0f
    val flip = n.flipFrom + (n.flipTo - n.flipFrom) * ease(((now - n.dropKickAt) / 650f))
    // глитч: на сильный хэт (и на любой — во время дропа) три горизонтальные полосы сетки сдвигаются
    // пыль: во время дропа из ядра непрерывно бьёт поток точек
    if (playing && motion && drop > 0.35f && calm < 0.5f) {
        val rate = (drop * 3).toInt() + 1
        repeat(rate) {
            if (n.sparks.size < 220) {
                val a = (Math.random() * 2 * PI).toFloat(); val sp = 380f + (Math.random() * 520).toFloat() * (0.6f + drop)
                n.sparks += Net.Spark(n.lastCx, n.lastCy, cos(a) * sp, sin(a) * sp * 0.92f, now, 0.45f + (Math.random() * 0.4).toFloat())
            }
        }
    }

    val target: Net.Mood = when {
        n.absorb != null -> Net.MOODS.getValue("absorbing")
        now < n.thinkUntil -> Net.MOODS.getValue("thinking")
        status == Status.ERROR || now - n.errorAt < 1500 -> Net.MOODS.getValue("error")
        loading -> Net.MOODS.getValue("thinking")
        // нарастание (по карте трека): сеть «заряжается» — импульсы бегут к ядру, вращение ускоряется, ядро разгорается
        playing -> Net.Mood((1.0f + energy * 1.2f + drop * 3f + tension * 2.5f) * (1 - 0.45f * calm) * (1 - 0.6f * antic) * (1 - 0.7f * outro),
            (0.4f + energy * 0.6f + drop * 1.2f + tension * 0.9f) * (1 - 0.4f * calm),
            1f + (drop * 2.5f + tension * 1.5f).roundToInt(), if (tension > 0.25f) -1f else 1f, 1.15f + bass * 0.8f + drop + tension * 0.9f, 0f, 0f, 1f)
        now - n.typedAt < 1400 -> Net.MOODS.getValue("listening")
        status == Status.PAUSED -> Net.MOODS.getValue("paused")
        else -> Net.MOODS.getValue("idle")
    }
    if (target.tint > 0) n.tintColor = p.critical
    val tk = min(1f, dt * 3)
    val tv = floatArrayOf(target.spin, target.pulse, target.heads, target.dir, target.core, target.blink, target.tint, target.walk)
    for (i in 0..7) if (i == 2 || i == 3) n.mood[i] = tv[i] else n.mood[i] += (tv[i] - n.mood[i]) * tk
    val mSpin = n.mood[0]; val mPulse = n.mood[1]; val mHeads = n.mood[2]; val mDir = n.mood[3]
    val mCore = n.mood[4]; val mBlink = n.mood[5]; val mTint = n.mood[6]; val mWalk = n.mood[7]
    n.pulseClock += dt * mPulse
    n.blinkClock += dt * 0.9f

    n.edgeWalk.clear()
    if (mWalk < 0.3f || !motion) n.walkers.clear() else {
        val count: Int; val speed: Float
        when {
            now < n.thinkUntil || loading -> { count = 28; speed = 3.4f }
            playing -> {
                count = ((4 + energy * 10 + drop * 30 + tension * 24 + antic * 10) * (1 - 0.5f * calm) * (1 - 0.8f * outro)).toInt().coerceIn(2, 56)
                speed = (2.0f + energy * 1.2f + drop * 2.4f + tension * 2f) * (1 - 0.3f * calm)
            }
            else -> { count = 4; speed = 1.9f }
        }
        while (n.walkers.size > count) n.walkers.removeAt(n.walkers.lastIndex)
        while (n.walkers.size < count) {
            val from = if (n.spawnFrom.isNotEmpty()) n.spawnFrom[n.walkers.size % n.spawnFrom.size] else (Math.random() * n.nodes.size).toInt()
            val opts = n.adj[from]
            if (opts.isEmpty()) continue
            val o = opts[(Math.random() * opts.size).toInt()]
            n.walkers += Net.Walker(from, o.to, o.e, o.len, if (n.spawnFrom.isNotEmpty()) 0f else (Math.random() * 0.5).toFloat())
            if (n.spawnFrom.isNotEmpty()) n.nodes[from].fireAt = now
        }
        n.spawnFrom = emptyList()
        for (w in n.walkers) {
            w.t += dt * speed / max(0.15f, w.len)
            if (w.t >= 1) {
                n.nodes[w.to].fireAt = now
                val opts = n.adj[w.to].filter { it.to != w.from }.ifEmpty { n.adj[w.to] }
                val o = opts[(Math.random() * opts.size).toInt()]
                w.from = w.to; w.to = o.to; w.e = o.e; w.len = o.len; w.t = 0f
            }
            val pos = if (w.e.a == w.from) w.t else 1 - w.t
            n.edgeWalk.getOrPut(w.e) { mutableListOf() } += floatArrayOf(pos, if (w.e.a == w.from) 1f else -1f)
        }
    }

    val asleep = !playing && !loading && n.absorb == null && now - n.activeAt > Net.SLEEP_AFTER
    n.sleep += ((if (asleep) 1f else 0f) - n.sleep) * min(1f, dt * (if (asleep) 0.35f else 4f))
    val dimTarget = (if (n.absorb != null) 0.9f else 1f) * (1 - 0.5f * n.sleep)
    n.dim += (dimTarget - n.dim) * min(1f, dt * 2.5f)
    val aliveT = (now - n.startAt - (Net.ASSEMBLE_MS - Net.ALIVE_LEAD_MS)) / 1000f
    val live = if (!motion) 1f else smooth(aliveT / Net.ALIVE_S)
    val kt = (aliveT - Net.KICK_DELAY) / Net.KICK_TAU
    val kick = if (!motion || kt <= 0 || kt > 12) 0f else Net.KICK * kt * exp(1 - kt)
    val pk = (now - n.pokeAt) / 500f
    val poke = if (!motion || pk < 0 || pk > 1) 0f else 3 * sin(PI.toFloat() * pk)
    val sq = n.squeeze
    val sqAge = (now - n.squeezeAt).toFloat()
    val frozen = if (sq != null && sq[3] > 0 && sqAge < sq[3]) 0f else 1f
    val scrubK = (1 - (now - n.scrubAt) / 700f).coerceIn(0f, 1f)
    val rewireK = (1 - (now - n.rewireAt) / 900f).coerceIn(0f, 1f)
    val poseTarget = if (!playing && !loading && n.grab == null && n.absorb == null && status != Status.ERROR && now - n.errorAt > 1500) 1f else 0f
    n.poseK += (poseTarget - n.poseK) * min(1f, dt * 1.4f)
    // вращение — только через губернатор: цель пружиной (старт/стоп ~0.8 с, потолок 0.35 рад/с),
    // толчки (клик/смена трека/дроп) — kick() и ограничены; «вдох» перед дропом на миг замирает
    if (motion) {
        val omega = Net.spin.step(dt, playing, bass, calm, tension) * frozen * (1f - 0.8f * n.sleep) * (1f - n.poseK) * (1f - 0.9f * inhale)
        n.angle += omega * dt + n.scrubDir * 16f * scrubK * scrubK * dt      // прокрутка при перемотке — руками пользователя
    }

    val w = size.width; val h = size.height
    val cell = max(2f, (Net.CELL * density).roundToInt().toFloat())
    n.cellPx = cell
    val zt = (aliveT / Net.ALIVE_S).coerceIn(0f, 1f)
    val zoom = if (!motion) 1f else 1 + Net.ZOOM_IN * (1 - zt).pow(3)
    val sqk = if (sq == null) 1f else sqAge / sq[1]
    if (sq != null && sqk >= 1) n.squeeze = null
    val shrinkK = if (sq != null && sqk < 1) sq[2] * (if (sqk < 0.18f) smooth(sqk / 0.18f) else 1 - smooth((sqk - 0.18f) / 0.82f)) else 0f
    val g = n.grab
    val leanOn = g != null && g.active && g.releasedAt == 0L
    val baseCx = w * center.x; val baseCy = h * center.y
    n.leanX += ((if (leanOn) (g!!.x - baseCx) * 0.06f else 0f) - n.leanX) * 0.12f
    n.leanY += ((if (leanOn) (g!!.y - baseCy) * 0.06f else 0f) - n.leanY) * 0.12f
    // тряска — только при взрыве дропа и плавная (синусы), без случайных рывков каждый кадр
    val shake = bangFlash * 12f * density
    val cx = baseCx + n.leanX + sin(now * 0.07f) * shake; val cy = baseCy + n.leanY + cos(now * 0.09f) * shake
    n.lastCx = cx; n.lastCy = cy
    // масштаб: пульс бита ≤3%, дроп — пружина 1.0→1.06→1.0, BREAKDOWN до 95%, «вдох» −4%
    val R = min(w, h * 0.92f) * 0.36f * scaleK * zoom * (1 - shrinkK) * (1 + punch * (0.02f + 0.01f * energy) + drop * 0.06f + max(0f, bang) * 0.08f - tension * 0.12f
        - antic * 0.06f - outro * 0.1f - 0.05f * calm - 0.04f * inhale)
    while (n.waves.isNotEmpty() && now - n.waves.first().first > Net.WAVE_MS) n.waves.removeFirst()
    val fronts = n.waves.map { (at, s) -> floatArrayOf(max(0f, (now - at).toFloat()) / Net.WAVE_MS * 1.35f, s * (1f - 0.5f * calm)) }
    val time = (now % 10_000_000L) / 1000f
    val assemble = if (!motion) 1f else min(1f, (now - n.startAt) / Net.ASSEMBLE_MS)
    // вращение: при игре — свободный спин (+ позы частей песни), на паузе — бленд к позе логотипа кратчайшим доворотом
    val poseK = n.poseK
    val poseDelta = ((Net.POSE_YAW - n.angle + PI.toFloat()) % (2 * PI.toFloat()) + 2 * PI.toFloat()) % (2 * PI.toFloat()) - PI.toFloat()
    val yaw = n.angle + poseDelta * poseK + n.yaw * (1 - poseK)
    val tiltFree = 0.32f + sin(time * 0.21f) * 0.08f * live + flip + n.tiltOff
    val tilt = tiltFree * (1 - poseK) + Net.POSE_TILT * poseK
    val roll = Net.POSE_ROLL * poseK
    val ca = cos(yaw); val sa = sin(yaw)
    val ct = cos(tilt); val stt = sin(tilt)
    val cr = cos(roll); val sr = sin(roll)

    val GW = ceil(w / cell).toInt() + 2; val GH = ceil(h / cell).toInt() + 2; val gsize = GW * GH
    if (gridV.size < gsize) { gridV = FloatArray(gsize); gridD = FloatArray(gsize); gridUsed = IntArray(gsize); gridN = 0 }
    for (i in 0 until gridN) gridV[gridUsed[i]] = 0f
    gridN = 0
    fun put(gx: Int, gy: Int, v: Float, depth: Float) {
        if (gx < -1 || gy < -1 || gx >= GW - 1 || gy >= GH - 1) return
        val k = (gy + 1) * GW + gx + 1
        val o = gridV[k]
        if (o == 0f) gridUsed[gridN++] = k
        if (o < v) { gridV[k] = v; gridD[k] = depth }
    }
    fun putPx(x: Float, y: Float, v: Float, depth: Float) = put(floor(x / cell).toInt(), floor(y / cell).toInt(), v, depth)
    var qx = 0f; var qy = 0f; var qd = 0f; var qk = 0f
    fun project(x0: Float, y0: Float, z0: Float) {
        var x = x0; var y = y0; var z = z0
        if (kickAmp > 0f) {
            val r = sqrt(x * x + y * y + z * z); val d = (r - kickFront) / 0.14f
            val f = 1 + kickAmp * exp(-d * d); x *= f; y *= f; z *= f
        }
        val x1 = x * ca - z * sa; val z1 = x * sa + z * ca
        val y2 = y * ct - z1 * stt; val z2 = y * stt + z1 * ct
        val xr = x1 * cr - y2 * sr; val yr = x1 * sr + y2 * cr        // крен — только в позе логотипа
        val k = 2.6f / (2.6f + z2)
        qx = cx + xr * R * k; qy = cy + yr * R * k; qd = z2; qk = k
    }
    fun nearest(bx: Float, by: Float, take: Int): List<Int> =
        n.nodes.indices.sortedBy { i -> project(n.nodes[i].x, n.nodes[i].y, n.nodes[i].z); hypot(qx - bx, qy - by) }.take(take)

    val rwFront = if (n.rewireAt > 0 && now - n.rewireAt < 900) (now - n.rewireAt) / 900f * 1.05f else -1f
    val prevFront = n.rewireFront; if (rwFront >= 0) n.rewireFront = rwFront
    for (pt in n.pts) {
        val age = assemble - pt.appear
        if (age < 0) continue
        var x = pt.x; var y = pt.y
        if (pt.core) {
            val jit = 0.012f * live * (1 + bass * 1.5f + drop * 2f)          // облако ядра колышется басом
            val swell = 1 + bass * 0.22f + punch * 0.12f                      // и разбухает
            x = x * swell + sin(time * 1.3f + pt.j) * jit; y = y * swell + cos(time * 1.1f + pt.j) * jit
        }
        project(x, y, pt.z)
        var v = pt.w * min(1f, 0.35f + age * 22)
        if (age < 0.018f) v = 1.6f                                           // головка пера — яркая
        val e = pt.edge
        if (e != null && stD > 0f) v = max(v, 1f + 0.5f * stD * pt.w)        // DROP: рёбра на миг ярче
        if (e != null && assemble >= 1) {
            val along0 = if (e.out) pt.t else 1 - pt.t
            val along = if (mDir > 0) along0 else 1 - along0
            val u = (n.pulseClock + e.phase) % 1
            val m = (((along - u) * mHeads) % 1 + 1) % 1
            if (min(m, 1 - m) / mHeads < 0.045f) v = 1.6f
            n.edgeWalk[e]?.forEach { wk ->
                val d = (wk[0] - pt.t) * wk[1]
                if (d >= -0.03f && d < 0.04f) { v = 1.75f; if (d > -0.005f && d < 0.012f) halos += floatArrayOf(qx, qy, cell * 4.2f, 1f) }
                else if (d >= 0.04f && d < 0.22f) v = max(v, 1.0f + 0.3f * (1 - (d - 0.04f) / 0.18f))
            }
        }
        val ab = n.absorb
        if (pt.core && ab != null) { val f = (now - ab.start) / Net.ABSORB_MS; if (f > Net.GROW_F && f < Net.PULL_F) v *= 1.25f }
        if (pt.core) v *= 0.75f + 0.25f * mCore * (1 + 0.25f * sin(time * 3.2f) * max(0f, mCore - 1.2f))
        if (pt.core && playing && Pulse.bpm > 0f) {
            val breath = if (calm > 0.5f) 0.5f else 1f                                     // тихая часть — вдох через долю
            v *= 0.88f + 0.24f * (0.5f + 0.5f * sin(now / 60_000f * Pulse.bpm * breath * 2 * PI.toFloat()))
        }
        if (outro > 0f) v *= 1 - 0.35f * outro
        if (rwFront >= 0) { if (pt.appear > rwFront) v *= 0.45f else if (rwFront - pt.appear < 0.03f) v = max(v, 1.6f) }
        if (pt.core && n.sleep > 0) v *= 1 - 0.35f * n.sleep * (0.5f + 0.5f * sin(time * 0.9f))
        for (f in fronts) if (abs(pt.r - f[0]) < 0.045f) v = max(v, 1.45f + 0.3f * f[1])
        put(floor(qx / cell).toInt(), floor(qy / cell).toInt(), v, qd)
    }

    // узлы-сгустки: набираются по пикселю, дышат своей полосой спектра, синапс — вспышка с ореолом
    for (nd in n.nodes) {
        project(nd.x, nd.y, nd.z)
        n.nodeWin[nd.idx * 2] = n.origin.x + qx; n.nodeWin[nd.idx * 2 + 1] = n.origin.y + qy
        val grow = if (!motion) 1f else (now - n.startAt - nd.appear * Net.ASSEMBLE_MS) / Net.NODE_GROW_MS
        if (grow <= 0) continue
        val px = qx; val py = qy; val pd = qd; val pkk = qk
        val fr = if (nd.fireAt > 0) (now - nd.fireAt) / 320f else 9f
        val band = if (playing) bands[nd.band] else 0f
        val r = (nd.size * 0.62f + sin(time * 1.6f + nd.beat) * 0.2f + (if (fr in 0f..1f) 0.5f * (1 - fr) else 0f) + band * (0.3f + 0.7f * drop)) * pkk
        val g0x = floor(px / cell).toInt(); val g0y = floor(py / cell).toInt(); val ri = ceil(r).toInt()
        var nodeV = 1.05f
        if (rwFront >= 0) { if (nd.appear > rwFront) nodeV = 0.6f; if (nd.appear in prevFront..rwFront) nd.fireAt = now }
        if (mBlink > 0.05f) { val b = (n.blinkClock - nd.order) % 1; if ((b + 1) % 1 < 0.07f) nodeV = 1.05f + 0.6f * mBlink }
        if (fr >= 0 && fr < 1) {
            nodeV = max(nodeV, if (fr < 0.45f) 1.75f else 1.75f - 0.6f * (fr - 0.45f) / 0.55f)
            halos += floatArrayOf(px, py, cell * (5 + nd.size * 2) * pkk, 1.2f * (1 - fr))
        }
        val pkn = (now - n.pokeAt) / 350f
        if (pkn in 0f..1f && cellHash(nd.idx * 3.1f, n.pokeSeed.toFloat()) < 0.3f) nodeV = max(nodeV, 1.65f - 0.5f * pkn)
        for (f in fronts) if (abs(nd.r - f[0]) < 0.09f) { nodeV = max(nodeV, 1.75f); halos += floatArrayOf(px, py, cell * (4 + nd.size * 2) * pkk, f[1]) }
        for (dy in -ri..ri) for (dx in -ri..ri) {
            val dd = (dx * dx + dy * dy).toFloat()
            if (dd > r * r) continue
            val at = 0.25f * sqrt(dd) / max(1f, r) + 0.75f * cellHash(dx + nd.beat * 13, dy.toFloat())
            val age = grow - at
            if (age < 0) continue
            put(g0x + dx, g0y + dy, if (age < 0.12f && grow < 1.1f) 1.4f else nodeV, pd - 0.05f)
        }
    }
    n.drawnAt = now

    // подключение к треку: линии от трёх ближайших вершин к месту клика, пакеты бегут в сеть
    n.absorb?.let { ab ->
        val f = (now - ab.start) / Net.ABSORB_MS
        if (f >= 1) { ab.links?.let { n.spawnFrom = it }; n.thinkUntil = now + 1200; n.absorb = null; return@let }
        val bx = ab.x.coerceIn(cell, w - cell); val by = ab.y.coerceIn(cell, h - cell)
        val links = ab.links ?: nearest(bx, by, 3).also { ab.links = it }
        val reach = if (f < Net.GROW_F) f / Net.GROW_F else if (f < Net.PULL_F) 1f else 1 - (f - Net.PULL_F) / (1 - Net.PULL_F)
        links.forEachIndexed { li, i ->
            val nd = n.nodes[i]; project(nd.x, nd.y, nd.z)
            val px = qx; val py = qy
            val len = hypot(bx - px, by - py); val steps = max(4, (len / cell).roundToInt())
            var k = 0
            while (k <= steps * reach) {
                val t = k / steps.toFloat()
                if (!(k % 2 == 1 && t < reach - 0.05f)) putPx(px + (bx - px) * t, py + (by - py) * t, if (t > reach - 0.05f) 1.6f else 1.0f, -0.6f)
                k++
            }
            if (f >= Net.GROW_F && f < Net.PULL_F) {
                val pf = (f - Net.GROW_F) / (Net.PULL_F - Net.GROW_F)
                for (j in 0 until 4) {
                    val u = 1 - ((pf * 3.2f + j / 4f + li * 0.13f) % 1)
                    val x = px + (bx - px) * u; val y = py + (by - py) * u
                    putPx(x, y, 1.75f, -0.8f); putPx(x + cell, y, 1.4f, -0.8f)
                }
                nd.fireAt = now - (120 * Math.random()).toLong()
            }
        }
        if (f < Net.PULL_F + 0.08f) {
            val pr = 2 + sin(time * 9) * 0.6f; val gx0 = floor(bx / cell).toInt(); val gy0 = floor(by / cell).toInt()
            for (dy in -3..3) for (dx in -3..3) if (dx * dx + dy * dy <= pr * pr) put(gx0 + dx, gy0 + dy, 1.5f, -0.8f)
        }
    }

    // «слушает»: искра от низа холста (поле поиска) к одной из нижних вершин
    val li = n.listens.iterator()
    while (li.hasNext()) {
        val ls = li.next()
        val f = max(0f, (now - ls.start) / Net.LISTEN_MS)
        if (f >= 1) { if (ls.node >= 0) n.nodes[ls.node].fireAt = now; li.remove(); continue }
        val bx = ls.x ?: (w / 2); val by = h - cell
        if (ls.node < 0) { val near = nearest(bx, by, 5); ls.node = near[(Math.random() * near.size).toInt()] }
        val nd = n.nodes[ls.node]; project(nd.x, nd.y, nd.z)
        val u = smooth(f)
        for (k in 0 until 4) {
            val t = (u - k * 0.04f).coerceIn(0f, 1f)
            putPx(bx + (qx - bx) * t, by + (qy - by) * t, if (k > 0) 1.35f - k * 0.1f else 1.75f, -0.8f)
        }
    }

    // схваченный курсор: нити от трёх ближайших вершин провисают и дышат, сгустки бегут к курсору
    if (g != null && !g.active && g.releasedAt == 0L && now - g.down > Net.GRAB_DELAY) g.active = true
    if (g != null && g.active) {
        val reach = if (g.releasedAt > 0) 1 - ((now - g.releasedAt) / Net.GRAB_RELEASE).coerceIn(0f, 1f)
            else ease((now - g.down - Net.GRAB_DELAY) / Net.GRAB_GROW)
        if (g.releasedAt > 0 && reach <= 0) n.grab = null
        else {
            val gnodes = g.nodes ?: nearest(g.x, g.y, 3).also { list -> g.nodes = list; list.forEach { n.nodes[it].fireAt = now } }
            gnodes.forEachIndexed { gi, ni ->
                val nd = n.nodes[ni]; project(nd.x, nd.y, nd.z)
                val px = qx; val py = qy
                val len = hypot(g.x - px, g.y - py); val steps = max(4, (len / cell).roundToInt())
                val l = if (len == 0f) 1f else len
                val nx = -(g.y - py) / l; val ny = (g.x - px) / l
                val sag = sin(time * 3 + gi * 2.1f) * 0.06f * len
                var k = 0
                while (k <= steps * reach) {
                    val t = k / steps.toFloat()
                    if (!(k % 2 == 1 && t < reach - 0.05f)) {
                        val s = sag * sin(PI.toFloat() * t)
                        putPx(px + (g.x - px) * t + nx * s, py + (g.y - py) * t + ny * s, if (t > reach - 0.05f) 1.6f else 1.05f, -0.7f)
                    }
                    k++
                }
                if (reach >= 1) for (j in 0 until 2) {
                    val t = (time * 1.4f + j / 2f + gi * 0.23f) % 1; val s = sag * sin(PI.toFloat() * t)
                    putPx(px + (g.x - px) * t + nx * s, py + (g.y - py) * t + ny * s, 1.75f, -0.8f)
                }
                if (reach >= 1 && Math.random() < 0.02) nd.fireAt = now
            }
            val pr = (1.6f + sin(time * 9) * 0.5f) * reach; val g0x = floor(g.x / cell).toInt(); val g0y = floor(g.y / cell).toInt()
            for (dy in -3..3) for (dx in -3..3) if (dx * dx + dy * dy <= pr * pr) put(g0x + dx, g0y + dy, 1.55f, -0.8f)
        }
    }

    if (motion && assemble >= 1) {
        // сердце из точек на лайк: вырастает в ядре, держится, рассыпается искрами
        val hk = now - n.heartAt
        if (hk in 0..1100) {
            val grow = ease(hk / 260f); val sc = cell * 2 * grow
            for ((hy, row) in HEART.withIndex()) for ((hx, ch) in row.withIndex()) {
                if (ch != '1') continue
                val x = cx + (hx - 4f) * sc; val y = cy + (hy - 3.5f) * sc
                if (hk > 850) continue
                for (dy in 0..1) for (dx in 0..1) putPx(x + dx * cell * grow, y + dy * cell * grow, 1.25f, -0.9f)
            }
            if (hk > 850 && !n.heartBurst) {
                n.heartBurst = true
                for ((hy, row) in HEART.withIndex()) for ((hx, ch) in row.withIndex()) if (ch == '1') {
                    val x = cx + (hx - 4f) * cell * 2; val y = cy + (hy - 3.5f) * cell * 2
                    val dx0 = x - cx; val dy0 = y - cy + 0.01f; val l0 = max(1f, hypot(dx0, dy0)); val sp = R * (0.8f + Math.random().toFloat())
                    n.sparks += Net.Spark(x, y, dx0 / l0 * sp, dy0 / l0 * sp, now, 0.8f)
                }
            }
        }
        // искры: летят от центра, тормозят и гаснут
        val it = n.sparks.iterator()
        while (it.hasNext()) {
            val sp = it.next()
            val age = (now - sp.born) / 1000f
            if (age > sp.life) { it.remove(); continue }
            sp.x += sp.vx * dt; sp.y += sp.vy * dt; sp.vx *= 0.94f; sp.vy *= 0.94f
            putPx(sp.x, sp.y, 1.75f * (1 - age / sp.life) + 0.2f, -0.8f)
        }
    }

    val tint = mTint * (0.75f + 0.25f * sin(time * 4))
    // тембр: светлый звук уводит цвет ко второму цвету обложки, басовитый — к третьему (тёплый/тёмный)
    val bright = if (playing && motion) (Pulse.brightness - 0.5f) * 2f else 0f
    val timbre = if (bright >= 0) lerpC(p.acc, p.c2, bright * 0.35f) else lerpC(p.acc, p.c3, -bright * 0.3f)
    var base = lerpC(lerpC(timbre, n.tintColor, tint), Color.White, bangFlash * 0.85f)
    if (stD > 0f) base = lerpC(base, p.acc, 0.35f * stD)                     // DROP: цвет — акцент
    val dimEf = n.dim * (1f - 0.6f * calm)                                   // BREAKDOWN: точки и рёбра до ~40%
    n.flash?.let { (at, dur, c) ->
        val ff = max(0f, (now - at) / dur)
        if (ff >= 1) n.flash = null
        else base = lerpC(base, c, (if (ff < 0.2f) ff / 0.2f else 1 - (ff - 0.2f) / 0.8f).coerceIn(0f, 1f) * 0.85f)
    }
    // размер самой точки фигуры (не вершины: сгустки-вершины ужимаются в radius узлов, см. цикл узлов)
    val dot = max(1f, (cell * 0.72f).roundToInt().toFloat()); val dotHot = max(dot + 1, (dot * 1.35f).roundToInt().toFloat())
    val glitch = if (motion) (1 - (now - n.glitchAt) / 110f).coerceIn(0f, 1f) else 0f
    val off = floor((cell - dot) / 2) + dot / 2; val offHot = floor((cell - dotHot) / 2) + dotHot / 2
    n.pendingSay?.let { text -> n.pendingSay = null; if (motion) n.say = layoutSay(text, w, h, cell, cx, cy, R) }

    // свет (S) — лёгкий слой поверх сети: ядро ≤ 0.5R и до 6 мягких лучей.
    // В NORMAL почти невидим (≤ 0.15), заметнее на BUILD и DROP; на подэкранах выключен (light = 0).
    if (light > 0f && motion) {
        val eff = ((0.12f + 0.5f * tension + 0.45f * stD + 0.15f * bangFlash) * light).coerceAtMost(0.85f)
        if (eff > 0.02f) {
            val lc = if (Look.accent == Accent.AUTO && p.covered) lerpC(p.acc, Color.White, 0.35f) else Color.White
            drawCoreGlow(Offset(cx, cy), R * 0.5f, lc, eff * 0.55f)
            drawLightRays(Offset(cx, cy), R, time, lc, eff,
                tension = Pulse.stTension, drop = stD, inhale = inhale, calm = calm, bass = bass)
        }
    }

    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        paint.mode = PaintMode.FILL; paint.shader = null
        // звёзды — цветом текста (видны и в светлой теме), часть — акцентом; крупные — с крестиком-бликом
        val since = if (!motion) 99f else aliveT
        if (since > 0) {
            val drift = n.angle * 0.012f
            for (st in n.stars) {
                val vis = smooth((since - st.appear * 0.2f) / (Net.ALIVE_S * 0.8f))
                if (vis <= 0) continue
                val a = vis * st.base * (0.55f + 0.45f * sin(time * st.tw + st.ph)) * dimEf
                if (a <= 0.02f) continue
                val x = (((st.x - drift) % 1 + 1) % 1) * w; val y = st.y * h; val r = st.r * density
                val c = if (st.tint) base else p.txt
                paint.color = c.copy(alpha = a.coerceAtMost(1f)).toArgb()
                nc.drawCircle(x, y, r, paint)
                if (st.spark) {
                    val L = r * 5.5f; val t2 = max(0.5f, r * 0.28f)
                    val c0 = c.copy(alpha = 0f).toArgb(); val c1 = c.copy(alpha = (a * 0.55f).coerceAtMost(1f)).toArgb()
                    paint.shader = Shader.makeLinearGradient(x - L, y, x + L, y, intArrayOf(c0, c1, c0), null)
                    nc.drawRect(Rect.makeXYWH(x - L, y - t2, L * 2, t2 * 2), paint)
                    paint.shader = Shader.makeLinearGradient(x, y - L, x, y + L, intArrayOf(c0, c1, c0), null)
                    nc.drawRect(Rect.makeXYWH(x - t2, y - L, t2 * 2, L * 2), paint)
                    paint.shader = null
                }
            }
        }
        // ореолы под искрами и сработавшими синапсами — первыми, чтобы головки остались чёткими
        val hc = lerpC(base, Color.White, 0.6f)
        for (hl in halos) {
            val rad = hl[2] / 2; val a = (0.22f * hl[3]).coerceIn(0f, 1f)
            if (rad < 1f || a < 0.01f) continue
            paint.shader = Shader.makeRadialGradient(hl[0], hl[1], rad, intArrayOf(hc.copy(alpha = a).toArgb(), hc.copy(alpha = a * 0.25f).toArgb(), hc.copy(alpha = 0f).toArgb()), floatArrayOf(0f, 0.5f, 1f))
            nc.drawCircle(hl[0], hl[1], rad, paint)
        }
        paint.shader = null
        halos.clear()

        // клетки: ближнее светлее (к белому), дальнее тусклее; импульсы почти белые
        for (b in buckets) b.n = 0
        for (i in 0 until gridN) {
            val k = gridUsed[i]; val v = gridV[k]; val depth = gridD[k]
            val near = (0.5f - depth * 0.55f).coerceIn(0f, 1f)
            val hot = v > 1.3f
            val isLine = v >= 0.95f && !hot
            var gx = (k % GW) - 1; val gy = (k / GW) - 1
            if (glitch > 0f) for (q in 0..2) {
                val row = (n.glitchRows[q] * GH).toInt(); if (gy in row until row + 3 + q * 2) gx += (n.glitchShift[q] * glitch).roundToInt()
            }
            if (v >= 1.7f) { bucket(1, 0.95f, 1f).add(gx * cell + offHot, gy * cell + offHot); continue }
            val mix = if (hot) 0.9f else near * (if (isLine) 0.6f else 0.4f)
            val alpha = max(0.12f, min(1f, (0.25f + near * 0.75f) * min(1f, v) * (if (isLine) 1.15f else 1f)))
            val bright = if (hot) 0.55f + 0.45f * dimEf else dimEf
            bucket(0, mix, alpha * bright).add(gx * cell + off, gy * cell + off)
        }
        // фраза из точек: символы вылетают из ядра по очереди, стоят, разом втягиваются обратно
        n.say?.let { s ->
            val k = (now - s.start).toFloat(); val back = s.outEnd + Net.SAY_HOLD
            for (d in s.dots) {
                val f = if (k < back) ease(max(0f, (k - d[2]) / Net.SAY_OUT)) else { val t = ((k - back - d[3]) / Net.SAY_IN).coerceIn(0f, 1f); 1 - t * t }
                if (f <= 0) continue
                val x = cx + (d[0] - cx) * f; val y = cy + (d[1] - cy) * f - sin(PI.toFloat() * f) * d[4] * density
                if (f > 0.97f) bucket(0, 0.85f, 1f).add((x - dot / 2).roundToInt() + dot / 2, (y - dot / 2).roundToInt() + dot / 2)
                else bucket(1, 0.85f, 0.85f).add((x - dotHot / 2).roundToInt() + dotHot / 2, (y - dotHot / 2).roundToInt() + dotHot / 2)
            }
            if (k > back + Net.SAY_IN + Net.SAY_JITTER) { n.say = null; n.react("accepted", p) }
        }
        paint.mode = PaintMode.STROKE; paint.strokeCap = PaintStrokeCap.ROUND
        // хроматический сдвиг: цветные тени точек расходятся на ударах хэта и во время дропа
        // визуал реагирует только на низкочастотные пульсации: хэты/высокие в сдвиге не участвуют
        val split = (drop * 2.5f + bangFlash * 6f) * density
        if (split > 0.6f) {
            for ((sign, col) in listOf(1f to p.c2, -1f to p.c3)) {
                nc.save(); nc.translate(sign * split, 0f)
                for ((bi, b) in buckets.withIndex()) {
                    if (b.n == 0) continue
                    val sz = bi / (MIXQ * ALQ); val aq = bi % ALQ
                    paint.strokeWidth = if (sz == 1) dotHot else dot
                    paint.color = col.copy(alpha = (aq + 1) / ALQ.toFloat() * 0.45f).toArgb()
                    nc.drawPoints(b.a.copyOf(b.n), paint)
                }
                nc.restore()
            }
        }
        for ((bi, b) in buckets.withIndex()) {
            if (b.n == 0) continue
            val sz = bi / (MIXQ * ALQ); val mq = (bi / ALQ) % MIXQ; val aq = bi % ALQ
            paint.strokeWidth = if (sz == 1) dotHot else dot
            paint.color = lerpC(base, Color.White, mq / (MIXQ - 1f)).copy(alpha = (aq + 1) / ALQ.toFloat()).toArgb()
            nc.drawPoints(b.a.copyOf(b.n), paint)
        }
        paint.mode = PaintMode.FILL
    }
}

private const val DOT_GAP = 1
private const val DOT_SPACE = 3
private const val DOT_LINE = 11

// Раскладка фразы матричным шрифтом под ядром (sayText из art-net.js), длинное — не больше двух строк.
private fun layoutSay(text: String, w: Float, h: Float, cell: Float, cx: Float, cy: Float, R: Float): Net.Say? {
    fun glyph(ch: Char) = DOT_FONT[ch] ?: DOT_FONT[ch.uppercaseChar()]
    fun width(word: String) = word.foldIndexed(0) { i, acc, ch -> acc + (glyph(ch)?.first ?: DOT_SPACE) + if (i > 0) DOT_GAP else 0 }
    // ширина — не больше самой сети и не шире холста; центр — под ядром
    val half = min(min(cx, w - cx) - 16, R * 1.25f)
    val maxW = max(10, (half * 2 / cell).toInt())
    val lines = mutableListOf<String>()
    for (word in text.replace("…", "...").split(Regex("\\s+")).filter { it.isNotEmpty() }) {
        val cur = lines.lastOrNull()
        if (cur != null && width(cur) + DOT_SPACE + 2 * DOT_GAP + width(word) <= maxW) lines[lines.lastIndex] = "$cur $word" else lines += word
    }
    val shown = lines.take(2).toMutableList()
    if (lines.size > 2 && shown.isNotEmpty()) shown[1] = shown[1].trimEnd() + "..."
    for (i in shown.indices) while (width(shown[i]) > maxW && shown[i].length > 4) shown[i] = shown[i].dropLast(4) + "..."
    if (shown.isEmpty()) return null
    val bh = (shown.size - 1) * DOT_LINE + 7
    val oy = min(cy + R * 1.02f + 14 * cell / 2.5f, h - bh * cell - 10)
    val dots = mutableListOf<FloatArray>()
    var order = 0
    shown.forEachIndexed { lineIdx, line ->
        var gx = ((cx / cell) - width(line) / 2f).roundToInt()
        for (ch in line) {
            val gl = glyph(ch)
            gl?.second?.forEach { xy ->
                dots += floatArrayOf((gx + xy[0] + 0.5f) * cell, oy + (lineIdx * DOT_LINE + xy[1] + 0.5f) * cell,
                    order * Net.SAY_CHAR + (Math.random() * 40).toFloat(), (Math.random() * Net.SAY_JITTER).toFloat(), ((Math.random() - 0.5) * 22).toFloat())
            }
            gx += (gl?.first ?: DOT_SPACE) + DOT_GAP
            order++
        }
        order++
    }
    if (dots.isEmpty()) return null
    return Net.Say(System.currentTimeMillis(), dots, dots.maxOf { it[2] } + Net.SAY_OUT)
}

// Сердце 9×8 для реакции на лайк — тем же языком точек, что и фраза.
private val HEART = listOf("011000110", "111101111", "111111111", "111111111", "011111110", "001111100", "000111000", "000010000")
