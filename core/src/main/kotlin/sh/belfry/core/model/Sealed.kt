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
 * (only mine), people are pubkeys the screens turn into names. The wire comes with the lead's
 * contract (docs/sealed.md); until then only the demo fills this.
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
)

/** Messages someone shared with this device, by their times (FROM..TO). Not proven authentic. */
data class SharedBlock(val by: String, val from: Long, val to: Long) {
    fun holds(at: Long): Boolean = at in from..to
}

/** A new device of mine asking for access, shown on my other devices. */
data class AccessAsk(val id: String, val device: String, val kind: DeviceKind, val at: Long)

/** The whole picture on this device. Empty (nothing sealed) until the contract arrives, outside the demo. */
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
) {
    fun seal(conv: String): ConvSeal? = sealed[conv]
    val thisDevice: MyDevice? get() = myDevices.firstOrNull { it.thisDevice }
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

    /** "5 people · 11 devices", under the Encryption title of a group. */
    fun peopleLine(people: Int, devices: Int): String = "$people people · ${devices(devices)}"

    /** The grey second line of another person's row: "Added a device yesterday". */
    fun changeLine(c: DeviceChange, now: Long, zone: ZoneId): String =
        (if (c.added) "Added a device " else "Removed a device ") + relDay(c.at, now, zone)

    /** Is a change recent enough to show (the last 30 days)? */
    fun recent(c: DeviceChange?, now: Long): Boolean = c != null && now - c.at <= 30 * 86400L

    /** Under one of my devices: "Added 2 Oct · from iPhone". */
    fun addedLine(d: MyDevice, now: Long, zone: ZoneId): String =
        "Added " + day(d.added, now, zone) + (d.from?.let { " · from $it" } ?: "")

    /** The same in Devices, with when it was last active. */
    fun activeLine(d: MyDevice, now: Long, zone: ZoneId): String {
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
        return addedLine(d, now, zone) + (active?.let { " · $it" } ?: "")
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
    const val LOCKED_BLOCK = "Locked on this device"
    fun lockedFrom(since: Long, now: Long, zone: ZoneId): String =
        "Messages from ${day(since, now, zone)} on can be read once another device of yours allows this one."
    const val WRITE_LATER = "You can write here once this device is allowed."
    fun sharedBy(name: String): String = "Shared by $name"
    fun sharedWhy(name: String): String =
        "$name sent you these earlier messages. They come from $name's device, so the app can't check them the way it checks new ones."
    const val BANNER_TEXT = "Open Belfry on another device of yours to unlock them."
    const val ASK_TEXT = "It will read all your messages, including earlier ones."
    const val ASK_NOT_ME = "Not me removes that device and signs it out."
    const val LINKED_TEXT = "It can read your conversations, including earlier ones."
    const val LINKED_CONFIRM = "Scanning your code was the confirmation: nothing more to allow."
    const val REMOVE_TEXT = "It will be signed out and stop reading new messages, in all your conversations. What it already shows stays on it."
    const val UNLOCK_TITLE = "Unlock with another device"
    val UNLOCK_STEPS = listOf(
        "Open Belfry on a phone, tablet or computer where you're already signed in.",
        "It asks whether to give this device access: tap Allow.",
        "Your conversations open here.",
    )
    const val UNLOCK_NONE = "No other device? The people you write to can share a conversation's history with this device."
}
