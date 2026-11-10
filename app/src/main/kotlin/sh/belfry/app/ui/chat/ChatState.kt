package sh.belfry.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import sh.belfry.core.model.Message

/** A picture opened full screen: a remote address or one decoded on the device. */
class ViewerImage(val url: String?, val bitmap: ImageBitmap?, val name: String?)

/** What is open in one channel (or one thread screen): unfolded threads, sheets, the edit in progress. */
@Stable
class ChatState(val groupId: String) {
    /** Unfolded inline threads by feed key (a message id, or "act:<root id>" for a resurfaced line) → focus its reply box. */
    val openThreads = mutableStateMapOf<String, Boolean>()
    /** The topic the channel is filtered by (null: everything). */
    var topic by mutableStateOf<String?>(null)
    /** The message whose menu is open. */
    var acting by mutableStateOf<Message?>(null)
    /** The menu shows only the emoji picker ("Add a reaction"). */
    var emojiOnly by mutableStateOf(false)
    var editing by mutableStateOf<Message?>(null)
    var deleting by mutableStateOf<Message?>(null)
    var viewer by mutableStateOf<ViewerImage?>(null)
    var plusOpen by mutableStateOf(false)

    fun toggleThread(key: String, focus: Boolean = false) {
        if (openThreads.containsKey(key) && !focus) openThreads.remove(key) else openThreads[key] = focus
    }

    fun menu(m: Message, emojiOnly: Boolean = false) {
        this.emojiOnly = emojiOnly
        acting = m
    }
}

/**
 * A topic another screen (the Topics page) asked a channel to open: (channel id, topic or null
 * for everything). The channel's state takes it when it is next shown.
 */
object TopicRequest {
    var pending by mutableStateOf<Pair<String, String?>?>(null)
}

@Composable
fun rememberChatState(groupId: String): ChatState = remember(groupId) { ChatState(groupId) }.also { state ->
    val asked = TopicRequest.pending
    if (asked != null && asked.first == groupId) {
        androidx.compose.runtime.SideEffect {
            state.topic = asked.second
            TopicRequest.pending = null
        }
    }
}
