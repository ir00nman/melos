package melos.host

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.notification.NotificationMiddleware
import com.badmanners.murglar.lib.core.preference.PreferenceMiddleware
import melos.core.JsonStore
import melos.core.Paths
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Настройки плагина: у каждого мурглара свой JSON-файл.
class MelosPreferences(murglarId: String) : PreferenceMiddleware {
    private val store = JsonStore(File(Paths.file("prefs").apply { mkdirs() }, "$murglarId.json"))

    override fun getKeys() = store.keys()
    override fun contains(key: String) = key in store

    @Suppress("UNCHECKED_CAST")
    override fun <S : String?> getString(key: String, defValue: S): S = (store.string(key) ?: defValue) as S
    override fun setString(key: String, value: String?) = store.put(key, value)

    @Suppress("UNCHECKED_CAST")
    override fun <I : Int?> getInt(key: String, defValue: I): I = (store.long(key)?.toInt() ?: defValue) as I
    override fun setInt(key: String, value: Int?) = store.put(key, value)

    @Suppress("UNCHECKED_CAST")
    override fun <L : Long?> getLong(key: String, defValue: L): L = (store.long(key) ?: defValue) as L
    override fun setLong(key: String, value: Long?) = store.put(key, value)

    @Suppress("UNCHECKED_CAST")
    override fun <F : Float?> getFloat(key: String, defValue: F): F = (store.double(key)?.toFloat() ?: defValue) as F
    override fun setFloat(key: String, value: Float?) = store.put(key, value)

    @Suppress("UNCHECKED_CAST")
    override fun <B : Boolean?> getBoolean(key: String, defValue: B): B = (store.bool(key) ?: defValue) as B
    override fun setBoolean(key: String, value: Boolean?) = store.put(key, value)

    override fun remove(key: String) = store.put(key, null as String?)
    override fun clearAll() = store.removeAll { true }
}

// Тосты: плагины и плеер шлют сюда, UI показывает внизу над плеером.
object Toasts : NotificationMiddleware {
    data class Toast(val text: String, val long: Boolean, val error: Boolean = false, val id: Long = System.nanoTime())

    private val flow = MutableSharedFlow<Toast>(extraBufferCapacity = 16)
    val events = flow.asSharedFlow()

    override fun shortNotify(text: String) { flow.tryEmit(Toast(text, false)) }
    override fun longNotify(text: String) { Log.i("Toast", text); flow.tryEmit(Toast(text, true)) }
    fun error(text: String) { Log.w("Toast", text); flow.tryEmit(Toast(text, true, error = true)) }
}

// Лог: в консоль и в файл за день.
object Log : LoggerMiddleware {
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val file by lazy { File(Paths.logs, "melos-${java.time.LocalDate.now()}.log") }

    @Synchronized
    private fun write(level: Char, tag: String, message: String?, tr: Throwable?) {
        val trace = tr?.let { StringWriter().also { w -> it.printStackTrace(PrintWriter(w)) }.toString() }
        val line = buildString {
            append(LocalDateTime.now().format(time)).append(' ').append(level).append('/').append(tag).append(": ")
            append(message.orEmpty())
            if (trace != null) append('\n').append(trace)
        }
        if (level >= 'I' || level == 'E') System.err.println(line)
        runCatching { file.appendText(line + "\n") }
    }

    override fun v(tag: String, message: String?) = write('V', tag, message, null)
    override fun v(tag: String, tr: Throwable?) = write('V', tag, null, tr)
    override fun v(tag: String, message: String?, tr: Throwable?) = write('V', tag, message, tr)
    override fun d(tag: String, message: String?) = write('D', tag, message, null)
    override fun d(tag: String, tr: Throwable?) = write('D', tag, null, tr)
    override fun d(tag: String, message: String?, tr: Throwable?) = write('D', tag, message, tr)
    override fun i(tag: String, message: String?) = write('I', tag, message, null)
    override fun i(tag: String, tr: Throwable?) = write('I', tag, null, tr)
    override fun i(tag: String, message: String?, tr: Throwable?) = write('I', tag, message, tr)
    override fun w(tag: String, message: String?) = write('W', tag, message, null)
    override fun w(tag: String, tr: Throwable?) = write('W', tag, null, tr)
    override fun w(tag: String, message: String?, tr: Throwable?) = write('W', tag, message, tr)
    override fun e(tag: String, message: String?) = write('E', tag, message, null)
    override fun e(tag: String, tr: Throwable?) = write('E', tag, null, tr)
    override fun e(tag: String, message: String?, tr: Throwable?) = write('E', tag, message, tr)
    override fun crashlyticsLog(tag: String, message: String?) = write('E', tag, message, null)
    override fun crashlyticsLog(tag: String, message: String?, tr: Throwable?) = write('E', tag, message, tr)
    override fun dump(tag: String, largeText: String?) = write('D', tag, largeText?.take(20_000), null)
}
