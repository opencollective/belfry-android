package sh.belfry.app.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What the home screen shows: the open channel and, once, a message to scroll to. Lives above the navigation graph. */
@Stable
class ShellState(initialChannel: String = "general") {
    var channel by mutableStateOf(initialChannel)
        private set
    var focusMessage by mutableStateOf<String?>(null)
    /** The person picked a channel themselves (the saved "last open" no longer applies). */
    var touched = false
        private set

    fun open(id: String, messageId: String? = null) {
        touched = true
        channel = id
        focusMessage = messageId
    }

    /** The channel open last time, loaded from the store at start. */
    fun restore(id: String) {
        if (!touched) channel = id
    }

    companion object {
        const val K_LAST_CHANNEL = "ui:last-channel"
    }
}
