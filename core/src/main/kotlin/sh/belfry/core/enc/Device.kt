package sh.belfry.core.enc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import sh.belfry.core.nostr.Event
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Format 3, part 5 (SPEC 19.85; docs/encrypted.md §7, §11, §16.3, §16.6, §16.12, §17.6, §17.7, §18.3, §20, §26):
 * a new device and its earlier messages, the card on my other devices, the warnings, the first screen, the
 * removed device. The pure rules: a port of the web's `src/core/enc/runtime.ts` (askForEarlier, addedDevices,
 * listWarnings, firstScreenDevices, checkRemoved, encOnlyDevice; branch enc-v3): change both together.
 */

/** My last ask (8574): when, the devices on my list then, its id. */
data class Asked(val at: Long, val devices: List<String>, val id: String)

/** A device as the screens name it: the name its session gave itself (`/me/sessions`), or null (never an id). */
data class DeviceName(val key: String, val name: String?)

/**
 * The card on my other devices (§7.4, §17.7, §17.9): DEVICE was added to my list at AT. READER: an agent given access
 * (part 6 draws its card). GONE: added and removed again while this device was away (until GONE_AT; a notice, OK only).
 * ASK: the ask it sent, when one was seen (an answer names it).
 */
data class DeviceCard(
    val device: String,
    val at: Long,
    val reader: Boolean = false,
    val ask: String? = null,
    val gone: Boolean = false,
    val goneAt: Long? = null,
    val name: String? = null,
)

/** A row of the first screen after sign-in (§18.3): a device (or my agent, a reader) already on my list, since ADDED_AT. */
data class FirstRow(val device: String, val addedAt: Long, val reader: Boolean = false, val name: String? = null, /** When /me/sessions says it was last active (seconds), so rows with one name can be told apart. */ val lastActive: Long? = null)

