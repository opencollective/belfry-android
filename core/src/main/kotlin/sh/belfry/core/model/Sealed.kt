package sh.belfry.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * End-to-end encrypted conversations, as people see them (canvas "Messages · encryption",
 * approved 2026-11-20). Screens only: what is sealed, which of my devices can read it, how many
 * devices the others have. No key, npub or id is ever text here: devices have plain names
 * (only mine), people are pubkeys the screens turn into names. Filled by the sealed engine
 * (docs/sealed.md) through EncryptionService, or by the demo.
 */
enum class DeviceKind { PHONE, TABLET, COMPUTER }

/** One of my devices, named in full. FROM is the device that let it in (null for the first). */
data class MyDevice(
    val id: String,
    val name: String,
    val kind: DeviceKind,
    val added: Long,
    val from: String? = null,
    val thisDevice: Boolean = false,
    /** Last seen (Unix seconds), null when not known. */
    val active: Long? = null,
    /** This device may remove it (in my head, not this one; the review's 12). */
    val removable: Boolean = !thisDevice,
    /** False for this device while it waits to be allowed ([added] = when it last asked, 0 if unknown). */
    val allowed: Boolean = true,
)

/** A device change of someone else's in the last 30 days: added or removed, and when. */
data class DeviceChange(val added: Boolean, val at: Long)

/** Another person in a sealed conversation: how many devices, never which. */
data class PersonDevices(val pubkey: String, val count: Int, val change: DeviceChange? = null)

/** A device line in a sealed conversation's thread, among its messages. */
data class DeviceNotice(
    val id: String,
    val at: Long,
    val who: String,
    val kind: Kind,
    /** Only for my own devices: "You added a device · Safari on Mac". */
    val device: String? = null,
) {
    enum class Kind { ADDED, REMOVED, NEW_LOCKED }
}

/** Someone's new device that nobody of theirs could unlock: the others may share history. */
data class LockedDevice(val pubkey: String, val kind: DeviceKind?, val at: Long)

/** What one sealed conversation shows. */
data class ConvSeal(
    val others: List<PersonDevices> = emptyList(),
    /** People outside this belfry, by e-mail (their names). */
    val mail: List<String> = emptyList(),
    val notices: List<DeviceNotice> = emptyList(),
    /** Set while someone's new device waits for the history (the amber line). */
    val waiting: LockedDevice? = null,
    /** When it became sealed: earlier messages read as usual, even on a device not yet allowed. */
    val since: Long? = null,
    /** History someone shared with this device (sealed.md §8), one block per sharer. */
    val shared: List<SharedBlock> = emptyList(),
    /** Times of messages that arrived the old, unsealed way after this device saw it sealed (§6, canvas v89). */
    val notSealed: Set<Long> = emptySet(),
) {
    fun isNotSealed(at: Long): Boolean = at in notSealed
}

/** Messages someone shared with this device, by their times (FROM..TO). Not proven authentic. */
data class SharedBlock(val by: String, val from: Long, val to: Long) {
    fun holds(at: Long): Boolean = at in from..to
}

/** A new device of mine asking for access, shown on my other devices. */
data class AccessAsk(val id: String, val device: String, val kind: DeviceKind, val at: Long)

/** A device added to my head without this device asking me (§5.2 step 2a): "‹name› was added just now". */
data class AddedDevice(
    val id: String, val name: String, val at: Long,
    /** sealed-v2 §3.3: a correspondent's message named a list of mine that has this device and that this device was never shown; their name. */
    val seenBy: String? = null,
)

/** Where and when my sealed conversations were started fresh (DEVICE: its signed-in name, null if unknown). */
data class FreshElsewhere(val device: String?, val at: Long)

