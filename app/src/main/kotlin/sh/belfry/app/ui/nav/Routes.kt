package sh.belfry.app.ui.nav

import android.net.Uri
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument

/**
 * Every route of the app, in one place. A `const` is the pattern a screen is registered under;
 * the function of the same name builds the string to navigate to (ids are URL-encoded here and
 * decoded by navigation-compose: read them with [arg] / [optArg]).
 *
 * Owners: shell = ui/shell + ui/chat (ShellRoutes.kt); info = ui/info/InfoRoutes.kt;
 * people = ui/people/PeopleRoutes.kt; cards = ui/cards/CardsRoutes.kt.
 */
object Routes {
    private fun enc(s: String): String = Uri.encode(s)

    // ---- shell ----
    /** The drawer + the open channel. Switch channels with `LocalNav.current.openChannel(id)`, not by navigating. */
    const val HOME = "home"
    const val THREAD = "thread/{id}/{root}"
    fun thread(channel: String, rootId: String) = "thread/${enc(channel)}/${enc(rootId)}"
    const val KIT = "kit"
    const val MENTIONS = "mentions"

    // ---- info (U1) ----
    const val SEARCH = "search?channel={channel}"
    /** Search one channel, or all of them (null). */
    fun search(channel: String? = null) = if (channel == null) "search" else "search?channel=${enc(channel)}"
    const val CHANNEL_INFO = "info/{id}"
    fun channelInfo(channel: String) = "info/${enc(channel)}"
    const val INVITE = "invite/{id}"
    fun invite(channel: String) = "invite/${enc(channel)}"
    const val TOPICS = "topics/{id}"
    fun topics(channel: String) = "topics/${enc(channel)}"
    /** A channel's files and its drop box links. */
    const val FILES = "files/{id}"
    fun files(channel: String) = "files/${enc(channel)}"
    /** New drop box link for a channel. */
    const val DROP = "drop/{id}"
    fun drop(channel: String) = "drop/${enc(channel)}"
    const val FOLLOW = "follow/{id}"
    fun follow(channel: String) = "follow/${enc(channel)}"

    // ---- people (U2) ----
    const val PROFILE = "profile/{pubkey}"
    fun profile(pubkey: String) = "profile/${enc(pubkey)}"
    const val ME = "me"
    const val SESSIONS = "sessions"
    /** Me → Notifications (push). */
    const val NOTIFICATIONS = "notifications"
    /** Everyone in the community. */
    const val MEMBERS = "members"
    /** Direct messages and e-mail: the list. */
    const val MESSAGES = "messages"
    const val CONVERSATION = "conversation/{conv}"
    /** [conv] is a DmSnapshot conversation key: `n:<pubkeys>`, `m:<address>|<subject key>` or `m:<address>`. */
    fun conversation(conv: String) = "conversation/${enc(conv)}"
    const val NEW_CONVERSATION = "new-conversation"

    // ---- cards (U3) ----
    const val EVENTS = "events?channel={channel}"
    /** A channel's events, or every channel's (null). */
    fun events(channel: String? = null) = if (channel == null) "events" else "events?channel=${enc(channel)}"
    const val EVENT = "event/{coord}"
    /** An event by its coordinate, or by the last part of its URL (`https://<domain>/events/<link>`). */
    fun event(coord: String) = "event/${enc(coord)}"
    const val EDIT_EVENT = "edit-event/{coord}"
    fun editEvent(coord: String) = "edit-event/${enc(coord)}"
    /** [createEvent] from All events: no channel (from a channel's ＋, the event's URL goes into its composer). */
    const val NO_CHANNEL = "-"
    const val POD = "pod/{id}"
    fun pod(id: String) = "pod/${enc(id)}"
    const val PROPOSAL = "proposal/{id}"
    /** [groupId] is the proposal's own group (`prop-…`). */
    fun proposal(groupId: String) = "proposal/${enc(groupId)}"
    const val CREATE_CHANNEL = "create-channel"
    const val CREATE_POD = "create-pod/{parent}"
    fun createPod(parent: String) = "create-pod/${enc(parent)}"
    const val CREATE_EVENT = "create-event/{channel}"
    fun createEvent(channel: String) = "create-event/${enc(channel)}"
    const val CREATE_PROPOSAL = "create-proposal/{parent}"
    fun createProposal(parent: String) = "create-proposal/${enc(parent)}"

    /** Declares an optional query argument (`?name={name}`), null when absent. */
    fun optional(name: String): NamedNavArgument = navArgument(name) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    }
}

/** A required path argument, decoded. */
fun NavBackStackEntry.arg(name: String): String = arguments?.getString(name) ?: ""

/** An optional query argument, decoded; null when absent. */
fun NavBackStackEntry.optArg(name: String): String? = arguments?.getString(name)?.takeIf { it.isNotEmpty() }
