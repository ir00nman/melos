package melos.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import melos.core.Settings
import java.net.InetAddress

// Сеть нужно знать для офлайн-приоритетов и Медиатеки. Яндекс может лежать при рабочем интернете,
// поэтому проверяем несколько хостов; ручной «Офлайн-режим» в настройках главнее всего.
// Проверка блокирующая — живёт только в фоновом корутинном цикле, UI лишь читает флаг.
object NetStatus {
    private val hosts = listOf("api.music.yandex.net", "api.vk.com", "soundcloud.com")

    val offline = MutableStateFlow(false)

    /** Ручной режим: true — сеть считается недоступной, что бы ни было на самом деле. */
    var manualOffline: Boolean
        get() = Settings.store.bool("offline.manual") == true
        set(v) {
            Settings.store.put("offline.manual", v)
            offline.value = v
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Асинхронная перепроверка — безопасно звать из UI. */
    fun recheck() {
        scope.launch { recheckBlocking() }
    }

    suspend fun recheckBlocking() {
        val auto = !hosts.any {
            runCatching { InetAddress.getByName(it).isReachable(1500) }.getOrDefault(false)
        }
        offline.value = manualOffline || auto
    }

    /** Периодическая проверка — стартует раз в жизни приложения. */
    fun start() {
        scope.launch {
            recheckBlocking()
            while (isActive) {
                delay(30_000)
                recheckBlocking()
            }
        }
    }
}