/** The whole picture on this device. Empty for a guest or without a device key (nothing sealed). */
data class SealState(
    val myDevices: List<MyDevice> = emptyList(),
    val sealed: Map<String, ConvSeal> = emptyMap(),
    /** This device is new: earlier messages are locked on it (the amber banner). */
    val earlierLocked: Boolean = false,
    /** Conversations whose earlier messages this device can't read yet. */
    val locked: Set<String> = emptySet(),
    val asks: List<AccessAsk> = emptyList(),
    /** A device just linked by scanning my code (Link a phone or tablet). */
    val linked: String? = null,
    /** This device is in my head without its secret (a lost hand-over, f28220c): waiting for another of my devices. */
    val waitingKeys: Boolean = false,
    /** My sealed conversations were started fresh on another device, leaving this one out (the lead, 0.7.7). */
    val freshElsewhere: FreshElsewhere? = null,
    /** Devices added without a prompt here, not answered yet (canvas v183 messages/encryption-device-added). */
    val added: List<AddedDevice> = emptyList(),
    /**
     * While this device waits (not in my head, or in it without the key): the devices that hold the key,
     * by their session names ("Chat · Safari on Mac"), never a key or an id. Empty = not known:
     * the screens then say "Belfry on another device of yours" (canvas Messages v5, n-e2e-locked-named).
     */
    val unlockers: List<String> = emptyList(),
    /** When this device last asked to be added (Unix seconds; 0 = not yet). */
    val askedAt: Long = 0,
    /** In my head, the key not here yet: when this device was added (Unix seconds; 0 = not so, or unknown). */
    val addedAt: Long = 0,
    /**
     * In my head, a letterbox behind (sealed.md §5.7; canvas messages/encryption-waiting-device): a device that
     * added itself holds the key of the newer messages. Its session name(s); empty = not so. Everything else works.
     */
    val waitingFor: List<String> = emptyList(),
    /** Version 2 (docs/sealed-v2.md): the belfry's device lists; nothing is ever locked or waiting. */
    val v2: Boolean = false,
    /** §3.1: this device does not find itself on its account's list: 0 = it does, 1 = "Getting your messages ready…", 2 = after 15 s, "not on your device list yet" with Retry. */
    val notListed: Int = 0,
    /** §6.2: a new device: the earlier messages were encrypted for my other devices (until dismissed; the Ask button comes later). */
    val earlierElsewhere: Boolean = false,
    /** §5: conversations not sealed because their people have more than 100 devices together. */
    val tooMany: Set<String> = emptySet(),
    /** A device that added itself without a live chat session was removed by this device, at (Unix seconds; 0 = none to tell). */
    val removedJoinerAt: Long = 0,
    /** Demo and tests only: how many newer messages wait (the real count is the conversations' `lockedCount`). */
    val demoWaiting: Int = 0,
) {
    fun seal(conv: String): ConvSeal? = sealed[conv]
    val thisDevice: MyDevice? get() = myDevices.firstOrNull { it.thisDevice }

    /**
     * This device is the only one in my roster (canvas v117 messages/encryption-only-device): losing it
     * makes my sealed messages unreadable until Start fresh. False with no roster yet (no letterbox),
     * when this device is still waiting to be allowed, and for guests (no devices at all).
     */
    val onlyDevice: Boolean get() = !earlierLocked && !waitingKeys && myDevices.size == 1 && myDevices[0].thisDevice
}

/**
 * Who can unlock a waiting device (canvas Messages v5, n-e2e-locked-named): the session names of my
 * head's other devices. Pure, so the engine's service and the tests name them the same way.
 */
object Unlockers {
    /** "Chat · Safari on Mac": the application and the device of a row of /api/me/sessions, as Devices shows them. */
    fun sessionName(application: String?, device: String?): String? {
        val a = application?.trim().orEmpty().take(80)
        val d = device?.trim().orEmpty().take(80)
        return when {
            a.isEmpty() && d.isEmpty() -> null
            d.isEmpty() -> a
            a.isEmpty() || d.contains(a, ignoreCase = true) -> d
            else -> "$a · $d"
        }
    }

    /**
     * The names to show for DEVICES (my head's devices but this one), from NAMES (device → session
     * name; live or kept from the last read). A device with no known name is "another device of
     * yours" ([SealedText.UNKNOWN_DEVICE]), said once and last; when the session list was read just
     * now (LIVE) and others are named, a device with no session is left out: it can't open anything.
     */
    fun names(devices: List<String>, names: Map<String, String>, live: Boolean): List<String> {
        val known = devices.mapNotNull { names[it]?.takeIf { n -> n.isNotBlank() } }.distinct()
        val unnamed = devices.any { names[it].isNullOrBlank() }
        return if (unnamed && (known.isEmpty() || !live)) known + SealedText.UNKNOWN_DEVICE else known
    }
}

/** The fixed words of the encryption screens, so every screen and test say the same. */
object SealedText {
    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val TIME = DateTimeFormatter.ofPattern("H:mm", Locale.ENGLISH)

