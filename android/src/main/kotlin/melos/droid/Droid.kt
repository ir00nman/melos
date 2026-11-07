package melos.droid

import android.content.Context

// Контекст приложения для слоёв, которые на десктопе его не знали (VlcEngine/LibVLC).
object Droid {
    lateinit var app: Context; private set
    fun init(context: Context) {
        if (!::app.isInitialized) {
            app = context.applicationContext
            melos.ui.Look.brain = false                 // «мозг» рисуется через skia — на Android этап 3
        }
    }
}
