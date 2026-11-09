package melos.host

import melos.core.Browse

import com.badmanners.murglar.lib.core.network.NetworkRequest
import com.badmanners.murglar.lib.core.network.ResponseConverters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

// самоподмену APK внутри Android сделать нельзя, ставит пользователь. Поверхность — как на десктопе.
object Updater {
    private const val REPO = "ir00nman/melos"
    val releasesPage = "https://github.com/$REPO/releases/latest"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data class Available(val version: String, val assetUrl: String) : State()
        data class Downloading(val frac: Float) : State()
        data object Restarting : State()
    }

    val state = MutableStateFlow<State>(State.Idle)
    private var dismissed: String? = null

    val current: String by lazy {
        runCatching {
            javaClass.getResourceAsStream("/melos/version.txt")?.bufferedReader()?.readText()
                ?.trim()?.substringAfter('=', "")?.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0"
    }

    fun startCheck() {
        if (state.value is State.Checking) return
        state.value = State.Checking
        scope.launch {
            state.value = runCatching {
                val body = HttpNetwork.execute(
                    NetworkRequest.Builder("https://api.github.com/repos/$REPO/releases/latest").build(),
                    ResponseConverters.asString()).result
                val o = json.parseToJsonElement(body).jsonObject
                val tag = o.str("tag_name").orEmpty().removePrefix("v")
                if (isNewer(tag, current) && tag != dismissed) State.Available(tag, releasesPage) else State.Idle
            }.getOrElse { State.Idle }
        }
    }

    fun update() { (state.value as? State.Available)?.let { Browse.url(releasesPage) } }
    fun dismiss() { (state.value as? State.Available)?.let { dismissed = it.version }; state.value = State.Idle }

    private fun isNewer(tag: String, cur: String): Boolean {
        fun parts(s: String) = s.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(tag); val b = parts(cur)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