    private fun date(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochSecond(at).atZone(zone).toLocalDate()

    /** "12 Aug", or "12 Aug 2025" in another year. */
    fun day(at: Long, now: Long, zone: ZoneId): String {
        val d = date(at, zone)
        return if (d.year == date(now, zone).year) DAY_MONTH.format(d) else DAY_MONTH_YEAR.format(d)
    }

    /** "today", "yesterday", "on 2 Oct". */
    fun relDay(at: Long, now: Long, zone: ZoneId): String = when (ChronoUnit.DAYS.between(date(at, zone), date(now, zone))) {
        0L -> "today"
        1L -> "yesterday"
        else -> "on " + day(at, now, zone)
    }

    fun devices(n: Int): String = if (n == 1) "1 device" else "$n devices"

    /** canvas v117: the only device in my roster, about to go (sign out, forget, remove). */
    const val ONLY_DEVICE_TITLE = "This is your only device for sealed conversations"
    enum class Leaving(val verb: String, val anyway: String) {
        SIGN_OUT("sign out", "Sign out anyway"), FORGET("forget this device", "Forget anyway"), REMOVE("remove this device", "Remove anyway"),
    }
    fun onlyDeviceText(how: Leaving): String =
        "If you ${how.verb}, your sealed messages can't be opened again, here or on a new device. Only what the people you write with share again comes back."
    const val ONLY_DEVICE_KEEP = "To keep them, link another device first."
    const val ONLY_DEVICE_LINK = "Link a device"

    /** "5 people · 11 devices", under the Encryption title of a group. */
    fun peopleLine(people: Int, devices: Int): String = "$people people · ${devices(devices)}"

    /** The grey second line of another person's row: "Added a device yesterday". */
    fun changeLine(c: DeviceChange, now: Long, zone: ZoneId): String =
        (if (c.added) "Added a device " else "Removed a device ") + relDay(c.at, now, zone)

    /** Is a change recent enough to show (the last 30 days)? */
    fun recent(c: DeviceChange?, now: Long): Boolean = c != null && now - c.at <= 30 * 86400L

    /** Under one of my devices: "Added 2 Oct · from iPhone". */
    fun addedLine(d: MyDevice, now: Long, zone: ZoneId, h24: Boolean = true): String =
        if (!d.allowed) notAllowedLine(d, now, zone, h24)
        else "Added " + day(d.added, now, zone) + (d.from?.let { " · from $it" } ?: "")

    /** canvas Messages v5 messages/encryption-devices-waiting: "Not added yet · asked 9:40 PM". */
    fun notAllowedLine(d: MyDevice, now: Long, zone: ZoneId, h24: Boolean = true): String =
        NOT_ADDED + (if (d.added > 0) " · asked " + stamp(d.added, now, zone, h24) else "")
    const val NOT_ADDED = "Not added yet"

    // ---- the waiting device says who can unlock and where things stand (canvas Messages v5, note
    // n-e2e-locked-named; boards messages/encryption-new-device, -waiting-list, -devices-waiting,
    // -locked-thread, -waiting, -locked-added, -start-fresh). The exact wording waits for Xavier:
    // every string of it is here.

    private val CLOCK_12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val CLOCK_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    /** "9:40 PM" or "21:40": the device's own way (H24 = its 24-hour setting). */
    fun clock(at: Long, zone: ZoneId, h24: Boolean): String = (if (h24) CLOCK_24 else CLOCK_12).format(Instant.ofEpochSecond(at).atZone(zone))

    /** The time today, "21 Nov 9:40 PM" on another day. */
    fun stamp(at: Long, now: Long, zone: ZoneId, h24: Boolean): String =
        if (date(at, zone) == date(now, zone)) clock(at, zone, h24) else day(at, now, zone) + " " + clock(at, zone, h24)

    /** A device of my head whose session name isn't known. */
    const val UNKNOWN_DEVICE = "another device of yours"
    /** No name known at all: "Open Belfry on another device of yours." */
    const val OPEN_UNKNOWN = "Belfry on another device of yours"
    const val OPEN = "Open "
    const val OPEN_OR = " or "
    const val ADDED_BY_ITSELF_ONE = "This device is added by itself as soon as that one is open."
    const val ADDED_BY_ITSELF_MANY = "This device is added by itself as soon as one is open."
    const val NOTHING_TO_APPROVE = "Nothing to approve on this device."
    const val NOTHING_TO_APPROVE_HERE = "Nothing to approve here."

    /** "A", "A or B", "A, B or C". */
    private fun joined(names: List<String>): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        names.forEachIndexed { i, n ->
            if (i > 0) out.add((if (i == names.size - 1) OPEN_OR else ", ") to false)
            out.add(n to (n != UNKNOWN_DEVICE && n != OPEN_UNKNOWN))
        }
        return out
    }

