package melos.host

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import melos.core.Settings

// медиа-клавиши macOS: NX_SYSDEFINED через CGEventTap (listen-only), разбор через NSEvent(data1).
// Системный Now Playing (MediaRemote) закрыт для неподписанных процессов — вызовы сегфолтят, путь не используется.
object MacMediaHub {
    private const val TAG = "MediaKeys"
    private const val NX_SYSDEFINED = 14
    private const val NX_SUBTYPE_AUX_CONTROL = 8
    private val NX_KEYS = mapOf(16 to "play", 17 to "next", 18 to "prev")   // NX_KEYCODE_PLAY/NEXT/PREVIOUS

    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer?
    }
    private interface CoreFoundation : Library {
        fun CFRunLoopGetCurrent(): Pointer?
        fun CFRunLoopRun()
        fun CFMachPortCreateRunLoopSource(allocator: Pointer?, port: Pointer?, order: Int): Pointer?
        fun CFRunLoopAddSource(rl: Pointer?, source: Pointer?, mode: Pointer?)
    }
    private interface CoreGraphics : Library {
        fun CGEventTapCreate(tap: Int, place: Int, options: Int, mask: Long, callback: Callback, userInfo: Pointer?): Pointer?
    }

    private var objc: ObjC? = null
    private var msgSend: Function? = null
    private val lastDown = HashMap<String, Long>()

    fun init() {
        if (!melos.core.Paths.isMac || Settings.store.bool("mediakeys.enabled") == false) return
        runCatching { bootstrap() }.onFailure { Log.w(TAG, "недоступны: ${it.message}") }
    }

    private fun cls(n: String) = objc?.objc_getClass(n)
    private fun sel(n: String) = objc?.sel_registerName(n)
    private fun send(receiver: Pointer?, selector: Pointer?, vararg args: Any?): Pointer? =
        msgSend?.invoke(Pointer::class.java, arrayOf(receiver, selector, *args)) as? Pointer?
    private fun sendInt(receiver: Pointer?, selector: Pointer?, vararg args: Any?): Long =
        msgSend?.invoke(Long::class.java, arrayOf(receiver, selector, *args)) as? Long ?: 0L

    private fun bootstrap() {
        objc = Native.load("objc", ObjC::class.java)
        val cf = Native.load("CoreFoundation", CoreFoundation::class.java)
        val cg = Native.load("CoreGraphics", CoreGraphics::class.java)
        msgSend = NativeLibrary.getInstance("objc").getFunction("objc_msgSend")

        // listen-only (1): только наблюдаем, не перехватываем; нажатия уходят и системе
        val tap = cg.CGEventTapCreate(1 /* kCGSessionEventTap */, 0 /* head insert */, 1 /* listen only */,
            1L shl NX_SYSDEFINED, object : Callback {
                fun invoke(proxy: Pointer?, type: Int, event: Pointer?, userInfo: Pointer?): Pointer? {
                    if (type == NX_SYSDEFINED && event != null) handle(event)
                    return event
                }
            }, null)
        if (tap == null) {
            Log.w(TAG, "CGEventTap не создан — нет разрешения «Мониторинг ввода» (Системные настройки → Конфиденциальность и безопасность → Мониторинг ввода → Melos/java)")
            return
        }
        val source = cf.CFMachPortCreateRunLoopSource(null, tap, 0)
        val commonModes = runCatching {
            NativeLibrary.getInstance("CoreFoundation").getGlobalVariableAddress("kCFRunLoopCommonModes").getPointer(0)
        }.getOrNull()
        Thread({
            runCatching {
                val rl = cf.CFRunLoopGetCurrent()
                cf.CFRunLoopAddSource(rl, source, commonModes)
                Log.i(TAG, "слушаю медиа-клавиши (Play/Pause, ⏭, ⏮)")
                cf.CFRunLoopRun()
            }.onFailure { Log.w(TAG, "runloop: ${it.message}") }
        }, "melos-mediakeys").apply { isDaemon = true }.start()
    }

    private fun handle(cgEvent: Pointer) {
        runCatching {
            // NX-событие заворачиваем в NSEvent: subtype 8 (AUX_CONTROL_BUTTONS), клавиша — в data1
            val ns = send(cls("NSEvent"), sel("eventWithCGEvent:"), cgEvent) ?: return
            if (sendInt(ns, sel("subtype")) != NX_SUBTYPE_AUX_CONTROL.toLong()) return
            val data1 = sendInt(ns, sel("data1"))
            val key = ((data1 shr 16) and 0xFFFF).toInt()
            val down = ((data1 shr 8) and 0x1).toInt() == 1
            val name = NX_KEYS[key] ?: return
            if (!down) return
            val now = System.currentTimeMillis()
            if (now - (lastDown[name] ?: 0L) < 150) return          // автоповтор клавиши глушим
            lastDown[name] = now
            Log.i(TAG, "клавиша: $name")
            when (name) {
                "play" -> melos.player.Player.toggle()
                "next" -> melos.player.Player.next()
                "prev" -> melos.player.Player.prev()
            }
        }.onFailure { Log.w(TAG, "handle: ${it.message}") }
    }
}