/** §16.6: what `GET /api/v1/sessions/removed` said. */
data class RemovedAnswer(val removed: Boolean, val at: Long = 0, val byName: String? = null) {
    companion object {
        /** The belfry's JSON, strictly: `removed` must be a boolean true to count; a date as seconds or ISO text. */
        fun parse(o: JsonObject?): RemovedAnswer? {
            if (o == null) return null
            val r = (o["removed"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
            if (!r) return RemovedAnswer(false)
            val atEl = o["at"] as? JsonPrimitive
            val at = when {
                atEl == null -> 0L
                !atEl.isString -> atEl.longOrNull ?: 0L
                else -> try { Instant.parse(atEl.content).epochSecond } catch (e: Exception) { 0L }
            }
            val by = (o["by_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.take(80)?.ifEmpty { null }
            return RemovedAnswer(true, at, by)
        }
    }
}

/** This device was removed by another of mine (§16.6, §26): when, and by which device's name when the belfry knows it. */
data class RemovedHere(val at: Long, val by: String?)

/** What the screens show of part 5 (filled by `Enc3Devices`; the demo sets one by hand). */
data class Enc3DeviceState(
    /** Format 3 runs here (the belfry's cached format is 3): only then is anything of this shown. */
    val on: Boolean = false,
    /** This device is on my current list (before that, nothing here is decided: no "can't be recovered"). */
    val listed: Boolean = false,
    /** My devices listed before this one (they hold what this device misses, §17.6), with their names. */
    val older: List<DeviceName> = emptyList(),
    /** When this device last asked (8574), or null. */
    val askedAt: Long? = null,
    /** The older device whose pack opened here last ("Earlier messages shared by ‹name›"). */
    val sharedBy: DeviceName? = null,
    /** "Earlier messages can't be recovered" was read (OK). */
    val lostOk: Boolean = false,
    /** The cards on this device: devices added to my list (readers too: part 6 draws theirs). */
    val cards: List<DeviceCard> = emptyList(),
    /** Said once, after a card went (shared, removed, answered on another device). */
    val notices: List<String> = emptyList(),
    /** §17.7: my lists forked (or a newer one isn't valid): the warning that doesn't go by itself. */
    val fork: Boolean = false,
    /** §17.7: an envelope for my account had an entry for a key never on my lists. */
    val strange: Boolean = false,
    /** §18.3: the first screen after sign-in, until Continue (empty: nothing to show). */
    val firstScreen: List<FirstRow> = emptyList(),
    /** encrypted/signout-only: this device is alone on my list and holds keys. */
    val onlyDevice: Boolean = false,
    /** The first read that said this belfry writes format 3 (Unix seconds; 0 unknown): "Messages from before ‹date›". */
    val since3: Long = 0,
    /** The demo only (no runtime there): messages it pretends this device can't open, format 3 / version 1. The app adds the real counts. */
    val demoMissing: Int = 0,
    val demoV1Missing: Int = 0,
    // ---- part 6 (SPEC 19.86): Me › Devices and my agent's access read my current list from here ----
    /** My current list's devices (this one included), or empty while this device has none. */
    val devices: List<String> = emptyList(),
    /** My current list's readers (my agent given access, §11): the truth for "access on". */
    val readers: List<String> = emptyList(),
    /** When each device or reader first appeared on my lists (Unix seconds). */
    val added: Map<String, Long> = emptyMap(),
    /** This device's key (its row says "This device" and has no trash icon). */
    val here: String? = null,
    /** The devices whose card was answered "shared" (One device: Earlier messages · Shared). */
    val sharedWith: Set<String> = emptySet(),
)

object EncDevice {
    /** §7.3 / §16.12: an ask again after 24 hours. */
    const val ASK_AGAIN = 86_400L

    /**
     * §7.3 + §16.12: this device may ask (again) now: never asked, or 24 h since, or another device of mine appeared on
     * the list since (MINE_NOW: my current list's devices).
     */
    fun mayAsk(asked: Asked?, now: Long, mineNow: List<String>): Boolean =
        asked == null || now - asked.at >= ASK_AGAIN || mineNow.any { it !in asked.devices }

    /** §17.6: my devices on the current list that were listed before DEVICE (they can share with it). */
    fun older(c: Chain?, device: String): List<String> =
        if (c == null) emptyList() else c.current.devices.filter { it != device && EncLists.listedBefore(c, it, device) }

    /** When each device or reader first appeared on a list of the chain (Added …). */
    fun since(c: Chain): Map<String, Long> {
        val m = LinkedHashMap<String, Long>()
        for (l in c.lists) for (d in l.devices + l.readers) if (d !in m) m[d] = l.createdAt
        return m
    }

    /** Asks of ACCOUNT among EVENTS from devices on its current list: the asking device's newest ask. */
    fun asks(events: Collection<Event>, account: String, c: Chain): Map<String, AskSeen> {
        val out = HashMap<String, AskSeen>()
        for (e in events) {
            val a = EncPacks.readAsk(e, account, c) ?: continue
            val was = out[a.device]
            if (was == null || a.at > was.at) out[a.device] = a
        }
        return out
    }

    /**
     * §7.7, §16.12, §17.6, §20: the answers of ACCOUNT that count, by the device they are about: by a device on the
     * current list (never a reader) listed before that device, no list missing. An `e` answer is about the device that
     * sent that ask (ASKS by id); an answer to an ask this device never saw doesn't count yet.
     */
    fun answers(events: Collection<Event>, account: String, c: Chain, asks: Collection<AskSeen>): Map<String, AnswerSeen> {
        val byId = asks.associateBy { it.id }
        val out = HashMap<String, AnswerSeen>()
        for (e in events) {
            if (e.kind != Enc.ANSWER) continue
            val about = Enc.one(e, "e")?.let { byId[it]?.device }
            val a = EncPacks.readAnswer(e, account, c, about) ?: continue
            val d = a.device ?: about ?: continue
            val was = out[d]
            if (was == null || a.at < was.at) out[d] = a // the first answer settles it
        }
        return out
    }

    /**
     * §7.4, §6.4, §17.6, §17.7, §17.9 (the web's `addedDevices`): the cards on this DEVICE for its account's chain C. Only
     * once this device is on the current list; nothing for what the first list this device saw (FIRST_N) already had;
     * a device only when this one was listed before it (only older devices answer) and nobody answered it (ANSWERED);
     * readers apart (their own card, part 6); a device added and removed again while away = GONE until read (GONE_SEEN).
     */
    fun cards(c: Chain, device: String, firstN: Int, asks: Map<String, AskSeen>, answered: Set<String>, goneSeen: Set<String>): List<DeviceCard> {
        if (firstN <= 0) return emptyList()
        val cur = c.current
        if (device !in cur.devices) return emptyList()
        val out = ArrayList<DeviceCard>()
        val seen = HashSet<String>()
        for (ch in EncLists.changes(c)) {
            if (ch.n <= firstN) continue
            val l = c.listById(ch.list) ?: continue
            for (d in ch.added) {
                if (d == device || d in answered || !seen.add(d)) continue
                if (d in cur.devices) {
                    if (EncLists.listedBefore(c, device, d)) out += DeviceCard(d, l.createdAt, ask = asks[d]?.id)
                } else if (d !in goneSeen) {
                    // the first list after it that no longer has it
                    val at = c.lists.firstOrNull { it.n > l.n && d !in it.devices }?.createdAt
                    out += DeviceCard(d, l.createdAt, gone = true, goneAt = at)
                }
            }
            for (d in ch.readersAdded) if (d in cur.readers && d !in answered && seen.add(d)) out += DeviceCard(d, l.createdAt, reader = true)
        }
        return out
    }

    /** §18.3: on the first list of mine this device sees, the other devices and readers already on it, with their dates. */
    fun firstScreen(c: Chain?, device: String): List<FirstRow> {
        if (c == null || device !in c.current.devices) return emptyList()
        val since = since(c)
        return c.current.devices.filter { it != device }.map { FirstRow(it, since[it] ?: c.current.createdAt) } +
            c.current.readers.map { FirstRow(it, since[it] ?: c.current.createdAt, reader = true) }
    }

    /**
     * §16.6 + §26 (the ruling): wipe ONLY when the belfry says removed AND this DEVICE is absent from my account's
     * current list, that list read whole on the connection there is now (LIST_READ_HERE). Never on one alone, never
     * without a list, never offline (no answer = no wipe).
     */
    fun shouldWipe(answer: RemovedAnswer?, listReadHere: Boolean, c: Chain?, device: String): Boolean =
        answer?.removed == true && listReadHere && c != null && device !in c.current.devices && device !in c.current.readers

    /** encrypted/signout-only: this DEVICE alone on my current list, holding keys (its earlier messages go with it). */
    fun onlyDevice(c: Chain?, device: String, keys: Int): Boolean =
        c != null && c.current.devices == listOf(device) && keys > 0
}

/**
 * Every word of part 5 (Encrypted canvas, approved 23 Nov; the web's EarlierLine.tsx / App.tsx / Sealed.tsx where
 * it shipped words different from a board). Names are the devices' own names, never an id.
 */
object EncDeviceText {
    const val EARLIER_TITLE = "Earlier messages are on your other devices"
    const val ASK = "Ask for them"
    const val ASKED_TITLE = "Asked your other devices"
    const val YOUR_DEVICES = "Your devices"
    const val LOST_TITLE = "Earlier messages can’t be recovered"
    const val LOST_TEXT = "None of the devices that could read them is signed in any more. New messages work as usual."
    const val OK = "OK"

    /** "Safari on iPhone and Safari on Mac can share them with this one." (only when every older device has a name). */
    fun earlierText(names: List<String?>): String =
        if (names.isNotEmpty() && names.all { it != null }) "${list(names.filterNotNull().distinct(), "and")} can share them with this one."
        else "Your other devices can share them with this one."

    /** encrypted/transition: version-1 messages this device never had. */
    fun transitionText(since3: Long, zone: ZoneId, names: List<String?>): String {
        val before = if (since3 > 0) "Messages from before ${day(since3, zone)} were" else "Earlier messages were"
        val who = if (names.isNotEmpty() && names.all { it != null }) "${list(names.filterNotNull().distinct(), "or")} can share them." else "Your other devices can share them."
        return "$before encrypted the old way. $who"
    }

    fun askedText(names: List<String?>): String =
        if (names.isNotEmpty() && names.all { it != null }) "Earlier messages arrive here when ${list(names.filterNotNull().distinct(), "or")} is next open."
        else "Earlier messages arrive here when one of your other devices is next open."

    fun sharedBy(name: String?): String = "Earlier messages shared by ${name ?: "another of your devices"}"

    // ---- the card (encrypted/card …) ----
    const val NEW_DEVICE = "A new device"
    const val CARD_TEXT = "Share your earlier messages with it? If this wasn’t you, remove it."
    const val SHARE = "Share earlier messages"
    const val SHARING = "Sharing…"
    const val REMOVE_DEVICE = "Remove this device"
    const val REMOVE = "Remove"
    const val CANCEL = "Cancel"
    /** encrypted/card-remove exactly (the lead, 24 Nov): two lines, the second its own sentence. */
    const val REMOVE_TEXT = "It’s signed out at once and can’t read new messages.\nIf you didn’t add it, someone used a code sent to your e-mail. Change your e-mail password."
    const val FAILED = "That did not work. Try again."
    const val NOT_YET = "This belfry can’t do that yet."

    fun cardTitle(name: String?, at: Long, now: Long, zone: ZoneId, h24: Boolean = true): String = "${name ?: NEW_DEVICE} was added · ${time(at, now, zone, h24)}"
    fun sharing(done: Int, total: Int): String = if (total > 0) "Sharing earlier messages… $done of $total" else "Sharing earlier messages…"
    fun removeTitle(name: String?): String = "Remove ${name ?: "this device"}?"
    fun shared(name: String?): String = "Earlier messages shared with ${name ?: "your new device"}"
    fun removed(name: String?): String = "${name ?: "The device"} was removed."
    fun answeredElsewhere(by: String?, name: String?, word: AnswerWord): String = when (word) {
        AnswerWord.SHARED -> "${by ?: "Another of your devices"} shared earlier messages with ${name ?: "your new device"}"
        AnswerWord.REMOVED -> "${by ?: "Another of your devices"} removed ${name ?: "the new device"}"
    }

    // ---- the warnings (encrypted/edge-added-removed, edge-two-lists) ----
    const val GONE_TEXT = "If you didn’t add it, someone used a code sent to your e-mail: change your e-mail password."
    fun goneTitle(name: String?): String = "${name ?: "A device"} was added and removed while you were away"
    fun goneWhen(at: Long, goneAt: Long?, now: Long, zone: ZoneId, h24: Boolean = true): String =
        if (goneAt == null) time(at, now, zone, h24) else "${time(at, now, zone, h24)} to ${clock(goneAt, zone, h24)}"
    const val FORK_TITLE = "Your belfry gave out two different lists of your devices"
    const val FORK_TEXT = "Something is wrong on the belfry’s side. Tell its admins, and check your devices."
    const val STRANGE_TITLE = "A device that was never on your list got your messages"
    const val STRANGE_TEXT = "Someone may be reading what is sent to you. Tell the belfry’s admins, and check your devices."

    // ---- the first screen (encrypted/first-screen) ----
    const val FIRST_TITLE = "These devices can read your messages"
    const val FIRST_TEXT = "They were already signed in to your account. If you don’t know one, remove it."
    const val CONTINUE = "Continue"
    const val AGENT = "Your agent"
    fun added(at: Long, zone: ZoneId): String = "Added ${day(at, zone)}"
    fun agentSince(at: Long, zone: ZoneId): String = "$AGENT · access given ${day(at, zone)}"

    // ---- the removed device (encrypted/removed-device) ----
    const val REMOVED_TITLE = "This device was removed"
    const val SIGN_IN_AGAIN = "Sign in again"
    fun removedText(r: RemovedHere, now: Long, zone: ZoneId, h24: Boolean = true): String {
        val whenText = if (r.at > 0) " " + (if (sameDay(r.at, now, zone)) "today" else "on ${day(r.at, zone)}") + " at ${clock(r.at, zone, h24)}" else ""
        return "${r.by ?: "Another of your devices"} removed it$whenText. Your messages were deleted from this device. They’re still on your other devices."
    }

    // ---- signing out of the only device (encrypted/signout-only) ----
    const val ONLY_TITLE = "This is your only device"
    const val ONLY_TEXT = "If you sign out, your earlier encrypted messages can’t be read anywhere, ever."
    const val ONLY_NEW = "New messages still reach you when you sign in again."
    const val ONLY_ADD = "Add another device first"
    const val ONLY_ANYWAY = "Sign out anyway"

    // ---- part 6 (SPEC 19.86): my agent given access, Me › Devices, One device (the web's WhoCanRead.tsx / EarlierLine.tsx,
    // f76fec5; the boards' words where the web shipped none) ----
    fun readerTitle(name: String): String = "$name can now read your private messages"
    fun readerOk(name: String): String = "$name reads your private messages from now on."
    fun agentRemoveTitle(name: String): String = "Remove $name’s access?"
    fun agentRemoveText(name: String): String = "$name won’t read your private messages and private groups any more. What it read before stays with it."
    fun agentOff(name: String): String = "$name can’t read your private messages any more."

    const val DEVICES = "Devices"
    const val DEVICES_TEXT = "These devices can read your encrypted messages."
    const val NEW = "New"
    const val DEVICES_FOOT = "A device you remove is signed out and can’t read anything new."
    const val AGENT_FOOT = "Removing your agent here turns its access off."
    const val NOT_SET_UP = "This device isn’t set up for encrypted messages yet."
    const val ADDED = "Added"
    const val LAST_ACTIVE = "Last active"
    const val EARLIER = "Earlier messages"
    const val SHARED = "Shared"
    const val NOT_SHARED = "Not shared"
    const val HERE_NOTE = "This device. Sign out is in Me."
    const val ONE_REMOVE_TEXT = "It’s signed out at once. Messages already on it stay there until it’s next online."

    /** "Added 12 Aug · last active Today" (either part left out when unknown). */
    fun deviceLine(added: Long?, lastActive: Long?, now: Long, zone: ZoneId): String =
        listOfNotNull(added?.let { "Added ${dayWord(it, now, zone)}" }, lastActive?.let { "last active ${dayWord(it, now, zone)}" }).joinToString(" · ")

    fun agentLine(added: Long?, now: Long, zone: ZoneId): String = AGENT + (added?.let { " · access given ${dayWord(it, now, zone)}" } ?: "")

    /** New: a device (not this one) added in the last day. */
    fun isNew(added: Long?, now: Long): Boolean = added != null && now - added in 0 until 86_400

    /** "Today", "Yesterday", "12 Aug". */
    fun dayWord(at: Long, now: Long, zone: ZoneId): String {
        val d = Instant.ofEpochSecond(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        return when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> day(at, zone) }
    }

    /** A `/me/sessions` date (ISO text) as Unix seconds, or null. */
    fun isoSeconds(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try { java.time.OffsetDateTime.parse(s).toEpochSecond() } catch (e: Exception) {
            try { Instant.parse(s).epochSecond } catch (e2: Exception) {
                try { java.time.LocalDate.parse(s.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond() } catch (e3: Exception) { null }
            }
        }
    }

    // ---- my agent's access (encrypted/agent-access, -confirm, -on; the web's AgentAccess) ----
    const val ACCESS_SECTION = "Private messages"
    const val ACCESS = "Give access to my private messages and private groups"
    fun accessOn(name: String): String = "$name is now one of your devices: it reads them like one."
    const val ACCESS_WAIT = "Waiting for the belfry…"
    const val ACCESS_OFF = "Off: it doesn’t read them."
    fun giveTitle(name: String): String = "Give $name access?"
    const val GIVE_TEXT = "It will read your private messages and private groups from now on, like one of your devices. You can turn this off at any time."
    const val GIVE = "Give access"

    private fun list(names: List<String>, word: String): String =
        if (names.size <= 1) names.joinToString("") else names.dropLast(1).joinToString(", ") + " $word " + names.last()

    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val C24 = DateTimeFormatter.ofPattern("H:mm", Locale.ENGLISH)
    private val C12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    fun day(at: Long, zone: ZoneId): String = DAY.format(Instant.ofEpochSecond(at).atZone(zone))
    fun clock(at: Long, zone: ZoneId, h24: Boolean): String = (if (h24) C24 else C12).format(Instant.ofEpochSecond(at).atZone(zone))
    private fun sameDay(a: Long, b: Long, zone: ZoneId) = Instant.ofEpochSecond(a).atZone(zone).toLocalDate() == Instant.ofEpochSecond(b).atZone(zone).toLocalDate()

    /** "Today 9:40", "Yesterday 23:20", "12 Nov 9:40". */
    fun time(at: Long, now: Long, zone: ZoneId, h24: Boolean = true): String {
        val d = Instant.ofEpochSecond(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val dayWord = when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> day(at, zone) }
        return "$dayWord ${clock(at, zone, h24)}"
    }
}
