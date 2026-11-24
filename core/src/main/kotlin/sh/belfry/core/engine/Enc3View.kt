package sh.belfry.core.engine

import sh.belfry.core.enc.Devices
import sh.belfry.core.net.SessionInfo

/**
 * What the screens of encrypted people conversations read (SPEC 19.84; Encrypted canvas encrypted/conversation,
 * who-can-read, who-agent, email-who, not-before, not-large): the lock next to the name, Who can read this, the
 * not-before line. [Session.enc3View] gives the real one (over [Enc3]); the demo has none, a test installs
 * [DemoEnc3View] to draw the format-3 screens.
 */
interface Enc3View {
    /** This device knows its belfry as format 3. */
    val format3: Boolean

    /** The people conversation CONV (`n:`) is encrypted here: a key of it is held, or a message was opened under one. */
    fun encrypted(conv: String): Boolean

    /** The date of CONV's first key known here (the not-before line), or null. */
    fun firstKeyAt(conv: String): Long?

    /** ACCOUNT's devices and readers on its current list, or null while its list isn't known (never which ones). */
    fun count(account: String): Int?

    /** My own current list, or null when this device has none yet ("isn't set up"). */
    fun mine(): MyDevices?

    /** My own sessions (`/me/sessions`): their names. Empty when the belfry can't say. */
    suspend fun sessions(): List<SessionInfo>
}

/** My current list: DEVICES (this one is HERE), READERS (my agent), ADDED = when each first appeared on my lists. */
data class MyDevices(val devices: List<String>, val readers: List<String>, val added: Map<String, Long>, val here: String)

/** One row of "Who can read this" for another person: a count only, never which devices (Xavier, 23 Nov). */
data class OtherReader(val account: String, val count: Int?)

/** One row of mine: a device by its name, or my agent (ACCOUNT = the agent's key, named by the screen). */
data class MyReader(val key: String, val name: String?, val here: Boolean, val agent: Boolean, val addedAt: Long?)

/** Everything "Who can read this" shows for one conversation (the web's `WhoCanRead`, enc-v3). */
data class WhoCanRead(
    val mail: Boolean,
    val others: List<OtherReader>,
    val myCount: Int?,
    val mine: List<MyReader>,
    /** this device has no list yet */
    val notSetUp: Boolean,
) {
    companion object {
        /**
         * Who can read CONV: OTHERS (the conversation's other people, sorted by NAME), each with a count; my devices
         * by their names from SESSIONS (matched by their client key; "A device" when the belfry doesn't name it),
         * this device first, then by the date each was added; my agents (readers) after them.
         */
        fun of(view: Enc3View, mail: Boolean, others: List<String>, sessions: List<SessionInfo>, name: (String) -> String): WhoCanRead {
            val mine = view.mine()
            val byKey = sessions.filter { !it.clientPubkey.isNullOrEmpty() }.associateBy { it.clientPubkey!! }
            val devs = mine?.devices.orEmpty().sortedWith(compareBy<String> { if (it == mine?.here) 0 else 1 }.thenBy { mine?.added?.get(it) ?: 0L })
            val rows = devs.map { k -> MyReader(k, WhoText.deviceName(byKey[k]), k == mine?.here, false, mine?.added?.get(k)) } +
                mine?.readers.orEmpty().map { k -> MyReader(k, null, false, true, mine?.added?.get(k)) }
            return WhoCanRead(
                mail = mail,
                others = if (mail) emptyList() else others.sortedBy { name(it).lowercase() }.map { OtherReader(it, view.count(it)) },
                myCount = mine?.let { it.devices.size + it.readers.size },
                mine = rows,
                notSetUp = mine == null,
            )
        }
    }
}

