package melos.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import melos.player.Analyzer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Реакции мозга, которые просят остальные части UI. Мозг сам разбирает их в начале кадра.
object BrainBus {
    sealed interface Ev
    data class Wave(val strength: Float) : Ev
    data class Say(val text: String) : Ev
    data class Flash(val color: Color) : Ev
    data class React(val kind: String) : Ev
    data object Tick : Ev
    data object Typed : Ev
    data object Absorb : Ev
    data object Heart : Ev

    val events = ConcurrentLinkedQueue<Ev>()
    fun wave(s: Float = 1f) { events += Wave(s) }
    fun say(text: String) { events += Say(text) }
    fun flash(c: Color) { events += Flash(c) }
    fun shake() { events += React("error") }
    fun react(kind: String) { events += React(kind) }
    fun tick() { events += Tick }
    fun typed() { events += Typed }
    /** Сеть «подключается» к месту последнего клика (трек, карточка) и выкачивает его пакетами. */
    fun absorb() { events += Absorb }
    /** Лайк: сердце из точек собирается в ядре и рассыпается искрами. */
    fun heart() { events += Heart }
}

// «Звёздная сеть» Chrome crawler (art/art-net.js), перенесённая один в один: та же модель (генератор и зерно —
// форма та же), те же настроения, импульсы, искры-синапсы, волны, вспышки, сжатие, хватание курсора, сон,
// «тик», фраза матричным шрифтом и подключение с выкачиванием. Вместо состояний агента — музыка (см. Brain.kt).
// Сеть одна на всё приложение: при переходах между экранами не собирается заново.
object Net {
    const val CELL = 2.5f
    const val ASSEMBLE_MS = 1700f
    const val SPIN = 0.16f
    const val NODE_GROW_MS = 450f
    const val ALIVE_S = 1.1f
    const val ALIVE_LEAD_MS = 300f
    const val KICK = 10f; const val KICK_TAU = 0.28f; const val KICK_DELAY = -0.04f
    const val ZOOM_IN = 0.3f
    const val WAVE_MS = 1100f
    const val LISTEN_MS = 420f
    const val SLEEP_AFTER = 60_000L
    const val GRAB_DELAY = 180L; const val GRAB_GROW = 220f; const val GRAB_RELEASE = 320f
    const val ABSORB_MS = 2200f; const val GROW_F = 0.22f; const val PULL_F = 0.82f
    const val SAY_OUT = 480f; const val SAY_CHAR = 45f; const val SAY_HOLD = 2000f; const val SAY_IN = 460f; const val SAY_JITTER = 90f

    // Поза логотипа (пауза/простой): вид на грань икосаэдра — шестиугольник с перевёрнутым треугольником.
    // Точное совмещение с лого (плоские верх/низ И треугольник на серединах сторон) проекцией икосаэдра
    // недостижимо: сдвиг треугольника к шестиугольнику у проекции фиксирован (0°, в лого — 30°).
    // Выбран апекс треугольника вниз — самый заметный элемент знака; шестиугольник при этом вершиной вверх.
    const val POSE_YAW = 0.3649f
    const val POSE_TILT = 3.1416f     // π: грань смотрит на камеру
    const val POSE_ROLL = -0.5236f    // −30°: апекс треугольника вниз
    /** 0 — свободное вращение, 1 — застыла в позе логотипа. */
    var poseK = 0f

    class Node(val x: Float, val y: Float, val z: Float, val size: Float, val beat: Float) {
        var appear = 0f; var order = 0f; var r = 0f; var idx = 0; var fireAt = 0L; var band = 0; var bandOn = false; var sparkedAt = 0L
    }
    class Edge(val a: Int, val b: Int, val dotted: Boolean, val phase: Float) { var out = true }
    class Pt(val x: Float, val y: Float, val z: Float, val w: Float, val edge: Edge? = null, val t: Float = 0f,
             val core: Boolean = false, val j: Float = 0f) { var r = 0f; var appear = 0f; var at = 0f }
    class Adj(val to: Int, val e: Edge, val len: Float)
    class Walker(var from: Int, var to: Int, var e: Edge, var len: Float, var t: Float)
    class Star(val x: Float, val y: Float, val r: Float, val tw: Float, val ph: Float, val base: Float, val tint: Boolean, val appear: Float) { var spark = false }
    class Say(val start: Long, val dots: List<FloatArray>, val outEnd: Float)   // точка: x, y (px холста), d0, d1, arc
    class Grab(var x: Float, var y: Float, val down: Long) { var active = false; var nodes: List<Int>? = null; var releasedAt = 0L }
    class Listen(val start: Long, var node: Int = -1, val x: Float?)
    class Absorb(val start: Long, val x: Float, val y: Float) { var links: List<Int>? = null }
    class Mood(val spin: Float, val pulse: Float, val heads: Float, val dir: Float, val core: Float, val blink: Float, val tint: Float, val walk: Float)

