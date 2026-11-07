package melos.download

import com.badmanners.murglar.lib.core.model.track.BaseTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import melos.core.Paths
import melos.host.Toasts
import java.io.File

// Загрузки на Android (этап 2): WRITE_EXTERNAL_STORAGE/SAF/MediaStore — пока заглушка с тем же API,
// чтобы общий UI (строки треков, экран «Загрузки») компилировался и честно говорил «скоро».
object Downloads {
    sealed interface State {
        data class Running(val progress: Float) : State
        data class Done(val file: File) : State
        data class Failed(val reason: String) : State
    }

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states = _states.asStateFlow()

    var folder: File
        get() = Paths.defaultDownloads
        set(value) {}

    fun download(tracks: List<BaseTrack>, to: File? = null) {
        Toasts.error("Загрузки на Android — следующий этап")
    }

    fun download(track: BaseTrack) = download(listOf(track))
}
