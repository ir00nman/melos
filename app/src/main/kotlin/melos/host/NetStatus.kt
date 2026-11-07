package melos.host

import java.net.InetAddress

/** Есть ли сеть (грубая проверка — для приоритета офлайн-источников в поиске). */
object NetStatus {
    @Volatile
    var offline: Boolean = runCatching { InetAddress.getByName("api.music.yandex.net").isReachable(1200) }.getOrDefault(false).not()
        private set

    fun recheck() {
        offline = runCatching { InetAddress.getByName("api.music.yandex.net").isReachable(1200) }.getOrDefault(false).not()
    }
}
