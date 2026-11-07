package melos.droid

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import melos.core.Browse
import melos.core.Paths
import melos.host.Services
import melos.host.Toasts
import melos.ui.DroidRoot

// Melos на Android: гейт логина (токен Яндекса) → мобильная оболочка DroidRoot (общий UI с десктопом).
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // тот же порядок, что на десктопе (Main.kт); любой сбой старта — сразу на экран, без logcat
        val error = runCatching {
            Paths.init(applicationContext)              // первым: Look/Settings читают Paths при инициализации
            Droid.init(applicationContext)
            Browse.url = { url -> runCatching { android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).also { it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }.let(applicationContext::startActivity) } }
            Browse.dir = { dir -> Toasts.shortNotify("Папка: ${dir.path}") }
            Browse.pickDir = { _, _ -> Toasts.error("Выбор папки на Android — следующий этап") }
            Services.load()
            Player_init()
        }.exceptionOrNull()
        // отладка: am start ... --ez wave true — сразу включить «Мою волну» (как MELOS_WAVE на десктопе)
        if (error == null && intent.getBooleanExtra("wave", false)) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                kotlinx.coroutines.delay(3000)
                runCatching {
                    val r = Services.byId("ynd")!!.murglar.nodeResolver
                    melos.player.Player.playRadio(r.getRootNodes(false).first { it.nodeType == com.badmanners.murglar.lib.core.model.node.NodeType.RADIO } as com.badmanners.murglar.lib.core.model.radio.BaseRadio)
                }.onFailure { Toasts.error("Волна: ${it.message}") }
            }
        }
        setContent { if (error == null) DroidApp(playerExtra = intent.getBooleanExtra("player", false)) else CrashScreen(error) }
    }

    @Composable
    private fun DroidApp(playerExtra: Boolean) {
        MaterialTheme {
            val context = androidx.compose.ui.platform.LocalContext.current
            LaunchedEffectOnce { Toasts.events.collect { t -> Toast.makeText(context, t.text, if (t.long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() } }
            val services by Services.all.collectAsState()
            Services.revision.collectAsState()
            val ynd = services.firstOrNull { it.id == "ynd" }
            val logged = ynd != null && runCatching { ynd.murglar.loginResolver.isLogged }.getOrDefault(false)
            if (logged) DroidRoot(openPlayer = playerExtra) else LoginScreen()
        }
    }

    @Composable
    private fun CrashScreen(error: Throwable) {
        Column(Modifier.fillMaxSize().background(Color(0xFFFFF3F3)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Melos упал при старте", fontSize = 20.sp, color = Color(0xFFB3261E))
            Text(error.stackTraceToString(), fontSize = 11.sp)
        }
    }
}

// ---- вход: OAuth-токен (как на десктопе «или по токену») ----

@Composable
private fun LoginScreen() {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.weight(0.6f))
        Text("Melos", fontSize = 34.sp)
        Text("Вставьте OAuth-токен Яндекс Музыки", color = Color.Gray)
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Токен") }, enabled = !busy)
        Button({
            if (busy || token.isBlank()) return@Button
            busy = true
            scope.launch {
                runCatching { Services.byId("ynd")!!.murglar.loginResolver.credentialsLogin("token", mapOf("token" to token.trim())) }
                    .onSuccess { Services.bump() }
                    .onFailure { Toasts.error("Не вошёл: ${it.message}") }
                busy = false
            }
        }) { Text(if (busy) "Вхожу…" else "Войти") }
        Text("Токен можно взять на ym.marshal.dev/token", color = Color.Gray, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun LaunchedEffectOnce(block: suspend () -> Unit) =
    androidx.compose.runtime.LaunchedEffect(Unit) { block() }

private fun Player_init() {
    melos.player.Player.init()
    melos.library.ServiceLikes.start()
}
