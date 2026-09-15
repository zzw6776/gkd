package li.gkd.app.priv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ScreenshotMonitorState {
    val message: StateFlow<String>
        field = MutableStateFlow("未启动")

    fun update(message: String) {
        this.message.value = message
    }
}