    /**
     * "Open Chat · Safari on Mac. This device is added by itself as soon as that one is open." as
     * pieces (text, is a device's name: the screens set names in bold). Several: "Open A or B. … as
     * soon as one is open." None known: "Open Belfry on another device of yours. …".
     */
    fun openParts(names: List<String>, short: Boolean = false): List<Pair<String, Boolean>> {
        val shown = names.ifEmpty { listOf(OPEN_UNKNOWN) }.let { if (it == listOf(UNKNOWN_DEVICE)) listOf(OPEN_UNKNOWN) else it }
        val tail = if (short) NOTHING_TO_APPROVE_HERE else if (shown.size == 1) ADDED_BY_ITSELF_ONE else ADDED_BY_ITSELF_MANY
        return listOf(OPEN to false) + joined(shown) + (". $tail" to false)
    }

    fun openText(names: List<String>, short: Boolean = false): String = openParts(names, short).joinToString("") { it.first }

    const val NOT_ASKED = "Not asked yet · this device asks as soon as it is online"
    /** "Asked 9:40 PM · waiting for one of your devices"; not asked yet: said plainly. */
    fun askedLine(askedAt: Long, now: Long, zone: ZoneId, h24: Boolean): String =
        if (askedAt <= 0) NOT_ASKED else "Asked " + stamp(askedAt, now, zone, h24) + " · waiting for one of your devices"

    /** messages/encryption-locked-added: "Added 9:42 PM" / "Your messages open here in a moment." */
    fun addedHereTitle(addedAt: Long, now: Long, zone: ZoneId, h24: Boolean): String =
        if (addedAt <= 0) "Added" else "Added " + stamp(addedAt, now, zone, h24)
    const val ADDED_HERE_TEXT = "Your messages open here in a moment."

    /** The link on the banners and the locked block, and the title of the screen it opens (was "Show how"). */
    const val NO_OTHER_LINK = "No other device?"

    /** The same in Devices, with when it was last active. */
    fun activeLine(d: MyDevice, now: Long, zone: ZoneId, h24: Boolean = true): String {
        val a = d.active
        val active = when {
            d.thisDevice || (a != null && now - a < 5 * 60) -> "active now"
            a == null -> null
            else -> {
                val days = ChronoUnit.DAYS.between(date(a, zone), date(now, zone))
                when (days) {
                    0L -> "active today"
                    1L -> "active yesterday"
                    else -> "active $days days ago"
                }
            }
        }
        return addedLine(d, now, zone, h24) + (active?.let { " · $it" } ?: "")
    }

    /** A thread's device line. NAME is the person's display name (mine: "You"). */
    fun notice(n: DeviceNotice, name: String, mine: Boolean): String = when (n.kind) {
        DeviceNotice.Kind.ADDED -> if (mine) "You added a device" + (n.device?.let { " · $it" } ?: "") else "$name added a device"
        DeviceNotice.Kind.REMOVED -> if (mine) "You removed a device" + (n.device?.let { " · $it" } ?: "") else "$name removed a device"
        DeviceNotice.Kind.NEW_LOCKED -> "$name signed in on a new device that can't read earlier messages yet"
    }

    fun kindWord(k: DeviceKind): String = when (k) {
        DeviceKind.PHONE -> "phone"
        DeviceKind.TABLET -> "tablet"
        DeviceKind.COMPUTER -> "computer"
    }

    /** "A new phone · signed in today 9:38" (never the device's name); "A new device" when its kind isn't known (v88). */
    fun newDeviceLine(d: LockedDevice, now: Long, zone: ZoneId): String {
        val rel = relDay(d.at, now, zone)
        return "A new ${d.kind?.let { kindWord(it) } ?: "device"} · signed in $rel " + TIME.format(Instant.ofEpochSecond(d.at).atZone(zone))
    }

    fun shareTitle(name: String, group: Boolean): String =
        "Share ${if (group) "this group's" else "this conversation's"} history with $name's new device?"