/** Every word of the format-3 conversation screens (the web's `WhoCanRead.tsx` / `Messages.tsx`, enc-v3). */
object WhoText {
    const val TITLE = "Who can read this"
    const val LOCK = "Encrypted: see who can read this"
    const val OTHERS = "Others"
    const val ONLY_COUNTS = "You see how many devices others have, never which."
    const val THIS_DEVICE = "This device"
    const val YOUR_AGENT = "Your agent"
    const val NOT_SET_UP = "This device isn’t set up for encrypted messages yet."
    const val HOW = "How it works"
    const val HOW_1 = "Each message is encrypted on the device that sends it. Only the devices above can read it. The belfry stores and delivers your messages but can’t read them, and neither can its admins. It does see who writes to whom, and when."
    const val HOW_2 = "Each device has its own key, made when it signs in, that never leaves it. When a device is added or removed, the next message uses a new lock, so a removed device can’t read anything new."
    const val MAIL = "E-mail"
    const val MAIL_LEAD = "On the belfry, only your devices can read this conversation."
    const val MAIL_WHO = "By e-mail · not on this belfry"
    const val NOT_BEFORE = "Messages after this are encrypted. Earlier ones are not."
    const val OPENS_ONLINE = "Opens when you’re back online"
    const val YOUR_DEVICES = "Your devices"

    fun mailText(who: String): String =
        "E-mails travel the usual way: they reach the belfry’s mail server unencrypted and are encrypted as soon as they arrive. Your replies leave the same way. Once here, the belfry can’t read them. $who’s mail provider keeps its own copy."

    /** The first line: whose devices can read it (FIRST = each other person's first name). */
    fun lead(mail: Boolean, first: List<String>): String = when {
        mail -> MAIL_LEAD
        first.isEmpty() -> "Only your devices can read this conversation."
        first.size == 1 -> "Only ${first[0]}’s devices and yours can read this conversation."
        else -> "Only the devices of the people here can read this conversation."
    }

    fun devices(n: Int): String = if (n == 1) "1 device" else "$n devices"

    /** "Sam Rivera (you)": my name in the section's head. */
    fun you(name: String): String = "${name.ifEmpty { "You" }} (you)"

    /** A session's name as the belfry gives it (the web's `nameOf`). */
    fun deviceName(s: SessionInfo?): String = (s?.device?.trim().orEmpty().ifEmpty { s?.applicationName?.trim().orEmpty() }).ifEmpty { "A device" }

    /** The 21st device line (encrypted/edge-21st), its two lines. */
    const val FULL_TITLE = "You have 20 devices signed in"
    const val FULL_TEXT = "Remove one to read encrypted messages here."
}

/** The real view over this session's [Enc3] (null in the demo and for a guest's session). */
internal class RealEnc3View(private val e: Enc3, private val format: () -> Boolean, private val mySessions: suspend () -> List<SessionInfo>) : Enc3View {
    override val format3: Boolean get() = format()
    override fun encrypted(conv: String): Boolean = e.everEncrypted(conv)
    override fun firstKeyAt(conv: String): Long? = e.firstKeyAt(conv)
    override fun count(account: String): Int? = when (val d = e.devices(account)) {
        is Devices.Listed -> d.chain.current.receivers.size
        else -> null
    }
    override fun mine(): MyDevices? {
        val c = (e.devices(e.me) as? Devices.Listed)?.chain ?: return null
        val cur = c.current
        val added = HashMap<String, Long>()
        for (l in c.lists) for (k in l.receivers) if (k !in added) added[k] = l.createdAt
        return MyDevices(cur.devices, cur.readers, added, e.device.pubkey)
    }
    override suspend fun sessions(): List<SessionInfo> = try { mySessions() } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (x: Exception) { emptyList() }
}

/**
 * A made-up format-3 view for screenshots and tests (the demo itself stays format 1, SPEC 19.83): ENCRYPTED
 * conversations with their first key's date, COUNTS per account, MINE (null = not set up) and the SESSIONS that name
 * my devices.
 */
class DemoEnc3View(
    val encryptedConvs: Map<String, Long> = emptyMap(),
    val counts: Map<String, Int> = emptyMap(),
    val myDevices: MyDevices? = null,
    val mySessions: List<SessionInfo> = emptyList(),
    override val format3: Boolean = true,
) : Enc3View {
    override fun encrypted(conv: String): Boolean = conv in encryptedConvs
    override fun firstKeyAt(conv: String): Long? = encryptedConvs[conv]
    override fun count(account: String): Int? = counts[account]
    override fun mine(): MyDevices? = myDevices
    override suspend fun sessions(): List<SessionInfo> = mySessions
}