    val MOODS = mapOf(
        "idle" to Mood(1f, 0.35f, 1f, 1f, 1f, 0f, 0f, 0f),
        "thinking" to Mood(0.5f, 0.25f, 1f, -1f, 1.9f, 0f, 0f, 1f),
        "paused" to Mood(0.45f, 0.18f, 1f, 1f, 0.95f, 0f, 0f, 0f),
        "error" to Mood(0.6f, 0.2f, 1f, 1f, 0.8f, 0f, 0.6f, 0f),
        "absorbing" to Mood(0.7f, 0.2f, 1f, -1f, 1.6f, 0f, 0f, 0f),
        "listening" to Mood(0.55f, 0.3f, 1f, -1f, 1.45f, 0f, 0f, 0f),
    )

    // mulberry32 — тот же генератор, что в art-net.js
    private fun rng(seed: Int): () -> Float {
        var a = seed
        return {
            a += 0x6D2B79F5
            var t = a
            t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + (t xor (t ushr 7)) * (t or 61))
            ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
        }
    }

    val nodes = mutableListOf<Node>()
    val edges: List<Edge>
    val pts = mutableListOf<Pt>()
    val adj: List<MutableList<Adj>>
    val stars: List<Star>

    init {
        val rand = rng(20261016)
        val gauss = { (rand() + rand() + rand() - 1.5f) / 1.5f }
        // Симметричная фигура: снаружи икосаэдр (12 вершин), внутри повёрнутый к нему додекаэдр (20 вершин —
        // напротив граней икосаэдра), в центре ядро. Рёбра оболочек сплошные, спицы между слоями и к ядру — пунктир.
        val phi = (1 + sqrt(5f)) / 2
        fun norm(v: FloatArray, r: Float): FloatArray { val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return floatArrayOf(v[0] / l * r, v[1] / l * r, v[2] / l * r) }
        val ico = buildList {
            for (a in listOf(-1f, 1f)) for (b in listOf(-1f, 1f)) {
                add(floatArrayOf(0f, a, b * phi)); add(floatArrayOf(a, b * phi, 0f)); add(floatArrayOf(b * phi, 0f, a))
            }
        }.map { norm(it, 0.95f) }
        val dod = buildList {
            for (a in listOf(-1f, 1f)) for (b in listOf(-1f, 1f)) for (c in listOf(-1f, 1f)) add(floatArrayOf(a, b, c))
            for (a in listOf(-1f, 1f)) for (b in listOf(-1f, 1f)) {
                add(floatArrayOf(0f, a / phi, b * phi)); add(floatArrayOf(a / phi, b * phi, 0f)); add(floatArrayOf(b * phi, 0f, a / phi))
            }
        }.map { norm(it, 0.52f) }
        ico.forEach { nodes += Node(it[0], it[1], it[2], 1.6f, rand() * 6.28f) }
        dod.forEach { nodes += Node(it[0], it[1], it[2], 1.0f, rand() * 6.28f) }
        nodes += Node(0f, 0f, 0f, 1.8f, 0f)
        val hub = nodes.lastIndex
        fun dist(a: Node, b: Node) = sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2) + (a.z - b.z).pow(2))
        val map = LinkedHashMap<String, Edge>()
        fun link(a: Int, b: Int, dotted: Boolean) { if (a != b) map[if (a < b) "$a-$b" else "$b-$a"] = Edge(min(a, b), max(a, b), dotted, rand()) }
        fun shell(from: Int, count: Int) {                                 // рёбра многогранника — пары на минимальном расстоянии
            val idx = (from until from + count).toList()
            val minD = idx.flatMap { a -> idx.filter { it > a }.map { b -> dist(nodes[a], nodes[b]) } }.min()
            for (a in idx) for (b in idx) if (b > a && dist(nodes[a], nodes[b]) < minD * 1.05f) link(a, b, false)
        }
        shell(0, 12); shell(12, 20)
        for (d in 12 until 32) (0 until 12).sortedBy { dist(nodes[d], nodes[it]) }.take(3).forEach { link(d, it, true) }   // спицы к ближайшей грани
        for (d in 12 until 32) link(hub, d, true)
        edges = map.values.toList()

        for (e in edges) {
            val A = nodes[e.a]; val B = nodes[e.b]; val len = dist(A, B); val steps = max(10, (len * 80).roundToInt())
            for (s in 0..steps) {
                if (e.dotted && s % 3 == 1) continue
                if (rand() < 0.03f) continue
                val t = s / steps.toFloat()
                val gx = gauss(); val gy = gauss(); val gz = gauss()
                pts += Pt(A.x + (B.x - A.x) * t + gx * 0.006f, A.y + (B.y - A.y) * t + gy * 0.006f, A.z + (B.z - A.z) * t + gz * 0.006f,
                    if (e.dotted) 0.95f else 1.1f, e, t)
            }
        }
        repeat(900) {
            val r = rand().pow(1.7f) * 0.32f
            val u = rand() * 2 - 1; val phi = rand() * PI.toFloat() * 2; val s = sqrt(1 - u * u)
            pts += Pt(s * cos(phi) * r, u * r, s * sin(phi) * r, 0.8f, core = true, j = rand() * 6.28f)
        }
        repeat(90) { val x = gauss() * 0.9f; val y = gauss() * 0.9f; val z = gauss() * 0.9f; pts += Pt(x, y, z, 0.3f) }
        for (p in pts) p.r = sqrt(p.x * p.x + p.y * p.y + p.z * p.z)

        // прорисовка: кратчайшие пути от крайнего правого узла
        val lens = edges.map { dist(nodes[it.a], nodes[it.b]) }
        fun dijkstra(src: Int): FloatArray {
            val T = FloatArray(nodes.size) { Float.POSITIVE_INFINITY }; T[src] = 0f
            val done = BooleanArray(nodes.size)
            repeat(nodes.size) {
                var u = -1
                for (i in nodes.indices) if (!done[i] && T[i] < Float.POSITIVE_INFINITY && (u < 0 || T[i] < T[u])) u = i
                if (u < 0) return T
                done[u] = true
                edges.forEachIndexed { k, e ->
                    val v = if (e.a == u) e.b else if (e.b == u) e.a else -1
                    if (v >= 0 && T[u] + lens[k] < T[v]) T[v] = T[u] + lens[k]
                }
            }
            return T
        }
        val start = nodes.indices.fold(0) { best, i -> if (nodes[i].x > nodes[best].x) i else best }
        val T = dijkstra(start)
        val maxT = T.filter { it < Float.POSITIVE_INFINITY }.maxOrNull() ?: 1f
        for (i in T.indices) if (T[i] == Float.POSITIVE_INFINITY) T[i] = maxT
        val lenOf = edges.withIndex().associate { (k, e) -> e to lens[k] }
        for (p in pts) {
            val e = p.edge
            p.at = when {
                e != null -> { val len = lenOf.getValue(e); if (T[e.a] <= T[e.b]) T[e.a] + p.t * len else T[e.b] + (1 - p.t) * len }
                p.core -> T[hub] + p.r * 1.4f
                else -> maxT * (0.75f + rand() * 0.35f)
            }
        }
        val end = pts.maxOf { it.at }
        for (p in pts) p.appear = p.at / end * 0.94f
        nodes.forEachIndexed { i, n -> n.appear = T[i] / end * 0.94f }
        val H = dijkstra(hub)                                          // импульсы «от ядра наружу»
        for (e in edges) e.out = (if (H[e.a].isInfinite()) 0f else H[e.a]) <= (if (H[e.b].isInfinite()) 0f else H[e.b])
        adj = nodes.map { mutableListOf<Adj>() }
        edges.forEachIndexed { k, e -> adj[e.a] += Adj(e.b, e, lens[k]); adj[e.b] += Adj(e.a, e, lens[k]) }
        nodes.forEachIndexed { i, n ->
            n.order = ((atan2(n.z, n.x) / (PI.toFloat() * 2)) + 1) % 1; n.idx = i; n.r = sqrt(n.x * n.x + n.y * n.y + n.z * n.z)
            // каждая вершина слушает свою полосу: ближе к ядру — бас, снаружи — по кругу середина и верха
            n.band = if (n.r < 0.7f) (i % 4) else (4 + (n.order * (Analyzer.BANDS - 4)).toInt()).coerceAtMost(Analyzer.BANDS - 1)
        }
        val sr = rng(777)
        stars = List(160) { Star(sr(), sr(), 0.5f + sr().pow(2.4f) * 1.5f, 0.6f + sr() * 1.8f, sr() * 6.28f, 0.25f + sr() * 0.5f, sr() < 0.18f, sr() * 0.9f) }
        stars.filter { it.r > 1.3f }.take(11).forEach { it.spark = true }
    }

    // ---- состояние ----
    var startAt = System.currentTimeMillis()
    var angle = 0.6f; var last = 0L
    var pulseClock = 0f; var blinkClock = 0f
    val mood = floatArrayOf(1f, 0.35f, 1f, 1f, 1f, 0f, 0f, 0f)        // spin pulse heads dir core blink tint walk
    var tintColor = Color(0xFFEF5350)
    val waves = ArrayDeque<Pair<Long, Float>>()
    var flash: Triple<Long, Float, Color>? = null
    var squeeze: FloatArray? = null                                       // —, dur, amt, freeze
    var squeezeAt = 0L
    val listens = mutableListOf<Listen>()
    var typedAt = -1_000_000L; var lastListenAt = 0L
    var activeAt = System.currentTimeMillis(); var sleep = 0f
    var grab: Grab? = null; var leanX = 0f; var leanY = 0f
    var say: Say? = null; var pendingSay: String? = null
    var absorb: Absorb? = null; var thinkUntil = 0L; var spawnFrom = listOf<Int>()
    var pokeAt = -1_000_000L; var pokeSeed = 0
    var errorAt = -1_000_000L; var lastBeatWave = 0L
    val walkers = mutableListOf<Walker>()
    val edgeWalk = HashMap<Edge, MutableList<FloatArray>>()
    var dim = 1f
    var origin = Offset.Zero                                              // левый верх холста в окне
    var lastStatus: melos.player.Status? = null
    // ---- свои эффекты Melos (их нет в расширении) ----
    class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, val born: Long, val life: Float)
    val sparks = mutableListOf<Spark>()                                    // искры, улетающие от сработавших синапсов
    val shocks = ArrayDeque<Long>(); var lastShock = 0L                     // ударные кольца на сильный бит
    var rewireAt = 0L; var rewireFront = 0f; var lastTrack: String? = null  // «перепрошивка» при смене трека
    var heartAt = 0L; var heartBurst = false                                // сердце из точек на лайк
    var scrubAt = 0L; var scrubDir = 0f; var lastTime = 0L; var lastTimeAt = 0L; var wasPlaying = false
    var lastDropAt = 0L; var dropKickAt = 0L                                 // дроп: момент последнего «взрыва»
    var flipFrom = 0f; var flipTo = 0f                                      // на дропе наклон сети переворачивается
    // поза под форму песни: поворот и наклон, к которым сеть плавно тянется (смена части, фраза, сильная доля)
    var yaw = 0f; var yawTo = 0f; var yawDir = 1f
    var tiltOff = 0f; var tiltTo = 0f
    var lastSectionAt = 0L; var lastPhraseAt = 0L; var lastDownAt = 0L
    var lastHatAt = 0L; var glitchAt = 0L                                   // глитч-срез на хэтах
    val glitchRows = FloatArray(3); val glitchShift = FloatArray(3)
    var lastCx = 0f; var lastCy = 0f
    var shockAt = 0L                                            // пиксельная ударная волна + вспышка ядра на дропе

    /** Вершины на экране в координатах окна (x, y) — отсюда щупальце тянется к кнопкам плеера. */
    val nodeWin = FloatArray(64 * 2)
    var cellPx = 5f
    var drawnAt = 0L

    fun wave(s: Float = 1f) { waves.addLast(System.currentTimeMillis() to s); if (waves.size > 4) waves.removeFirst() }
    fun flashColor(c: Color, dur: Float = 900f) { flash = Triple(System.currentTimeMillis(), dur, c) }
    fun shrink(amt: Float, dur: Float, freeze: Float = 0f) { squeezeAt = System.currentTimeMillis(); squeeze = floatArrayOf(0f, dur, amt, freeze) }
    /** Все толчки вращения — только через губернатор (клик ≤ 0.08 рад/с). */
    fun poke() { pokeAt = System.currentTimeMillis(); pokeSeed++; spin.kick(0.08f) }

    /** Ограничитель вращения: скорость пружиной к цели, толчки ограничены, старт/стоп плавные (~0.8 с). */
    val spin = melos.player.SpinGovernor()

    fun react(kind: String, p: Palette) {
        activeAt = System.currentTimeMillis()
        when (kind) {
            "approve" -> { flashColor(p.ok, 900f); wave(0.8f) }
            "good" -> { flashColor(Color(0xFFFFCF7A), 1100f); wave(1f) }
            "deny", "bad", "error" -> { flashColor(p.critical, 650f); shrink(0.07f, 520f) }
            "stop" -> shrink(0.12f, 1200f, 800f)
            "tab" -> { wave(0.7f); poke() }
            "accepted" -> { shrink(-0.07f, 1400f); wave(1f); flashColor(p.warn, 1200f) }
        }
    }

    fun wake() {
        if (sleep > 0.5f && Look.motion) { wave(0.6f); poke() }
        activeAt = System.currentTimeMillis()
    }

    /** Ближайшая к точке окна вершина, которую мозг рисовал в последнем кадре. */
    fun nearestNode(x: Float, y: Float): Int? {
        if (System.currentTimeMillis() - drawnAt > 500) return null
        var best = -1; var bd = Float.MAX_VALUE
        for (i in nodes.indices) { val d = (nodeWin[i * 2] - x).pow(2) + (nodeWin[i * 2 + 1] - y).pow(2); if (d < bd) { bd = d; best = i } }
        return best.takeIf { it >= 0 }
    }
}