    fun shareBody(name: String): String =
        "$name started over on a new device and can't read your earlier messages. Only do this if you're sure it's $name: ask in person or by phone if you're not."

    fun askTitle(device: String): String = "Give your new device “$device” access to your conversations?"

    /** "2 min ago", "just now", "3 h ago". */
    fun ago(at: Long, now: Long): String {
        val s = (now - at).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} days ago"
        }
    }

    const val ONLY_THESE = "Only these devices can read this conversation"
    const val COUNTS_ONLY = "You see only how many devices others have, not which."
    const val SERVER = "The server and the belfry's admins can't read it."
    const val LOCK_CHANGES = "When a device is removed, the lock changes: that device can't read anything new."
    const val LOCK_CHANGES_GROUP = "When a device is removed or someone leaves, the lock changes: they can't read anything new."
    const val MAIL = "E-mails are locked as soon as they arrive. They pass through the belfry's mail server, and the other side's mail provider keeps its own copy."
    const val NOT_HERE = "not on this belfry"
    const val LOCKED_HERE = "Locked on this device"
    /** canvas v84: a device not yet allowed reads no sealed message, old or new. */
    const val BANNER_TITLE = "Messages are locked on this device"
    /** The Encryption screen's first line on a device not allowed yet (the lead, 2026-11-22; draft until the board). */
    const val CANT_READ_YET = "This device can't read it yet"
    /** canvas v183 messages/encryption-device-added: "Belfry for iPhone was added just now". */
    fun addedTitle(name: String, at: Long, now: Long, zone: ZoneId): String = "$name was added " + when {
        now - at < 120 -> "just now"
        now - at < 3600 -> "${(now - at) / 60} min ago"
        date(at, zone) == date(now, zone) -> "at " + java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochSecond(at).atZone(zone))
        else -> "on " + day(at, now, zone)
    }
    const val ADDED_TEXT = "It can read your conversations"
    const val ADDED_REMOVE = "Not you? Remove it"
    const val ADDED_MINE = "It was me"
    /**
     * Sealed messages that came to this device and can't be opened here yet (Xavier's mail to himself,
     * 2026-11-22): no conversation shows them. Words until belfry-canvas draws the board.
     */
    fun waitingMessages(n: Int): String =
        if (n == 1) "1 message is waiting for another of your devices" else "$n messages are waiting for another of your devices"
    /** canvas Messages v8 messages/encryption-waiting-device (sealed.md §5.7): newer messages wait for the device that added itself. Wording for Xavier. */
    fun waitingFor(names: List<String>): String = "Waiting for " + when (names.size) {
        0 -> UNKNOWN_DEVICE
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " or " + names.last()
    }
    /** The same on top of Messages, with how many messages wait. */
    fun waitingForMessages(n: Int, names: List<String>): String =
        (if (n == 1) "1 message is waiting for " else "$n messages are waiting for ") + waitingFor(names).removePrefix("Waiting for ")
    const val WAITING_FOR_TEXT = "They open here when that device is next open."
    /** sealed.md §5.7 "The joiner on first sight": a device that added itself with no session was removed. Wording for Xavier. */
    fun removedJoinerTitle(at: Long, now: Long, zone: ZoneId): String = "A device that was not signed in was added and removed " + when {
        now - at < 120 -> "just now"
        now - at < 3600 -> "${(now - at) / 60} min ago"
        else -> "on " + day(at, now, zone)
    }
    const val REMOVED_JOINER_TEXT = "It can't read anything new. If this wasn't you, change your e-mail's password."
    const val REMOVED_JOINER_OK = "OK"
    /** docs/sealed-v2.md §6.2 (slice 1: the sentence without "Ask my other devices"). Wording is the contract's. */
    const val EARLIER_ELSEWHERE = "Earlier messages were encrypted for your other devices."
    const val EARLIER_ELSEWHERE_OK = "OK"
    /** §3.1: a signed-in device that does not find itself on its account's list. */
    const val GETTING_READY = "Getting your messages ready…"
    const val NOT_ON_LIST = "This device isn't on your device list yet."
    const val RETRY = "Retry"
    /** §5: over 100 devices in a conversation. */
    const val TOO_MANY_DEVICES = "Too many devices to encrypt for everyone here"
    /** §3.3: the card of a device found through a correspondent's message. */
    fun seenBy(name: String): String = "Seen by $name, not shown to this device before"
    const val LOCKED_BLOCK = "Locked on this device"
    fun lockedFrom(since: Long, now: Long, zone: ZoneId): String =
        "Messages from ${day(since, now, zone)} on can be read once another device of yours adds this one."
    const val WRITE_LATER = "You can write here once this device is added."
    const val WAITING_KEYS = "Waiting for another of your devices…"
    const val ALLOW_WAITING = "Not allowed yet: waiting for your other device"
    const val NOT_SEALED = "Not sealed"
    const val TOO_MANY = "You already have 20 devices. Remove one in Devices, then try again."
    const val NOT_SEALED_WHY = "This message was not sent with the lock. It may come from an old app version."
    fun sharedBy(name: String): String = "Shared by $name"
    fun sharedWhy(name: String): String =
        "$name sent you these earlier messages. They come from $name's device, so the app can't check them the way it checks new ones."
    /** Under the count of waiting messages on a device that IS in my head (an older key is missing): nothing to name. */
    const val BANNER_TEXT = "Open Belfry on another device of yours to unlock them."
    const val ASK_TEXT = "It will read all your messages, including earlier ones."
    const val ASK_NOT_ME = "Not me removes that device and signs it out."
    const val LINKED_TEXT = "It can read your conversations, including earlier ones."
    const val LINKED_CONFIRM = "Scanning your code was the confirmation: nothing more to allow."
    const val REMOVE_TEXT = "It will be signed out and stop reading new messages, in all your conversations. What it already shows stays on it."
    /** "No other device?" (messages/encryption-start-fresh), under "With another device": the named device(s), nothing to approve. */
    fun unlockParts(names: List<String>): List<Pair<String, Boolean>> = openParts(names) + (" $NOTHING_TO_APPROVE_HERE" to false)
    const val UNLOCK_NONE = "The people you write to can also share a conversation's history with this device."
    /** canvas v84 messages/encryption-start-fresh (+ -confirm), approved for Xavier 2026-11-21. */
    const val UNLOCK_TITLE = NO_OTHER_LINK
    const val UNLOCK_WITH = "With another device"
    const val NO_OTHER = "I don't have another device"
    const val START_FRESH_ROW = "Start fresh on this device"
    const val START_FRESH_TITLE = "Start fresh on this device?"
    const val START_FRESH_TEXT = "Earlier sealed messages stay locked here unless someone shares them with you or an old device comes back."
    const val START_FRESH_SEEN = "The people you write with will see that you started on a new device."
    const val START_FRESH = "Start fresh"
    /** An old device of mine after a fresh start elsewhere (canvas v122 messages/encryption-fresh-elsewhere + -done). */
    fun freshElsewhereTitle(device: String?): String = "Sealed conversations were started fresh on " + (device?.let { "\u201c$it\u201d" } ?: "a new device")
    private val TIME_12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    /** "Today at 9:41 PM", or "21 Nov at 9:41 PM". */
    fun freshElsewhereWhen(at: Long, now: Long, zone: ZoneId): String {
        val t = Instant.ofEpochSecond(at).atZone(zone)
        val day = if (date(at, zone) == date(now, zone)) "Today" else day(at, now, zone)
        return "$day at " + TIME_12.format(t)
    }
    const val FRESH_ELSEWHERE_BODY = "This device can't read new sealed messages until it's allowed again."
    const val FRESH_ELSEWHERE_BOLD = "If this wasn't you, sign out every other device."
    const val FRESH_ELSEWHERE_SIGN_OUT = "Sign out every other device"
    const val FRESH_ELSEWHERE_ME = "It was me"
    const val FRESH_ELSEWHERE_FAILED = "Couldn't sign out every device. Try again."
    const val FRESH_DONE_TITLE = "Every other device is signed out"
    /** "Firefox on Windows and Safari on Mac can't open your account any more." */
    fun freshDoneNames(names: List<String>): String {
        val list = when (names.size) {
            0 -> "No other device"
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
        return "$list can't open your account any more."
    }
    fun freshDoneEmail(email: String?): String = "Someone may have signed in with a code sent to ${email ?: "your e-mail"}: change that e-mail's password."
    const val FRESH_DONE_START = "To read sealed conversations here again, start fresh on this device."
    const val FRESH_DONE_SHOW_HOW = "Show how"
    const val FRESH_DONE_OK = "Done"
    const val FRESH_ALREADY_ALLOWED = "This device was just allowed: its key is on the way. No need to start fresh."
}
