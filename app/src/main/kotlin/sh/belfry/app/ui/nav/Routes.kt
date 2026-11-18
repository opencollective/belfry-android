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
    /** A channel pushed over a place (from a card, a link, search): with a back arrow. */
    const val CHAT = "chat/{id}?m={m}"
    fun chat(channel: String, messageId: String? = null) = "chat/${enc(channel)}" + if (messageId != null) "?m=${enc(messageId)}" else ""

    // ---- places (SPEC 19.31) ----
    /** To-dos on me and open ones, across the channels I'm in (Home's To-dos tile). */
    const val MY_TODOS = "my-todos"
    /** The charter: the agreements each person ticks (SPEC 19.32). */
    const val CHARTER = "charter"
    /** Who agreed to one agreement (stewards). */
    const val CHARTER_WHO = "charter-who/{id}"
    fun charterWho(id: String) = "charter-who/${enc(id)}"
    /** The archived channels and groups I can open (SPEC 19.30). */
    const val ARCHIVED = "archived"
    /** Belfry settings (stewards): branding, roles, membership, channels. */
    const val BELFRY_SETTINGS = "belfry-settings"
    const val SETTINGS_CHANNELS = "settings/channels"
    const val SETTINGS_ROLES = "settings/roles"
    const val SETTINGS_MEMBERSHIP = "settings/membership"
    /** A guest asks to become a member (guestapply.py). */
    const val APPLY = "apply"
    /** A new member's first minutes: welcome, the charter, name and photo (SPEC 19.32). */
    const val WELCOME = "welcome"
    const val MENTIONS = "mentions"

    // ---- info (U1) ----
    const val SEARCH = "search?channel={channel}"
    /** Search one channel, or all of them (null). */
    fun search(channel: String? = null) = if (channel == null) "search" else "search?channel=${enc(channel)}"
    const val CHANNEL_INFO = "info/{id}?tab={tab}"
    /** A channel's page; TAB opens it on Members, Todos, Files or its Log (`InfoTab.KEY_…`). */
    fun channelInfo(channel: String, tab: String? = null) = "info/${enc(channel)}" + if (tab != null) "?tab=${enc(tab)}" else ""
    /** One of a channel's screens from its pull-down (SPEC: the pull-down is a menu of rows that each push a screen). */
    const val CHANNEL_PLACE = "place/{id}/{key}"
    fun channelPlace(channel: String, key: String) = "place/${enc(channel)}/${enc(key)}"
    /** One to-do of a channel: tick it, claim / assign / unassign it, discuss it (SPEC 19.40). */
    const val TODO = "todo/{id}/{task}"
    fun todo(channel: String, taskId: String) = "todo/${enc(channel)}/${enc(taskId)}"
    const val INVITE = "invite/{id}?members={members}"
    fun invite(channel: String) = "invite/${enc(channel)}"
    /** From Members: "Invite to <community>", no search by name (inviting/sheet-from-members). */
    fun inviteToCommunity() = "invite/general?members=1"
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
    const val PROFILE = "profile/{pubkey}?group={group}"
    /** A person; GROUP: opened from that channel's, group's or event's people (their role there shows). */
    fun profile(pubkey: String, group: String? = null) = "profile/${enc(pubkey)}" + if (group != null) "?group=${enc(group)}" else ""
    const val ME = "me"
    const val SESSIONS = "sessions"
    /** Me → Notifications (push). */
    const val NOTIFICATIONS = "notifications"
    /** Me → Branding (admins of the belfry): its logo, cover and colours. */
    const val BRANDING = "branding"
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
    const val EVENT = "event/{coord}?t={t}"
    /** An event by its coordinate, or by the last part of its URL (`https://<domain>/events/<link>`). */
    fun event(coord: String, ticket: String? = null) = "event/${enc(coord)}" + if (ticket != null) "?t=${enc(ticket)}" else ""
    /** Checking people in at an event (hosts, co-hosts, Door). */
    const val CHECK_IN = "check-in/{coord}"
    fun checkIn(coord: String) = "check-in/${enc(coord)}"
    const val EDIT_EVENT = "edit-event/{coord}"
    fun editEvent(coord: String) = "edit-event/${enc(coord)}"
    /** [createEvent] from All events: no channel (from a channel's ＋, the event's URL goes into its composer). */
    const val NO_CHANNEL = "-"
    const val POD = "pod/{id}"
    fun pod(id: String) = "pod/${enc(id)}"
    const val PROPOSAL = "proposal/{id}"
    /** [groupId] is the proposal's own group (`prop-…`). */
    fun proposal(groupId: String) = "proposal/${enc(groupId)}"
    const val CREATE_CHANNEL = "create-channel?category={category}"
    /** A new channel; CATEGORY (a belfry admin's ＋ on a category of the drawer): it goes at its end (SPEC 19.34). */
    fun createChannel(category: String? = null) = "create-channel" + if (category != null) "?category=${enc(category)}" else ""
    const val CREATE_POD = "create-pod/{parent}"
    fun createPod(parent: String) = "create-pod/${enc(parent)}"
    const val CREATE_EVENT = "create-event/{channel}"
    fun createEvent(channel: String) = "create-event/${enc(channel)}"
    /** A new meeting made from a channel's ＋ (web 2026-11-12). */
    const val CREATE_MEETING = "create-meeting/{channel}"
    fun createMeeting(channel: String) = "create-meeting/${enc(channel)}"
    const val CREATE_PROPOSAL = "create-proposal/{parent}?from={from}&in={in}"
    /** FROM: a request (message id in channel FROMCHANNEL) the proposal starts from. */
    fun createProposal(parent: String, from: String? = null, fromChannel: String? = null) =
        "create-proposal/${enc(parent)}" + if (from != null && fromChannel != null) "?from=${enc(from)}&in=${enc(fromChannel)}" else ""

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

/** The screens a channel's pull-down pushes (Routes.channelPlace). */
object ChannelPlaceKey {
    const val PINNED = "pinned"
    const val DECIDE = "decide"
    const val TODOS = "todos"
    const val PHOTOS = "photos"
    const val GROUPS = "groups"
    const val MEMBERS = "members"
    const val LOG = "log"
    const val SETTINGS = "settings"
}