// Матричный шрифт 5×7 из art-net.js: штрих в одну точку, точка — клетка сетки мозга; строчные — заглавными.
val DOT_FONT: Map<Char, Pair<Int, List<IntArray>>> by lazy {
    val src = mutableMapOf(
        'А' to "01110 10001 10001 11111 10001 10001 10001", 'Б' to "11111 10000 10000 11110 10001 10001 11110",
        'В' to "11110 10001 10001 11110 10001 10001 11110", 'Г' to "11111 10000 10000 10000 10000 10000 10000",
        'Д' to "00110 01010 01010 01010 01010 11111 10001", 'Е' to "11111 10000 10000 11110 10000 10000 11111",
        'Ё' to "01010 00000 11111 10000 11110 10000 11111", 'Ж' to "10101 10101 10101 01110 10101 10101 10101",
        'З' to "01110 10001 00001 00110 00001 10001 01110", 'И' to "10001 10001 10011 10101 11001 10001 10001",
        'Й' to "01010 00100 10001 10011 10101 11001 10001", 'К' to "10001 10010 10100 11000 10100 10010 10001",
        'Л' to "00111 01001 01001 01001 01001 01001 10001", 'М' to "10001 11011 10101 10101 10001 10001 10001",
        'Н' to "10001 10001 10001 11111 10001 10001 10001", 'О' to "01110 10001 10001 10001 10001 10001 01110",
        'П' to "11111 10001 10001 10001 10001 10001 10001", 'Р' to "11110 10001 10001 11110 10000 10000 10000",
        'С' to "01110 10001 10000 10000 10000 10001 01110", 'Т' to "11111 00100 00100 00100 00100 00100 00100",
        'У' to "10001 10001 10001 01111 00001 10001 01110", 'Ф' to "00100 01110 10101 10101 10101 01110 00100",
        'Х' to "10001 10001 01010 00100 01010 10001 10001", 'Ц' to "10010 10010 10010 10010 10010 11111 00001",
        'Ч' to "10001 10001 10001 01111 00001 00001 00001", 'Ш' to "10101 10101 10101 10101 10101 10101 11111",
        'Щ' to "10101 10101 10101 10101 10101 11111 00001", 'Ъ' to "11000 01000 01000 01110 01001 01001 01110",
        'Ы' to "10001 10001 10001 11101 10011 10011 11101", 'Ь' to "10000 10000 10000 11110 10001 10001 11110",
        'Э' to "01110 10001 00001 00111 00001 10001 01110", 'Ю' to "10010 10101 10101 11101 10101 10101 10010",
        'Я' to "01111 10001 10001 01111 00101 01001 10001",
        'D' to "11110 10001 10001 10001 10001 10001 11110", 'F' to "11111 10000 10000 11110 10000 10000 10000",
        'G' to "01110 10001 10000 10111 10001 10001 01111", 'I' to "01110 00100 00100 00100 00100 00100 01110",
        'J' to "00111 00010 00010 00010 00010 10010 01100", 'L' to "10000 10000 10000 10000 10000 10000 11111",
        'N' to "10001 10001 11001 10101 10011 10001 10001", 'Q' to "01110 10001 10001 10001 10101 10010 01101",
        'R' to "11110 10001 10001 11110 10100 10010 10001", 'S' to "01111 10000 10000 01110 00001 00001 11110",
        'U' to "10001 10001 10001 10001 10001 10001 01110", 'V' to "10001 10001 10001 10001 10001 01010 00100",
        'W' to "10001 10001 10001 10101 10101 10101 01010", 'Y' to "10001 10001 01010 00100 00100 00100 00100",
        'Z' to "11111 00001 00010 00100 01000 10000 11111",
        '0' to "01110 10001 10011 10101 11001 10001 01110", '1' to "00100 01100 00100 00100 00100 00100 01110",
        '2' to "01110 10001 00001 00010 00100 01000 11111", '3' to "11111 00010 00100 00010 00001 10001 01110",
        '4' to "00010 00110 01010 10010 11111 00010 00010", '5' to "11111 10000 11110 00001 00001 10001 01110",
        '6' to "00110 01000 10000 11110 10001 10001 01110", '7' to "11111 00001 00010 00100 01000 01000 01000",
        '8' to "01110 10001 10001 01110 10001 10001 01110", '9' to "01110 10001 10001 01111 00001 00010 01100",
        '.' to "00000 00000 00000 00000 00000 00000 00100", ',' to "00000 00000 00000 00000 00000 00100 01000",
        '!' to "00100 00100 00100 00100 00100 00000 00100", '?' to "01110 10001 00001 00010 00100 00000 00100",
        ':' to "00000 00000 00100 00000 00000 00100 00000", ';' to "00000 00000 00100 00000 00000 00100 01000",
        '-' to "00000 00000 00000 01110 00000 00000 00000", '—' to "00000 00000 00000 11111 00000 00000 00000",
        '\'' to "00100 00100 00000 00000 00000 00000 00000", '"' to "01010 01010 00000 00000 00000 00000 00000",
        '«' to "00000 00101 01010 10100 01010 00101 00000", '»' to "00000 10100 01010 00101 01010 10100 00000",
        '(' to "00010 00100 01000 01000 01000 00100 00010", ')' to "01000 00100 00010 00010 00010 00100 01000",
        '/' to "00001 00010 00010 00100 01000 01000 10000", '&' to "01100 10010 10100 01000 10101 10010 01101",
    )
    mapOf('A' to 'А', 'B' to 'В', 'C' to 'С', 'E' to 'Е', 'H' to 'Н', 'K' to 'К', 'M' to 'М', 'O' to 'О', 'P' to 'Р', 'T' to 'Т', 'X' to 'Х')
        .forEach { (l, c) -> src[l] = src.getValue(c) }
    src['’'] = src.getValue('\''); src['‘'] = src.getValue('\''); src['–'] = src.getValue('-')
    src['„'] = src.getValue('"'); src['“'] = src.getValue('"'); src['”'] = src.getValue('"')
    src.mapValues { (_, s) ->
        val rows = s.split(' ')
        val used = (0..4).filter { x -> rows.any { it[x] == '1' } }.ifEmpty { listOf(0) }
        val x0 = used.min(); val x1 = used.max()
        val pts = mutableListOf<IntArray>()
        rows.forEachIndexed { y, r -> for (x in x0..x1) if (r[x] == '1') pts += intArrayOf(x - x0, y) }
        (x1 - x0 + 1) to pts
    }
}
