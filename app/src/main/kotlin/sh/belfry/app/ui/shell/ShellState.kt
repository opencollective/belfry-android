package sh.belfry.app.ui.shell

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** What the home screen shows: the open channel and, once, a message to scroll to. Lives above the navigation graph. */
@Stable
class ShellState(initialChannel: String = "general") {
    var channel by mutableStateOf(initialChannel)
        private set
    var focusMessage by mutableStateOf<String?>(null)
    /** A large screen: the thread open in the third column (root message id), in [channel]. */
    var thread by mutableStateOf<String?>(null)
    /** Which of the five places is on screen (SPEC 19.31): Home, Calendar, Groups or Town (Create is a sheet). */
    var place by mutableStateOf(Place.HOME)
    /** The Create menu is open. */
    var creating by mutableStateOf(false)
    /** The person picked a channel themselves (the saved "last open" no longer applies). */
    var touched = false
        private set

    fun open(id: String, messageId: String? = null) {
        touched = true
        place = Place.TOWN
        if (id != channel) thread = null
        channel = id
        focusMessage = messageId
    }

    /** Bumped by a second tap on Town in the main channel: it scrolls to the newest message. */
    var newestTick by mutableIntStateOf(0)
        private set

    /**
     * The Town tab (Xavier, 19 Nov): it always opens the belfry's main channel (group `general`),
     * never the last open one; a second tap there goes to the newest message.
     */
    fun townTapped() {
        if (place == Place.TOWN && channel == MAIN_CHANNEL && thread == null) { newestTick++; return }
        open(MAIN_CHANNEL)
    }

    /** The channel open last time, loaded from the store at start. */
    fun restore(id: String) {
        if (!touched) channel = id
    }

    /** The bottom bar's places (Create is not one: it opens a menu over the place on screen). */
    enum class Place { HOME, CALENDAR, GROUPS, TOWN }

    companion object {
        const val K_LAST_CHANNEL = "ui:last-channel"
        const val MAIN_CHANNEL = "general"
    }
}

/** The places' state for screens drawn inside them (Home's "Calendar" link…); null in tests that show a screen alone. */
val LocalShell = staticCompositionLocalOf<ShellState?> { null }
